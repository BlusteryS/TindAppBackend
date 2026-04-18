package com.tindapp.repository.postgres;

import com.tindapp.model.Report;
import com.tindapp.repository.ReportRepository;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class PostgresReportRepository extends AbstractPostgresRepository implements ReportRepository {

    private static final int MAX_LIMIT = 100;
    private static final String REPORT_COLUMNS = """
        id, reporter_id, target_id, chat_id, message_id, reason, description, status, created_at
        """;

    public PostgresReportRepository(final PgPool client) {
        super(client);
    }

    @Override
    public Report save(final Report report) {
        if (report == null) {
            throw new IllegalArgumentException("Report is null");
        }
        if (report.getId() == null || report.getId().isBlank()) {
            report.setId(UUID.randomUUID().toString());
        }
        if (report.getStatus() == null) {
            report.setStatus(Report.ReportStatus.PENDING);
        }
        if (report.getCreatedAt() == null) {
            report.setCreatedAt(LocalDateTime.now());
        }
        if (report.getDescription() == null) {
            report.setDescription("");
        }

        execute("""
            INSERT INTO reports (id, reporter_id, target_id, chat_id, message_id, reason, description, status, created_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
            ON CONFLICT (id) DO UPDATE SET
                reporter_id = EXCLUDED.reporter_id,
                target_id = EXCLUDED.target_id,
                chat_id = EXCLUDED.chat_id,
                message_id = EXCLUDED.message_id,
                reason = EXCLUDED.reason,
                description = EXCLUDED.description,
                status = EXCLUDED.status,
                created_at = EXCLUDED.created_at
            """, Tuple.of(
            report.getId(),
            report.getReporterId(),
            report.getTargetId(),
            report.getChatId(),
            report.getMessageId(),
            report.getReason() != null ? report.getReason().name() : null,
            report.getDescription(),
            report.getStatus().name(),
            toOffset(report.getCreatedAt())
        ));
        return report;
    }

    @Override
    public Optional<Report> findById(final String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return firstRow("SELECT " + REPORT_COLUMNS + " FROM reports WHERE id = $1 LIMIT 1", Tuple.of(id))
            .map(this::mapReport);
    }

    public List<Report> findAll(final int page, final int limit) {
        final int safeLimit = safeLimit(limit, MAX_LIMIT);
        return queryReports(
            "SELECT " + REPORT_COLUMNS + " FROM reports ORDER BY created_at DESC LIMIT $1 OFFSET $2",
            Tuple.of(safeLimit, offset(page, safeLimit))
        );
    }

    public List<Report> findByReporterId(final Long reporterId, final int page, final int limit) {
        if (reporterId == null) {
            return List.of();
        }
        final int safeLimit = safeLimit(limit, MAX_LIMIT);
        return queryReports(
            "SELECT " + REPORT_COLUMNS + " FROM reports WHERE reporter_id = $1 ORDER BY created_at DESC LIMIT $2 OFFSET $3",
            Tuple.of(reporterId, safeLimit, offset(page, safeLimit))
        );
    }

    public void updateStatus(final String reportId, final Report.ReportStatus status) {
        execute("UPDATE reports SET status = $2 WHERE id = $1", Tuple.of(reportId, status.name()));
    }

    @Override
    public long countByTargetId(final Long targetId) {
        return countRows("SELECT COUNT(*) AS cnt FROM reports WHERE target_id = $1", Tuple.of(targetId));
    }

    @Override
    public long countByReporterId(final Long reporterId) {
        return countRows("SELECT COUNT(*) AS cnt FROM reports WHERE reporter_id = $1", Tuple.of(reporterId));
    }

    @Override
    public boolean existsByReporterAndTarget(final Long reporterId, final Long targetId) {
        return exists(
            "SELECT 1 FROM reports WHERE reporter_id = $1 AND target_id = $2 LIMIT 1",
            Tuple.of(reporterId, targetId)
        );
    }

    @Override
    public void deleteById(final String id) {
        execute("DELETE FROM reports WHERE id = $1", Tuple.of(id));
    }

    @Override
    public boolean existsById(final String id) {
        return exists("SELECT 1 FROM reports WHERE id = $1 LIMIT 1", Tuple.of(id));
    }

    @Override
    public long count() {
        return countRows("SELECT COUNT(*) AS cnt FROM reports");
    }

    private List<Report> queryReports(final String sql, final Tuple params) {
        final RowSet<Row> rows = execute(sql, params);
        final List<Report> reports = new ArrayList<>();
        for (final Row row : rows) {
            final Report report = mapReport(row);
            if (report != null) {
                reports.add(report);
            }
        }
        return reports;
    }

    private Report mapReport(final Row row) {
        if (row == null) {
            return null;
        }
        final Report report = new Report();
        report.setId(row.getString("id"));
        report.setReporterId(row.getLong("reporter_id"));
        report.setTargetId(row.getLong("target_id"));
        report.setChatId(row.getString("chat_id"));
        report.setMessageId(row.getString("message_id"));

        final String reason = row.getString("reason");
        if (reason != null) {
            report.setReason(Report.ReportReason.valueOf(reason));
        }

        report.setDescription(row.getString("description"));

        final String status = row.getString("status");
        if (status != null) {
            report.setStatus(Report.ReportStatus.valueOf(status));
        }

        final OffsetDateTime createdAt = row.getOffsetDateTime("created_at");
        report.setCreatedAt(createdAt != null ? createdAt.toLocalDateTime() : null);
        return report;
    }
}
