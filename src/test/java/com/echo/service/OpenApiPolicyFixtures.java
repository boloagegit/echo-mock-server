package com.echo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/** Owned, inert input documents shared by reference-policy and real HTTP tests. */
final class OpenApiPolicyFixtures {
    static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    static final String MARKER = "OWNED-MARKER";

    private OpenApiPolicyFixtures() { }

    static ObjectNode object() { return JSON.createObjectNode(); }
    static ObjectNode ref(String uri) { return object().put("$ref", uri); }
    static ObjectNode schema() {
        return object().put("type", "object").set("properties",
                object().set("marker", object().put("type", "string").put("example", MARKER)));
    }

    static String document(String version, String kind, boolean yaml, String external) throws Exception {
        boolean v2 = version.equals("2.0");
        String root = v2 ? "#/definitions/" : "#/components/schemas/";
        ObjectNode doc = object().put(v2 ? "swagger" : "openapi", version);
        doc.set("info", object().put("title", "Owned policy fixture").put("version", "1.0"));
        ObjectNode components = object();
        ObjectNode definitions = object().set("Owned", schema());
        ObjectNode shape = ref(root + "Owned");
        switch (kind) {
            case "inline" -> shape = schema();
            case "array-ref" -> shape = object().put("type", "array").set("items", ref(root + "Owned"));
            case "nested-ref" -> shape = object().put("type", "object").set("properties", object().set("child", ref(root + "Owned")));
            case "allof-ref" -> shape = object().set("allOf", JSON.createArrayNode().add(ref(root + "Owned"))
                    .add(object().put("type", "object").set("properties", object().set("extra", object().put("type", "integer")))));
            case "escaped-schema-name" -> {
                definitions.remove("Owned"); definitions.set("Owned/Name~", schema());
                shape = ref(root + "Owned~1Name~0");
            }
            case "missing-local" -> shape = ref(root + "Missing");
            case "recursive-ref" -> ((ObjectNode) definitions.get("Owned").get("properties")).set("child", ref(root + "Owned"));
            case "reserved-property" -> shape = object().put("type", "object").set("properties", object().set("example", ref(root + "Owned")));
            case "external" -> shape = ref(external);
            case "indirect" -> {
                shape = ref("#/components/examples/Bridge/value");
                components.set("examples", object().set("Bridge", object().set("value", ref(external))));
            }
            case "pointer-aliases" -> {
                ObjectNode properties = object();
                for (int i = 0; i < 48; i++) properties.set("alias" + i, ref("#/" + "/".repeat(i) + root.substring(2) + "Owned"));
                shape = object().put("type", "object").set("properties", properties);
            }
            case "schema-example" -> {
                shape = schema(); shape.set("example", object().put("$ref", external).put("marker", MARKER));
            }
            case "schema-id" -> {
                shape = ref(root + "Owned").put("$id", external);
            }
            case "id-indirect" -> {
                shape = schema().put("$id", external).put("$ref", "#/example");
                shape.set("example", ref(external));
            }
            default -> { }
        }
        ObjectNode response = object().put("description", "ok");
        ObjectNode media = object().set("schema", shape);
        if (v2) response.set("schema", shape);
        else response.set("content", object().set("application/json", media));
        if (kind.equals("media-example")) {
            ObjectNode example = object().put("$ref", external).put("marker", MARKER);
            if (v2) response.set("examples", object().set("application/json", example));
            else media.set("example", example);
        }
        if (kind.equals("example-ref") && !v2) {
            components.set("examples", object().set("OwnedExample", object().set("value", object().put("marker", MARKER))));
            media.set("examples", object().set("one", ref("#/components/examples/OwnedExample")));
        }
        ObjectNode responses = object().set("200", response);
        if (kind.equals("response-ref")) {
            if (v2) doc.set("responses", object().set("OwnedResponse", response));
            else components.set("responses", object().set("OwnedResponse", response));
            responses.set("200", ref(v2 ? "#/responses/OwnedResponse" : "#/components/responses/OwnedResponse"));
        }
        ObjectNode path = object().set("get", object().set("responses", responses));
        ObjectNode paths = object().set("/owned", path);
        if (kind.equals("path-ref")) paths.set("/alias", ref("#/paths/~1owned"));
        doc.set("paths", paths);
        if (kind.equals("metadata-url")) doc.set("externalDocs", object().put("url", external));
        if (v2) doc.set("definitions", definitions);
        else { components.set("schemas", definitions); doc.set("components", components); }
        return (yaml ? YAML : JSON).writeValueAsString(doc);
    }
}
