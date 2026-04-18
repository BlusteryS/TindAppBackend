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
    public List<Report> findAll(final int page, final int limit) {
        final List<Report> allReports = new ArrayList<>(reports.values()).stream()
            .sorted((r1, r2) -> r2.getCreatedAt().compareTo(r1.getCreatedAt()))
            .collect(Collectors.toList());

        final int start = (page - 1) * limit;
        final int end = Math.min(start + limit, allReports.size());

        if (start >= allReports.size()) {
            return new ArrayList<>();
        }

        return allReports.subList(start, end);
    }

    private List<Report> getReporterReports(final Long reporterId) {
        return reports.values().stream()
            .filter(report -> reporterId.equals(report.getReporterId()))
            .sorted((r1, r2) -> r2.getCreatedAt().compareTo(r1.getCreatedAt()))
            .collect(Collectors.toList());
    }

    @Override
    public List<Report> findByReporterId(final Long reporterId, final int page, final int limit) {
        final List<Report> reporterReports = getReporterReports(reporterId);
        final int start = (page - 1) * limit;
        final int end = Math.min(start + limit, reporterReports.size());

        if (start >= reporterReports.size()) {
            return new ArrayList<>();
        }

        return reporterReports.subList(start, end);
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
