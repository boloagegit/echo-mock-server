package com.echo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.reference.ReferenceUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Validates reference scope before the unchanged full parser is allowed to resolve anything. */
final class OpenApiLocalReferenceGuard {
    static final String EXTERNAL_DISABLED = "OPENAPI_EXTERNAL_REFERENCE_DISABLED";
    static final String INVALID_LOCAL = "OPENAPI_INVALID_LOCAL_REFERENCE";
    static final String INSPECTION_FAILED = "OPENAPI_REFERENCE_INSPECTION_FAILED";

    private OpenApiLocalReferenceGuard() { }

    /** Null permits normal parsing, including its existing missing-fragment diagnostics. */
    static String validate(String content) {
        try {
            ParseOptions options = new ParseOptions();
            options.setResolve(false);
            options.setResolveFully(false);
            var parsed = new OpenAPIV3Parser().readContents(content, null, options);
            if (parsed.getOpenAPI() != null) {
                var api = parsed.getOpenAPI();
                ObjectMapper mapper = api.getOpenapi() != null && api.getOpenapi().startsWith("3.1")
                        ? io.swagger.v3.core.util.Json31.mapper() : io.swagger.v3.core.util.Json.mapper();
                return inspect(api, mapper);
            }
            // Inspect the original Swagger 2 model before conversion normalizes file references.
            var legacy = new io.swagger.parser.SwaggerParser().readWithInfo(content, false);
            return legacy.getSwagger() == null ? null : inspect(legacy.getSwagger(), io.swagger.util.Json.mapper());
        } catch (RuntimeException failure) {
            // Never fall through to resolving uninspected content.
            return INSPECTION_FAILED;
        }
    }

    private static String inspect(Object root, ObjectMapper mapper) {
        JsonNode document = mapper.valueToTree(root);
        ArrayDeque<Object> work = new ArrayDeque<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<JsonNode, Set<Class<?>>> inspectedTargets = new IdentityHashMap<>();
        Map<Class<?>, Method[]> getters = new HashMap<>();
        work.add(root);
        while (!work.isEmpty()) {
            Object value = work.removeLast();
            if (!seen.add(value)) continue;
            if (value instanceof Map<?, ?> map) {
                for (Object child : map.values()) enqueue(work, child);
            } else if (value instanceof Iterable<?> items) {
                for (Object child : items) enqueue(work, child);
            } else if (isModel(value)) {
                boolean legacyRef = isLegacyReference(value);
                for (Method method : getters.computeIfAbsent(value.getClass(), OpenApiLocalReferenceGuard::getters)) {
                    String name = method.getName();
                    // Swagger 2 Reference Objects intentionally reject access to inline-only fields.
                    if (legacyRef && !name.equals("getOriginalRef") && !name.equals("get$ref")) continue;
                    Object child;
                    try {
                        child = method.invoke(value);
                    } catch (ReflectiveOperationException failure) {
                        return INSPECTION_FAILED;
                    }
                    if (isReferenceGetter(name) && child instanceof String ref && !ref.isEmpty()) {
                        if (!ref.startsWith("#")) return EXTERNAL_DISABLED;
                        if (ref.startsWith("#/")) {
                            JsonNode target;
                            try {
                                String fragment = ReferenceUtils.getFragment(ref);
                                target = ReferenceUtils.jsonPointerEvaluate(fragment, document, ref);
                            } catch (Exception missingOrMalformedFragment) {
                                // A missing fragment cannot load a document. Keep the full parser's diagnostics.
                                continue;
                            }
                            Class<?> targetType = referenceType(value);
                            // Pointer aliases must not repeatedly materialize the same potentially large target.
                            if (inspectedTargets.computeIfAbsent(target, ignored -> new HashSet<>()).add(targetType)) {
                                try {
                                    enqueue(work, mapper.convertValue(target, targetType));
                                } catch (IllegalArgumentException invalidTarget) {
                                    return INVALID_LOCAL;
                                }
                            }
                        }
                    }
                    enqueue(work, child);
                }
            }
        }
        return null;
    }

    private static void enqueue(ArrayDeque<Object> work, Object value) {
        if (value instanceof Map<?, ?> || value instanceof Iterable<?> || isModel(value)) work.add(value);
    }

    private static boolean isModel(Object value) {
        if (value == null || value.getClass().isEnum()) return false;
        String namespace = value.getClass().getPackageName();
        return namespace.equals("io.swagger.v3.oas.models") || namespace.startsWith("io.swagger.v3.oas.models.")
                || namespace.equals("io.swagger.models") || namespace.startsWith("io.swagger.models.");
    }

    private static boolean isReferenceGetter(String name) {
        return name.equals("getOriginalRef") || name.equals("get$ref")
                || name.equals("get$dynamicRef") || name.equals("get$recursiveRef");
    }

    private static Method[] getters(Class<?> type) {
        Method[] methods = Arrays.stream(type.getMethods())
                .filter(method -> method.getParameterCount() == 0 && !Modifier.isStatic(method.getModifiers())
                        && !method.isBridge() && !method.isSynthetic() && method.getName().startsWith("get")
                        && !method.getName().equals("getClass") && !method.getName().equals("getJsonSchema")
                        && !method.getName().equals("getJsonSchemaImpl") && !method.getReturnType().isPrimitive())
                .toArray(Method[]::new);
        Arrays.sort(methods, Comparator.comparingInt(method -> method.getName().equals("getOriginalRef") ? 0 : 1));
        return methods;
    }

    private static boolean isLegacyReference(Object value) {
        return value instanceof io.swagger.models.RefModel || value instanceof io.swagger.models.properties.RefProperty
                || value instanceof io.swagger.models.RefResponse || value instanceof io.swagger.models.RefPath
                || value instanceof io.swagger.models.parameters.RefParameter;
    }

    private static Class<?> referenceType(Object value) {
        if (value instanceof Schema) return Schema.class;
        if (value instanceof io.swagger.models.Model) return io.swagger.models.Model.class;
        if (value instanceof io.swagger.models.properties.Property) return io.swagger.models.properties.Property.class;
        if (value instanceof io.swagger.models.RefResponse) return io.swagger.models.Response.class;
        if (value instanceof io.swagger.models.RefPath) return io.swagger.models.Path.class;
        if (value instanceof io.swagger.models.parameters.RefParameter) return io.swagger.models.parameters.Parameter.class;
        return value.getClass();
    }
}
