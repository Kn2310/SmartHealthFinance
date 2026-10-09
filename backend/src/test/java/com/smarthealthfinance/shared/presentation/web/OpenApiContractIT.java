package com.smarthealthfinance.shared.presentation.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;

import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Snapshot do contrato OpenAPI (ADR-0010). O spec gerado pelo springdoc precisa ser igual ao versionado em
 * {@code src/test/resources/openapi/openapi.json}; o frontend valida o {@code schema.d.ts} contra o mesmo arquivo.
 * Mudou a API de propósito: rode com {@code -Dshf.openapi.update=true} e revise o diff no PR.
 */
class OpenApiContractIT extends IntegrationTest {

	static final Path SNAPSHOT = Path.of("src/test/resources/openapi/openapi.json");

	static final Path GENERATED = Path.of("target/openapi/openapi.json");

	/** Endpoints que só existem no contexto de teste ({@code support.ProbeController}). */
	private static final String TEST_ONLY_PATH_PREFIX = "/api/v1/test-probe";

	private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

	@Test
	void generatedSpecMatchesTheVersionedSnapshot() throws IOException {
		MvcTestResult result = mvc.get().uri("/v3/api-docs").exchange();
		assertThat(result).hasStatus(HttpStatus.OK);

		String generated = normalize(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
		Files.createDirectories(GENERATED.getParent());
		Files.writeString(GENERATED, generated);

		if (Boolean.getBoolean("shf.openapi.update")) {
			Files.createDirectories(SNAPSHOT.getParent());
			Files.writeString(SNAPSHOT, generated);
		}

		assertThat(SNAPSHOT).as("snapshot do OpenAPI ausente: rode ./mvnw verify -Dshf.openapi.update=true").exists();
		assertThat(JSON.readTree(Files.readString(SNAPSHOT)))
			.as("O OpenAPI mudou. Compare %s com %s; se a mudança for intencional, rode "
					+ "./mvnw verify -Dshf.openapi.update=true e atualize frontend/src/lib/api/schema.d.ts",
					SNAPSHOT, GENERATED)
			.isEqualTo(JSON.readTree(generated));
	}

	/** Remove o que varia por ambiente ou só existe em teste e ordena as chaves (diff estável no PR). */
	private static String normalize(String spec) {
		ObjectNode root = (ObjectNode) JSON.readTree(spec);
		root.remove("servers");
		if (root.get("paths") instanceof ObjectNode paths) {
			paths.propertyNames().stream().filter(p -> p.startsWith(TEST_ONLY_PATH_PREFIX)).toList()
				.forEach(paths::remove);
		}
		if (root.path("components").get("schemas") instanceof ObjectNode schemas) {
			schemas.remove("ProbeRequest");
		}
		return JSON.writeValueAsString(sorted(root)) + "\n";
	}

	private static JsonNode sorted(JsonNode node) {
		if (node instanceof ObjectNode object) {
			ObjectNode copy = JSON.createObjectNode();
			TreeMap<String, JsonNode> fields = new TreeMap<>();
			object.properties().forEach(e -> fields.put(e.getKey(), sorted(e.getValue())));
			fields.forEach(copy::set);
			return copy;
		}
		if (node instanceof ArrayNode array) {
			ArrayNode copy = JSON.createArrayNode();
			array.forEach(item -> copy.add(sorted(item)));
			return copy;
		}
		return node;
	}

}
