package com.tindapp.repository.postgres;

import com.tindapp.model.Report;
import com.tindapp.repository.ReportRepository;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class PostgresReportRepository extends AbstractPostgresRepository implements ReportRepository {

    private static final Comparator<Report> CREATED_AT_DESC = Comparator
        .comparing(Report::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
        .reversed();

    public PostgresReportRepository(final PgPool client) {
        super(client);
        ensureTable("""
            CREATE TABLE IF NOT EXISTS reports (
                id TEXT PRIMARY KEY,
                data JSONB NOT NULL
            )
            """);
    }

    @Override
    public Report save(final Report report) {
        if (report == null) {
            throw new IllegalArgumentException("Report is null");
        }
        if (report.getId() == null) {
            report.setId(UUID.randomUUID().toString());
        }
        if (report.getCreatedAt() == null) {
            report.setCreatedAt(LocalDateTime.now());
        }
        final JsonObject payload = toJson(report);
        execute(
            "INSERT INTO reports (id, data) VALUES ($1, $2::jsonb) " +
                "ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data",
            Tuple.of(report.getId(), payload)
        );
        return report;
    }

    @Override
    public Optional<Report> findById(final String id) {
        if (id == null) {
            return Optional.empty();
        }
        final RowSet<Row> rows = execute("SELECT data FROM reports WHERE id = $1 LIMIT 1", Tuple.of(id));
        if (!rows.iterator().hasNext()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapRow(rows.iterator().next(), Report.class));
    }

    @Override
    public List<Report> findAll() {
        final RowSet<Row> rows = execute("SELECT data FROM reports");
        final List<Report> result = new ArrayList<>();
        for (final Row row : rows) {
            final Report report = mapRow(row, Report.class);
            if (report != null) {
                result.add(report);
            }
        }
        return result;
    }

    @Override
    public List<Report> findAll(final int page, final int limit) {
        final List<Report> allReports = findAll().stream()
            .sorted(CREATED_AT_DESC)
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
        return findAll().stream()
            .filter(report -> reporterId.equals(report.getReporterId()))
            .sorted(CREATED_AT_DESC)
            .collect(Collectors.toList());
    }

    @Override
    public List<Report> findByTargetId(final Long targetId) {
        return findAll().stream()
            .filter(report -> targetId.equals(report.getTargetId()))
            .sorted(CREATED_AT_DESC)
            .collect(Collectors.toList());
    }

    @Override
    public List<Report> findByStatus(final Report.ReportStatus status) {
        return findAll().stream()
            .filter(report -> status.equals(report.getStatus()))
            .sorted(CREATED_AT_DESC)
            .collect(Collectors.toList());
    }

    @Override
    public List<Report> findByReason(final Report.ReportReason reason) {
        return findAll().stream()
            .filter(report -> reason.equals(report.getReason()))
            .sorted(CREATED_AT_DESC)
            .collect(Collectors.toList());
    }

    @Override
    public List<Report> findByChatId(final String chatId) {
        return findAll().stream()
            .filter(report -> chatId.equals(report.getChatId()))
            .sorted(CREATED_AT_DESC)
            .collect(Collectors.toList());
    }

    @Override
    public List<Report> findByMessageId(final String messageId) {
        return findAll().stream()
            .filter(report -> messageId.equals(report.getMessageId()))
            .sorted(CREATED_AT_DESC)
            .collect(Collectors.toList());
    }

    @Override
    public List<Report> findPendingReports() {
        return findByStatus(Report.ReportStatus.PENDING);
    }

    @Override
    public void updateStatus(final String reportId, final Report.ReportStatus status) {
        findById(reportId).ifPresent(report -> {
            report.setStatus(status);
            save(report);
        });
    }

    @Override
    public long countByTargetId(final Long targetId) {
        return findAll().stream()
            .filter(report -> targetId.equals(report.getTargetId()))
            .count();
    }

    @Override
    public long countByReporterId(final Long reporterId) {
        return findAll().stream()
            .filter(report -> reporterId.equals(report.getReporterId()))
            .count();
    }

    @Override
    public boolean existsByReporterAndTarget(final Long reporterId, final Long targetId) {
        return findAll().stream()
            .anyMatch(report -> reporterId.equals(report.getReporterId()) &&
                targetId.equals(report.getTargetId()));
    }

    @Override
    public void deleteById(final String id) {
        execute("DELETE FROM reports WHERE id = $1", Tuple.of(id));
    }

    @Override
    public boolean existsById(final String id) {
        final RowSet<Row> rows = execute("SELECT 1 FROM reports WHERE id = $1 LIMIT 1", Tuple.of(id));
        return rows.iterator().hasNext();
    }

    @Override
    public long count() {
        final RowSet<Row> rows = execute("SELECT COUNT(*) as cnt FROM reports");
        final Row row = rows.iterator().hasNext() ? rows.iterator().next() : null;
        return row != null ? row.getLong("cnt") : 0L;
    }
}
