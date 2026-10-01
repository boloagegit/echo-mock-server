package com.echo.config;

import com.echo.controller.IssueReportController;
import com.echo.service.IssueReportService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IssueReportingFeatureConditionTest {
    @Test
    void absentOrExplicitFalseDisablesControllerWhilePreservingService() {
        var reports = mock(IssueReportService.class);
        var runner = new ApplicationContextRunner().withBean(IssueReportService.class, () -> reports)
                .withUserConfiguration(IssueReportController.class);
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(IssueReportController.class).hasSingleBean(IssueReportService.class);
        });
        runner.withPropertyValues("echo.features.issue-reporting-enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(IssueReportController.class).hasSingleBean(IssueReportService.class);
        });
        runner.withPropertyValues("echo.features.issue-reporting-enabled=true").run(context -> {
            assertThat(context).hasSingleBean(IssueReportController.class).hasSingleBean(IssueReportService.class);
        });
        verifyNoInteractions(reports);
    }
}
