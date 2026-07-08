package com.tindapp.repository.postgres;

import com.tindapp.model.Report;
import com.tindapp.repository.ReportRepository;
import io.vertx.core.Future;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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
    public Future<Report> save(final Report report) {
        if (report == null) {
            return Future.failedFuture(new IllegalArgumentException("Report is null"));
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

        return execute("""
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
        )).map(report);
    }

    @Override
    public Future<Optional<Report>> findById(final String id) {
        if (id == null || id.isBlank()) {
            return Future.succeededFuture(Optional.empty());
        }
        return queryOptional(
            "SELECT " + REPORT_COLUMNS + " FROM reports WHERE id = $1 LIMIT 1",
            Tuple.of(id),
            this::mapReport
        );
    }

    @Override
    public Future<List<Report>> findAll(final int page, final int limit) {
        final int safeLimit = safeLimit(limit, MAX_LIMIT);
        return queryList(
            "SELECT " + REPORT_COLUMNS + " FROM reports ORDER BY created_at DESC LIMIT $1 OFFSET $2",
            Tuple.of(safeLimit, offset(page, safeLimit)),
            this::mapReport
        );
    }

    @Override
    public Future<List<Report>> findByReporterId(final Long reporterId, final int page, final int limit) {
        if (reporterId == null) {
            return Future.succeededFuture(List.of());
        }
        final int safeLimit = safeLimit(limit, MAX_LIMIT);
        return queryList(
            "SELECT " + REPORT_COLUMNS + " FROM reports WHERE reporter_id = $1 ORDER BY created_at DESC LIMIT $2 OFFSET $3",
            Tuple.of(reporterId, safeLimit, offset(page, safeLimit)),
            this::mapReport
        );
    }

    @Override
    public Future<Void> updateStatus(final String reportId, final Report.ReportStatus status) {
        return execute("UPDATE reports SET status = $2 WHERE id = $1", Tuple.of(reportId, status.name())).mapEmpty();
    }

    @Override
    public Future<Long> countByTargetId(final Long targetId) {
        if (targetId == null) {
            return Future.succeededFuture(0L);
        }
        return countRows("SELECT COUNT(*) AS cnt FROM reports WHERE target_id = $1", Tuple.of(targetId));
    }

    @Override
    public Future<Long> countByReporterId(final Long reporterId) {
        if (reporterId == null) {
            return Future.succeededFuture(0L);
        }
        return countRows("SELECT COUNT(*) AS cnt FROM reports WHERE reporter_id = $1", Tuple.of(reporterId));
    }

    @Override
    public Future<Boolean> existsByReporterAndTarget(final Long reporterId, final Long targetId) {
        if (reporterId == null || targetId == null) {
            return Future.succeededFuture(false);
        }
        return exists(
            "SELECT 1 FROM reports WHERE reporter_id = $1 AND target_id = $2 LIMIT 1",
            Tuple.of(reporterId, targetId)
        );
    }

    @Override
    public Future<Void> deleteById(final String id) {
        return execute("DELETE FROM reports WHERE id = $1", Tuple.of(id)).mapEmpty();
    }

    @Override
    public Future<Boolean> existsById(final String id) {
        if (id == null || id.isBlank()) {
            return Future.succeededFuture(false);
        }
        return exists("SELECT 1 FROM reports WHERE id = $1 LIMIT 1", Tuple.of(id));
    }

    @Override
    public Future<Long> count() {
        return countRows("SELECT COUNT(*) AS cnt FROM reports");
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
