package com.echo.controller;

import com.echo.config.LdapConfig;
import com.echo.config.SecurityConfig;
import com.echo.repository.BuiltinUserRepository;
import com.echo.service.IssueReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IssueReportController.class)
@Import({SecurityConfig.class, LdapConfig.class})
class IssueReportingDisabledTest {
    @Autowired private MockMvc mvc;
    @Autowired private ApplicationContext context;
    @MockitoBean private IssueReportService reports;
    @MockitoBean @SuppressWarnings("UnusedVariable") private BuiltinUserRepository builtinUsers;

    @Test
    void issueReportingIsDisabledByDefault() {
        assertThat(context.getBeansOfType(IssueReportController.class)).isEmpty();
        verifyNoInteractions(reports);
    }

    @Test @WithMockUser(roles = "ADMIN")
    void everyIssueEndpointIsUnavailableWithoutAccessingOrChangingReports() throws Exception {
        for (String path : new String[]{"", "/page", "/count", "/example"}) {
            mvc.perform(get("/api/admin/issues" + path).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/admin/issues").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Example\",\"description\":\"Details\"}"))
                .andExpect(status().isNotFound());
        for (String action : new String[]{"reply", "resolve", "reopen"}) {
            mvc.perform(put("/api/admin/issues/example/" + action).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reply\":\"Response\"}"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(delete("/api/admin/issues/example")).andExpect(status().isNotFound());
        verifyNoInteractions(reports);
    }

    @Test
    void anonymousIssueRequestsStillRequireAuthentication() throws Exception {
        mvc.perform(get("/api/admin/issues/page").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reports);
    }
}
