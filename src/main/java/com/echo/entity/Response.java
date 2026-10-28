package com.echo.entity;

import com.fasterxml.jackson.annotation.JsonCreator;

import jakarta.persistence.*;

import lombok.*;

import org.hibernate.Length;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * Shared response content
 * <p>
 * Response template that can be shared by multiple rules, applicable to:
 * <ul>
 *   <li>Multiple endpoints return the same content</li>
 *   <li>Centralized management of large response content</li>
 *   <li>response content version control</li>
 * </ul>
 * 
 * <h3>How to use</h3>
 * The rule can be associated with this table through responseId, or responseBody can be set directly in the rule.
 * Use shared responses to reduce duplicate data and facilitate unified maintenance.
 */
@Entity
@Table(name = "responses")
@Getter
@Setter
@Builder
@NoArgsConstructor(onConstructor_ = @JsonCreator)
@AllArgsConstructor
public class Response {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Optimistic locking version number */
    @Version
    private Long version;

    /** Opaque row incarnation: identity values may be reused after deletion. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(length = 36, updatable = false)
    private String incarnation;

    /** Response description (for identification and search) */
    private String description;

    /** 
     * Response content
     * <p>uses Lazy Load to avoid loading large content during list query
     */
    @Column(length = Length.LONG32)
    @Basic(fetch = FetchType.LAZY)
    private String body;

    /** 
     * Body size (bytes)
     * <p>is used for cache decision-making and display, automatic calculation
     */
    private Integer bodySize;

    /** 
     * Response content type
     * <p>TEXT = General text/JSON/XML, SSE_EVENTS = SSE event array
     */
    @Column(length = 20)
    private String contentType;

    /** SSE The type value of the response; legacy or declarative files may be written as {@code SSE_EVENTS} and are treated the same when read. */
    public static final String SSE = "SSE";

    /** Whether it is the SSE event response ({@code SSE} or the old value {@code SSE_EVENTS}). */
    public static boolean isSse(String contentType) {
        return SSE.equalsIgnoreCase(contentType) || "SSE_EVENTS".equalsIgnoreCase(contentType);
    }

    /** Always expose a normalized type value; legacy {@code SSE_EVENTS} values are returned as {@code SSE} too. */
    public String getContentType() {
        return normalizeContentType(contentType);
    }

    /** The type value of unified storage: SSE is always saved as {@code SSE}, and the general response is saved as null. */
    public static String normalizeContentType(String contentType) {
        return isSse(contentType) ? SSE : null;
    }

    /** Creation time */
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** Last updated */
    private LocalDateTime updatedAt;

    /** Extension time (used to calculate the automatic clearing date of orphan responses) */
    private LocalDateTime extendedAt;

    /**
     * Metadata constructor (for JPQL projection)
     * <p>The body field is not loaded when  is used to query the list.
     */
    public Response(Long id, Long version, String description, Integer bodySize,
                    String contentType, LocalDateTime createdAt, LocalDateTime updatedAt,
                    LocalDateTime extendedAt) {
        this.id = id;
        this.version = version;
        this.description = description;
        this.bodySize = bodySize;
        this.contentType = contentType;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.extendedAt = extendedAt;
    }

    @PrePersist
    protected void onCreate() {
        incarnation = java.util.UUID.randomUUID().toString();
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        updateBodySize();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        updateBodySize();
    }

    /** Calculate and update body size */
    private void updateBodySize() {
        bodySize = body != null ? body.getBytes(StandardCharsets.UTF_8).length : 0;
    }
}
