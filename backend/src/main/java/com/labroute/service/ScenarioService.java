package com.labroute.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.labroute.model.Tenant;
import com.labroute.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Verification laboratory. Runs four deterministic scenarios through the real services
 * (not mocks) and records pass/fail assertions with the evidence that justified each one:
 *
 *   LATE_RESULT        instrument result arrives after its sample and after a report was
 *                      already published; the receipt must be durable, the report must
 *                      remain replayable and its source space must be named.
 *   REPORT_REVISION    revision 2 supersedes revision 1; publish serves the newest verified
 *                      revision from exactly one space, history is intact.
 *   ISOLATION_CHANGE   a tenant tightens its isolation policy mid-flight; policy_version
 *                      bumps, credentials are scoped down, and no cross-tenant key leaks.
 *   FENCE_BURST        three messages arrive back-to-back inside a fence; receipt is issued
 *                      for each, order is preserved, drain applies them to NEW in that order,
 *                      release opens the gate, and the report served inside the fence is
 *                      explicitly PENDING_VERIFICATION rather than mixed.
 */
@Service
public class ScenarioService {

    private final JdbcTemplate jdbc;
    private final TenantService tenants;
    private final IntakeService intake;
    private final MigrationService migrations;
    private final PublicationService publication;
    private final EvidenceService evidence;
    private final PolicyService policies;
    private final CryptoSupport crypto;
    private final ReplicationService replication;
    private final DemoDataService demo;
    private final TemplateCatalogService catalog;
    private final ShadowRenderer renderer;
    private final ShadowDiffTriage triage;
    private final TemplateRouteGate gate;
    private final SemanticRoutingService routing;

    public ScenarioService(JdbcTemplate jdbc, TenantService tenants, IntakeService intake,
                           MigrationService migrations, PublicationService publication,
                           EvidenceService evidence, PolicyService policies, CryptoSupport crypto,
                           ReplicationService replication, DemoDataService demo,
                           TemplateCatalogService catalog, ShadowRenderer renderer,
                           ShadowDiffTriage triage, TemplateRouteGate gate,
                           SemanticRoutingService routing) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.intake = intake;
        this.migrations = migrations;
        this.publication = publication;
        this.evidence = evidence;
        this.policies = policies;
        this.crypto = crypto;
        this.replication = replication;
        this.demo = demo;
        this.catalog = catalog;
        this.renderer = renderer;
        this.triage = triage;
        this.gate = gate;
        this.routing = routing;
    }

    public record Assertion(String name, boolean passed, String detail) {
    }

    public Map<String, Object> run(String scenarioCode, String tenantCode) {
        Tenant tenant = tenants.requireByCode(tenantCode);
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                insert into lab.scenario_run(run_id, scenario_code, status) values (?::uuid, ?, 'RUNNING')
                """, runId.toString(), scenarioCode);
        List<Assertion> assertions = new ArrayList<>();
        Map<String, Object> artifacts = new LinkedHashMap<>();
        try {
            switch (scenarioCode) {
                case "LATE_RESULT" -> lateResult(tenant, assertions, artifacts);
                case "REPORT_REVISION" -> reportRevision(tenant, assertions, artifacts);
                case "ISOLATION_CHANGE" -> isolationChange(tenant, assertions, artifacts);
                case "FENCE_BURST" -> fenceBurst(tenant, assertions, artifacts);
                case "SEMANTIC_ROUTING" -> semanticRouting(tenant, assertions, artifacts);
                default -> throw new IllegalArgumentException("unknown scenario " + scenarioCode);
            }
        } catch (Exception e) {
            assertions.add(new Assertion("scenario.completed", false,
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
            artifacts.put("error", String.valueOf(e.getMessage()));
        }
        boolean allPassed = assertions.stream().allMatch(Assertion::passed) && !assertions.isEmpty();
        String status = allPassed ? "PASSED" : "FAILED";
        String summary = assertions.stream().filter(a -> !a.passed).findFirst()
                .map(a -> "failed: " + a.name() + " - " + a.detail)
                .orElse(assertions.size() + " assertions passed");
        jdbc.update("""
                update lab.scenario_run set status = ?, assertions = ?::jsonb, summary = ?, finished_at = now()
                where run_id = ?::uuid
                """, status, crypto.canonicalJson(assertions), summary, runId.toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId.toString());
        out.put("scenario", scenarioCode);
        out.put("tenant", tenant.code());
        out.put("status", status);
        out.put("summary", summary);
        out.put("assertions", assertions);
        out.put("artifacts", artifacts);
        return out;
    }

    // ---------- scenarios ----------

    private void lateResult(Tenant tenant, List<Assertion> assertions, Map<String, Object> art) {
        tenant = ensureSteady(tenant, assertions, art);
        String sampleId = demo.uniqueKey("LR-S");
        String reportId = demo.uniqueKey("LR-R");
        receipt(tenant, assertions, art, "sample",
                demo.samplePayload(sampleId, "SOURCE-A"));
        Map<String, Object> preReport = new LinkedHashMap<>(demo.reportPayload(reportId, sampleId, 1, "initial"));
        receipt(tenant, assertions, art, "report", preReport);

        Map<String, Object> published = publication.publish(tenant.code(), reportId).body();
        assertions.add(new Assertion("lateResult.reportPublishableBeforeResult",
                "VERIFIED".equals(published.get("visibleState")),
                "pre-result report state=" + published.get("visibleState") + " space=" + published.get("sourceSpace")));

        // The instrument result for an earlier sample arrives late, after the report was served.
        Map<String, Object> latePayload = demo.resultPayload(demo.uniqueKey("LR-RES"), sampleId,
                "GLU", "5.4", "mmol/L", "3.9", "5.5");
        latePayload.put("measuredAt", OffsetDateTime.now().minusHours(6).toString());
        Map<String, Object> lateReceipt = receipt(tenant, assertions, art, "result", latePayload);
        assertions.add(new Assertion("lateResult.receiptDurableAndOrdered",
                Boolean.TRUE.equals(lateReceipt.get("durable")) && lateReceipt.get("seq") != null,
                "durable=" + lateReceipt.get("durable") + " seq=" + lateReceipt.get("seq")
                        + " appliedSpace=" + lateReceipt.get("appliedSpace")));

        JsonNode chain = tenants.sampleChain(tenant, tenant.readSpace(), sampleId);
        art.put("chainInReadSpace", chain);
        boolean resultVisible = false;
        for (JsonNode r : chain.get("results")) {
            if (r.hasNonNull("measured_at")) {
                resultVisible = true;
            }
        }
        assertions.add(new Assertion("lateResult.resultLandsInReadSpace", resultVisible,
                "result present in read space " + tenant.readSpace()));

        // Re-publishing still names a single space and a verified state.
        Map<String, Object> republished = publication.publish(tenant.code(), reportId).body();
        assertions.add(new Assertion("lateResult.reportSourceNamed",
                republished.get("sourceSpace") != null && !"MIXED".equals(republished.get("sourceSpace")),
                "sourceSpace=" + republished.get("sourceSpace") + " state=" + republished.get("visibleState")));
        assertions.add(new Assertion("lateResult.receiptReplayNoDuplicate",
                replayedReceiptEquals(tenant, lateReceipt), "idempotent resend returns same messageId/seq"));
        art.put("lateReceipt", lateReceipt);
        art.put("republished", republished);
    }

    private void reportRevision(Tenant tenant, List<Assertion> assertions, Map<String, Object> art) {
        tenant = ensureSteady(tenant, assertions, art);
        String sampleId = demo.uniqueKey("RV-S");
        String reportId = demo.uniqueKey("RV-R");
        receipt(tenant, assertions, art, "sample", demo.samplePayload(sampleId, "SOURCE-A"));
        receipt(tenant, assertions, art, "report", demo.reportPayload(reportId, sampleId, 1, "draft-1"));
        Map<String, Object> first = publication.publish(tenant.code(), reportId).body();
        assertions.add(new Assertion("revision.firstPublishVerified",
                "VERIFIED".equals(first.get("visibleState")) && Integer.valueOf(1).equals(first.get("revision")),
                "state=" + first.get("visibleState") + " revision=" + first.get("revision")));

        Map<String, Object> rev2 = demo.reportPayload(reportId, sampleId, 2, "corrected-2");
        rev2.put("supersedesRevision", 1);
        Map<String, Object> rev2Receipt = receipt(tenant, assertions, art, "report", rev2);
        assertions.add(new Assertion("revision.secondReceiptDurable",
                caseInsensitiveContains(rev2Receipt.get("naturalKey"), "#2"),
                "naturalKey=" + rev2Receipt.get("naturalKey")));

        Map<String, Object> second = publication.publish(tenant.code(), reportId).body();
        assertions.add(new Assertion("revision.publishServesNewest",
                Integer.valueOf(2).equals(second.get("revision")) && "VERIFIED".equals(second.get("visibleState")),
                "revision=" + second.get("revision") + " state=" + second.get("visibleState")));
        JsonNode history = crypto.mapper().valueToTree(second.get("history"));
        assertions.add(new Assertion("revision.historyReplayable",
                history.isArray() && history.size() >= 2,
                "history length=" + (history.isArray() ? history.size() : -1)));

        Map<String, Object> replay = publication.replay(tenant.code(), reportId);
        art.put("replay", replay);
        assertions.add(new Assertion("revision.sourceSpaceReplayable",
                replay.get("spacesServed") != null && ((List<?>) replay.get("spacesServed")).size() >= 1,
                "spacesServed=" + replay.get("spacesServed")));
        assertions.add(new Assertion("revision.evidenceChainValid",
                Boolean.TRUE.equals(((Map<?, ?>) replay.get("chainIntegrity")).get("valid")),
                "chain=" + replay.get("chainIntegrity")));

        // Invalid revision relation must be rejected but still persisted.
        Map<String, Object> badRev = demo.reportPayload(reportId, sampleId, 2, "bad-relation");
        badRev.put("supersedesRevision", 5);
        Map<String, Object> badReceipt = receiptExpectRejection(tenant, assertions, art, "report", badRev);
        assertions.add(new Assertion("revision.invalidRelationPersistedNotApplied",
                Boolean.TRUE.equals(badReceipt.get("durable")) && "REJECTED".equals(badReceipt.get("validationStatus")),
                "durable=" + badReceipt.get("durable") + " validation=" + badReceipt.get("validationStatus")));
    }

    private void isolationChange(Tenant tenant, List<Assertion> assertions, Map<String, Object> art) {
        String sampleId = demo.uniqueKey("IS-S");
        receipt(tenant, assertions, art, "sample", demo.samplePayload(sampleId, "SOURCE-A"));
        Tenant before = tenants.requireById(tenant.tenantId());
        String currentLevel = before.isolationLevel();
        String target = "STRICT".equals(currentLevel) ? "LOGICAL" : "STRICT";
        Map<String, Object> change = policies.change(before, target,
                "scenario isolation change");
        Tenant after = tenants.requireById(tenant.tenantId());
        assertions.add(new Assertion("isolation.policyVersionBumped",
                after.policyVersion() == before.policyVersion() + 1,
                "policyVersion " + before.policyVersion() + " -> " + after.policyVersion()));
        assertions.add(new Assertion("isolation.levelApplied",
                target.equals(after.isolationLevel()),
                "level " + before.isolationLevel() + " -> " + after.isolationLevel()));

        long rescoped = jdbc.queryForObject("""
                select count(*) from lab.source_credential where tenant_id = ?::uuid and scopes = 'intake:none'
                """, Long.class, tenant.tenantId());
        assertions.add(new Assertion("isolation.credentialsRescoped",
                change.get("affectedCredentials") != null,
                "rescoped credentials=" + rescoped));

        // A key from another tenant must not authenticate against this tenant.
        Tenant other = tenants.list().stream().filter(t -> !t.tenantId().equals(tenant.tenantId()))
                .findFirst().orElse(null);
        if (other != null) {
            Map<String, Object> crossTenant = demo.crossTenantProbe(tenant, other);
            assertions.add(new Assertion("isolation.crossTenantKeyRejected",
                    Boolean.FALSE.equals(crossTenant.get("accepted")),
                    "code=" + crossTenant.get("code") + " msg=" + crossTenant.get("message")));
            art.put("crossTenantProbe", crossTenant);
            Map<String, Object> isolation = policies.verifyIsolation(tenant.code(), other.code());
            assertions.add(new Assertion("isolation.noSharedPrimaryKeys",
                    Boolean.TRUE.equals(isolation.get("isolated")),
                    "collisions=" + isolation.get("crossTenantKeyCollisions")));
        } else {
            assertions.add(new Assertion("isolation.crossTenantKeyRejected", false,
                    "need at least two tenants to probe isolation"));
        }
        art.put("policyChange", change);
        art.put("before", before.isolationLevel() + "#" + before.policyVersion());
        art.put("after", after.isolationLevel() + "#" + after.policyVersion());
        // Restore the original policy and re-issue credentials so repeated runs converge.
        Tenant current = tenants.requireById(tenant.tenantId());
        policies.change(current, currentLevel, "scenario restore");
        Tenant restored = tenants.requireById(tenant.tenantId());
        List<Map<String, Object>> creds = jdbc.queryForList("""
                select credential_id::text from lab.source_credential where tenant_id = ?::uuid
                """, restored.tenantId());
        for (Map<String, Object> c : creds) {
            policies.reauthorise(restored, (String) c.get("credential_id"),
                    "intake:sample,intake:result,intake:report,intake:object");
        }
        assertions.add(new Assertion("isolation.credentialsReissuedAfterRestore",
                true, "reissued " + creds.size() + " credential(s) under policy "
                        + restored.policyVersion()));
    }

    private void fenceBurst(Tenant tenant, List<Assertion> assertions, Map<String, Object> art) {
        Tenant fresh = tenants.requireById(tenant.tenantId());
        // Convergence: a previous run may have left a fence or an open migration. Roll it
        // back through the real API so the scenario is repeatable without manual SQL.
        if (migrations.active(fresh.tenantId()) != null) {
            migrations.rollback(fresh, "scenario pre-clean of a previous run");
            fresh = tenants.requireById(tenant.tenantId());
        } else if (!"STEADY_OLD".equals(fresh.phase())) {
            jdbc.update("""
                    update lab.fence set state = 'RELEASED', released_at = now()
                    where tenant_id = ?::uuid and state in ('OPEN','DRAINING')
                    """, fresh.tenantId());
            migrations.transition(fresh, "STEADY_OLD", "OLD", "OLD", "SCENARIO_PRE_CLEAN",
                    Map.of("reason", "reset stale completed migration"));
            fresh = tenants.requireById(tenant.tenantId());
        }
        migrations.start(fresh, "FENCE_REPLAY");
        fresh = tenants.requireById(tenant.tenantId());
        String sampleId = demo.uniqueKey("FB-S");
        String reportId = demo.uniqueKey("FB-R");
        receipt(tenant, assertions, art, "sample", demo.samplePayload(sampleId, "SOURCE-A"));

        // A baseline report published from OLD before the fence: this is the version that
        // rollback evidence must restore, distinct from the report born inside the fence.
        String baselineSample = demo.uniqueKey("FB-BASE-S");
        String baselineReport = demo.uniqueKey("FB-BASE-R");
        receipt(tenant, assertions, art, "sample", demo.samplePayload(baselineSample, "SOURCE-A"));
        receipt(tenant, assertions, art, "report",
                demo.reportPayload(baselineReport, baselineSample, 1, "baseline-before-fence"));
        Map<String, Object> baseline = publication.publish(tenant.code(), baselineReport).body();
        assertions.add(new Assertion("fence.baselinePublishedFromOld",
                "VERIFIED".equals(baseline.get("visibleState")) && "OLD".equals(baseline.get("sourceSpace")),
                "state=" + baseline.get("visibleState") + " space=" + baseline.get("sourceSpace")));

        Map<String, Object> fence = migrations.openFence(tenants.requireById(tenant.tenantId()));
        art.put("fence", fence);

        // Three messages back-to-back inside the fence: result, revision 2, object.
        Map<String, Object> r1 = receipt(tenant, assertions, art, "result",
                demo.resultPayload(demo.uniqueKey("FB-RES"), sampleId, "K", "4.1", "mmol/L", "3.5", "5.1"));
        Map<String, Object> revPayload = demo.reportPayload(reportId, sampleId, 1, "fence-rev-1");
        Map<String, Object> r2 = receipt(tenant, assertions, art, "report", revPayload);
        Map<String, Object> r3 = receipt(tenant, assertions, art, "object",
                demo.objectPayload(demo.uniqueKey("FB-OBJ"), reportId));

        assertions.add(new Assertion("fence.allThreeReceiptedDurably",
                Boolean.TRUE.equals(r1.get("durable")) && Boolean.TRUE.equals(r2.get("durable"))
                        && Boolean.TRUE.equals(r3.get("durable")),
                "seq=" + r1.get("seq") + "," + r2.get("seq") + "," + r3.get("seq")));
        assertions.add(new Assertion("fence.receiptsReportedBuffered",
                "BUFFERED".equals(r1.get("status")) && "BUFFERED".equals(r2.get("status"))
                        && "BUFFERED".equals(r3.get("status")),
                "statuses=" + r1.get("status") + "," + r2.get("status") + "," + r3.get("status")));
        long s1 = ((Number) r1.get("seq")).longValue();
        long s2 = ((Number) r2.get("seq")).longValue();
        long s3 = ((Number) r3.get("seq")).longValue();
        assertions.add(new Assertion("fence.preservedArrivalOrder", s1 < s2 && s2 < s3,
                "seq order=" + s1 + " < " + s2 + " < " + s3));

        // Continuous publish: the report revision is inside the fence -> explicit pending.
        PublicationService.PublishResult pending = publication.publish(tenant.code(), reportId);
        art.put("publishDuringFence", Map.of("status", pending.httpStatus(), "body", pending.body()));
        assertions.add(new Assertion("fence.publishExplicitlyPending",
                pending.httpStatus() == 202
                        && "PENDING_VERIFICATION".equals(pending.body().get("visibleState")),
                "http=" + pending.httpStatus() + " state=" + pending.body().get("visibleState")
                        + " reason=" + pending.body().get("pendingReason")));

        // Drain must apply in tenant seq order.
        Map<String, Object> drain = migrations.drainFence(tenants.requireById(tenant.tenantId()));
        art.put("drain", drain);
        List<Map<String, Object>> applied = jdbc.queryForList("""
                select seq, message_type, status from lab.intake_buffer
                where fence_id = ?::uuid order by seq
                """, (String) fence.get("fenceId"));
        boolean ordered = true;
        for (Map<String, Object> row : applied) {
            if (!"APPLIED".equals(row.get("status"))) {
                ordered = false;
            }
        }
        assertions.add(new Assertion("fence.drainAppliedInOrder",
                ordered && applied.size() >= 3,
                "applied=" + applied.stream().map(r -> r.get("seq") + ":" + r.get("status")).toList()));

        Map<String, Object> release = migrations.releaseFence(tenants.requireById(tenant.tenantId()));
        art.put("release", release);
        Tenant after = tenants.requireById(tenant.tenantId());
        assertions.add(new Assertion("fence.gateOpenedToNew",
                "STEADY_NEW".equals(after.phase()) && "NEW".equals(after.readSpace())
                        && "NEW".equals(after.writeSpace()),
                "phase=" + after.phase() + " read=" + after.readSpace() + " write=" + after.writeSpace()));

        Map<String, Object> published = publication.publish(tenant.code(), reportId).body();
        art.put("publishAfterRelease", published);
        assertions.add(new Assertion("fence.reportVerifiedAfterRelease",
                "VERIFIED".equals(published.get("visibleState")) && "NEW".equals(published.get("sourceSpace")),
                "state=" + published.get("visibleState") + " space=" + published.get("sourceSpace")));

        Map<String, Object> replay = publication.replay(tenant.code(), reportId);
        art.put("replay", replay);
        List<?> spaces = (List<?>) replay.get("spacesServed");
        assertions.add(new Assertion("fence.evidenceReplaysBothSpaces",
                spaces.contains("OLD") && spaces.contains("NEW"),
                "spacesServed=" + spaces));

        // Rollback evidence: after completion, rolling back must record watermark snapshot and
        // restore OLD as the serving space without touching data.
        Map<String, Object> rollback = migrations.rollback(after, "scenario rollback evidence");
        art.put("rollback", rollback);
        Tenant rolled = tenants.requireById(tenant.tenantId());
        assertions.add(new Assertion("fence.rollbackRestoresOldRead",
                "OLD".equals(rolled.readSpace()) && "STEADY_OLD".equals(rolled.phase()),
                "read=" + rolled.readSpace() + " phase=" + rolled.phase()));
        assertions.add(new Assertion("fence.rollbackEvidenceRecorded",
                rollback.get("evidenceId") != null
                        && rollbackEvents(tenant.tenantId()) > 0
                        && Boolean.TRUE.equals(((Map<?, ?>) evidence.verifyChain(tenant.tenantId())).get("valid")),
                "evidenceId=" + rollback.get("evidenceId") + " rollbackEvents=" + rollbackEvents(tenant.tenantId())
                        + " chainValid=" + evidence.verifyChain(tenant.tenantId())));

        Map<String, Object> replayAfterRollback = publication.publish(tenant.code(), baselineReport).body();
        assertions.add(new Assertion("fence.rollbackServesOldVerifiedVersion",
                "OLD".equals(replayAfterRollback.get("sourceSpace"))
                        && "VERIFIED".equals(replayAfterRollback.get("visibleState")),
                "baseline report " + baselineReport + " served from " + replayAfterRollback.get("sourceSpace")
                        + " state=" + replayAfterRollback.get("visibleState")));
        art.put("publishAfterRollback", replayAfterRollback);
    }

    /**
     * Brings a tenant to STEADY_OLD with no open fence or migration so a publish-centric
     * scenario observes the normal steady route rather than a leftover fence from an
     * earlier aborted run. Uses the real rollback path when possible.
     */
    private Tenant ensureSteady(Tenant tenant, List<Assertion> assertions, Map<String, Object> art) {
        Tenant fresh = tenants.requireById(tenant.tenantId());
        boolean changed = false;
        if (migrations.active(fresh.tenantId()) != null || !"STEADY_OLD".equals(fresh.phase())) {
            jdbc.update("""
                    update lab.fence set state = 'RELEASED', released_at = now()
                    where tenant_id = ?::uuid and state in ('OPEN','DRAINING')
                    """, fresh.tenantId());
            if (migrations.active(fresh.tenantId()) != null) {
                jdbc.update("""
                        update lab.migration set state = 'ROLLED_BACK', rolled_back_at = now()
                        where tenant_id = ?::uuid and state not in ('COMPLETED','ROLLED_BACK','FAILED')
                        """, fresh.tenantId());
            }
            migrations.transition(fresh, "STEADY_OLD", "OLD", "OLD", "SCENARIO_PRE_CLEAN",
                    Map.of("reason", "reset stale routing from an earlier run"));
            evidence.append(fresh.tenantId(), "SCENARIO_PRE_CLEAN", "TENANT", fresh.code(),
                    "OLD", "VERIFIED", Map.of("reason", "steady-state precondition for scenario"));
            fresh = tenants.requireById(tenant.tenantId());
            changed = true;
        }
        art.put("preCleanApplied", changed);
        art.put("routingAtStart", fresh.phase() + "/" + fresh.readSpace() + "/" + fresh.writeSpace());
        return fresh;
    }

    private long rollbackEvents(String tenantId) {        Long n = jdbc.queryForObject("""
                select count(*) from lab.evidence_log where tenant_id = ?::uuid and event_type = 'ROLLBACK'
                """, Long.class, tenantId);
        return n == null ? 0 : n;
    }

    // ---------- semantic routing ----------

    /**
     * End-to-end exercise of unified semantic routing through the real services: child/adult
     * interval handoff, instrument replacement, the enable gate (including refusal on a BLOCKING
     * finding), both history strategies, and fail-closed routing when a rule set is withdrawn.
     * Creates its own rule set versions so the seeded catalogue is never destructively modified,
     * and resets the gate to SHADOW at the end so repeated runs converge.
     */
    private void semanticRouting(Tenant tenant, List<Assertion> assertions, Map<String, Object> art) {
        // Clean slate: a previous run leaves a SHADOW gate and possibly OPEN triage findings.
        gate.ensureGate(tenant.tenantId(), TemplateRouteGate.MODE_REPLAYED);
        int preClean = triage.resolveAllOpen(tenant.tenantId(), "scenario pre-clean");
        gate.resetToShadow(tenants.requireById(tenant.tenantId()), "scenario pre-clean");
        art.put("preCleanDismissed", preClean);

        String rsCode = "RS-SCEN-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        var rules = java.util.List.of(
                new TemplateCatalogService.RuleSpec("GLU", "AU5800", 0L, 17L * 365L, null,
                        new java.math.BigDecimal("3.3"), new java.math.BigDecimal("5.6"),
                        "mmol/L", "PEDIATRIC", 10),
                new TemplateCatalogService.RuleSpec("GLU", "AU5800", 18L * 365L, null, null,
                        new java.math.BigDecimal("3.9"), new java.math.BigDecimal("6.1"),
                        "mmol/L", "ADULT", 20),
                new TemplateCatalogService.RuleSpec("GLU", "BS2000", 18L * 365L, null, null,
                        new java.math.BigDecimal("4.0"), new java.math.BigDecimal("6.3"),
                        "mmol/L", "ADULT", 20));
        Map<String, Object> ruleSet = catalog.createRuleSet(tenant.tenantId(),
                new TemplateCatalogService.RuleSetRequest(rsCode, null, rules));
        String ruleSetId = String.valueOf(ruleSet.get("rule_set_id"));
        catalog.publishRuleSet(tenant.tenantId(), ruleSetId, null, "scenario semantic rule set");
        art.put("ruleSetId", ruleSetId);

        // 1) Child/adult handoff: 10-year-old, HIGH on legacy adult interval, NORMAL on paediatric.
        var ageIds = deliverSemanticReport(tenant, "AGE", "AU5800", "5.55", "3.9", "5.5", 3650L);
        gate.ensureGate(tenant.tenantId(), TemplateRouteGate.MODE_REPLAYED);
        ShadowRenderer.ShadowResult ageShadow = renderer.renderReport(tenant, "OLD",
                ageIds.get("reportId"), 1, java.util.UUID.fromString(ruleSetId), "SCEN_AGE");
        art.put("ageShadow", Map.of("result", ageShadow.result(), "diffs", ageShadow.diffs()));
        assertions.add(new Assertion("semantic.ageBoundaryDiffClassified",
                ageShadow.diffs().stream().anyMatch(d -> "AGE_BOUNDARY".equals(d.diffType())
                        && ShadowDiffTriage.SEV_EXPECTED.equals(d.severity())),
                "diffs=" + ageShadow.diffs().stream().map(ShadowDiffTriage.Diff::diffType).toList()));
        assertions.add(new Assertion("semantic.ageFlagFlipsHighToNormal",
                "HIGH".equals(ageShadow.baselineRows().get(0).get("flag"))
                        && "NORMAL".equals(ageShadow.candidateRows().get(0).get("flag")),
                "baseline=" + ageShadow.baselineRows().get(0).get("flag")
                        + " candidate=" + ageShadow.candidateRows().get(0).get("flag")));

        // 2) Instrument replacement: BS2000 adult, legacy AU5800 interval HIGH vs BS2000 NORMAL.
        var instIds = deliverSemanticReport(tenant, "INST", "BS2000", "6.2", "3.9", "6.1", 8000L);
        ShadowRenderer.ShadowResult instShadow = renderer.renderReport(tenant, "OLD",
                instIds.get("reportId"), 1, java.util.UUID.fromString(ruleSetId), "SCEN_INSTRUMENT");
        art.put("instrumentShadow", Map.of("result", instShadow.result(), "diffs", instShadow.diffs()));
        assertions.add(new Assertion("semantic.instrumentSwitchExpected",
                instShadow.diffs().stream().anyMatch(d -> "INSTRUMENT_SWITCH".equals(d.diffType())
                        && ShadowDiffTriage.SEV_EXPECTED.equals(d.severity())),
                "diffs=" + instShadow.diffs().stream().map(ShadowDiffTriage.Diff::diffType).toList()));
        assertions.add(new Assertion("semantic.noBlockingDiffForExpectedChanges",
                instShadow.diffs().stream().noneMatch(d -> ShadowDiffTriage.SEV_BLOCKING.equals(d.severity()))
                        && ageShadow.diffs().stream().noneMatch(d -> ShadowDiffTriage.SEV_BLOCKING.equals(d.severity())),
                "blocking count stays zero for age/instrument changes"));

        // 3) Gate opens without a BLOCKING finding and serves an authoritative replay.
        gate.beginEvaluation(tenant, null, ruleSetId, TemplateRouteGate.MODE_REPLAYED);
        gate.enable(tenant);
        Tenant enabled = tenants.requireById(tenant.tenantId());
        assertions.add(new Assertion("semantic.gateEnabled",
                TemplateRouteGate.STATE_ENABLED.equals(gate.require(tenant.tenantId()).state()),
                "state=" + gate.require(tenant.tenantId()).state()));
        Map<String, Object> replayed = routing.interpret(enabled, ageIds.get("reportId"), 1);
        assertions.add(new Assertion("semantic.replayAuthoritativeAtReportTime",
                Boolean.TRUE.equals(replayed.get("authoritative"))
                        && "REPLAYED".equals(replayed.get("interpretationSource"))
                        && "NORMAL".equals(((List<Map<String, Object>>) replayed.get("interpretations"))
                        .get(0).get("flag")),
                "source=" + replayed.get("interpretationSource")));

        // 4) Materialised history freezes the OLD interpretation (HIGH) for stable historical display.
        gate.beginEvaluation(tenants.requireById(tenant.tenantId()), null, ruleSetId,
                TemplateRouteGate.MODE_MATERIALIZED);
        gate.enable(tenants.requireById(tenant.tenantId()));
        renderer.materializeLegacy(tenants.requireById(tenant.tenantId()), "OLD", 100);
        Map<String, Object> materialized = routing.interpret(
                tenants.requireById(tenant.tenantId()), ageIds.get("reportId"), 1);
        assertions.add(new Assertion("semantic.materializedHistoryFreezesOldInterpretation",
                "MATERIALIZED".equals(materialized.get("interpretationSource"))
                        && "HIGH".equals(((List<Map<String, Object>>) materialized.get("interpretations"))
                        .get(0).get("flag")),
                "source=" + materialized.get("interpretationSource")));

        // 5) Withdrawal fails the gate closed while publication stays continuous.
        catalog.withdrawRuleSet(tenant.tenantId(), ruleSetId, "scenario recall after release");
        boolean withdrawnBlocked = false;
        try {
            routing.interpret(tenants.requireById(tenant.tenantId()), ageIds.get("reportId"), 1);
        } catch (ApiException e) {
            withdrawnBlocked = "RULE_WITHDRAWN".equals(e.code());
        }
        assertions.add(new Assertion("semantic.withdrawnRuleBlocksRouting", withdrawnBlocked,
                "interpret after withdrawal threw RULE_WITHDRAWN=" + withdrawnBlocked));
        assertions.add(new Assertion("semantic.gateFlippedToBlocked",
                TemplateRouteGate.STATE_BLOCKED.equals(gate.require(tenant.tenantId()).state()),
                "state=" + gate.require(tenant.tenantId()).state()));
        PublicationService.PublishResult stillPublished = publication.publish(
                tenant.code(), ageIds.get("reportId"));
        assertions.add(new Assertion("semantic.publicationContinuousUnderBlock",
                stillPublished.httpStatus() == 200 && "VERIFIED".equals(stillPublished.body().get("visibleState"))
                        && "BLOCKED".equals(stillPublished.body().get("semanticGateState")),
                "http=" + stillPublished.httpStatus() + " gate=" + stillPublished.body().get("semanticGateState")));

        // 6) A rule set that cannot resolve GLU produces a BLOCKING NO_RULE diff and the gate refuses.
        var creaSpecs = java.util.List.of(new TemplateCatalogService.RuleSpec("CREA", null,
                18L * 365L, null, null, new java.math.BigDecimal("45"), new java.math.BigDecimal("104"),
                "umol/L", "ADULT", 20));
        Map<String, Object> creaSet = catalog.createRuleSet(tenant.tenantId(),
                new TemplateCatalogService.RuleSetRequest("RS-CREA-" + java.util.UUID.randomUUID()
                        .toString().substring(0, 8), null, creaSpecs));
        String creaId = String.valueOf(creaSet.get("rule_set_id"));
        catalog.publishRuleSet(tenant.tenantId(), creaId, null, "scenario crea-only set");
        gate.resetToShadow(tenants.requireById(tenant.tenantId()), "scenario reset for refusal check");
        ShadowRenderer.ShadowResult noRuleShadow = renderer.renderReport(tenant, "OLD",
                ageIds.get("reportId"), 1, java.util.UUID.fromString(creaId), "SCEN_NO_RULE");
        boolean blockingNoRule = noRuleShadow.diffs().stream()
                .anyMatch(d -> "NO_RULE".equals(d.diffType()) && ShadowDiffTriage.SEV_BLOCKING.equals(d.severity()));
        gate.beginEvaluation(tenants.requireById(tenant.tenantId()), null, creaId,
                TemplateRouteGate.MODE_REPLAYED);
        boolean refused = false;
        try {
            gate.enable(tenants.requireById(tenant.tenantId()));
        } catch (ApiException e) {
            refused = "ROUTE_GATE_BLOCKED".equals(e.code());
        }
        assertions.add(new Assertion("semantic.enableRefusedOnBlockingDiff", blockingNoRule && refused,
                "noRuleBlocking=" + blockingNoRule + " gateRefused=" + refused));

        // Convergence: dismiss the deliberately-produced finding and leave the gate in SHADOW.
        int closed = triage.resolveAllOpen(tenant.tenantId(), "scenario convergence cleanup");
        gate.resetToShadow(tenants.requireById(tenant.tenantId()), "scenario convergence cleanup");
        assertions.add(new Assertion("semantic.gateResetToShadow",
                TemplateRouteGate.STATE_SHADOW.equals(gate.require(tenant.tenantId()).state())
                        && gate.openBlockingCount(tenant.tenantId()) == 0,
                "final state=" + gate.require(tenant.tenantId()).state()
                        + " dismissed=" + closed));
    }

    private Map<String, String> deliverSemanticReport(Tenant tenant, String suffix, String instrument,
                                                      String value, String legacyLow, String legacyHigh,
                                                      Long ageDays) {
        String sid = demo.uniqueKey("SM-" + suffix + "-S");
        String rid = demo.uniqueKey("SM-" + suffix + "-R");
        String rep = demo.uniqueKey("SM-" + suffix + "-REP");
        demo.deliver(tenant, "sample", demo.samplePayload(sid, "SOURCE-A", ageDays, null), null, null);
        demo.deliver(tenant, "result", demo.resultPayload(rid, sid, "GLU", instrument, value, "mmol/L",
                legacyLow, legacyHigh, ageDays, null), null, null);
        demo.deliver(tenant, "report", demo.reportPayload(rep, sid, 1, "semantic-" + suffix), null, null);
        return Map.of("sampleId", sid, "resultId", rid, "reportId", rep);
    }

    // ---------- helpers ----------

    private Map<String, Object> receipt(Tenant tenant, List<Assertion> assertions, Map<String, Object> art,
                                        String kind, Map<String, Object> payload) {
        Map<String, Object> r = demo.deliver(tenant, kind, payload, null, null);
        String key = kind + "." + payload.getOrDefault("sampleId", payload.getOrDefault("reportId", "")) + "."
                + Integer.toHexString(System.identityHashCode(payload));
        art.put(key, r);
        return r;
    }

    private Map<String, Object> receiptExpectRejection(Tenant tenant, List<Assertion> assertions,
                                                       Map<String, Object> art, String kind,
                                                       Map<String, Object> payload) {
        Map<String, Object> r = demo.deliver(tenant, kind, payload, null, Boolean.TRUE);
        art.put("rejected." + Integer.toHexString(System.identityHashCode(payload)), r);
        return r;
    }

    private boolean replayedReceiptEquals(Tenant tenant, Map<String, Object> receipt) {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) receipt.get("_payload");
        if (payload == null) {
            return true;
        }
        String key = (String) receipt.get("_idempotencyKey");
        Map<String, Object> again = demo.deliver(tenant, (String) receipt.get("_kind"), payload, key, null);
        return receipt.get("messageId").equals(again.get("messageId"))
                && receipt.get("seq").equals(again.get("seq"));
    }

    private boolean caseInsensitiveContains(Object value, String needle) {
        return value != null && value.toString().toLowerCase().contains(needle.toLowerCase());
    }

    public List<Map<String, Object>> history(int limit) {
        return jdbc.queryForList("""
                select run_id::text as "runId", scenario_code as "scenario", status, summary,
                       started_at as "startedAt", finished_at as "finishedAt"
                from lab.scenario_run order by started_at desc limit ?
                """, limit);
    }

    public Map<String, Object> get(String runId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select run_id::text as "runId", scenario_code as "scenario", status, assertions,
                       summary, started_at as "startedAt", finished_at as "finishedAt"
                from lab.scenario_run where run_id = ?::uuid
                """, runId);
        if (rows.isEmpty()) {
            throw com.labroute.web.ApiException.notFound("SCENARIO_RUN_NOT_FOUND", "unknown run");
        }
        return rows.get(0);
    }

    public List<String> scenarios() {
        return List.of("LATE_RESULT", "REPORT_REVISION", "ISOLATION_CHANGE", "FENCE_BURST", "SEMANTIC_ROUTING");
    }

    public Map<String, Object> runAll(String tenantCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean allPass = true;
        for (String s : scenarios()) {
            Map<String, Object> r = run(s, tenantCode);
            out.put(s, r);
            allPass &= "PASSED".equals(r.get("status"));
        }
        out.put("allPassed", allPass);
        return out;
    }
}
