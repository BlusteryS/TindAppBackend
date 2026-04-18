package com.tindapp.repository;

import com.tindapp.model.Report;

import java.util.List;

public interface ReportRepository extends Repository<Report, String> {

    List<Report> findByReporterId(Long reporterId, int page, int limit);

    void updateStatus(String reportId, Report.ReportStatus status);

    long countByTargetId(Long targetId);

    long countByReporterId(Long reporterId);

    boolean existsByReporterAndTarget(Long reporterId, Long targetId);
}
