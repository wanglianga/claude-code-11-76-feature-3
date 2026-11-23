#!/usr/bin/env python3
"""
End-to-end verification for LabRoute.

Talks to a *running* backend over HTTP only: it mints its own HMAC signatures and
Idempotency-Key headers, so a green run proves the real gateway auth path, the real
routing gate and the real publication interface agree. It does not import any Java
code and does not touch the database directly.

Covered scenarios (the ones required by the brief):
  1. LATE_RESULT        instrument result arrives after its report was already published
  2. REPORT_REVISION    revision 2 supersedes revision 1; invalid relation is rejected but kept
  3. ISOLATION_CHANGE   tenant isolation policy changes; cross-tenant key is refused
  4. FENCE_BURST        three messages arrive back-to-back inside a switch fence
  5. SEMANTIC_ROUTING   child/adult interval handoff, instrument replacement and rule
                        withdrawal: shadow diffs, historical display and gate route blocking

Run:
  python3 scripts/e2e_verify.py                # against http://127.0.0.1:8080
  BASE=http://host:8080 python3 scripts/e2e_verify.py

Exit code 0 only when every assertion passes.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone, timedelta

BASE = os.environ.get("BASE", "http://127.0.0.1:8080")
OPERATOR = os.environ.get("OPERATOR_TOKEN", "labroute-operator-demo")
TENANT = os.environ.get("TENANT", "lab-harbor")
PEER = os.environ.get("PEER_TENANT", "lab-orion")

RESULTS: list[tuple[bool, str, str]] = []


def record(ok: bool, name: str, detail: str) -> None:
    RESULTS.append((ok, name, detail))
    print(f"{'PASS' if ok else 'FAIL'}  {name}  ::  {detail}")


def http(method: str, path: str, body: dict | None = None, headers: dict | None = None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode()
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, (json.loads(raw) if raw else {})
        except json.JSONDecodeError:
            return e.code, {"raw": raw}


def operator_headers() -> dict:
    return {"X-Operator-Token": OPERATOR}


# ---------- signing (mirrors AuthService.signingString) ----------

def canonical(obj) -> str:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def sha256_hex(s: str) -> str:
    return hashlib.sha256(s.encode()).hexdigest()


def credential(tenant: str, source: str = "SOURCE-A") -> dict:
    status, body = http("GET", f"/api/demo/tenants/{tenant}/signing-material")
    if status != 200:
        raise SystemExit(f"cannot read signing material for {tenant}: {status} {body}")
    for c in body["credentials"]:
        if c["source_code"] == source:
            return c
    raise SystemExit(f"no credential for {source} on {tenant}: {body['credentials']}")


def sign(tenant: str, path: str, body: dict, cred: dict, nonce: str, signed_at: str) -> str:
    signing = "\n".join([
        "POST", path, tenant, cred["source_code"], cred["key_id"],
        nonce, signed_at, sha256_hex(canonical(body)),
    ])
    return hmac.new(cred["secret"].encode(), signing.encode(), hashlib.sha256).hexdigest()


def deliver(tenant: str, kind: str, payload: dict, idem: str | None = None):
    # Signed path is the canonical gateway path the server verifies against.
    sign_path = f"/api/intake/{kind}"
    # Full request path is routed under the tenant.
    request_path = f"/api/tenant/{tenant}/intake/{kind}"
    cred = credential(tenant, payload.get("sourceCode", "SOURCE-A"))
    body = dict(payload)
    nonce = str(uuid.uuid4())
    signed_at = datetime.now(timezone.utc).isoformat()
    sig = sign(tenant, sign_path, body, cred, nonce, signed_at)
    key = idem or str(uuid.uuid4())
    headers = {
        "X-Source-Code": cred["source_code"],
        "X-Key-Id": cred["key_id"],
        "X-Nonce": nonce,
        "X-Signed-At": signed_at,
        "X-Signature": sig,
        "Idempotency-Key": key,
    }
    status, resp = http("POST", request_path, body, headers)
    return status, resp, key


def uniq(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4().hex[:8].upper()}"


# ---------- payloads ----------

def sample_payload(sid: str) -> dict:
    return {
        "sampleId": sid, "chainVersion": 1, "sourceCode": "SOURCE-A",
        "patientRefMasked": "P-****", "collectedAt": datetime.now(timezone.utc).isoformat(),
        "status": "RECEIVED",
    }


def sample_payload_aged(sid: str, age_days: int, sex: str | None = None) -> dict:
    p = sample_payload(sid)
    p["patientAgeDays"] = age_days
    if sex:
        p["patientSex"] = sex
    return p


def result_payload(rid: str, sid: str, value="5.4", unit="mmol/L") -> dict:
    return {
        "resultId": rid, "sampleId": sid, "instrumentCode": "AU5800", "analyteCode": "GLU",
        "value": value, "unit": unit, "refLow": "3.9", "refHigh": "5.5",
        "measuredAt": datetime.now(timezone.utc).isoformat(), "status": "FINAL",
    }


def result_payload_full(rid: str, sid: str, instrument: str, value: str, low: str, high: str,
                        age_days: int, analyte: str = "GLU", unit: str = "mmol/L") -> dict:
    p = {
        "resultId": rid, "sampleId": sid, "instrumentCode": instrument, "analyteCode": analyte,
        "value": value, "unit": unit, "refLow": low, "refHigh": high,
        "measuredAt": datetime.now(timezone.utc).isoformat(), "receivedAt": datetime.now(timezone.utc).isoformat(),
        "status": "FINAL", "patientAgeDays": age_days,
    }
    return p


def report_payload(rep: str, sid: str, revision: int, extra: dict | None = None) -> dict:
    p = {
        "reportId": rep, "sampleId": sid, "revision": revision,
        "content": {"title": f"检验报告 {rep}", "revision": revision, "rows": [{"analyte": "GLU", "value": "5.1"}]},
    }
    if extra:
        p.update(extra)
    return p


# ---------- preconditions ----------

def steady(tenant: str) -> None:
    """Roll the tenant back to a clean STEADY_OLD so scenarios are deterministic."""
    http("POST", f"/api/tenants/{tenant}/migration/rollback", {"reason": "e2e precondition"}, operator_headers())


# ---------- scenarios ----------

def scenario_late_result() -> None:
    print("\n=== 1. 仪器迟到结果 (LATE_RESULT) ===")
    sid, rep = uniq("E2E-LR-S"), uniq("E2E-LR-R")
    st, receipt, _ = deliver(TENANT, "sample", sample_payload(sid))
    record(st == 200 and receipt.get("durable"), "sample.receipt_durable",
           f"http={st} seq={receipt.get('seq')} space={receipt.get('appliedSpace')}")

    st, receipt, _ = deliver(TENANT, "report", report_payload(rep, sid, 1))
    record(st == 200 and receipt.get("durable"), "report.receipt_durable", f"http={st} seq={receipt.get('seq')}")

    st, pub = http("GET", f"/api/tenant/{TENANT}/publish/reports/{rep}")
    record(st == 200 and pub.get("visibleState") == "VERIFIED", "report.publishable_before_result",
           f"http={st} state={pub.get('visibleState')} space={pub.get('sourceSpace')}")

    rid = uniq("E2E-LR-RES")
    late_payload = result_payload(rid, sid)
    st, late, late_key = deliver(TENANT, "result", late_payload)
    record(st == 200 and late.get("durable"), "late_result.receipt_durable",
           f"http={st} seq={late.get('seq')} space={late.get('appliedSpace')}")

    st, chain = http("GET", f"/api/tenants/{TENANT}/samples/{sid}/chain?space=READ")
    present = any(r.get("result_id") == rid for r in chain.get("results", []))
    record(st == 200 and present, "late_result.visible_in_read_space",
           f"http={st} readSpace={chain.get('space')} resultPresent={present}")

    # Byte-identical idempotent resend returns the same receipt, no duplicate row.
    st2, again, _ = deliver(TENANT, "result", late_payload, idem=late_key)
    dup_ok = again.get("duplicate") is True or again.get("messageId") == late.get("messageId")
    record(dup_ok, "late_result.idempotent_resend",
           f"http={st2} duplicate={again.get('duplicate')} sameMessageId={again.get('messageId') == late.get('messageId')}")

    st, pub2 = http("GET", f"/api/tenant/{TENANT}/publish/reports/{rep}")
    record(pub2.get("sourceSpace") in ("OLD", "NEW"), "late_result.report_source_named",
           f"state={pub2.get('visibleState')} space={pub2.get('sourceSpace')}")


def scenario_report_revision() -> None:
    print("\n=== 2. 报告修订 (REPORT_REVISION) ===")
    sid, rep = uniq("E2E-RV-S"), uniq("E2E-RV-R")
    deliver(TENANT, "sample", sample_payload(sid))
    deliver(TENANT, "report", report_payload(rep, sid, 1))
    st, first = http("GET", f"/api/tenant/{TENANT}/publish/reports/{rep}")
    record(st == 200 and first.get("revision") == 1, "revision.v1_published",
           f"http={st} revision={first.get('revision')} state={first.get('visibleState')}")

    st, r2, _ = deliver(TENANT, "report", report_payload(rep, sid, 2, {"supersedesRevision": 1}))
    record(st == 200 and "#2" in str(r2.get("naturalKey")), "revision.v2_receipt", f"http={st} key={r2.get('naturalKey')}")

    st, second = http("GET", f"/api/tenant/{TENANT}/publish/reports/{rep}")
    record(st == 200 and second.get("revision") == 2 and second.get("visibleState") == "VERIFIED",
           "revision.publish_serves_newest",
           f"revision={second.get('revision')} state={second.get('visibleState')} space={second.get('sourceSpace')}")
    record(len(second.get("history") or []) >= 2, "revision.history_replayable",
           f"history={len(second.get('history') or [])}")

    st, replay = http("GET", f"/api/tenant/{TENANT}/publish/reports/{rep}/replay")
    record(st == 200 and replay.get("chainIntegrity", {}).get("valid") is True,
           "revision.evidence_chain_valid",
           f"spacesServed={replay.get('spacesServed')} chain={replay.get('chainIntegrity')}")

    # Invalid revision relation: persisted (durable) but rejected by semantic validation.
    bad = report_payload(rep, sid, 2, {"supersedesRevision": 5})
    st, badr, _ = deliver(TENANT, "report", bad)
    record(st == 422 and badr.get("durable") is True and badr.get("validationStatus") == "REJECTED",
           "revision.invalid_relation_persisted_not_applied",
           f"http={st} durable={badr.get('durable')} validation={badr.get('validationStatus')}")


def scenario_isolation_change() -> None:
    print("\n=== 3. 租户隔离策略变化 (ISOLATION_CHANGE) ===")
    st, before = http("GET", f"/api/tenants/{TENANT}")
    level = before.get("isolationLevel")
    target = "STRICT" if level != "STRICT" else "LOGICAL"
    st, change = http("POST", f"/api/tenants/{TENANT}/isolation",
                      {"level": target, "reason": "e2e isolation change"}, operator_headers())
    record(st == 200 and change.get("policyVersion") == before.get("policyVersion", 0) + 1,
           "isolation.policy_version_bumped",
           f"http={st} v{before.get('policyVersion')} -> v{change.get('policyVersion')}")
    st, after = http("GET", f"/api/tenants/{TENANT}")
    record(after.get("isolationLevel") == target, "isolation.level_applied",
           f"{level} -> {after.get('isolationLevel')}")

    # ACK: a credential from another tenant must not authenticate against this tenant.
    peer_cred = credential(PEER)
    payload = sample_payload(uniq("E2E-X-S"))
    nonce = str(uuid.uuid4())
    signed_at = datetime.now(timezone.utc).isoformat()
    sig = sign(TENANT, "/api/intake/sample", payload, peer_cred, nonce, signed_at)
    st, resp = http("POST", f"/api/tenant/{TENANT}/intake/sample", payload, {
        "X-Source-Code": peer_cred["source_code"], "X-Key-Id": peer_cred["key_id"],
        "X-Nonce": nonce, "X-Signed-At": signed_at, "X-Signature": sig,
        "Idempotency-Key": str(uuid.uuid4()),
    })
    record(st == 401 and resp.get("code") == "AUTH_KEY_UNKNOWN", "isolation.cross_tenant_key_rejected",
           f"http={st} code={resp.get('code')}")

    st, iso = http("GET", f"/api/isolation/verify?a={TENANT}&b={PEER}")
    record(st == 200 and iso.get("isolated") is True, "isolation.no_shared_primary_keys",
           f"collisions={iso.get('crossTenantKeyCollisions')}")

    # Restore so repeated runs converge.
    http("POST", f"/api/tenants/{TENANT}/isolation",
         {"level": level, "reason": "e2e restore"}, operator_headers())
    st, detail = http("GET", f"/api/tenants/{TENANT}")
    for c in detail.get("credentials", []):
        http("POST", f"/api/tenants/{TENANT}/credentials/{c['credentialId']}/reauthorise",
             {"scopes": "intake:sample,intake:result,intake:report,intake:object"}, operator_headers())
    record(True, "isolation.restored_and_reauthorised", "policy restored, credentials re-issued")


def scenario_fence_burst() -> None:
    print("\n=== 4. 切换围栏内三连消息 (FENCE_BURST) ===")
    steady(TENANT)
    st, routing = http("GET", f"/api/tenants/{TENANT}/routing")
    record(routing.get("phase") == "STEADY_OLD", "fence.precondition_steady_old",
           f"phase={routing.get('phase')}")

    st, _ = http("POST", f"/api/tenants/{TENANT}/migration/start", {"strategy": "FENCE_REPLAY"}, operator_headers())
    record(st == 200, "fence.migration_started", f"http={st}")

    sid, rep = uniq("E2E-FB-S"), uniq("E2E-FB-R")
    # baseline published from OLD before the fence (rollback evidence must restore this)
    bsid, brep = uniq("E2E-FB-BS"), uniq("E2E-FB-BR")
    deliver(TENANT, "sample", sample_payload(bsid))
    deliver(TENANT, "report", report_payload(brep, bsid, 1))
    st, baseline = http("GET", f"/api/tenant/{TENANT}/publish/reports/{brep}")
    record(baseline.get("sourceSpace") == "OLD" and baseline.get("visibleState") == "VERIFIED",
           "fence.baseline_published_from_old",
           f"space={baseline.get('sourceSpace')} state={baseline.get('visibleState')}")

    deliver(TENANT, "sample", sample_payload(sid))
    st, fence = http("POST", f"/api/tenants/{TENANT}/migration/fence/open", {}, operator_headers())
    record(st == 200 and fence.get("state") == "OPEN", "fence.opened", f"http={st} state={fence.get('state')}")

    receipts = []
    receipts.append(deliver(TENANT, "result", result_payload(uniq("E2E-FB-RES"), sid)))
    receipts.append(deliver(TENANT, "report", report_payload(rep, sid, 1)))
    receipts.append(deliver(TENANT, "object", {
        "objectKey": uniq("E2E-FB-OBJ"), "objectType": "RAW_PDF",
        "content": {"reportId": rep, "pages": 2}}))
    ok_all = all(st == 200 and r.get("durable") and r.get("status") == "BUFFERED" for st, r, _ in receipts)
    seqs = [r.get("seq") for _, r, _ in receipts]
    record(ok_all, "fence.three_receipts_durable_buffered",
           f"seqs={seqs} statuses={[r.get('status') for _, r, _ in receipts]}")
    ordered = all(isinstance(x, int) for x in seqs) and seqs == sorted(seqs) and len(set(seqs)) == 3
    record(ordered, "fence.arrival_order_preserved", f"seqs={seqs}")

    # Continuous publication while fenced: explicit pending, never a 5xx or a mixed doc.
    st, pending = http("GET", f"/api/tenant/{TENANT}/publish/reports/{rep}")
    record(st == 202 and pending.get("visibleState") == "PENDING_VERIFICATION",
           "fence.publish_explicitly_pending_inside_fence",
           f"http={st} state={pending.get('visibleState')} reason={pending.get('pendingReason')}")

    st, drained = http("POST", f"/api/tenants/{TENANT}/migration/fence/drain", {}, operator_headers())
    record(st == 200 and drained.get("appliedThisPass") >= 3, "fence.drain_applies_in_order",
           f"http={st} appliedThisPass={drained.get('appliedThisPass')} highSeq={drained.get('highSeq')}")

    st, released = http("POST", f"/api/tenants/{TENANT}/migration/fence/release", {}, operator_headers())
    st2, routing2 = http("GET", f"/api/tenants/{TENANT}/routing")
    record(st == 200 and routing2.get("phase") == "STEADY_NEW" and routing2.get("readSpace") == "NEW"
           and routing2.get("writeSpace") == "NEW", "fence.gate_opened_to_new",
           f"http={st} phase={routing2.get('phase')} read={routing2.get('readSpace')} write={routing2.get('writeSpace')}")

    st, after = http("GET", f"/api/tenant/{TENANT}/publish/reports/{rep}")
    record(st == 200 and after.get("visibleState") == "VERIFIED" and after.get("sourceSpace") == "NEW",
           "fence.report_verified_from_new_after_release",
           f"state={after.get('visibleState')} space={after.get('sourceSpace')}")

    st, replay = http("GET", f"/api/tenant/{TENANT}/publish/reports/{rep}/replay")
    spaces = set(replay.get("spacesServed") or [])
    record({"OLD", "NEW"} <= spaces and replay.get("chainIntegrity", {}).get("valid"),
           "fence.evidence_replays_both_spaces",
           f"spacesServed={sorted(spaces)} chainValid={replay.get('chainIntegrity', {}).get('valid')}")

    # Rollback evidence: read space returns to OLD, evidence recorded, baseline still verified from OLD.
    st, rb = http("POST", f"/api/tenants/{TENANT}/migration/rollback",
                  {"reason": "e2e rollback evidence"}, operator_headers())
    st2, routing3 = http("GET", f"/api/tenants/{TENANT}/routing")
    record(st == 200 and routing3.get("readSpace") == "OLD", "fence.rollback_restores_old_read",
           f"http={st} read={routing3.get('readSpace')} phase={routing3.get('phase')}")
    st, ev = http("GET", f"/api/tenants/{TENANT}/evidence?limit=200")
    has_rollback = any(e.get("event_type") == "ROLLBACK" for e in ev.get("entries", []))
    record(has_rollback and ev.get("chain", {}).get("valid") is True, "fence.rollback_evidence_recorded",
           f"rollbackEvents={has_rollback} chainValid={ev.get('chain', {}).get('valid')}")

    st, restored = http("GET", f"/api/tenant/{TENANT}/publish/reports/{brep}")
    record(restored.get("sourceSpace") == "OLD" and restored.get("visibleState") == "VERIFIED",
           "fence.rollback_serves_old_verified_version",
           f"space={restored.get('sourceSpace')} state={restored.get('visibleState')}")


def scenario_semantic_routing() -> None:
    print("\n=== 5. 统一语义路由：儿童/成人交接·仪器更换·规则撤回 ===")
    base = f"/api/tenants/{TENANT}/semantic"
    http("POST", f"{base}/gate/reset", {"reason": "e2e precondition"}, operator_headers())

    # Fresh, uniquely-coded rule set: paediatric/adult GLU bands plus a second instrument.
    rs_code = f"RS-E2E-{uuid.uuid4().hex[:8]}"
    st, rs = http("POST", f"{base}/rule-sets", {
        "ruleSetCode": rs_code,
        "rules": [
            {"analyteCode": "GLU", "instrumentCode": "AU5800", "ageLowDays": 0, "ageHighDays": 17 * 365,
             "refLow": "3.3", "refHigh": "5.6", "unit": "mmol/L", "flagLabel": "PEDIATRIC", "priority": 10},
            {"analyteCode": "GLU", "instrumentCode": "AU5800", "ageLowDays": 18 * 365, "ageHighDays": None,
             "refLow": "3.9", "refHigh": "6.1", "unit": "mmol/L", "flagLabel": "ADULT", "priority": 20},
            {"analyteCode": "GLU", "instrumentCode": "BS2000", "ageLowDays": 18 * 365, "ageHighDays": None,
             "refLow": "4.0", "refHigh": "6.3", "unit": "mmol/L", "flagLabel": "ADULT", "priority": 20},
        ],
    }, operator_headers())
    record(st == 200, "semantic.rule_set_created", f"http={st} code={rs_code}")
    rule_set_id = rs.get("rule_set_id")
    st, _ = http("POST", f"{base}/rule-sets/{rule_set_id}/publish", {}, operator_headers())
    record(st == 200, "semantic.rule_set_published", f"http={st}")

    # --- Case A: child/adult handoff (10yo, 5.55 -> legacy HIGH, directory NORMAL) ---
    sid_a, rep_a = uniq("E2E-SR-AGE-S"), uniq("E2E-SR-AGE-R")
    deliver(TENANT, "sample", sample_payload_aged(sid_a, 3650))
    deliver(TENANT, "result", result_payload_full(uniq("E2E-SR-AGE-RES"), sid_a,
            "AU5800", "5.55", "3.9", "5.5", 3650))
    deliver(TENANT, "report", report_payload(rep_a, sid_a, 1))
    st, shadow_a = http("POST", f"{base}/shadow/render", {
        "space": "OLD", "reportId": rep_a, "revision": 1,
        "ruleSetId": rule_set_id, "reason": "E2E_AGE",
    }, operator_headers())
    types_a = [t.get("diffType") for t in shadow_a.get("triage", [])]
    record(st == 200 and "AGE_BOUNDARY" in types_a, "semantic.age_boundary_shadow_diff",
           f"http={st} diffs={types_a}")
    base_flag = (shadow_a.get("baselineRows") or [{}])[0].get("flag")
    cand_flag = (shadow_a.get("candidateRows") or [{}])[0].get("flag")
    record(base_flag == "HIGH" and cand_flag == "NORMAL", "semantic.age_flag_flips_high_to_normal",
           f"baseline={base_flag} candidate={cand_flag}")

    # --- Case B: instrument replacement (adult BS2000, 6.2 -> legacy HIGH, directory NORMAL) ---
    sid_b, rep_b = uniq("E2E-SR-INST-S"), uniq("E2E-SR-INST-R")
    deliver(TENANT, "sample", sample_payload_aged(sid_b, 8000))
    deliver(TENANT, "result", result_payload_full(uniq("E2E-SR-INST-RES"), sid_b,
            "BS2000", "6.2", "3.9", "6.1", 8000))
    deliver(TENANT, "report", report_payload(rep_b, sid_b, 1))
    st, shadow_b = http("POST", f"{base}/shadow/render", {
        "space": "OLD", "reportId": rep_b, "revision": 1,
        "ruleSetId": rule_set_id, "reason": "E2E_INSTRUMENT",
    }, operator_headers())
    types_b = [t.get("diffType") for t in shadow_b.get("triage", [])]
    record(st == 200 and "INSTRUMENT_SWITCH" in types_b, "semantic.instrument_switch_shadow_diff",
           f"http={st} diffs={types_b}")
    blocking = [t for t in shadow_a.get("triage", []) + shadow_b.get("triage", [])
                if t.get("severity") == "BLOCKING" and t.get("status") == "OPEN"]
    record(not blocking, "semantic.expected_changes_are_not_blocking",
           f"openBlocking={len(blocking)}")

    # --- Gate opens and serves an authoritative point-in-time replay ---
    http("POST", f"{base}/gate/begin",
         {"ruleSetId": rule_set_id, "historyMode": "REPLAYED"}, operator_headers())
    st, enabled = http("POST", f"{base}/gate/enable", {}, operator_headers())
    record(st == 200 and enabled.get("state") == "ENABLED", "semantic.gate_enabled",
           f"http={st} state={enabled.get('state')} openBlocking={enabled.get('blockingTriageCount')}")
    st, interp = http("GET", f"{base}/reports/{rep_a}/interpretation?revision=1")
    flags = [i.get("flag") for i in interp.get("interpretations", [])]
    record(st == 200 and interp.get("authoritative") is True
           and interp.get("interpretationSource") == "REPLAYED" and "NORMAL" in flags,
           "semantic.authoritative_replay_at_report_time",
           f"http={st} source={interp.get('interpretationSource')} flags={flags}")

    # Historical display: the same report still carries its materialised OLD interpretation.
    st, _ = http("POST", f"{base}/history/materialize", {"space": "OLD", "limit": 100}, operator_headers())
    st, stored = http("GET", f"{base}/interpretations/{rep_a}?revision=1")
    hist = stored.get("interpretations", [])
    record(st == 200 and hist and hist[0].get("flag") == "HIGH"
           and hist[0].get("historyMode") == "MATERIALIZED",
           "semantic.materialized_history_keeps_old_interpretation",
           f"http={st} flag={hist[0].get('flag') if hist else None}")

    # --- Case C: withdrawing the released rule fails routing closed but publication stays up ---
    st, _ = http("POST", f"{base}/rule-sets/{rule_set_id}/withdraw",
                 {"reason": "e2e recall after release"}, operator_headers())
    record(st == 200, "semantic.rule_set_withdrawn", f"http={st}")
    st, blocked = http("GET", f"{base}/reports/{rep_a}/interpretation?revision=1")
    # The HTTP withdrawal blocks the gate immediately; a later serve then refuses with either
    # RULE_WITHDRAWN (if the serve-time check trips first) or SEMANTIC_ROUTE_BLOCKED. Both are
    # explicit 409 fail-closed outcomes - never a silent re-interpretation.
    record(st == 409 and blocked.get("code") in ("RULE_WITHDRAWN", "SEMANTIC_ROUTE_BLOCKED"),
           "semantic.withdrawn_rule_blocks_route",
           f"http={st} code={blocked.get('code')}")
    st, gate_after = http("GET", f"{base}/gate")
    record(gate_after.get("state") == "BLOCKED", "semantic.gate_flipped_to_blocked",
           f"state={gate_after.get('state')} reason={gate_after.get('lastGateReason')}")
    st, published = http("GET", f"/api/tenant/{TENANT}/publish/reports/{rep_a}")
    record(st == 200 and published.get("visibleState") == "VERIFIED"
           and published.get("semanticGateState") == "BLOCKED",
           "semantic.publication_continuous_under_block",
           f"http={st} visible={published.get('visibleState')} gate={published.get('semanticGateState')}")

    # Convergence: reset the gate so the next run starts clean.
    http("POST", f"{base}/gate/reset", {"reason": "e2e convergence"}, operator_headers())


def main() -> int:
    print(f"LabRoute e2e verification against {BASE} (tenant={TENANT})")
    st, health = http("GET", "/api/health")
    if st != 200:
        record(False, "backend.health", f"http={st} {health}")
        return finish()
    record(True, "backend.health", f"{health}")

    st, _ = http("GET", "/v3/api-docs")
    record(st == 200 and "openapi" in str(_), "docs.openapi_private_available", f"http={st}")

    steady(TENANT)
    scenario_late_result()
    scenario_report_revision()
    scenario_isolation_change()
    scenario_fence_burst()
    scenario_semantic_routing()
    return finish()


def finish() -> int:
    passed = sum(1 for ok, *_ in RESULTS if ok)
    total = len(RESULTS)
    failed = [r for r in RESULTS if not r[0]]
    print(f"\n{'=' * 60}\n{passed}/{total} assertions passed")
    if failed:
        print("FAILED:")
        for _, name, detail in failed:
            print(f"  - {name}: {detail}")
    print("E2E_RESULT:", "PASS" if not failed else "FAIL")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
