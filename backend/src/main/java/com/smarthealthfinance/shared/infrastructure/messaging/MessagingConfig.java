package com.smarthealthfinance.shared.infrastructure.messaging;

import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Topologia comum de eventos (spec 05.7): um exchange topic durável, roteado por {@code eventType}. Cada módulo
 * consumidor declara as próprias filas, DLQs e bindings.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class MessagingConfig {

    /** Exchange de todos os eventos de integração; a routing key é o {@code eventType}. */
    public static final String EVENTS_EXCHANGE = "shf.events";

    @Bean
    TopicExchange eventsExchange() {
        return ExchangeBuilder.topicExchange(EVENTS_EXCHANGE).durable(true).build();
    }
}
