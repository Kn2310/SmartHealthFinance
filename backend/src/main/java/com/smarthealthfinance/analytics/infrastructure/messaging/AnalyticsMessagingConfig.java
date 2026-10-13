package com.smarthealthfinance.analytics.infrastructure.messaging;

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
 * Topologia e retry do consumidor da projeção de Analytics (ADR-0014 §4), no padrão do Import Worker (ADR-0009 §15).
 * <p>
 * {@code shf.events --transaction.* / import.completed--> shf.analytics.projection}; rejeições vão para
 * {@code shf.analytics.projection.dlq}. Falhas transitórias (banco indisponível, deadlock, timeout de lock) são
 * retentadas com backoff exponencial + jitter; as demais vão direto para a DLQ. Depois de um replay da DLQ, o
 * runbook manda rodar o rebuild.
 */
@Configuration(proxyBeanMethods = false)
public class AnalyticsMessagingConfig {

    public static final String QUEUE = "shf.analytics.projection";
    public static final String DEAD_LETTER_QUEUE = QUEUE + ".dlq";
    public static final String CONTAINER_FACTORY = "analyticsListenerContainerFactory";

    /** O Analytics conhece só o nome do evento do Ingestion, nunca as classes dele. */
    public static final String IMPORT_COMPLETED = "import.completed";

    @Bean
    Declarables analyticsTopology(TopicExchange eventsExchange) {
        Queue queue = QueueBuilder.durable(QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE)
                .build();
        Queue deadLetters = QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
        return new Declarables(queue, deadLetters,
                BindingBuilder.bind(queue).to(eventsExchange).with("transaction.*"),
                BindingBuilder.bind(queue).to(eventsExchange).with(IMPORT_COMPLETED));
    }

    @Bean(CONTAINER_FACTORY)
    SimpleRabbitListenerContainerFactory analyticsListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            AnalyticsFailureRecoverer recoverer,
            @Value("${shf.analytics.retry.max-retries:4}") int maxRetries,
            @Value("${shf.analytics.retry.delay:1s}") Duration delay,
            @Value("${shf.analytics.retry.max-delay:30s}") Duration maxDelay,
            @Value("${shf.analytics.retry.jitter:500ms}") Duration jitter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(retryInterceptor(recoverer, maxRetries, delay, maxDelay, jitter));
        return factory;
    }

    static MethodInterceptor retryInterceptor(AnalyticsFailureRecoverer recoverer, int maxRetries, Duration delay,
                                              Duration maxDelay, Duration jitter) {
        return RetryInterceptorBuilder.stateless()
                .configureRetryPolicy(policy -> policy
                        .maxRetries(maxRetries)
                        .delay(delay)
                        .multiplier(2)
                        .maxDelay(maxDelay)
                        .jitter(jitter)
                        .predicate(AnalyticsMessagingConfig::isRetryable))
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
