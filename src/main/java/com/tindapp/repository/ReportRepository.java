package com.tindapp.repository;

import com.tindapp.model.Report;

import java.util.List;

public interface ReportRepository extends Repository<Report, String> {

    List<Report> findByReporterId(Long reporterId);

    List<Report> findByTargetId(Long targetId);

    List<Report> findByStatus(Report.ReportStatus status);

    List<Report> findByReason(Report.ReportReason reason);

    List<Report> findByChatId(String chatId);

    List<Report> findByMessageId(String messageId);

    List<Report> findPendingReports();

    void updateStatus(String reportId, Report.ReportStatus status);

    long countByTargetId(Long targetId);

    long countByReporterId(Long reporterId);

    boolean existsByReporterAndTarget(Long reporterId, Long targetId);
}
