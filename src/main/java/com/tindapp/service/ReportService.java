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

    public ReportService(ReportRepository reportRepository, UserRepository userRepository) {
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
    }

    public Report createReport(Long reporterId, Long targetId, String chatId, String messageId,
                              Report.ReportReason reason, String description) {
        userRepository.findById(reporterId)
            .orElseThrow(() -> new RuntimeException("Reporter not found"));
        userRepository.findById(targetId)
            .orElseThrow(() -> new RuntimeException("Target user not found"));

        if (reportRepository.existsByReporterAndTarget(reporterId, targetId)) {
            throw new RuntimeException("Report already exists for this user");
        }

        String reportId = UUID.randomUUID().toString();
        Report report = new Report(reportId, reporterId, targetId, reason);
        report.setChatId(chatId);
        report.setMessageId(messageId);
        report.setDescription(description);

        return reportRepository.save(report);
    }

    public List<Report> getUserReports(Long userId, int page, int limit) {
        return reportRepository.findByReporterId(userId);
    }

    public List<Report> getAllReports(int page, int limit) {
        return reportRepository.findAll(page, limit);
    }

    public long countReports() {
        return reportRepository.count();
    }

    public List<Report> getReportsAgainstUser(Long userId) {
        return reportRepository.findByTargetId(userId);
    }

    public List<Report> getPendingReports() {
        return reportRepository.findPendingReports();
    }

    public List<Report> getReportsByStatus(Report.ReportStatus status) {
        return reportRepository.findByStatus(status);
    }

    public void updateReportStatus(String reportId, Report.ReportStatus status) {
        Report report = reportRepository.findById(reportId)
            .orElseThrow(() -> new RuntimeException("Report not found"));

        report.setStatus(status);
        reportRepository.save(report);
    }

    public void resolveReport(String reportId) {
        updateReportStatus(reportId, Report.ReportStatus.RESOLVED);
    }

    public void dismissReport(String reportId) {
        updateReportStatus(reportId, Report.ReportStatus.DISMISSED);
    }

    public long getReportCountForUser(Long userId) {
        return reportRepository.countByTargetId(userId);
    }

    public boolean shouldBlockUser(Long userId) {
        long reportCount = getReportCountForUser(userId);
        return reportCount >= 5; // Блокируем после 5 жалоб
    }

    public Optional<Report> getReportById(String reportId) {
        return reportRepository.findById(reportId);
    }

    public List<Report> getReportsByReason(Report.ReportReason reason) {
        return reportRepository.findByReason(reason);
    }

    public void deleteReport(String reportId) {
        reportRepository.deleteById(reportId);
    }
}
