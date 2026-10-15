package com.smarthealthfinance.transactions.infrastructure.startup;

import com.smarthealthfinance.transactions.application.usecase.RenormalizeMerchants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Renormalização dos comerciantes na inicialização (adendo de 2026-10-14 ao ADR-0013 §1, runbook
 * {@code merchant-renormalization.md}).
 * <ul>
 *   <li>{@code shf.transactions.merchant-renormalize=outdated} (padrão): transações com comerciante de versão
 *   anterior do normalizador. Faz o backfill de cada nova versão; idempotente.</li>
 *   <li>{@code off}: não faz nada.</li>
 * </ul>
 * Roda antes dos demais runners (o rebuild do Analytics lê as chaves já renormalizadas). Uma falha não derruba a API:
 * os eventos {@code transaction.categorized} corrigem a projeção quando a renormalização terminar numa próxima
 * inicialização.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MerchantRenormalizationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MerchantRenormalizationRunner.class);

    private final RenormalizeMerchants renormalize;
    private final String mode;

    public MerchantRenormalizationRunner(RenormalizeMerchants renormalize,
                                         @Value("${shf.transactions.merchant-renormalize:outdated}") String mode) {
        this.renormalize = renormalize;
        this.mode = mode.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public void run(ApplicationArguments args) {
        switch (mode) {
            case "off" -> {
                return;
            }
            case "outdated" -> {
            }
            default -> throw new IllegalStateException("shf.transactions.merchant-renormalize deve ser outdated ou off");
        }
        try {
            renormalize.execute();
        }
        catch (RuntimeException failure) {
            log.error("Merchant renormalization failed error={}", failure.getClass().getSimpleName());
        }
    }
}
