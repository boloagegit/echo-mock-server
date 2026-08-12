package com.echo.repository;

import com.echo.entity.IssueReport;
import com.echo.entity.IssueReport.IssueStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IssueReportRepository extends JpaRepository<IssueReport, String> {

    List<IssueReport> findAllByOrderByCreatedAtDesc();

    List<IssueReport> findByStatusOrderByCreatedAtDesc(IssueStatus status);

    long countByStatus(IssueStatus status);
}
