package com.echo.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 靜態資源快取：瀏覽器可保留 CSS／JS／字型，但每次驗證（未變更回 304），
 * 讓重新整理不必重新下載；index.html 則永遠取最新版。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaticResourceCachingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void appAssetsAreCachedButRevalidated() throws Exception {
        MvcResult first = mockMvc.perform(get("/console.css"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andReturn();
        String lastModified = first.getResponse().getHeader("Last-Modified");
        assertThat(lastModified).isNotBlank();

        mockMvc.perform(get("/console.css").header("If-Modified-Since", lastModified))
                .andExpect(status().isNotModified());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/webjars/vue/dist/vue.global.prod.js",
            "/webjars/bootstrap/dist/css/bootstrap.min.css",
            "/webjars/bootstrap-icons/font/bootstrap-icons.min.css",
            "/webjars/bootstrap-icons/font/fonts/bootstrap-icons.woff2",
            "/webjars/codemirror/lib/codemirror.js",
            "/webjars/codemirror/lib/codemirror.css",
            "/webjars/codemirror/mode/javascript/javascript.js",
            "/webjars/codemirror/mode/xml/xml.js",
            "/webjars/fontsource__jetbrains-mono/files/jetbrains-mono-latin-400-normal.woff2",
            "/webjars/fontsource__jetbrains-mono/files/jetbrains-mono-latin-500-normal.woff2",
            "/webjars/fontsource__jetbrains-mono/files/jetbrains-mono-latin-600-normal.woff2"
    })
    void webJarsAreCachedButRevalidated(String path) throws Exception {
        MvcResult first = mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andReturn();
        assertThat(first.getResponse().getContentAsByteArray()).isNotEmpty();
        String lastModified = first.getResponse().getHeader("Last-Modified");
        assertThat(lastModified).isNotBlank();
        mockMvc.perform(get(path).header("If-Modified-Since", lastModified))
                .andExpect(status().isNotModified());
    }

    @Test
    void missingWebJarAssetReturnsNotFound() throws Exception {
        mockMvc.perform(get("/webjars/bootstrap/dist/css/does-not-exist.css"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/webjars/not-an-installed-webjar/file.js"))
                .andExpect(status().isNotFound());
    }

    @Test
    void indexHtmlIsNeverStored() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }
}
