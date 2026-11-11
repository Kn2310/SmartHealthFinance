package com.smarthealthfinance.copilot.infrastructure.messaging;

import java.time.Duration;
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

/**
 * Topologia e retry do consumidor de revogação de consentimento do Copilot (ADR-0040 §6), no padrão dos Insights
 * (ADR-0022 §2).
 * <p>
 * {@code shf.events --consent.revoked--> shf.copilot.history-revocation}; rejeições vão para
 * {@code shf.copilot.history-revocation.dlq}. Só falhas transitórias são retentadas, com backoff exponencial + jitter.
 */
@Configuration(proxyBeanMethods = false)
public class CopilotMessagingConfig {

    public static final String QUEUE = "shf.copilot.history-revocation";
    public static final String DEAD_LETTER_QUEUE = QUEUE + ".dlq";
    public static final String CONTAINER_FACTORY = "copilotListenerContainerFactory";

    /** O Copilot conhece o evento dos Consentimentos só pelo nome (ADR-0038 §8); ArchUnit proíbe depender do módulo. */
    public static final String CONSENT_REVOKED = "consent.revoked";

    @Bean
    Declarables copilotTopology(TopicExchange eventsExchange) {
        Queue queue = QueueBuilder.durable(QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE)
                .build();
        Queue deadLetters = QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
        return new Declarables(queue, deadLetters,
                BindingBuilder.bind(queue).to(eventsExchange).with(CONSENT_REVOKED));
    }

    @Bean(CONTAINER_FACTORY)
    SimpleRabbitListenerContainerFactory copilotListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            CopilotFailureRecoverer recoverer,
            @Value("${shf.copilot.history-revocation.retry.max-retries:4}") int maxRetries,
            @Value("${shf.copilot.history-revocation.retry.delay:1s}") Duration delay,
            @Value("${shf.copilot.history-revocation.retry.max-delay:30s}") Duration maxDelay,
            @Value("${shf.copilot.history-revocation.retry.jitter:500ms}") Duration jitter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(retryInterceptor(recoverer, maxRetries, delay, maxDelay, jitter));
        return factory;
    }

    static MethodInterceptor retryInterceptor(CopilotFailureRecoverer recoverer, int maxRetries, Duration delay,
                                              Duration maxDelay, Duration jitter) {
        return RetryInterceptorBuilder.stateless()
                .configureRetryPolicy(policy -> policy
                        .maxRetries(maxRetries)
                        .delay(delay)
                        .multiplier(2)
                        .maxDelay(maxDelay)
                        .jitter(jitter)
                        .predicate(CopilotMessagingConfig::isRetryable))
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
