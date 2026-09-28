package com.smarthealthfinance.shared.infrastructure.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

@Configuration(proxyBeanMethods = false)
class ClockConfig {

    /**
     * UTC com precisão de microssegundos, a mesma do timestamptz do PostgreSQL: o instante devolvido
     * ao criar um recurso é idêntico ao lido do banco depois.
     */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.of(1, ChronoUnit.MICROS));
    }
}
