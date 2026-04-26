package com.echo.client;

import feign.Request;
import feign.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for MockRedirectClient.
 */
@ExtendWith(MockitoExtension.class)
class MockRedirectClientTest {

    @Mock
    private feign.Client delegateClient;

    @Mock
    private Response mockResponse;

    private MockRedirectClient mockRedirectClient;

    @BeforeEach
    void setUp() {
        mockRedirectClient = new MockRedirectClient(delegateClient, "http://localhost:8080");
    }

    @Test
    @DisplayName("execute rewrites HTTPS URL to Echo Server URL")
    void execute_RewritesHttpsUrlToEchoServer() throws IOException {
        // Given
        Request originalRequest = Request.create(
                Request.HttpMethod.GET,
                "https://api.google.com/foo",
                Collections.emptyMap(),
                null,
                StandardCharsets.UTF_8,
                null
        );

        when(delegateClient.execute(any(Request.class), any(Request.Options.class)))
                .thenReturn(mockResponse);

        // When
        mockRedirectClient.execute(originalRequest, new Request.Options());

        // Then
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(delegateClient).execute(requestCaptor.capture(), any(Request.Options.class));

        Request capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.url()).isEqualTo("http://localhost:8080/foo");
    }

    @Test
    @DisplayName("execute sets X-Original-Host header correctly")
    void execute_SetsOriginalHostHeader() throws IOException {
        // Given
        Request originalRequest = Request.create(
                Request.HttpMethod.GET,
                "https://api.google.com/foo",
                Collections.emptyMap(),
                null,
                StandardCharsets.UTF_8,
                null
        );

        when(delegateClient.execute(any(Request.class), any(Request.Options.class)))
                .thenReturn(mockResponse);

        // When
        mockRedirectClient.execute(originalRequest, new Request.Options());

        // Then
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(delegateClient).execute(requestCaptor.capture(), any(Request.Options.class));

        Request capturedRequest = requestCaptor.getValue();
        Collection<String> originalHostHeader = capturedRequest.headers().get("X-Original-Host");
        assertThat(originalHostHeader).isNotNull().hasSize(1);
        assertThat(originalHostHeader.iterator().next()).isEqualTo("api.google.com");
    }

    @Test
    @DisplayName("execute preserves original request path")
    void execute_PreservesOriginalPath() throws IOException {
        // Given
        Request originalRequest = Request.create(
                Request.HttpMethod.POST,
                "http://example.com/api/v1/users/123",
                Collections.emptyMap(),
                null,
                StandardCharsets.UTF_8,
                null
        );

        when(delegateClient.execute(any(Request.class), any(Request.Options.class)))
                .thenReturn(mockResponse);

        // When
        mockRedirectClient.execute(originalRequest, new Request.Options());

        // Then
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(delegateClient).execute(requestCaptor.capture(), any(Request.Options.class));

        Request capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.url()).isEqualTo("http://localhost:8080/api/v1/users/123");
    }

    @Test
    @DisplayName("execute preserves query string")
    void execute_PreservesQueryString() throws IOException {
        // Given
        Request originalRequest = Request.create(
                Request.HttpMethod.GET,
                "https://api.example.com/search?q=test&page=1",
                Collections.emptyMap(),
                null,
                StandardCharsets.UTF_8,
                null
        );

        when(delegateClient.execute(any(Request.class), any(Request.Options.class)))
                .thenReturn(mockResponse);

        // When
        mockRedirectClient.execute(originalRequest, new Request.Options());

        // Then
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(delegateClient).execute(requestCaptor.capture(), any(Request.Options.class));

        Request capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.url()).isEqualTo("http://localhost:8080/search?q=test&page=1");
    }

    @Test
    @DisplayName("execute includes port in X-Original-Host when present")
    void execute_IncludesPortInOriginalHost() throws IOException {
        // Given
        Request originalRequest = Request.create(
                Request.HttpMethod.GET,
                "http://api.example.com:9090/api/test",
                Collections.emptyMap(),
                null,
                StandardCharsets.UTF_8,
                null
        );

        when(delegateClient.execute(any(Request.class), any(Request.Options.class)))
                .thenReturn(mockResponse);

        // When
        mockRedirectClient.execute(originalRequest, new Request.Options());

        // Then
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(delegateClient).execute(requestCaptor.capture(), any(Request.Options.class));

        Request capturedRequest = requestCaptor.getValue();
        Collection<String> originalHostHeader = capturedRequest.headers().get("X-Original-Host");
        assertThat(originalHostHeader.iterator().next()).isEqualTo("api.example.com:9090");
    }

    @Test
    @DisplayName("execute preserves existing headers")
    void execute_PreservesExistingHeaders() throws IOException {
        // Given
        Map<String, Collection<String>> headers = new HashMap<>();
        headers.put("Content-Type", Collections.singletonList("application/json"));
        headers.put("Authorization", Collections.singletonList("Bearer token123"));

        Request originalRequest = Request.create(
                Request.HttpMethod.POST,
                "https://api.example.com/api",
                headers,
                "{\"data\": \"test\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8,
                null
        );

        when(delegateClient.execute(any(Request.class), any(Request.Options.class)))
                .thenReturn(mockResponse);

        // When
        mockRedirectClient.execute(originalRequest, new Request.Options());

        // Then
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(delegateClient).execute(requestCaptor.capture(), any(Request.Options.class));

        Request capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.headers()).containsKey("Content-Type");
        assertThat(capturedRequest.headers()).containsKey("Authorization");
        assertThat(capturedRequest.headers()).containsKey("X-Original-Host");
    }

    @Test
    @DisplayName("execute preserves request body")
    void execute_PreservesRequestBody() throws IOException {
        // Given
        byte[] body = "{\"name\": \"test\"}".getBytes(StandardCharsets.UTF_8);
        Request originalRequest = Request.create(
                Request.HttpMethod.POST,
                "https://api.example.com/api",
                Collections.emptyMap(),
                body,
                StandardCharsets.UTF_8,
                null
        );

        when(delegateClient.execute(any(Request.class), any(Request.Options.class)))
                .thenReturn(mockResponse);

        // When
        mockRedirectClient.execute(originalRequest, new Request.Options());

        // Then
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(delegateClient).execute(requestCaptor.capture(), any(Request.Options.class));

        Request capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.body()).isEqualTo(body);
    }

    @Test
    @DisplayName("execute preserves HTTP method")
    void execute_PreservesHttpMethod() throws IOException {
        // Given
        Request originalRequest = Request.create(
                Request.HttpMethod.DELETE,
                "https://api.example.com/resource/123",
                Collections.emptyMap(),
                null,
                StandardCharsets.UTF_8,
                null
        );

        when(delegateClient.execute(any(Request.class), any(Request.Options.class)))
                .thenReturn(mockResponse);

        // When
        mockRedirectClient.execute(originalRequest, new Request.Options());

        // Then
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(delegateClient).execute(requestCaptor.capture(), any(Request.Options.class));

        Request capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.httpMethod()).isEqualTo(Request.HttpMethod.DELETE);
    }

    @Test
    @DisplayName("constructor handles trailing slash in Echo Server URL")
    void constructor_HandlesTrailingSlash() throws IOException {
        // Given
        MockRedirectClient clientWithSlash = new MockRedirectClient(delegateClient, "http://localhost:8080/");

        Request originalRequest = Request.create(
                Request.HttpMethod.GET,
                "https://api.example.com/test",
                Collections.emptyMap(),
                null,
                StandardCharsets.UTF_8,
                null
        );

        when(delegateClient.execute(any(Request.class), any(Request.Options.class)))
                .thenReturn(mockResponse);

        // When
        clientWithSlash.execute(originalRequest, new Request.Options());

        // Then
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(delegateClient).execute(requestCaptor.capture(), any(Request.Options.class));

        Request capturedRequest = requestCaptor.getValue();
        // Should not have double slashes
        assertThat(capturedRequest.url()).isEqualTo("http://localhost:8080/test");
    }

    @Test
    @DisplayName("getEchoServerUrl returns configured URL")
    void getEchoServerUrl_ReturnsConfiguredUrl() {
        // When & Then
        assertThat(mockRedirectClient.getEchoServerUrl()).isEqualTo("http://localhost:8080");
    }
}
