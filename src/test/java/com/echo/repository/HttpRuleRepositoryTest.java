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
        // Given: 建立受保護和未保護的規則
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
        
        // 設定 createdAt 為 200 天前
        LocalDateTime oldDate = LocalDateTime.now().minusDays(200);
        entityManager.getEntityManager()
                .createQuery("UPDATE HttpRule r SET r.createdAt = :oldDate WHERE r.id IN :ids")
                .setParameter("oldDate", oldDate)
                .setParameter("ids", List.of(protectedRule.getId(), unprotectedRule.getId()))
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
        
        assertThat(httpRuleRepository.findAll()).hasSize(2);
        
        // When: 執行清除
        LocalDateTime cutoff = LocalDateTime.now().minusDays(180);
        int deleted = httpRuleRepository.deleteExpiredRules(cutoff);
        
        // Then: 只有未保護的規則被刪除
        assertThat(deleted).isEqualTo(1);
        var remaining = httpRuleRepository.findAll();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getMatchKey()).isEqualTo("/protected");
    }

    @Test
    void deleteExpiredRules_shouldNotDeleteRecentRules() {
        // Given: 建立一個新規則
        HttpRule newRule = HttpRule.builder()
                .matchKey("/new")
                .method("GET")
                .isProtected(false)
                .enabled(true)
                .priority(0)
                .delayMs(0L)
                .build();
        entityManager.persistAndFlush(newRule);
        
        // When: 執行清除（cutoff = 180 天前）
        LocalDateTime cutoff = LocalDateTime.now().minusDays(180);
        int deleted = httpRuleRepository.deleteExpiredRules(cutoff);
        
        // Then: 新規則不會被刪除
        assertThat(deleted).isEqualTo(0);
        assertThat(httpRuleRepository.findAll()).hasSize(1);
    }

    @Test
    void deleteExpiredRules_shouldRespectExtendedAt() {
        // Given: 建立一個舊規則但有展延
        HttpRule extendedRule = HttpRule.builder()
                .matchKey("/extended")
                .method("GET")
                .isProtected(false)
                .enabled(true)
                .priority(0)
                .delayMs(0L)
                .build();
        entityManager.persistAndFlush(extendedRule);
        
        // 設定 createdAt 為 200 天前，但 extendedAt 為今天
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
        
        // When: 執行清除
        LocalDateTime cutoff = LocalDateTime.now().minusDays(180);
        int deleted = httpRuleRepository.deleteExpiredRules(cutoff);
        
        // Then: 展延過的規則不會被刪除
        assertThat(deleted).isEqualTo(0);
        assertThat(httpRuleRepository.findAll()).hasSize(1);
    }
}
