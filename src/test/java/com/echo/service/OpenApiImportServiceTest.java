package com.echo.service;

import com.echo.dto.RuleDto;
import com.echo.entity.Protocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiImportServiceTest {

    private OpenApiImportService service;

    @BeforeEach
    void setUp() {
        service = new OpenApiImportService(new ObjectMapper());
    }

    @Test
    void parse_validOpenApi3_shouldReturnRules() {
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": { "title": "Pet Store", "version": "1.0.0" },
                  "paths": {
                    "/pets": {
                      "get": {
                        "summary": "List all pets",
                        "tags": ["pets"],
                        "responses": {
                          "200": {
                            "description": "A list of pets",
                            "content": {
                              "application/json": {
                                "example": [{"id": 1, "name": "Fido"}]
                              }
                            }
                          }
                        }
                      },
                      "post": {
                        "summary": "Create a pet",
                        "responses": {
                          "201": {
                            "description": "Pet created"
                          }
                        }
                      }
                    },
                    "/pets/{petId}": {
                      "get": {
                        "summary": "Get pet by ID",
                        "responses": {
                          "200": {
                            "description": "A pet",
                            "content": {
                              "application/json": {
                                "schema": {
                                  "type": "object",
                                  "properties": {
                                    "id": { "type": "integer" },
                                    "name": { "type": "string" },
                                    "tag": { "type": "string" }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;

        OpenApiImportService.OpenApiParseResult result = service.parse(spec);

        assertTrue(result.isSuccess());
        assertEquals("Pet Store", result.getTitle());
        assertEquals("1.0.0", result.getVersion());
        assertEquals(3, result.getRules().size());

        // GET /pets
        RuleDto getPets = result.getRules().get(0);
        assertEquals("/pets", getPets.getMatchKey());
        assertEquals("GET", getPets.getMethod());
        assertEquals(200, getPets.getStatus());
        assertEquals(Protocol.HTTP, getPets.getProtocol());
        assertTrue(getPets.getResponseBody().contains("Fido"));
        assertTrue(getPets.getDescription().contains("[OpenAPI]"));
        assertTrue(getPets.getDescription().contains("List all pets"));

        // POST /pets
        RuleDto postPets = result.getRules().get(1);
        assertEquals("/pets", postPets.getMatchKey());
        assertEquals("POST", postPets.getMethod());
        assertEquals(201, postPets.getStatus());

        // GET /pets/{petId} - schema-generated body
        RuleDto getPetById = result.getRules().get(2);
        assertEquals("/pets/{petId}", getPetById.getMatchKey());
        assertEquals("GET", getPetById.getMethod());
        assertTrue(getPetById.getResponseBody().contains("\"id\""));
        assertTrue(getPetById.getResponseBody().contains("\"name\""));
    }

    @Test
    void parse_invalidSpec_shouldReturnFailure() {
        String invalidSpec = "this is not a valid spec";

        OpenApiImportService.OpenApiParseResult result = service.parse(invalidSpec);

        assertFalse(result.isSuccess());
        assertFalse(result.getErrors().isEmpty());
    }

    @Test
    void parse_swagger2_shouldReturnRules() {
        // Swagger 2.0 spec - the parser auto-converts to OpenAPI 3.0
        String spec = """
                {
                  "swagger": "2.0",
                  "info": { "title": "Legacy API", "version": "2.0" },
                  "host": "api.example.com",
                  "basePath": "/v2",
                  "schemes": ["https"],
                  "paths": {
                    "/users": {
                      "get": {
                        "summary": "Get users",
                        "produces": ["application/json"],
                        "responses": {
                          "200": {
                            "description": "Success",
                            "schema": {
                              "type": "array",
                              "items": {
                                "type": "object",
                                "properties": {
                                  "id": { "type": "integer" },
                                  "name": { "type": "string" }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;

        OpenApiImportService.OpenApiParseResult result = service.parse(spec);

        assertTrue(result.isSuccess(), "Parse should succeed, errors: " + result.getErrors());
        assertEquals("Legacy API", result.getTitle());
        assertFalse(result.getRules().isEmpty(), "Should have at least one rule");
        RuleDto rule = result.getRules().get(0);
        assertTrue(rule.getMatchKey().contains("/users"));
        assertEquals("GET", rule.getMethod());
    }

    @Test
    void parse_schemaWithEnum_shouldUseFirstEnumValue() {
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": { "title": "Test", "version": "1.0" },
                  "paths": {
                    "/status": {
                      "get": {
                        "responses": {
                          "200": {
                            "description": "OK",
                            "content": {
                              "application/json": {
                                "schema": {
                                  "type": "object",
                                  "properties": {
                                    "status": {
                                      "type": "string",
                                      "enum": ["active", "inactive", "pending"]
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;

        OpenApiImportService.OpenApiParseResult result = service.parse(spec);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getRules().size());
        assertTrue(result.getRules().get(0).getResponseBody().contains("active"));
    }

    @Test
    void parse_schemaWithFormats_shouldGenerateAppropriateDefaults() {
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": { "title": "Test", "version": "1.0" },
                  "paths": {
                    "/data": {
                      "get": {
                        "responses": {
                          "200": {
                            "description": "OK",
                            "content": {
                              "application/json": {
                                "schema": {
                                  "type": "object",
                                  "properties": {
                                    "email": { "type": "string", "format": "email" },
                                    "created": { "type": "string", "format": "date-time" },
                                    "uuid": { "type": "string", "format": "uuid" },
                                    "count": { "type": "integer" },
                                    "score": { "type": "number" },
                                    "active": { "type": "boolean" }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;

        OpenApiImportService.OpenApiParseResult result = service.parse(spec);

        assertTrue(result.isSuccess());
        String body = result.getRules().get(0).getResponseBody();
        assertTrue(body.contains("user@example.com"));
        assertTrue(body.contains("2000-01-01T00:00:00Z"));
        assertTrue(body.contains("550e8400"));
    }

    @Test
    void parse_emptyPaths_shouldReturnEmptyRules() {
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": { "title": "Empty", "version": "1.0" },
                  "paths": {}
                }
                """;

        OpenApiImportService.OpenApiParseResult result = service.parse(spec);

        assertTrue(result.isSuccess());
        assertTrue(result.getRules().isEmpty());
    }

    @Test
    void parse_xmlContentType_shouldSetResponseHeaders() {
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": { "title": "XML API", "version": "1.0" },
                  "paths": {
                    "/data": {
                      "get": {
                        "responses": {
                          "200": {
                            "description": "OK",
                            "content": {
                              "application/xml": {
                                "example": "<root><name>test</name></root>"
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;

        OpenApiImportService.OpenApiParseResult result = service.parse(spec);

        assertTrue(result.isSuccess());
        RuleDto rule = result.getRules().get(0);
        assertNotNull(rule.getResponseHeaders());
        assertTrue(rule.getResponseHeaders().contains("application/xml"));
    }

    @Test
    void parse_multipleResponseCodes_shouldPrefer2xx() {
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": { "title": "Test", "version": "1.0" },
                  "paths": {
                    "/data": {
                      "delete": {
                        "responses": {
                          "404": { "description": "Not found" },
                          "204": { "description": "Deleted" },
                          "500": { "description": "Error" }
                        }
                      }
                    }
                  }
                }
                """;

        OpenApiImportService.OpenApiParseResult result = service.parse(spec);

        assertTrue(result.isSuccess());
        assertEquals(204, result.getRules().get(0).getStatus());
    }

    @Test
    void parse_allRulesHaveRequiredFields() {
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": { "title": "Test", "version": "1.0" },
                  "paths": {
                    "/a": { "get": { "responses": { "200": { "description": "OK" } } } },
                    "/b": { "post": { "responses": { "201": { "description": "Created" } } } }
                  }
                }
                """;

        OpenApiImportService.OpenApiParseResult result = service.parse(spec);

        assertTrue(result.isSuccess());
        for (RuleDto rule : result.getRules()) {
            assertEquals(Protocol.HTTP, rule.getProtocol());
            assertNotNull(rule.getMatchKey());
            assertNotNull(rule.getMethod());
            assertNotNull(rule.getStatus());
            assertNotNull(rule.getDescription());
            assertTrue(rule.getEnabled());
            assertEquals(0, rule.getPriority());
            assertEquals(0L, rule.getDelayMs());
        }
    }

    @Test
    void parse_tagsFromOperation_shouldBeIncluded() {
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": { "title": "Test", "version": "1.0" },
                  "paths": {
                    "/pets": {
                      "get": {
                        "tags": ["animals"],
                        "responses": { "200": { "description": "OK" } }
                      }
                    }
                  }
                }
                """;

        OpenApiImportService.OpenApiParseResult result = service.parse(spec);

        assertTrue(result.isSuccess());
        String tags = result.getRules().get(0).getTags();
        assertNotNull(tags);
        assertTrue(tags.contains("openapi"));
        assertTrue(tags.contains("animals"));
    }

    @Test
    void parse_arraySchema_shouldGenerateArrayBody() {
        String spec = """
                {
                  "openapi": "3.0.3",
                  "info": { "title": "Test", "version": "1.0" },
                  "paths": {
                    "/items": {
                      "get": {
                        "responses": {
                          "200": {
                            "description": "OK",
                            "content": {
                              "application/json": {
                                "schema": {
                                  "type": "array",
                                  "items": {
                                    "type": "object",
                                    "properties": {
                                      "id": { "type": "integer" },
                                      "name": { "type": "string" }
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;

        OpenApiImportService.OpenApiParseResult result = service.parse(spec);

        assertTrue(result.isSuccess());
        String body = result.getRules().get(0).getResponseBody();
        assertTrue(body.startsWith("["));
        assertTrue(body.contains("\"id\""));
    }
}
