package com.smarthealthfinance.copilot.evaluation;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validador mínimo de JSON Schema (draft 2020-12) só para o {@code golden-case.schema.json}: o projeto não tem
 * biblioteca de JSON Schema e o ADR-0042 não autoriza dependência nova. Suporta {@code type}, {@code enum},
 * {@code const}, {@code required}, {@code properties}, {@code additionalProperties}, {@code items}, {@code minItems},
 * {@code minLength}, {@code minimum}, {@code pattern}, {@code anyOf} e {@code $ref} local ({@code #/$defs/x}).
 * Qualquer palavra-chave fora dessa lista (exceto as de anotação) é erro: o schema não pode prometer o que não é
 * conferido.
 */
final class MiniJsonSchema {

    private static final List<String> ANNOTATIONS = List.of("$schema", "$id", "title", "description", "$defs",
            "$comment", "examples", "default");
    private static final List<String> SUPPORTED = List.of("type", "enum", "const", "required", "properties",
            "additionalProperties", "items", "minItems", "minLength", "minimum", "pattern", "anyOf", "$ref");

    private final JsonNode root;

    MiniJsonSchema(JsonNode root) {
        this.root = root;
    }

    /** Mensagens de erro com o caminho do campo; vazio quando o documento é válido. */
    List<String> validate(JsonNode document) {
        List<String> errors = new ArrayList<>();
        check(root, document, "$", errors);
        return errors;
    }

    private void check(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (schema.isBoolean()) {
            if (!schema.asBoolean()) {
                errors.add(path + ": not allowed");
            }
            return;
        }
        for (String keyword : schema.propertyNames()) {
            if (!ANNOTATIONS.contains(keyword) && !SUPPORTED.contains(keyword)) {
                throw new IllegalStateException("schema keyword not supported by MiniJsonSchema: " + keyword);
            }
        }
        if (schema.has("$ref")) {
            check(resolve(schema.get("$ref").asString()), value, path, errors);
            return;
        }
        if (schema.has("anyOf")) {
            boolean any = false;
            for (JsonNode option : schema.get("anyOf")) {
                List<String> sub = new ArrayList<>();
                check(option, value, path, sub);
                if (sub.isEmpty()) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                errors.add(path + ": matches none of the anyOf options");
                return;
            }
        }
        if (schema.has("type") && !typeMatches(schema.get("type"), value)) {
            errors.add(path + ": expected type " + schema.get("type") + " but was " + value.getNodeType());
            return;
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode option : schema.get("enum")) {
                found |= option.equals(value);
            }
            if (!found) {
                errors.add(path + ": value is not one of " + schema.get("enum"));
            }
        }
        if (schema.has("const") && !schema.get("const").equals(value)) {
            errors.add(path + ": must be " + schema.get("const"));
        }
        if (value.isString()) {
            if (schema.has("minLength") && value.asString().length() < schema.get("minLength").asInt()) {
                errors.add(path + ": shorter than " + schema.get("minLength"));
            }
            if (schema.has("pattern") && !Pattern.compile(schema.get("pattern").asString())
                    .matcher(value.asString()).find()) {
                errors.add(path + ": does not match " + schema.get("pattern"));
            }
        }
        if (value.isNumber() && schema.has("minimum") && value.asDouble() < schema.get("minimum").asDouble()) {
            errors.add(path + ": below " + schema.get("minimum"));
        }
        if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) {
                errors.add(path + ": fewer than " + schema.get("minItems") + " items");
            }
            if (schema.has("items")) {
                for (int i = 0; i < value.size(); i++) {
                    check(schema.get("items"), value.get(i), path + "[" + i + "]", errors);
                }
            }
        }
        if (value.isObject()) {
            checkObject(schema, value, path, errors);
        }
    }

    private void checkObject(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (schema.has("required")) {
            for (JsonNode name : schema.get("required")) {
                if (!value.has(name.asString())) {
                    errors.add(path + ": missing required property " + name.asString());
                }
            }
        }
        JsonNode properties = schema.get("properties");
        for (String name : value.propertyNames()) {
            JsonNode child = value.get(name);
            if (properties != null && properties.has(name)) {
                check(properties.get(name), child, path + "." + name, errors);
            } else if (schema.has("additionalProperties")) {
                check(schema.get("additionalProperties"), child, path + "." + name, errors);
            }
        }
    }

    private JsonNode resolve(String ref) {
        if (!ref.startsWith("#/")) {
            throw new IllegalStateException("only local $ref is supported: " + ref);
        }
        JsonNode node = root;
        for (String part : ref.substring(2).split("/")) {
            node = node.get(part);
            if (node == null) {
                throw new IllegalStateException("unresolved $ref " + ref);
            }
        }
        return node;
    }

    private static boolean typeMatches(JsonNode type, JsonNode value) {
        if (type.isArray()) {
            for (JsonNode option : type) {
                if (typeMatches(option, value)) {
                    return true;
                }
            }
            return false;
        }
        return switch (type.asString()) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isString();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            default -> throw new IllegalStateException("unsupported type " + type);
        };
    }
}
