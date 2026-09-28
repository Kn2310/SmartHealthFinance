package com.smarthealthfinance;

import static org.assertj.core.api.Assertions.assertThat;

import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class ApplicationSmokeIT extends IntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void flywayAppliesBaselineMigration() {
		Integer applied = jdbc.queryForObject(
				"select count(*) from flyway_schema_history where version = '1' and success", Integer.class);

		assertThat(applied).isEqualTo(1);
	}

	@Test
	void healthIsUpWithAllInfrastructureComponents() {
		assertThat(mvc.get().uri("/actuator/health")).hasStatus(HttpStatus.OK)
			.bodyJson()
			.hasPathSatisfying("$.status", status -> assertThat(status).asString().isEqualTo("UP"))
			.hasPathSatisfying("$.components.db.status", status -> assertThat(status).asString().isEqualTo("UP"))
			.hasPathSatisfying("$.components.redis.status", status -> assertThat(status).asString().isEqualTo("UP"))
			.hasPathSatisfying("$.components.rabbit.status", status -> assertThat(status).asString().isEqualTo("UP"));
	}

	@Test
	void readinessProbeIsExposed() {
		assertThat(mvc.get().uri("/actuator/health/readiness")).hasStatus(HttpStatus.OK);
		assertThat(mvc.get().uri("/actuator/health/liveness")).hasStatus(HttpStatus.OK);
	}

}
