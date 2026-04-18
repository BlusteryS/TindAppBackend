package com.tindapp.service;

import com.tindapp.model.Report;
import com.tindapp.repository.ReportRepository;
import com.tindapp.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ReportService {

    private static final Logger logger = LoggerFactory.getLogger(ReportService.class);

    private final ReportRepository reportRepository;
    private final UserRepository userRepository;

    public ReportService(final ReportRepository reportRepository, final UserRepository userRepository) {
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
    }

    public Report createReport(final Long reporterId, final Long targetId, final String chatId, final String messageId,
                               final Report.ReportReason reason, final String description) {
        userRepository.findById(reporterId)
            .orElseThrow(() -> new RuntimeException("Reporter not found"));
        userRepository.findById(targetId)
            .orElseThrow(() -> new RuntimeException("Target user not found"));

        if (reportRepository.existsByReporterAndTarget(reporterId, targetId)) {
            throw new RuntimeException("Report already exists for this user");
        }

        final String reportId = UUID.randomUUID().toString();
        final Report report = new Report(reportId, reporterId, targetId, reason);
        report.setChatId(chatId);
        report.setMessageId(messageId);
        report.setDescription(description);

        return reportRepository.save(report);
    }

    public List<Report> getUserReports(final Long userId, final int page, final int limit) {
        return reportRepository.findByReporterId(userId, page, limit);
    }

    public List<Report> getAllReports(final int page, final int limit) {
        return reportRepository.findAll(page, limit);
    }

    public long countReports() {
        return reportRepository.count();
    }

    public long countUserReports(final Long userId) {
        return reportRepository.countByReporterId(userId);
    }

    public void updateReportStatus(final String reportId, final Report.ReportStatus status) {
        final Report report = reportRepository.findById(reportId)
            .orElseThrow(() -> new RuntimeException("Report not found"));

        report.setStatus(status);
        reportRepository.save(report);
    }

    public void resolveReport(final String reportId) {
        updateReportStatus(reportId, Report.ReportStatus.RESOLVED);
    }

    public void dismissReport(final String reportId) {
        updateReportStatus(reportId, Report.ReportStatus.DISMISSED);
    }

    public long getReportCountForUser(final Long userId) {
        return reportRepository.countByTargetId(userId);
    }

    public boolean shouldBlockUser(final Long userId) {
        final long reportCount = getReportCountForUser(userId);
        return reportCount >= 5; // Блокируем после 5 жалоб
    }

    public Optional<Report> getReportById(final String reportId) {
        return reportRepository.findById(reportId);
    }

    public void deleteReport(final String reportId) {
        reportRepository.deleteById(reportId);
    }
}
