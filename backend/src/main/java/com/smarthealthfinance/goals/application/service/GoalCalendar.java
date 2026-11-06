package com.smarthealthfinance.goals.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** "Hoje" dos objetivos no fuso de negócio (ADR-0006 §10), o mesmo do Overview, do Health e do Forecast. */
@Service
public class GoalCalendar {

    private final Clock clock;
    private final ZoneId zone;

    public GoalCalendar(Clock clock, @Value("${shf.overview.zone:America/Sao_Paulo}") ZoneId zone) {
        this.clock = clock;
        this.zone = zone;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }
}
