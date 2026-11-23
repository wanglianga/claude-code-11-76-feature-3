package com.labroute.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.labroute.model.Tenant;
import com.labroute.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Continuous report publication.
 *
 * A published report is always served from exactly one verified space, and every
 * response names that space. When the gate is mid-flight and a report revision is
 * sitting in the fence buffer (or its validation did not clear), the interface
 * answers with an explicit PENDING_VERIFICATION payload carrying the last verified
 * version and the reason. It never blocks, never returns a half-mixed document, and
 * never silently downgrades a verified report to an unverified one.
 */
@Service
public class PublicationService {

    private final JdbcTemplate jdbc;
    private final TenantService tenants;
    private final CryptoSupport crypto;
    private final EvidenceService evidence;
    private final MigrationService migrations;
    private final SemanticRoutingService semanticRouting;
    private final TemplateRouteGate routeGate;

    public PublicationService(JdbcTemplate jdbc, TenantService tenants, CryptoSupport crypto,
                              EvidenceService evidence, MigrationService migrations,
                              SemanticRoutingService semanticRouting, TemplateRouteGate routeGate) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.crypto = crypto;
        this.evidence = evidence;
        this.migrations = migrations;
        this.semanticRouting = semanticRouting;
        this.routeGate = routeGate;
    }

    public record PublishResult(Map<String, Object> body, int httpStatus) {
    }

    @Transactional
    public PublishResult publish(String tenantCode, String reportId) {
        Tenant tenant = tenants.requireByCode(tenantCode);
        String readSpace = tenant.readSpace();
        if (!List.of("OLD", "NEW").contains(readSpace)) {
            throw ApiException.conflict("READ_SPACE_INVALID",
                    "read space is " + readSpace + "; refuse to publish from an undefined space", Map.of());
        }
        String schema = "OLD".equals(readSpace) ? tenant.oldSchema() : tenant.newSchema();

        List<Map<String, Object>> revisions = jdbc.queryForList("""
                select revision, sample_id, content::text as content, content_hash, updated_seq
                from %s.report_revision where report_id = ? order by revision desc
                """.formatted(schema), reportId);

        List<Map<String, Object>> buffered = jdbc.queryForList("""
                select seq, message_type, payload->>'revision' as revision, status
                from lab.intake_buffer
                where tenant_id = ?::uuid and status = 'BUFFERED' and payload->>'reportId' = ?
                order by seq
                """, tenant.tenantId(), reportId);

        if (revisions.isEmpty()) {
            // A report first introduced inside the switch fence exists nowhere verified yet.
            // Continuous publication still answers: explicit pending, never 404 and never a
            // read of the buffered (unapplied) payload.
            if (!buffered.isEmpty()) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("reportId", reportId);
                body.put("tenant", tenant.code());
                body.put("visibleState", "PENDING_VERIFICATION");
                body.put("sourceSpace", readSpace);
                body.put("lastVerifiedRevision", null);
                body.put("pendingReason", "report first arrived inside the switch fence; revisions "
                        + buffered.stream().map(b -> String.valueOf(b.get("revision"))).toList()
                        + " are buffered and not yet applied to " + readSpace);
                body.put("bufferedRevisions", buffered.stream().map(b -> Map.of(
                        "revision", b.get("revision"), "seq", b.get("seq"), "state", b.get("status"))).toList());
                body.put("phase", tenant.phase());
                body.put("routeGeneration", tenant.generation());
                body.put("servedAt", java.time.Instant.now().toString());
                body.put("history", List.of());
                recordPublication(tenant, reportId, 0, readSpace, "PENDING_VERIFICATION", body,
                        "report buffered inside fence, no verified version yet");
                evidence.append(tenant.tenantId(), "REPORT_PENDING", "REPORT", reportId, readSpace,
                        "PENDING_VERIFICATION", Map.of("reportId", reportId,
                                "reason", "first version inside fence", "bufferedCount", buffered.size()));
                return new PublishResult(body, 202);
            }
            // Perhaps only the other space has it (e.g. read still OLD but data only in NEW).
            String otherSchema = "OLD".equals(readSpace) ? tenant.newSchema() : tenant.oldSchema();
            Long otherCount = jdbc.queryForObject(
                    "select count(*) from " + otherSchema + ".report_revision where report_id = ?",
                    Long.class, reportId);
            if (otherCount != null && otherCount > 0) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("reportId", reportId);
                body.put("tenant", tenant.code());
                body.put("visibleState", "PENDING_VERIFICATION");
                body.put("sourceSpace", readSpace);
                body.put("pendingReason", "report exists in the " + ("OLD".equals(readSpace) ? "NEW" : "OLD")
                        + " space but not yet in the serving space " + readSpace
                        + "; waiting for the routing gate to verify the switch");
                body.put("phase", tenant.phase());
                body.put("routeGeneration", tenant.generation());
                body.put("servedAt", java.time.Instant.now().toString());
                body.put("history", List.of());
                recordPublication(tenant, reportId, 0, readSpace, "PENDING_VERIFICATION", body,
                        "report not in serving space");
                evidence.append(tenant.tenantId(), "REPORT_PENDING", "REPORT", reportId, readSpace,
                        "PENDING_VERIFICATION", Map.of("reportId", reportId,
                                "reason", "awaiting gate verification"));
                return new PublishResult(body, 202);
            }
            throw ApiException.notFound("REPORT_NOT_FOUND",
                    "report " + reportId + " is not present in the current read space " + readSpace);
        }

        // The newest revision present in the read space, plus its validation state.
        Map<String, Object> newest = revisions.get(0);
        int revision = ((Number) newest.get("revision")).intValue();
        String validation = latestValidation(tenant.tenantId(), reportId, revision);

        Optional<Map<String, Object>> pendingMeta = latestPendingRevision(tenant.tenantId(), reportId, revision);
        boolean fenced = migrations.currentFence(tenant.tenantId()).isPresent();

        if (!"VALID".equals(validation) || !buffered.isEmpty() || pendingMeta.isPresent()) {
            String reason = !"VALID".equals(validation) ? "revision " + revision + " validation is " + validation
                    : !buffered.isEmpty() ? "revision(s) " + buffered.stream().map(b -> String.valueOf(b.get("revision")))
                    .toList() + " are buffered inside the switch fence"
                    : "a newer revision " + pendingMeta.map(m -> m.get("revision")).orElse("") + " is awaiting verification";
            Map<String, Object> body = pendingBody(tenant, reportId, revision, readSpace, reason, buffered, revisions);
            recordPublication(tenant, reportId, revision, readSpace, "PENDING_VERIFICATION", body, reason);
            evidence.append(tenant.tenantId(), "REPORT_PENDING", "REPORT", reportId, readSpace,
                    "PENDING_VERIFICATION", Map.of("reportId", reportId, "revision", revision,
                            "reason", reason, "bufferedRevisions", buffered.stream().map(b -> b.get("revision")).toList()));
            return new PublishResult(body, 202);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportId", reportId);
        report.put("tenant", tenant.code());
        report.put("revision", revision);
        report.put("sampleId", newest.get("sample_id"));
        report.put("content", crypto.readTree((String) newest.get("content")));
        report.put("contentHash", newest.get("content_hash"));
        report.put("sourceSpace", readSpace);
        report.put("visibleState", "VERIFIED");
        report.put("routeGeneration", tenant.generation());
        report.put("phase", tenant.phase());
        report.put("servedAt", java.time.Instant.now().toString());
        report.put("history", revisionHistory(revisions));
        attachSemanticRouting(tenant, report, reportId, revision);
        recordPublication(tenant, reportId, revision, readSpace, "VERIFIED", report, null);
        evidence.append(tenant.tenantId(), "REPORT_SERVED", "REPORT", reportId, readSpace, "VERIFIED",
                Map.of("reportId", reportId, "revision", revision, "contentHash", newest.get("content_hash"),
                        "fenced", fenced, "generation", tenant.generation()));
        return new PublishResult(report, 200);
    }

    /**
     * Attaches the unified semantic interpretation without ever breaking continuous publication:
     * when the gate blocks routing (e.g. an active rule set was withdrawn) the document is still
     * served and the block is reported explicitly in the response rather than as a 5xx.
     */
    private void attachSemanticRouting(Tenant tenant, Map<String, Object> report, String reportId, int revision) {
        try {
            Map<String, Object> interpretation = semanticRouting.interpret(tenant, reportId, revision);
            report.put("semanticGateState", interpretation.get("gateState"));
            report.put("interpretationAuthoritative", interpretation.get("authoritative"));
            report.put("interpretationSource", interpretation.get("interpretationSource"));
            report.put("interpretations", interpretation.get("interpretations"));
        } catch (ApiException e) {
            report.put("semanticGateState", "BLOCKED");
            report.put("interpretationAuthoritative", false);
            report.put("semanticRoutingBlocked", Map.of("code", e.code(), "message", e.getMessage(),
                    "details", e.details()));
            evidence.append(tenant.tenantId(), "SEMANTIC_ROUTE_BLOCKED_ON_PUBLISH", "REPORT", reportId,
                    null, "PENDING_VERIFICATION", Map.of("code", e.code(), "message", e.getMessage()));
        }
    }

    private Map<String, Object> pendingBody(Tenant tenant, String reportId, int revision, String readSpace,
                                            String reason, List<Map<String, Object>> buffered,
                                            List<Map<String, Object>> revisions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reportId", reportId);
        body.put("tenant", tenant.code());
        body.put("visibleState", "PENDING_VERIFICATION");
        body.put("sourceSpace", readSpace);
        body.put("lastVerifiedRevision", revision);
        body.put("lastVerifiedContentHash", revisions.get(0).get("content_hash"));
        body.put("pendingReason", reason);
        body.put("bufferedRevisions", buffered.stream().map(b -> Map.of(
                "revision", b.get("revision"), "seq", b.get("seq"), "state", b.get("status"))).toList());
        body.put("phase", tenant.phase());
        body.put("routeGeneration", tenant.generation());
        body.put("servedAt", java.time.Instant.now().toString());
        body.put("history", revisionHistory(revisions));
        return body;
    }

    private List<Map<String, Object>> revisionHistory(List<Map<String, Object>> revisions) {
        List<Map<String, Object>> h = new ArrayList<>();
        for (Map<String, Object> r : revisions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("revision", r.get("revision"));
            m.put("contentHash", r.get("content_hash"));
            m.put("updatedSeq", r.get("updated_seq"));
            h.add(m);
        }
        return h;
    }

    private String latestValidation(String tenantId, String reportId, int revision) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select validation_status from lab.report_revision_meta
                where tenant_id = ?::uuid and report_id = ? and revision = ?
                """, tenantId, reportId, revision);
        if (rows.isEmpty()) {
            return "VALID";
        }
        String s = (String) rows.get(0).get("validation_status");
        return switch (s) {
            case "REJECTED" -> "REJECTED";
            case "FLAGGED" -> "FLAGGED";
            default -> "VALID";
        };
    }

    private Optional<Map<String, Object>> latestPendingRevision(String tenantId, String reportId, int current) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select report_id, revision, validation_status, message_seq
                from lab.report_revision_meta
                where tenant_id = ?::uuid and report_id = ? and revision > ?
                  and validation_status <> 'VALID'
                order by revision desc limit 1
                """, tenantId, reportId, current);
        return rows.stream().findFirst();
    }

    private void recordPublication(Tenant tenant, String reportId, int revision, String space,
                                   String visibleState, Map<String, Object> body, String reason) {
        String hash = body.get("contentHash") != null ? String.valueOf(body.get("contentHash"))
                : String.valueOf(body.get("lastVerifiedContentHash"));
        jdbc.update("""
                insert into lab.report_publication(publication_id, tenant_id, report_id, revision,
                  source_space, visible_state, content_hash, pending_reason)
                values (gen_random_uuid(), ?::uuid, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, report_id, revision) do update set
                  source_space = excluded.source_space,
                  visible_state = excluded.visible_state,
                  content_hash = excluded.content_hash,
                  pending_reason = excluded.pending_reason,
                  updated_at = now()
                """, tenant.tenantId(), reportId, revision, space, visibleState, hash, reason);
    }

    public List<Map<String, Object>> publications(String tenantId, int limit) {
        return jdbc.queryForList("""
                select report_id as "reportId", revision, source_space as "sourceSpace",
                       visible_state as "visibleState", content_hash as "contentHash",
                       pending_reason as "pendingReason", first_published_at as "firstPublishedAt",
                       updated_at as "updatedAt"
                from lab.report_publication where tenant_id = ?::uuid
                order by updated_at desc limit ?
                """, tenantId, limit);
    }

    /** Evidence replay: every space that ever served this report, in order. */
    public Map<String, Object> replay(String tenantCode, String reportId) {
        Tenant tenant = tenants.requireByCode(tenantCode);
        List<String> sampleIds = jdbc.queryForList("""
                select distinct sample_id from (
                  select sample_id from %s.report_revision where report_id = ?
                  union
                  select sample_id from %s.report_revision where report_id = ?
                ) s
                """.formatted(tenant.oldSchema(), tenant.newSchema()), String.class, reportId, reportId);
        List<Map<String, Object>> publications = jdbc.queryForList("""
                select revision, source_space as "sourceSpace", visible_state as "visibleState",
                       content_hash as "contentHash", pending_reason as "pendingReason", updated_at as "updatedAt"
                from lab.report_publication where tenant_id = ?::uuid and report_id = ?
                order by revision, updated_at
                """, tenant.tenantId(), reportId);
        List<Map<String, Object>> ev = jdbc.queryForList("""
                select seq, event_type as "eventType", source_space as "sourceSpace",
                       visible_state as "visibleState", payload, hash, created_at as "createdAt"
                from lab.evidence_log
                where tenant_id = ?::uuid
                  and (entity_id = ? or payload->>'reportId' = ?
                       or payload->>'sampleId' = any(?::text[]))
                order by seq
                """, tenant.tenantId(), reportId, reportId,
                sampleIds.isEmpty() ? new String[]{"__none__"}
                        : sampleIds.toArray(new String[0]));
        List<String> spaces = publications.stream().map(p -> String.valueOf(p.get("sourceSpace")))
                .distinct().toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportId", reportId);
        out.put("tenant", tenant.code());
        out.put("currentReadSpace", tenant.readSpace());
        out.put("phase", tenant.phase());
        out.put("spacesServed", spaces);
        out.put("publications", publications);
        out.put("evidence", ev);
        out.put("chainIntegrity", evidence.verifyChain(tenant.tenantId()));
        return out;
    }

    public Map<String, Object> reportInventory(String tenantCode, String space) {
        Tenant tenant = tenants.requireByCode(tenantCode);
        String role = "NEW".equalsIgnoreCase(space) ? "NEW" : "OLD";
        String schema = "OLD".equals(role) ? tenant.oldSchema() : tenant.newSchema();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select r.report_id as "reportId", max(r.revision) as "latestRevision",
                       count(*) as "revisionCount", max(r.updated_seq) as "updatedSeq"
                from %s.report_revision r group by r.report_id order by r.report_id
                """.formatted(schema));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant", tenant.code());
        out.put("space", role);
        out.put("readSpace", tenant.readSpace());
        out.put("reports", rows);
        return out;
    }

    public JsonNode reportContent(Tenant tenant, String role, String reportId) {
        String schema = "NEW".equalsIgnoreCase(role) ? tenant.newSchema() : tenant.oldSchema();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select report_id, revision, content, content_hash from %s.report_revision
                where report_id = ? order by revision
                """.formatted(schema), reportId);
        return crypto.mapper().valueToTree(rows);
    }
}
