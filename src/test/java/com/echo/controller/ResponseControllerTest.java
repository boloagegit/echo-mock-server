package com.echo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.echo.entity.Response;
import com.echo.protocol.ProtocolHandlerRegistry;
import com.echo.service.ResponseService;
import com.echo.service.RuleAuditService;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.Optional;

class ResponseControllerTest {
    private final ResponseService service = mock(ResponseService.class);
    private final RuleAuditService audit = mock(RuleAuditService.class);
    private final ResponseController controller =
            new ResponseController(
                    service, mock(ProtocolHandlerRegistry.class), Optional.of(audit));

    private Response existing() {
        Response response =
                Response.builder()
                        .id(7L)
                        .version(3L)
                        .incarnation("row-identity")
                        .description("original")
                        .body("{}")
                        .build();
        when(service.findById(7L)).thenReturn(Optional.of(response));
        return response;
    }

    @Test
    void exactRepeatPreservesRevisionWithoutSavingOrAuditing() {
        Response existing = existing();
        var result =
                controller.updateResponse(7L, Response.builder().version(3L).body("{}").build());
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(existing);
        verify(service).validateSseContent(any());
        verify(service, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void exactRepeatStillRejectsPreviouslyInvalidContent() {
        existing();
        doThrow(new IllegalArgumentException("Invalid legacy content"))
                .when(service)
                .validateSseContent(any());
        assertThatThrownBy(
                        () -> controller.updateResponse(7L, Response.builder().body("{}").build()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(service, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void staleRepeatIsRejectedBeforeNoOpDetection() {
        existing();
        var result =
                controller.updateResponse(7L, Response.builder().version(2L).body("{}").build());
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(service, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void metadataEditPreservesInternalRowIdentityAndSaves() {
        Response existing = existing();
        Response incoming = Response.builder().description("renamed").body("{}").build();
        when(service.save(incoming)).thenReturn(incoming);
        var result = controller.updateResponse(7L, incoming);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(incoming.getIncarnation()).isEqualTo(existing.getIncarnation());
        assertThat(incoming.getVersion()).isEqualTo(existing.getVersion());
        verify(service).save(incoming);
        verify(audit).logResponseUpdate(any(), same(incoming));
    }

    @Test
    void retentionChangesAreNotMistakenForNoOps() {
        Response existing = existing();
        existing.setExtendedAt(LocalDateTime.of(2026, 10, 25, 0, 0));
        Response incoming = Response.builder().body("{}").build();
        when(service.save(incoming)).thenReturn(incoming);
        assertThat(controller.updateResponse(7L, incoming).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        verify(service).save(incoming);
        assertThat(incoming.getExtendedAt()).isNull();
    }

    @Test
    void concurrentWriteStillReturnsConflict() {
        existing();
        Response incoming = Response.builder().body("changed").build();
        when(service.save(incoming))
                .thenThrow(new ObjectOptimisticLockingFailureException(Response.class, 7L));
        assertThat(controller.updateResponse(7L, incoming).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(audit);
    }
}
