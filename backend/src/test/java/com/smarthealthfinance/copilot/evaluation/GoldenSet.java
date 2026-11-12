package com.smarthealthfinance.copilot.evaluation;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Carrega o golden set {@value #VERSION} de {@code classpath:copilot/golden/*.json} (ADR-0042 §1): um arquivo por
 * grupo, cada caso validado contra {@code golden-case.schema.json} antes de virar {@link GoldenCase}. O catálogo
 * {@code shared} do arquivo vale para os casos que não trazem o seu.
 */
public final class GoldenSet {

    public static final String VERSION = "golden-v1";
    static final String FOLDER = "copilot/golden/";
    static final String SCHEMA = FOLDER + "golden-case.schema.json";

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /** Um arquivo do golden set. */
    public record File(String name, String group, List<GoldenCase> cases) {
    }

    private GoldenSet() {
    }

    /** Todos os arquivos, em ordem de nome; erro de formato ou de schema falha com o arquivo e o campo. */
    public static List<File> load() {
        MiniJsonSchema schema = new MiniJsonSchema(read(new ClassPathResource(SCHEMA)));
        List<File> files = new ArrayList<>();
        for (Resource resource : resources()) {
            String name = resource.getFilename();
            JsonNode document = read(resource);
            List<String> errors = schema.validate(document);
            if (!errors.isEmpty()) {
                throw new IllegalStateException("golden file " + name + " violates the schema: " + errors);
            }
            files.add(new File(name, document.get("group").asString(), cases(name, document)));
        }
        return List.copyOf(files);
    }

    /** Todos os casos de todos os arquivos. */
    public static List<GoldenCase> cases() {
        return load().stream().flatMap(file -> file.cases().stream()).toList();
    }

    private static List<Resource> resources() {
        try {
            return Arrays.stream(new PathMatchingResourcePatternResolver().getResources("classpath*:" + FOLDER
                            + "*.json"))
                    .filter(resource -> resource.getFilename() != null
                            && !resource.getFilename().endsWith(".schema.json"))
                    .sorted(Comparator.comparing(Resource::getFilename)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<GoldenCase> cases(String name, JsonNode document) {
        if (!VERSION.equals(document.get("version").asString())) {
            throw new IllegalStateException("golden file " + name + " is not " + VERSION);
        }
        GoldenCase.Catalog shared = document.has("shared") && document.get("shared").has("catalog")
                ? JSON.treeToValue(document.get("shared").get("catalog"), GoldenCase.Catalog.class) : null;
        String group = document.get("group").asString();
        List<GoldenCase> cases = new ArrayList<>();
        for (JsonNode node : document.get("cases")) {
            GoldenCase parsed = JSON.treeToValue(node, GoldenCase.class);
            if (!group.equals(parsed.group())) {
                throw new IllegalStateException("case " + parsed.id() + " is in the file of group " + group);
            }
            cases.add(withSharedCatalog(parsed, shared));
        }
        Set<String> ids = new HashSet<>();
        cases.forEach(c -> {
            if (!ids.add(c.id())) {
                throw new IllegalStateException("duplicated case id " + c.id() + " in " + name);
            }
        });
        return List.copyOf(cases);
    }

    private static GoldenCase withSharedCatalog(GoldenCase golden, GoldenCase.Catalog shared) {
        GoldenCase.Fixtures fixtures = golden.fixtures();
        if (fixtures.catalog() != null || shared == null) {
            return golden;
        }
        return new GoldenCase(golden.id(), golden.group(), golden.note(), golden.question(),
                golden.previousQuestions(), golden.context(),
                new GoldenCase.Fixtures(shared, fixtures.entry(), fixtures.tool()), golden.scripted(),
                golden.expect());
    }

    /** Ids duplicados entre arquivos diferentes. */
    public static Set<String> duplicatedIds(List<File> files) {
        return files.stream().flatMap(file -> file.cases().stream()).map(GoldenCase::id)
                .collect(Collectors.groupingBy(id -> id, Collectors.counting())).entrySet().stream()
                .filter(entry -> entry.getValue() > 1).map(java.util.Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private static JsonNode read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
