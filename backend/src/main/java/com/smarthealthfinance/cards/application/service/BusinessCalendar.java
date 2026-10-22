package com.smarthealthfinance.cards.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** "Hoje" dos cartões no fuso de negócio (ADR-0006 §10), o mesmo do Overview, Analytics, Health e Insights. */
@Service
public class BusinessCalendar {

    private final Clock clock;
    private final ZoneId zone;

    public BusinessCalendar(Clock clock, @Value("${shf.overview.zone:America/Sao_Paulo}") ZoneId zone) {
        this.clock = clock;
        this.zone = zone;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }
}
