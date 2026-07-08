package com.tindapp.repository;

import com.tindapp.model.Report;
import io.vertx.core.Future;

import java.util.List;

public interface ReportRepository extends Repository<Report, String> {

    Future<List<Report>> findByReporterId(Long reporterId, int page, int limit);

    Future<Void> updateStatus(String reportId, Report.ReportStatus status);

    Future<Long> countByTargetId(Long targetId);

    Future<Long> countByReporterId(Long reporterId);

    Future<Boolean> existsByReporterAndTarget(Long reporterId, Long targetId);
}
