package com.echo.config;

import com.github.jknack.handlebars.Handlebars;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateDependencySecurityTest {
    @Test
    void legacyJavaScriptIsAbsentFromTheRuntimeDependency() {
        ClassLoader loader = Handlebars.class.getClassLoader();
        assertThat(loader.getResource("handlebars-v4.7.10.js")).isNotNull();
        assertThat(loader.getResource("handlebars-v4.7.9.js")).isNull();
    }
}
