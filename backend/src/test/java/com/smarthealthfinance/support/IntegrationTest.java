package com.smarthealthfinance.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Base dos testes de integração: aplicação completa + PostgreSQL, RabbitMQ e Redis reais.
 * Todas as subclasses compartilham o mesmo contexto (e containers) — não adicione configuração por classe.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, ProbeController.class })
public abstract class IntegrationTest {

	@Autowired
	protected MockMvcTester mvc;

}
