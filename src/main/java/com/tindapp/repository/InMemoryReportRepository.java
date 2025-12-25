package com.tindapp.repository;

import com.tindapp.model.Report;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class InMemoryReportRepository implements ReportRepository {

    private final Map<String, Report> reports = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    @Override
    public Report save(final Report report) {
        if (report.getId() == null) {
            report.setId(String.valueOf(idGenerator.getAndIncrement()));
        }
        reports.put(report.getId(), report);
        return report;
    }

    @Override
    public Optional<Report> findById(final String id) {
        return Optional.ofNullable(reports.get(id));
    }

    @Override
    public List<Report> findAll() {
        return new ArrayList<>(reports.values());
    }

    @Override
    public List<Report> findAll(final int page, final int limit) {
        final List<Report> allReports = findAll().stream()
            .sorted((r1, r2) -> r2.getCreatedAt().compareTo(r1.getCreatedAt()))
            .collect(Collectors.toList());

        final int start = (page - 1) * limit;
        final int end = Math.min(start + limit, allReports.size());

        if (start >= allReports.size()) {
            return new ArrayList<>();
        }

        return allReports.subList(start, end);
    }

    @Override
    public List<Report> findByReporterId(final Long reporterId) {
        return reports.values().stream()
            .filter(report -> reporterId.equals(report.getReporterId()))
            .sorted((r1, r2) -> r2.getCreatedAt().compareTo(r1.getCreatedAt()))
            .collect(Collectors.toList());
    }

    @Override
    public List<Report> findByTargetId(final Long targetId) {
        return reports.values().stream()
            .filter(report -> targetId.equals(report.getTargetId()))
            .sorted((r1, r2) -> r2.getCreatedAt().compareTo(r1.getCreatedAt()))
            .collect(Collectors.toList());
    }

    @Override
    public List<Report> findByStatus(final Report.ReportStatus status) {
        return reports.values().stream()
            .filter(report -> status.equals(report.getStatus()))
            .sorted((r1, r2) -> r2.getCreatedAt().compareTo(r1.getCreatedAt()))
            .collect(Collectors.toList());
    }

    @Override
    public List<Report> findByReason(final Report.ReportReason reason) {
        return reports.values().stream()
            .filter(report -> reason.equals(report.getReason()))
            .sorted((r1, r2) -> r2.getCreatedAt().compareTo(r1.getCreatedAt()))
            .collect(Collectors.toList());
    }

    @Override
    public List<Report> findByChatId(final String chatId) {
        return reports.values().stream()
            .filter(report -> chatId.equals(report.getChatId()))
            .sorted((r1, r2) -> r2.getCreatedAt().compareTo(r1.getCreatedAt()))
            .collect(Collectors.toList());
    }

    @Override
    public List<Report> findByMessageId(final String messageId) {
        return reports.values().stream()
            .filter(report -> messageId.equals(report.getMessageId()))
            .sorted((r1, r2) -> r2.getCreatedAt().compareTo(r1.getCreatedAt()))
            .collect(Collectors.toList());
    }

    @Override
    public List<Report> findPendingReports() {
        return findByStatus(Report.ReportStatus.PENDING);
    }

    @Override
    public void updateStatus(final String reportId, final Report.ReportStatus status) {
        final Report report = reports.get(reportId);
        if (report != null) {
            report.setStatus(status);
        }
    }

    @Override
    public long countByTargetId(final Long targetId) {
        return reports.values().stream()
            .filter(report -> targetId.equals(report.getTargetId()))
            .count();
    }

    @Override
    public long countByReporterId(final Long reporterId) {
        return reports.values().stream()
            .filter(report -> reporterId.equals(report.getReporterId()))
            .count();
    }

    @Override
    public boolean existsByReporterAndTarget(final Long reporterId, final Long targetId) {
        return reports.values().stream()
            .anyMatch(report -> reporterId.equals(report.getReporterId()) &&
                targetId.equals(report.getTargetId()));
    }

    @Override
    public void deleteById(final String id) {
        reports.remove(id);
    }

    @Override
    public boolean existsById(final String id) {
        return reports.containsKey(id);
    }

    @Override
    public long count() {
        return reports.size();
    }
}
