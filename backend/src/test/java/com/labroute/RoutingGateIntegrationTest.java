package com.labroute;

import com.labroute.model.Tenant;
import com.labroute.service.DemoDataService;
import com.labroute.service.MigrationService;
import com.labroute.service.PublicationService;
import com.labroute.service.ReplicationService;
import com.labroute.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test against a real PostgreSQL instance (database ccdev_test).
 * Each test creates its own fresh tenant, so runs are idempotent and isolated.
 */
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class RoutingGateIntegrationTest {

    @Autowired TenantService tenants;
    @Autowired com.labroute.service.AuthService auth;
    @Autowired DemoDataService demo;
    @Autowired MigrationService migrations;
    @Autowired ReplicationService replication;
    @Autowired PublicationService publication;

    private Tenant freshTenant(String strategy) {
        String code = "test-" + strategy.toLowerCase().replace('_', '-') + "-"
                + java.util.UUID.randomUUID().toString().substring(0, 8);
        Tenant t = tenants.create(new TenantService.CreateTenant(code, code, strategy));
        auth.addCredential(t.tenantId(), "SOURCE-A", "test-secret-" + code,
                "intake:sample,intake:result,intake:report,intake:object");
        return tenants.requireByCode(t.code());
    }

    private Map<String, Object> deliver(Tenant t, String kind, Map<String, Object> payload) {
        return demo.deliver(t, kind, payload, null, null);
    }

    @Test
    void fenceReplayRouteKeepsIntakeLiveAndPublishesPendingThenVerified() {
        Tenant tenant = freshTenant("FENCE_REPLAY");
        String sid = demo.uniqueKey("IT-FS");
        String rep = demo.uniqueKey("IT-FR");
        deliver(tenant, "sample", demo.samplePayload(sid, "SOURCE-A"));

        migrations.start(tenant, "FENCE_REPLAY");
        migrations.openFence(tenants.requireById(tenant.tenantId()));

        Map<String, Object> r1 = deliver(tenant, "result",
                demo.resultPayload(demo.uniqueKey("IT-FRES"), sid, "K", "4.2", "mmol/L", "3.5", "5.1"));
        Map<String, Object> r2 = deliver(tenant, "report", demo.reportPayload(rep, sid, 1, "v1"));
        assertThat(r1.get("status")).isEqualTo("BUFFERED");
        assertThat(r2.get("status")).isEqualTo("BUFFERED");
        assertThat(((Number) r1.get("seq")).longValue()).isLessThan(((Number) r2.get("seq")).longValue());

        PublicationService.PublishResult pending = publication.publish(tenant.code(), rep);
        assertThat(pending.httpStatus()).isEqualTo(202);
        assertThat(pending.body().get("visibleState")).isEqualTo("PENDING_VERIFICATION");

        migrations.drainFence(tenants.requireById(tenant.tenantId()));
        migrations.releaseFence(tenants.requireById(tenant.tenantId()));
        Tenant after = tenants.requireById(tenant.tenantId());
        assertThat(after.phase()).isEqualTo("STEADY_NEW");
        assertThat(after.readSpace()).isEqualTo("NEW");

        Map<String, Object> published = publication.publish(tenant.code(), rep).body();
        assertThat(published.get("visibleState")).isEqualTo("VERIFIED");
        assertThat(published.get("sourceSpace")).isEqualTo("NEW");

        Map<String, Object> replay = publication.replay(tenant.code(), rep);
        assertThat((java.util.List<Object>) replay.get("spacesServed")).contains((Object) "NEW");
    }

    @Test
    void onlineRouteSwitchesReadsBeforeWritesAndHonoursWatermarks() {
        Tenant tenant = freshTenant("WATERMARK_ONLINE");
        String sid = demo.uniqueKey("IT-OS");
        deliver(tenant, "sample", demo.samplePayload(sid, "SOURCE-A"));

        migrations.start(tenant, "WATERMARK_ONLINE");
        migrations.startDualWrite(tenants.requireById(tenant.tenantId()));
        Tenant dual = tenants.requireById(tenant.tenantId());
        assertThat(dual.writeSpace()).isEqualTo("DUAL");
        assertThat(dual.readSpace()).isEqualTo("OLD");

        Map<String, Object> receipt = deliver(tenant, "sample", demo.samplePayload(demo.uniqueKey("IT-OD"), "SOURCE-A"));
        assertThat(receipt.get("status")).isEqualTo("APPLIED_DUAL");

        migrations.shadowCheck(tenants.requireById(tenant.tenantId()));
        migrations.switchRead(tenants.requireById(tenant.tenantId()));
        Tenant readNew = tenants.requireById(tenant.tenantId());
        assertThat(readNew.readSpace()).isEqualTo("NEW");
        assertThat(readNew.writeSpace()).isEqualTo("DUAL");

        migrations.switchWrite(tenants.requireById(tenant.tenantId()));
        Tenant done = tenants.requireById(tenant.tenantId());
        assertThat(done.phase()).isEqualTo("STEADY_NEW");
        assertThat(done.writeSpace()).isEqualTo("NEW");
    }

    @Test
    void rollbackRestoresOldReadSpaceAndRecordsEvidence() {
        Tenant tenant = freshTenant("WATERMARK_ONLINE");
        migrations.start(tenant, "WATERMARK_ONLINE");
        migrations.startDualWrite(tenants.requireById(tenant.tenantId()));
        migrations.shadowCheck(tenants.requireById(tenant.tenantId()));
        replication.replicateDeltas(tenants.requireById(tenant.tenantId()),
                migrations.active(tenant.tenantId()).migrationId());
        replication.refreshWatermarks(tenants.requireById(tenant.tenantId()),
                migrations.active(tenant.tenantId()).migrationId(), false);
        migrations.switchRead(tenants.requireById(tenant.tenantId()));
        assertThat(tenants.requireById(tenant.tenantId()).readSpace()).isEqualTo("NEW");

        Map<String, Object> rb = migrations.rollback(tenants.requireById(tenant.tenantId()), "test fault");
        Tenant rolled = tenants.requireById(tenant.tenantId());
        assertThat(rolled.readSpace()).isEqualTo("OLD");
        assertThat(rolled.phase()).isEqualTo("STEADY_OLD");
        assertThat(rb.get("evidenceId")).isNotNull();
    }

    @Test
    void dualWriteIsIdempotentUnderReplay() {
        Tenant tenant = freshTenant("WATERMARK_ONLINE");
        migrations.start(tenant, "WATERMARK_ONLINE");
        migrations.startDualWrite(tenants.requireById(tenant.tenantId()));
        String sid = demo.uniqueKey("IT-ID");
        Map<String, Object> payload = demo.samplePayload(sid, "SOURCE-A");
        Map<String, Object> receipt = deliver(tenant, "sample", payload);

        // Byte-identical replay with the same Idempotency-Key must return the same receipt
        // and must not add a second row in either space.
        Map<String, Object> again = demo.deliver(tenant, "sample", payload,
                (String) receipt.get("_idempotencyKey"), null);
        assertThat(again.get("duplicate")).isEqualTo(true);
        assertThat(again.get("messageId")).isEqualTo(receipt.get("messageId"));
        assertThat(jdbcCount("select count(*) from " + tenant.oldSchema()
                + ".sample where sample_id = '" + sid + "'")).isEqualTo(1);
        assertThat(jdbcCount("select count(*) from " + tenant.newSchema()
                + ".sample where sample_id = '" + sid + "'")).isEqualTo(1);
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Long jdbcCount(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
