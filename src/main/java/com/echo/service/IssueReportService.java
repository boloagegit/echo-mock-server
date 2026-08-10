package com.echo.service;

import com.echo.entity.IssueReport;
import com.echo.entity.IssueReport.IssueStatus;
import com.echo.repository.IssueReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class IssueReportService {

    private final IssueReportRepository issueReportRepository;

    public List<IssueReport> findAll() {
        return issueReportRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<IssueReport> findByStatus(IssueStatus status) {
        return issueReportRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    public Optional<IssueReport> findById(String id) {
        return issueReportRepository.findById(id);
    }

    public long countOpen() {
        return issueReportRepository.countByStatus(IssueStatus.OPEN);
    }

    @Transactional
    public IssueReport create(String title, String description, String createdBy) {
        IssueReport issue = IssueReport.builder()
                .title(title.trim())
                .description(description.trim())
                .status(IssueStatus.OPEN)
                .createdBy(createdBy)
                .build();
        return issueReportRepository.save(issue);
    }

    @Transactional
    public IssueReport reply(String id, String replyContent, String repliedBy) {
        IssueReport issue = issueReportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("ISSUE_NOT_FOUND"));
        issue.setAdminReply(replyContent.trim());
        issue.setRepliedBy(repliedBy);
        issue.setRepliedAt(LocalDateTime.now());
        return issueReportRepository.save(issue);
    }

    @Transactional
    public IssueReport resolve(String id, String resolvedBy) {
        IssueReport issue = issueReportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("ISSUE_NOT_FOUND"));
        issue.setStatus(IssueStatus.RESOLVED);
        issue.setResolvedAt(LocalDateTime.now());
        if (issue.getRepliedBy() == null) {
            issue.setRepliedBy(resolvedBy);
        }
        return issueReportRepository.save(issue);
    }

    @Transactional
    public IssueReport reopen(String id) {
        IssueReport issue = issueReportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("ISSUE_NOT_FOUND"));
        issue.setStatus(IssueStatus.OPEN);
        issue.setResolvedAt(null);
        return issueReportRepository.save(issue);
    }

    @Transactional
    public void delete(String id) {
        if (!issueReportRepository.existsById(id)) {
            throw new IllegalArgumentException("ISSUE_NOT_FOUND");
        }
        issueReportRepository.deleteById(id);
    }
}
