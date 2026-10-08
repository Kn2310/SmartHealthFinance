package com.smarthealthfinance.ingestion.infrastructure.messaging;

import com.smarthealthfinance.ingestion.application.usecase.ConfirmImport;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Duration;

/**
 * Topologia e política de retry do Import Worker (ADR-0009 §15).
 * <p>
 * {@code shf.events --import.confirmed--> shf.ingestion.import}; rejeições vão para {@code shf.ingestion.import.dlq}
 * pelo dead-letter da fila. Falhas transitórias (banco indisponível, deadlock, lock otimista) são retentadas com
 * backoff exponencial + jitter; as demais vão direto para o recoverer, que marca o batch como FAILED e manda a
 * mensagem para a DLQ.
 */
@Configuration(proxyBeanMethods = false)
public class ImportMessagingConfig {

    public static final String QUEUE = "shf.ingestion.import";
    public static final String DEAD_LETTER_QUEUE = QUEUE + ".dlq";
    public static final String CONTAINER_FACTORY = "importListenerContainerFactory";

    @Bean
    Declarables importTopology(TopicExchange eventsExchange) {
        Queue queue = QueueBuilder.durable(QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE)
                .build();
        Queue deadLetters = QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
        return new Declarables(queue, deadLetters,
                BindingBuilder.bind(queue).to(eventsExchange).with(ConfirmImport.EVENT_TYPE));
    }

    @Bean(CONTAINER_FACTORY)
    SimpleRabbitListenerContainerFactory importListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            ImportFailureRecoverer recoverer,
            @Value("${shf.ingestion.import.retry.max-retries:4}") int maxRetries,
            @Value("${shf.ingestion.import.retry.delay:1s}") Duration delay,
            @Value("${shf.ingestion.import.retry.max-delay:30s}") Duration maxDelay,
            @Value("${shf.ingestion.import.retry.jitter:500ms}") Duration jitter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(retryInterceptor(recoverer, maxRetries, delay, maxDelay, jitter));
        return factory;
    }

    static MethodInterceptor retryInterceptor(ImportFailureRecoverer recoverer, int maxRetries, Duration delay,
                                              Duration maxDelay, Duration jitter) {
        return RetryInterceptorBuilder.stateless()
                .configureRetryPolicy(policy -> policy
                        .maxRetries(maxRetries)
                        .delay(delay)
                        .multiplier(2)
                        .maxDelay(maxDelay)
                        .jitter(jitter)
                        .predicate(ImportMessagingConfig::isRetryable))
                .recoverer(recoverer)
                .build();
    }

    /** Só falhas de infraestrutura passageiras valem nova tentativa; repetir um bug ou dado inválido não ajuda. */
    static boolean isRetryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AmqpRejectAndDontRequeueException) {
                return false;
            }
            if (cause instanceof TransientDataAccessException
                    || cause instanceof RecoverableDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException) {
                return true;
            }
        }
        return false;
    }
}
