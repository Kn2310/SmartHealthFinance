package com.smarthealthfinance.ingestion.infrastructure.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.assertj.core.api.Assertions.assertThat;

/** Só falhas passageiras de infraestrutura são retentadas (ADR-0009 §15). */
class ImportRetryPolicyTest {

    @Test
    void retriesTransientInfrastructureFailures() {
        assertThat(ImportMessagingConfig.isRetryable(wrapped(new CannotAcquireLockException("deadlock")))).isTrue();
        assertThat(ImportMessagingConfig.isRetryable(wrapped(new OptimisticLockingFailureException("race"))))
                .isTrue();
        assertThat(ImportMessagingConfig.isRetryable(wrapped(new CannotGetJdbcConnectionException("down"))))
                .isTrue();
        assertThat(ImportMessagingConfig.isRetryable(wrapped(new CannotCreateTransactionException("down"))))
                .isTrue();
    }

    @Test
    void doesNotRetryBugsOrBadData() {
        assertThat(ImportMessagingConfig.isRetryable(wrapped(new IllegalStateException("bug")))).isFalse();
        assertThat(ImportMessagingConfig.isRetryable(wrapped(new DataIntegrityViolationException("check"))))
                .isFalse();
        assertThat(ImportMessagingConfig.isRetryable(wrapped(new AmqpRejectAndDontRequeueException("malformed",
                new CannotGetJdbcConnectionException("down"))))).isFalse();
    }

    private static Throwable wrapped(Throwable cause) {
        return new ListenerExecutionFailedException("Listener threw exception", cause);
    }
}
