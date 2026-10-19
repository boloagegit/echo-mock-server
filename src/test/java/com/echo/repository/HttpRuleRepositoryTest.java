package com.echo.repository;

import com.echo.entity.HttpRule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class HttpRuleRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private HttpRuleRepository httpRuleRepository;

    @Test
    void deleteExpiredRules_shouldNotDeleteProtectedRules() {
        // Given: Creating protected and unprotected rules
        HttpRule protectedRule = HttpRule.builder()
                .matchKey("/protected")
                .method("GET")
                .isProtected(true)
                .enabled(true)
                .priority(0)
                .delayMs(0L)
                .build();
        entityManager.persistAndFlush(protectedRule);
        
        HttpRule unprotectedRule = HttpRule.builder()
                .matchKey("/unprotected")
                .method("GET")
                .isProtected(false)
                .enabled(true)
                .priority(0)
                .delayMs(0L)
                .build();
        entityManager.persistAndFlush(unprotectedRule);
        
        // Set createdAt to 200 days ago
        LocalDateTime oldDate = LocalDateTime.now().minusDays(200);
        entityManager.getEntityManager()
                .createQuery("UPDATE HttpRule r SET r.createdAt = :oldDate WHERE r.id IN :ids")
                .setParameter("oldDate", oldDate)
                .setParameter("ids", List.of(protectedRule.getId(), unprotectedRule.getId()))
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
        
        assertThat(httpRuleRepository.findAll()).hasSize(2);
        
        // When: Perform cleanup
        LocalDateTime cutoff = LocalDateTime.now().minusDays(180);
        int deleted = httpRuleRepository.deleteExpiredRules(cutoff, false);
        
        // Then: Only unprotected rules are deleted
        assertThat(deleted).isEqualTo(1);
        var remaining = httpRuleRepository.findAll();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getMatchKey()).isEqualTo("/protected");
    }

    @Test
    void deleteExpiredRules_shouldNotDeleteRecentRules() {
        // Given: Create a new rule
        HttpRule newRule = HttpRule.builder()
                .matchKey("/new")
                .method("GET")
                .isProtected(false)
                .enabled(true)
                .priority(0)
                .delayMs(0L)
                .build();
        entityManager.persistAndFlush(newRule);
        
        // When: Performing cleanup (cutoff = 180 days ago)
        LocalDateTime cutoff = LocalDateTime.now().minusDays(180);
        int deleted = httpRuleRepository.deleteExpiredRules(cutoff, false);
        
        // Then: New rules will not be deleted
        assertThat(deleted).isEqualTo(0);
        assertThat(httpRuleRepository.findAll()).hasSize(1);
    }

    @Test
    void deleteExpiredRules_shouldRespectExtendedAt() {
        // Given: Create an old rule but with an extension
        HttpRule extendedRule = HttpRule.builder()
                .matchKey("/extended")
                .method("GET")
                .isProtected(false)
                .enabled(true)
                .priority(0)
                .delayMs(0L)
                .build();
        entityManager.persistAndFlush(extendedRule);
        
        // Set createdAt to 200 days ago, but extendedAt to today
        LocalDateTime oldDate = LocalDateTime.now().minusDays(200);
        LocalDateTime today = LocalDateTime.now();
        entityManager.getEntityManager()
                .createQuery("UPDATE HttpRule r SET r.createdAt = :oldDate, r.extendedAt = :today WHERE r.id = :id")
                .setParameter("oldDate", oldDate)
                .setParameter("today", today)
                .setParameter("id", extendedRule.getId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
        
        // When: Perform cleanup
        LocalDateTime cutoff = LocalDateTime.now().minusDays(180);
        int deleted = httpRuleRepository.deleteExpiredRules(cutoff, false);
        
        // Then: Extended rules will not be deleted
        assertThat(deleted).isEqualTo(0);
        assertThat(httpRuleRepository.findAll()).hasSize(1);
    }
}
