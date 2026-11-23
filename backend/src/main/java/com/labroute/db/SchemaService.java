package com.labroute.db;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Owns the control-plane schema (lab.*) and the per-tenant physical data spaces.
 * Every tenant gets two real, physically separate PostgreSQL schemas (old/new)
 * so that a routing mistake cannot silently produce a cross-space mixed read.
 */
@Service
public class SchemaService {

    private static final Logger log = LoggerFactory.getLogger(SchemaService.class);
    public static final String CONTROL = "lab";

    private final JdbcTemplate jdbc;

    public SchemaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String CONTROL_DDL = """
            create schema if not exists lab;

            create table if not exists lab.tenant (
              tenant_id uuid primary key,
              code text not null unique,
              name text not null,
              status text not null default 'ACTIVE',
              strategy text not null default 'FENCE_REPLAY',
              isolation_level text not null default 'LOGICAL',
              policy_version int not null default 1,
              read_space text not null default 'OLD',
              write_space text not null default 'OLD',
              phase text not null default 'STEADY_OLD',
              generation bigint not null default 1,
              dedicated_role text,
              isolation_changed_at timestamptz,
              created_at timestamptz not null default now(),
              updated_at timestamptz not null default now()
            );

            create table if not exists lab.tenant_policy_event (
              event_id uuid primary key,
              tenant_id uuid not null references lab.tenant(tenant_id) on delete cascade,
              sequence int not null,
              from_level text,
              to_level text not null,
              policy_version int not null,
              dedicated_role text,
              reason text,
              created_at timestamptz not null default now(),
              unique (tenant_id, sequence)
            );

            create table if not exists lab.tenant_space (
              tenant_id uuid not null references lab.tenant(tenant_id) on delete cascade,
              space_role text not null,
              schema_name text not null unique,
              object_prefix text not null,
              label text,
              created_at timestamptz not null default now(),
              primary key (tenant_id, space_role)
            );

            create table if not exists lab.source_credential (
              credential_id uuid primary key,
              tenant_id uuid not null references lab.tenant(tenant_id) on delete cascade,
              source_code text not null,
              key_id text not null unique,
              secret text not null,
              scopes text not null,
              key_version int not null default 1,
              active boolean not null default true,
              created_at timestamptz not null default now(),
              rotated_at timestamptz,
              unique (tenant_id, source_code, key_version)
            );

            create table if not exists lab.boundary_book_entry (
              entry_id uuid primary key,
              tenant_id uuid not null references lab.tenant(tenant_id) on delete cascade,
              sample_id text not null,
              chain_version int not null,
              entry_type text not null,
              source_code text not null,
              key_id text not null,
              authorization_scope text not null,
              payload_hash text not null,
              signature text not null,
              message_id uuid not null,
              message_seq bigint not null,
              space_role text,
              status text not null default 'VALID',
              created_at timestamptz not null default now(),
              unique (tenant_id, message_id)
            );
            create index if not exists ix_boundary_sample on lab.boundary_book_entry(tenant_id, sample_id, chain_version);

            create table if not exists lab.intake_counter (
              tenant_id uuid primary key references lab.tenant(tenant_id) on delete cascade,
              last_seq bigint not null default 0
            );

            create table if not exists lab.used_nonce (
              tenant_id uuid not null,
              source_code text not null,
              nonce text not null,
              signed_at timestamptz,
              used_at timestamptz not null default now(),
              primary key (tenant_id, source_code, nonce)
            );

            create table if not exists lab.migration (
              migration_id uuid primary key,
              tenant_id uuid not null references lab.tenant(tenant_id) on delete cascade,
              strategy text not null,
              state text not null,
              source_space text not null default 'OLD',
              target_space text not null default 'NEW',
              started_at timestamptz not null default now(),
              fenced_at timestamptz,
              cutover_at timestamptz,
              completed_at timestamptz,
              rolled_back_at timestamptz,
              watermark_seq bigint default 0,
              rollback_reason text,
              rollback_id uuid
            );
            create unique index if not exists ux_migration_active on lab.migration(tenant_id)
              where state not in ('COMPLETED','ROLLED_BACK','FAILED');

            create table if not exists lab.fence (
              fence_id uuid primary key,
              migration_id uuid not null references lab.migration(migration_id) on delete cascade,
              tenant_id uuid not null,
              state text not null,
              opened_at timestamptz not null default now(),
              released_at timestamptz,
              high_seq bigint,
              buffered_count int not null default 0,
              applied_count int not null default 0,
              notes text
            );
            create unique index if not exists ux_fence_open on lab.fence(tenant_id)
              where state in ('OPEN','DRAINING');

            create table if not exists lab.intake_message (
              message_id uuid primary key,
              tenant_id uuid not null,
              seq bigint not null,
              idempotency_key text not null,
              endpoint text not null,
              message_type text not null,
              natural_key text not null,
              payload jsonb not null,
              payload_hash text not null,
              source_code text not null,
              key_id text not null,
              signature text not null,
              nonce text not null,
              signed_at timestamptz,
              received_at timestamptz not null default now(),
              storage_mode text not null,
              status text not null,
              applied_space text,
              fence_id uuid,
              migration_id uuid,
              validation_status text not null default 'VALID',
              findings jsonb not null default '[]'::jsonb,
              unique (tenant_id, idempotency_key),
              unique (tenant_id, seq)
            );
            create index if not exists ix_intake_msg_tenant_seq on lab.intake_message(tenant_id, seq);
            create index if not exists ix_intake_msg_tenant_key on lab.intake_message(tenant_id, natural_key);

            create table if not exists lab.intake_buffer (
              buffer_id uuid primary key,
              fence_id uuid not null references lab.fence(fence_id) on delete cascade,
              tenant_id uuid not null,
              seq bigint not null,
              message_id uuid not null,
              message_type text not null,
              natural_key text not null,
              payload jsonb not null,
              status text not null default 'BUFFERED',
              applied_space text,
              buffered_at timestamptz not null default now(),
              applied_at timestamptz,
              apply_error text,
              unique (fence_id, seq)
            );
            create index if not exists ix_buffer_report on lab.intake_buffer(tenant_id, (payload->>'reportId'));
            create index if not exists ix_buffer_sample on lab.intake_buffer(tenant_id, (payload->>'sampleId'));
            create index if not exists ix_buffer_order on lab.intake_buffer(fence_id, seq);

            create table if not exists lab.watermark (
              watermark_id uuid primary key,
              migration_id uuid not null references lab.migration(migration_id) on delete cascade,
              tenant_id uuid not null,
              stream_type text not null,
              stream_name text not null,
              source_position text,
              target_position text,
              verified_position text,
              lag bigint not null default 0,
              state text not null,
              updated_at timestamptz not null default now(),
              unique (migration_id, stream_type, stream_name)
            );

            create table if not exists lab.shadow_comparison (
              comparison_id uuid primary key,
              migration_id uuid not null,
              tenant_id uuid not null,
              mode text not null,
              compared_rows int not null,
              mismatched_rows int not null,
              result text not null,
              details jsonb not null default '{}'::jsonb,
              created_at timestamptz not null default now()
            );

            create table if not exists lab.semantic_finding (
              finding_id uuid primary key,
              tenant_id uuid not null,
              space_role text,
              sample_id text,
              result_id text,
              report_id text,
              rule_code text not null,
              severity text not null,
              message text not null,
              detail jsonb not null default '{}'::jsonb,
              status text not null default 'OPEN',
              detected_at timestamptz not null default now()
            );
            create index if not exists ix_finding_tenant on lab.semantic_finding(tenant_id, detected_at desc);

            create table if not exists lab.report_revision_meta (
              tenant_id uuid not null,
              report_id text not null,
              revision int not null,
              validation_status text not null,
              message_seq bigint,
              updated_at timestamptz not null default now(),
              primary key (tenant_id, report_id, revision)
            );

            create table if not exists lab.report_publication (              publication_id uuid primary key,
              tenant_id uuid not null,
              report_id text not null,
              revision int not null,
              source_space text,
              visible_state text not null,
              content_hash text,
              migration_id uuid,
              pending_reason text,
              first_published_at timestamptz not null default now(),
              updated_at timestamptz not null default now(),
              unique (tenant_id, report_id, revision)
            );
            create index if not exists ix_publication_tenant on lab.report_publication(tenant_id, report_id);

            create table if not exists lab.evidence_log (
              evidence_id uuid primary key,
              tenant_id uuid not null,
              seq bigint not null,
              event_type text not null,
              entity_type text,
              entity_id text,
              source_space text,
              visible_state text,
              payload jsonb not null default '{}'::jsonb,
              prev_hash text,
              hash text not null,
              created_at timestamptz not null default now(),
              unique (tenant_id, seq)
            );
            create index if not exists ix_evidence_tenant on lab.evidence_log(tenant_id, seq);

            create table if not exists lab.rollback_record (
              rollback_id uuid primary key,
              migration_id uuid not null,
              tenant_id uuid not null,
              reason text,
              from_read_space text,
              to_read_space text,
              from_phase text,
              to_phase text,
              watermark_snapshot jsonb not null default '[]'::jsonb,
              affected_reports jsonb not null default '[]'::jsonb,
              evidence_id uuid,
              created_at timestamptz not null default now()
            );

            create table if not exists lab.scenario_run (
              run_id uuid primary key,
              scenario_code text not null,
              status text not null,
              tenant_codes jsonb not null default '[]'::jsonb,
              assertions jsonb not null default '[]'::jsonb,
              summary text,
              started_at timestamptz not null default now(),
              finished_at timestamptz
            );

            -- ===== Unified semantic routing: template directory, reference interval rules, =====
            -- ===== materialised interpretations, shadow renders, diff triage and enable gate. =====

            create table if not exists lab.template_catalog (
              template_id uuid primary key,
              tenant_id uuid references lab.tenant(tenant_id) on delete cascade,
              template_code text not null,
              version int not null,
              analyte_code text not null,
              name text not null,
              status text not null default 'DRAFT',
              rule_set_id uuid,
              content jsonb not null default '{}'::jsonb,
              effective_from timestamptz,
              effective_to timestamptz,
              withdrawn_at timestamptz,
              withdrawn_reason text,
              created_at timestamptz not null default now(),
              updated_at timestamptz not null default now(),
              unique (tenant_id, template_code, version)
            );
            create index if not exists ix_template_tenant on lab.template_catalog(tenant_id, analyte_code, status);

            create table if not exists lab.reference_rule_set (
              rule_set_id uuid primary key,
              tenant_id uuid references lab.tenant(tenant_id) on delete cascade,
              rule_set_code text not null,
              version int not null,
              status text not null default 'DRAFT',
              effective_from timestamptz,
              effective_to timestamptz,
              withdrawn_at timestamptz,
              withdrawn_reason text,
              created_at timestamptz not null default now(),
              updated_at timestamptz not null default now(),
              unique (tenant_id, rule_set_code, version)
            );
            create index if not exists ix_rule_set_tenant on lab.reference_rule_set(tenant_id, status);

            create table if not exists lab.reference_interval_rule (
              rule_id uuid primary key,
              rule_set_id uuid not null references lab.reference_rule_set(rule_set_id) on delete cascade,
              tenant_id uuid not null,
              analyte_code text not null,
              instrument_code text,
              age_low_days bigint not null default 0,
              age_high_days bigint,
              sex text,
              ref_low numeric(18,6),
              ref_high numeric(18,6),
              unit text not null,
              flag_label text not null default 'NORMAL',
              priority int not null default 100,
              status text not null default 'ACTIVE',
              effective_from timestamptz not null default now(),
              effective_to timestamptz,
              created_at timestamptz not null default now()
            );
            create index if not exists ix_interval_rule_lookup
              on lab.reference_interval_rule(tenant_id, analyte_code, instrument_code, status);

            create table if not exists lab.report_interpretation (
              interpretation_id uuid primary key,
              tenant_id uuid not null,
              report_id text not null,
              revision int not null,
              sample_id text,
              result_id text,
              analyte_code text not null,
              template_id uuid,
              rule_set_id uuid,
              rule_id uuid,
              rule_set_version text,
              history_mode text not null,
              report_time timestamptz not null,
              value_num numeric(18,6),
              unit text,
              ref_low numeric(18,6),
              ref_high numeric(18,6),
              flag text not null,
              rendered_text text,
              content_hash text not null,
              created_at timestamptz not null default now(),
              unique (tenant_id, report_id, revision, result_id)
            );
            create index if not exists ix_interpretation_report
              on lab.report_interpretation(tenant_id, report_id, revision);

            create table if not exists lab.shadow_render (
              shadow_id uuid primary key,
              tenant_id uuid not null,
              report_id text not null,
              revision int not null,
              run_reason text not null default 'MANUAL',
              baseline_label text not null,
              candidate_label text not null,
              baseline_hash text,
              candidate_hash text,
              baseline_payload jsonb not null default '{}'::jsonb,
              candidate_payload jsonb not null default '{}'::jsonb,
              result text not null,
              diff_count int not null default 0,
              created_at timestamptz not null default now()
            );
            create index if not exists ix_shadow_report on lab.shadow_render(tenant_id, report_id, created_at desc);

            create table if not exists lab.shadow_triage (
              triage_id uuid primary key,
              shadow_id uuid references lab.shadow_render(shadow_id) on delete cascade,
              tenant_id uuid not null,
              report_id text not null,
              revision int,
              result_id text,
              analyte_code text,
              diff_type text not null,
              severity text not null,
              status text not null default 'OPEN',
              rule_id uuid,
              rule_set_id uuid,
              detail jsonb not null default '{}'::jsonb,
              resolution_note text,
              created_at timestamptz not null default now(),
              resolved_at timestamptz
            );
            create index if not exists ix_triage_gate on lab.shadow_triage(tenant_id, status, severity);

            create table if not exists lab.template_route_gate (
              gate_id uuid primary key,
              tenant_id uuid not null unique references lab.tenant(tenant_id) on delete cascade,
              state text not null default 'SHADOW',
              history_mode text not null default 'REPLAYED',
              candidate_template_id uuid,
              candidate_rule_set_id uuid,
              active_template_id uuid,
              active_rule_set_id uuid,
              open_triage_count int not null default 0,
              blocking_triage_count int not null default 0,
              last_shadow_result text,
              last_gate_reason text,
              created_at timestamptz not null default now(),
              updated_at timestamptz not null default now()
            );

            create table if not exists lab.template_gate_event (
              event_id uuid primary key,
              tenant_id uuid not null,
              from_state text,
              to_state text not null,
              reason text,
              detail jsonb not null default '{}'::jsonb,
              created_at timestamptz not null default now()
            );
            create index if not exists ix_gate_event_tenant on lab.template_gate_event(tenant_id, created_at desc);
            """;

    /**
     * Per-space tables. %s is replaced by a schema name that has already been
     * validated against the tenant slug pattern (never interpolate user input).
     */
    private static final String SPACE_DDL = """
            create table if not exists %1$s.sample (
              sample_id text primary key,
              chain_version int not null default 1,
              parent_sample_id text,
              source_code text,
              patient_ref_masked text,
              collected_at timestamptz,
              status text,
              payload jsonb not null default '{}'::jsonb,
              origin_message_id uuid,
              updated_seq bigint not null default 0,
              updated_at timestamptz not null default now()
            );
            create table if not exists %1$s.instrument_result (
              result_id text primary key,
              sample_id text not null,
              instrument_code text,
              analyte_code text,
              value_num numeric(18,6),
              unit text,
              ref_low numeric(18,6),
              ref_high numeric(18,6),
              measured_at timestamptz,
              received_at timestamptz,
              status text,
              payload jsonb not null default '{}'::jsonb,
              origin_message_id uuid,
              updated_seq bigint not null default 0,
              updated_at timestamptz not null default now()
            );
            create index if not exists ix_%2$s_result_sample on %1$s.instrument_result(sample_id);
            create index if not exists ix_%2$s_result_seq on %1$s.instrument_result(updated_seq);
            create table if not exists %1$s.report_revision (
              report_id text not null,
              revision int not null,
              sample_id text not null,
              content jsonb not null,
              content_hash text not null,
              source_space text,
              origin_message_id uuid,
              updated_seq bigint not null default 0,
              created_at timestamptz not null default now(),
              primary key (report_id, revision)
            );
            create index if not exists ix_%2$s_report_sample on %1$s.report_revision(sample_id);
            create table if not exists %1$s.object_store (
              object_key text primary key,
              object_type text,
              content_hash text not null,
              payload jsonb not null default '{}'::jsonb,
              updated_seq bigint not null default 0,
              updated_at timestamptz not null default now()
            );
            create table if not exists %1$s.applied_message (
              message_id uuid primary key,
              seq bigint not null,
              applied_at timestamptz not null default now()
            );
            """;

    @PostConstruct
    public void init() {
        runScript(CONTROL_DDL);
        // Idempotent repairs for databases created before a column was added.
        jdbc.execute("alter table lab.reference_rule_set add column if not exists updated_at timestamptz not null default now()");
        log.info("control-plane schema ready");
    }

    /**
     * Idempotent repair pass: every tenant_space row recorded in the control plane must
     * have a live physical schema. A crash between registering a tenant and creating its
     * schemas is healed here instead of surfacing as a 500 on the first read.
     */
    public void ensureAllSpaces() {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("select schema_name from lab.tenant_space");
        } catch (Exception e) {
            return;
        }
        for (Map<String, Object> row : rows) {
            String schema = String.valueOf(row.get("schema_name"));
            Integer present = jdbc.queryForObject(
                    "select count(*) from information_schema.schemata where schema_name = ?", Integer.class, schema);
            boolean tableMissing = present == null || present == 0
                    || jdbc.queryForObject("select count(*) from information_schema.tables " +
                    "where table_schema = ? and table_name = 'sample'", Integer.class, schema) == 0;
            if (tableMissing) {
                createSpace(schema);
            }
        }
    }

    public static String spaceSchema(String tenantCode, String role) {
        if (!tenantCode.matches("^[a-z0-9][a-z0-9-]{1,40}$")) {
            throw new IllegalArgumentException("invalid tenant code");
        }
        return "sp_" + tenantCode.replace('-', '_') + "_" + role.toLowerCase();
    }

    public void createSpace(String schema) {
        if (!schema.matches("^sp_[a-z0-9_]+$")) {
            throw new IllegalArgumentException("invalid space schema");
        }
        jdbc.execute("create schema if not exists " + schema);
        String shortName = schema.replace("sp_", "sp");
        runScript(SPACE_DDL.formatted(schema, shortName));
        log.info("tenant space {} ready", schema);
    }

    private void runScript(String ddl) {
        for (String raw : ddl.split(";")) {
            String stmt = raw.strip();
            if (!stmt.isEmpty()) {
                jdbc.execute(stmt);
            }
        }
    }

    public List<String> tables() {
        return List.of("sample", "instrument_result", "report_revision", "object_store");
    }
}
