package com.tindapp.service;

import com.tindapp.model.Report;
import com.tindapp.repository.ReportRepository;
import com.tindapp.repository.UserRepository;
import com.tindapp.util.FutureUtils;
import io.vertx.core.Future;
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

    public Future<Report> createReport(final Long reporterId, final Long targetId, final String chatId, final String messageId,
                                       final Report.ReportReason reason, final String description) {
        return FutureUtils.requirePresent(userRepository.findById(reporterId), "Reporter not found")
            .compose(user -> FutureUtils.requirePresent(userRepository.findById(targetId), "Target user not found"))
            .compose(user -> reportRepository.existsByReporterAndTarget(reporterId, targetId))
            .compose(exists -> {
                if (exists) {
                    return FutureUtils.failed("Report already exists for this user");
                }
                final Report report = new Report(UUID.randomUUID().toString(), reporterId, targetId, reason);
                report.setChatId(chatId);
                report.setMessageId(messageId);
                report.setDescription(description);
                return reportRepository.save(report);
            });
    }

    public Future<List<Report>> getUserReports(final Long userId, final int page, final int limit) {
        return reportRepository.findByReporterId(userId, page, limit);
    }

    public Future<List<Report>> getAllReports(final int page, final int limit) {
        return reportRepository.findAll(page, limit);
    }

    public Future<Long> countReports() {
        return reportRepository.count();
    }

    public Future<Long> countUserReports(final Long userId) {
        return reportRepository.countByReporterId(userId);
    }

    public Future<Void> updateReportStatus(final String reportId, final Report.ReportStatus status) {
        return FutureUtils.requirePresent(reportRepository.findById(reportId), "Report not found")
            .compose(report -> {
                report.setStatus(status);
                return reportRepository.save(report).mapEmpty();
            });
    }

    public Future<Void> resolveReport(final String reportId) {
        return updateReportStatus(reportId, Report.ReportStatus.RESOLVED);
    }

    public Future<Void> dismissReport(final String reportId) {
        return updateReportStatus(reportId, Report.ReportStatus.DISMISSED);
    }

    public Future<Long> getReportCountForUser(final Long userId) {
        return reportRepository.countByTargetId(userId);
    }

    public Future<Boolean> shouldBlockUser(final Long userId) {
        return getReportCountForUser(userId).map(reportCount -> reportCount >= 5);
    }

    public Future<Optional<Report>> getReportById(final String reportId) {
        return reportRepository.findById(reportId);
    }

    public Future<Void> deleteReport(final String reportId) {
        return reportRepository.deleteById(reportId);
    }
}
