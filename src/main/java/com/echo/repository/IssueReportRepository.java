package com.echo.repository;

import com.echo.entity.IssueReport;
import com.echo.entity.IssueReport.IssueStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface IssueReportRepository extends JpaRepository<IssueReport, String> {

    List<IssueReport> findAllByOrderByCreatedAtDesc();

    List<IssueReport> findByStatusOrderByCreatedAtDesc(IssueStatus status);

    long countByStatus(IssueStatus status);

    @Query("SELECT i FROM IssueReport i WHERE " +
           "(:status IS NULL OR i.status = :status) AND " +
           "(:keyword IS NULL OR LOWER(i.title) LIKE :keyword ESCAPE '\\' " +
           "OR LOWER(i.createdBy) LIKE :keyword ESCAPE '\\')")
    Page<IssueReport> queryIssues(@Param("status") IssueStatus status,
                                  @Param("keyword") String keyword,
                                  Pageable pageable);
}
