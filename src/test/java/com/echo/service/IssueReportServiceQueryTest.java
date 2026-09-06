package com.echo.service;

import com.echo.entity.IssueReport;
import com.echo.entity.IssueReport.IssueStatus;
import com.echo.repository.IssueReportRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(IssueReportService.class)
class IssueReportServiceQueryTest {

    @Autowired
    private IssueReportService service;

    @Autowired
    private IssueReportRepository repository;

    @Test
    void query_shouldFilterSearchSortAndPaginate() {
        repository.saveAll(List.of(
                issue("Payment timeout", "alice", IssueStatus.OPEN),
                issue("Login error", "bob", IssueStatus.RESOLVED),
                issue("Payment response", "carol", IssueStatus.OPEN)));

        var result = service.query(IssueStatus.OPEN, "payment", 0, 1, "title", "desc");

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.getContent()).extracting(IssueReport::getTitle)
                .containsExactly("Payment timeout");
    }

    @Test
    void query_shouldBoundPageSizeAndRejectSortInjectionByFallback() {
        repository.save(issue("Only issue", "alice", IssueStatus.OPEN));

        var result = service.query(null, null, 0, 1000, "title desc, id", "asc");

        assertThat(result.getSize()).isEqualTo(100);
        assertThat(result.getContent()).hasSize(1);
    }

    private IssueReport issue(String title, String createdBy, IssueStatus status) {
        return IssueReport.builder()
                .title(title)
                .description("details")
                .createdBy(createdBy)
                .status(status)
                .build();
    }
}
