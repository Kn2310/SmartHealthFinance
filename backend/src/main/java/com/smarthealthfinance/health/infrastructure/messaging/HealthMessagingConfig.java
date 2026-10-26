package com.smarthealthfinance.health.infrastructure.messaging;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
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
 * Topologia e retry do consumidor do Health (ADR-0018 §2), no padrão do consumidor de Analytics (ADR-0014 §4).
 * <p>
 * {@code shf.events --analytics.projection-updated / account.changed--> shf.health.snapshot}; rejeições vão para
 * {@code shf.health.snapshot.dlq}. Só falhas transitórias são retentadas, com backoff exponencial + jitter. Depois de
 * um replay da DLQ, o runbook manda rodar o rebuild.
 */
@Configuration(proxyBeanMethods = false)
public class HealthMessagingConfig {

    public static final String QUEUE = "shf.health.snapshot";
    public static final String DEAD_LETTER_QUEUE = QUEUE + ".dlq";
    public static final String CONTAINER_FACTORY = "healthListenerContainerFactory";

    /** O Health conhece os eventos de Analytics, Accounts e Cards só pelo nome. */
    public static final String PROJECTION_UPDATED = "analytics.projection-updated";
    public static final String ACCOUNT_CHANGED = "account.changed";
    /** Limite, arquivamento e início do acompanhamento mudam Credit e Debt sem transação (ADR-0027 §6). */
    public static final List<String> CARD_EVENTS = List.of("card.created", "card.updated", "card.archived",
            "card.reactivated", "invoice.closed");

    @Bean
    Declarables healthTopology(TopicExchange eventsExchange) {
        Queue queue = QueueBuilder.durable(QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE)
                .build();
        Queue deadLetters = QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
        List<Declarable> declarables = new ArrayList<>(List.of(queue, deadLetters,
                BindingBuilder.bind(queue).to(eventsExchange).with(PROJECTION_UPDATED),
                BindingBuilder.bind(queue).to(eventsExchange).with(ACCOUNT_CHANGED)));
        CARD_EVENTS.forEach(event -> declarables.add(BindingBuilder.bind(queue).to(eventsExchange).with(event)));
        return new Declarables(declarables);
    }

    @Bean(CONTAINER_FACTORY)
    SimpleRabbitListenerContainerFactory healthListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            HealthFailureRecoverer recoverer,
            @Value("${shf.health.retry.max-retries:4}") int maxRetries,
            @Value("${shf.health.retry.delay:1s}") Duration delay,
            @Value("${shf.health.retry.max-delay:30s}") Duration maxDelay,
            @Value("${shf.health.retry.jitter:500ms}") Duration jitter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(retryInterceptor(recoverer, maxRetries, delay, maxDelay, jitter));
        return factory;
    }

    static MethodInterceptor retryInterceptor(HealthFailureRecoverer recoverer, int maxRetries, Duration delay,
                                              Duration maxDelay, Duration jitter) {
        return RetryInterceptorBuilder.stateless()
                .configureRetryPolicy(policy -> policy
                        .maxRetries(maxRetries)
                        .delay(delay)
                        .multiplier(2)
                        .maxDelay(maxDelay)
                        .jitter(jitter)
                        .predicate(HealthMessagingConfig::isRetryable))
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
