package com.echo.controller;

import com.echo.config.LdapConfig;
import com.echo.config.SecurityConfig;
import com.echo.entity.IssueReport;
import com.echo.repository.BuiltinUserRepository;
import com.echo.service.IssueReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IssueReportController.class)
@Import({SecurityConfig.class, LdapConfig.class})
@TestPropertySource(properties = "echo.features.issue-reporting-enabled=true")
class IssueReportingEnabledTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private IssueReportService reports;
    @MockitoBean @SuppressWarnings("UnusedVariable") private BuiltinUserRepository builtinUsers;

    private IssueReport report() {
        return IssueReport.builder().id("example").title("Example").description("Details")
                .status(IssueReport.IssueStatus.OPEN).createdBy("reporter").version(0L).build();
    }

    @Test @WithMockUser(username = "reporter", roles = "USER")
    void enabledFeaturePreservesListDetailPagingCountAndCreation() throws Exception {
        when(reports.findAll()).thenReturn(List.of(report()));
        when(reports.findById("example")).thenReturn(Optional.of(report()));
        when(reports.countOpen()).thenReturn(1L);
        when(reports.query(isNull(), isNull(), eq(0), eq(20), eq("createdAt"), eq("desc")))
                .thenReturn(new PageImpl<>(List.of(report()), PageRequest.of(0, 20), 1));
        when(reports.create("Example", "Details", "reporter")).thenReturn(report());
        mvc.perform(get("/api/admin/issues").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("example"));
        mvc.perform(get("/api/admin/issues/example").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("example"));
        mvc.perform(get("/api/admin/issues/page").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].id").value("example"))
                .andExpect(jsonPath("$.openCount").value(1));
        mvc.perform(get("/api/admin/issues/count").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(1));
        mvc.perform(post("/api/admin/issues").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Example\",\"description\":\"Details\"}"))
                .andExpect(status().isCreated());
        verify(reports).create("Example", "Details", "reporter");
    }

    @Test @WithMockUser(username = "admin", roles = "ADMIN")
    void enabledFeaturePreservesAdministration() throws Exception {
        when(reports.reply("example", "Response", "admin")).thenReturn(report());
        when(reports.resolve("example", "admin")).thenReturn(report());
        when(reports.reopen("example")).thenReturn(report());
        mvc.perform(put("/api/admin/issues/example/reply").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content("{\"reply\":\"Response\"}")).andExpect(status().isOk());
        mvc.perform(put("/api/admin/issues/example/resolve").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk());
        mvc.perform(put("/api/admin/issues/example/reopen").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk());
        mvc.perform(delete("/api/admin/issues/example").accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        verify(reports).delete("example");
    }

    @Test @WithMockUser(roles = "USER")
    void enablingFeatureDoesNotGrantAdministrationToRegularUsers() throws Exception {
        for (String action : new String[]{"reply", "resolve", "reopen"}) {
            mvc.perform(put("/api/admin/issues/example/" + action).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .content("{\"reply\":\"Response\"}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(delete("/api/admin/issues/example").accept(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden());
        verifyNoInteractions(reports);
    }

    @Test
    void enablingFeatureDoesNotMakeReportsPublic() throws Exception {
        mvc.perform(get("/api/admin/issues/page").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reports);
    }

    @Test @WithMockUser(roles = "USER")
    void validationIsUnchanged() throws Exception {
        mvc.perform(post("/api/admin/issues").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"Details\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("TITLE_REQUIRED"));
        verifyNoInteractions(reports);
    }

    @Test @WithMockUser(username = "admin", roles = "ADMIN")
    void optimisticLockConflictsStillReturn409() throws Exception {
        when(reports.reply("example", "Response", "admin"))
                .thenThrow(new ObjectOptimisticLockingFailureException(IssueReport.class, "example"));
        mvc.perform(put("/api/admin/issues/example/reply").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content("{\"reply\":\"Response\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("OPTIMISTIC_LOCK_CONFLICT"));
    }
}
