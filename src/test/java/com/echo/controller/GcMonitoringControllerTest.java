package com.echo.controller;

import com.echo.config.LdapConfig;
import com.echo.config.SecurityConfig;
import com.echo.repository.BuiltinUserRepository;
import com.echo.service.GcMonitoringService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GcMonitoringController.class)
@Import({SecurityConfig.class, LdapConfig.class})
class GcMonitoringControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private GcMonitoringService monitoring;
    @MockitoBean @SuppressWarnings("UnusedVariable") private BuiltinUserRepository builtinUsers;

    @Test
    void anonymousCannotReadHistory() throws Exception {
        mvc.perform(get("/api/admin/resources/gc")).andExpect(status().isUnauthorized());
        verifyNoInteractions(monitoring);
    }

    @Test @WithMockUser(roles = "USER")
    void regularUserCannotReadHistory() throws Exception {
        mvc.perform(get("/api/admin/resources/gc")).andExpect(status().isForbidden());
        verifyNoInteractions(monitoring);
    }

    @Test @WithMockUser(roles = "ADMIN")
    void adminCanReadHistoryButCannotTriggerGc() throws Exception {
        mvc.perform(get("/api/admin/resources/gc").param("after", "25").param("limit", "100"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(monitoring).snapshot(25L, 100);
        mvc.perform(post("/api/admin/resources/gc")).andExpect(status().isMethodNotAllowed());
    }

    @Test @WithMockUser(roles = "ADMIN")
    void invalidOrUnboundedQueriesAreRejectedBeforeCollection() throws Exception {
        for (String query : new String[]{"?after=-1", "?limit=0", "?limit=513", "?after=wrong"}) {
            mvc.perform(get("/api/admin/resources/gc" + query)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(monitoring);
    }
}
