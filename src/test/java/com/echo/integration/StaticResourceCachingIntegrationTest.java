package com.echo.integration;

import org.junit.jupiter.api.Test;
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

    @Test
    void webJarsAreCachedButRevalidated() throws Exception {
        mockMvc.perform(get("/webjars/bootstrap-icons/font/bootstrap-icons.min.css"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void indexHtmlIsNeverStored() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }
}
