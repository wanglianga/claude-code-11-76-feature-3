# LabRoute · 多租户检验数据搬迁与路由控制平台

某连锁检验机构把多租户检验数据搬迁到独立租户空间。**样本接收网关与报告发布接口在整个迁移期间持续可用**：

- 网关永不关闭写入：每条样本 / 仪器结果 / 报告修订在返回回执前已持久化到 `lab.intake_message`。
- 发布接口永不返回“迁移中、请稍候”：它要么返回**已验证旧空间版本**、要么返回**已验证新空间版本**、要么返回**显式 `PENDING_VERIFICATION`**；三者都带上来源空间与原因，绝不混读、静默降级或停服。
- 每个租户拥有两套**物理隔离**的 PostgreSQL schema（`sp_<tenant>_old` / `sp_<tenant>_new`），读空间 / 写空间在任何时刻都是**单一显式取值**，不存在“先读旧再读新”的混读路径。

平台按租户选择两条迁移路线，均完整实现：

| 路线 | 关键机制 | 用户可见效果 |
| --- | --- | --- |
| **FENCE_REPLAY** | 接入缓冲账 + 一次性路由闸门 | 切换点前后分别由旧 / 新空间服务；围栏内消息按租户 `seq` 有序排空后放行；围栏内首次出现的报告短暂显示 `PENDING_VERIFICATION` |
| **WATERMARK_ONLINE** | 双写协调器 + 复制水位账 + 影子比对 + 分阶段读写路由 | 接入同时写旧/新空间（幂等）；读侧按已校验水位先切、写侧后切；更少看到待验证，但严格处理双写幂等、复制滞后与回退范围 |

两条路线都满足硬约束：**输入不丢失、已发布报告可回放、不跨租户泄露**。冻结切换 = 冻结写入排序，**不等于停止接收或停止发布**。

---

## 1. 架构总览

```
浏览器 (Vue 3 控制台, nginx)
   │  /api/**  ──反代──▶ Spring Boot (LabRoute)
   ▼
控制面（lab.* schema）                         数据面（每租户两套物理 schema）
├─ tenant / tenant_space / tenant_policy_event  ├─ sp_<t>_old.sample / instrument_result
├─ source_credential（密钥绑租户）              │                 / report_revision / object_store
├─ boundary_book_entry（样本链+签名+授权）      ├─ sp_<t>_new.sample / instrument_result
├─ intake_counter / intake_message（回执源）    │                 / report_revision / object_store
├─ intake_buffer（围栏内有序缓冲）              └─ applied_message（按 message_id 幂等去重）
├─ fence / migration（状态机与闸门）
├─ watermark（表/对象/消息确认位置）
├─ shadow_comparison（空间影子比对）
├─ semantic_finding（语义校验发现）
├─ template_catalog / reference_rule_set        统一语义路由（迁移后单一解释目录）
│   / reference_interval_rule（模板+区间规则版本） ├─ report_interpretation（物化/回放的历史解释）
├─ report_interpretation / shadow_render        ├─ shadow_render（基线 vs 候选渲染与哈希）
│   / shadow_triage（影子渲染+差异分诊）         └─ template_route_gate / template_gate_event（启用闸门）
├─ report_publication（每版报告来源空间+可见状态）
├─ evidence_log（哈希链证据账）
└─ rollback_record（回退范围与水位快照）
```

关键不变量（代码位置）：

- **路由唯一**：读 / 写空间只在 `MigrationService.transition()` 一处翻转（`backend/.../service/MigrationService.java`），接入路径读取快照后只写一个空间。
- **接入持久化优先**：`IntakeService.intake()` 先落 `intake_message` 再应用 / 缓冲；语义拒收的消息同样落库（HTTP 422 仍带回执）。
- **发布显式化**：`PublicationService.publish()` 只从 `tenant.read_space` 单一 schema 取最新修订；围栏缓冲 / 校验未通过 / 仅存在于另一空间时返回 `202 + PENDING_VERIFICATION`。
- **证据链**：`EvidenceService` 以 `sha256(prev_hash + 内容)` 串联，`verifyChain()` 可检出篡改或被删条目。
- **语义闸门唯一翻转**：目录解释是否可服务只在 `TemplateRouteGate` 翻转（SHADOW→GATED→ENABLED/BLOCKED）；撤回生效规则集时服务端 fail-closed，发布接口仍连续可用。

---

## 1.1 统一语义路由（模板语义目录 · 区间求值 · 影子渲染 · 差异分诊 · 启用闸门）

迁移后各租户的报告模板与参考区间统一路由，但**同一分析物可因仪器、患者年龄和生效日期不同而有不同解释**。平台用五个协作组件实现，并完整保留两种历史策略：

| 组件 | 职责 | 代码 |
| --- | --- | --- |
| 模板语义目录 | 模板与规则集的版本化目录，生命周期 `DRAFT→PUBLISHED→WITHDRAWN`，均带生效时间窗；撤回只关闭窗口、不删行，历史报告仍可按时点解析 | `TemplateCatalogService` |
| 参考区间求值器 | 按 分析物 + 仪器 + 年龄带 + 生效日期 解析唯一规则，产出 `LOW/NORMAL/HIGH`；仪器精确匹配优先于通配、窄年龄带优先于通配 | `ReferenceIntervalEvaluator` |
| 影子渲染器 | 对同一报告分别渲染**基线**（结果携带的旧区间）与**候选**（目录按报告时点求值），哈希比对；并负责历史物化/回放 | `ShadowRenderer` |
| 差异分诊器 | 把差异归类为 `AGE_BOUNDARY`/`INSTRUMENT_SWITCH`/`RULE_WITHDRAWN`/`INTERVAL_DRIFT`/`FLAG_CHANGE`/`UNIT_MISMATCH`/`NO_RULE`/`RENDER_ONLY`，定级 `BENIGN/EXPECTED/ACTION_REQUIRED/BLOCKING` | `ShadowDiffTriage` |
| 启用闸门 | 每租户 `SHADOW→GATED→ENABLED/BLOCKED`；存在 OPEN 的 BLOCKING 分诊或候选/生效规则被撤回时**拒绝启用** | `TemplateRouteGate` |

两种历史解释策略（均可经 API 与闸门切换实跑）：

- **MATERIALIZED**：切换前为每份历史报告物化旧解释到 `lab.report_interpretation`，查询稳定、不依赖当前规则目录，代价是修订维护重；若闸门处于该模式却缺少物化行，路由显式报 `INTERPRETATION_NOT_MATERIALIZED`，绝不静默回退到实时规则。
- **REPLAYED**：只保留规则版本，按报告时点从生效窗重建解释，存储少；读取依赖可靠回放（生效窗 schema 保证确定性）。

fail-closed 语义：生效规则集在发布后被撤回时，下一次解释服务把闸门翻成 `BLOCKED` 并返回 409（`RULE_WITHDRAWN`/`SEMANTIC_ROUTE_BLOCKED`）；报告本身仍连续发布，响应中显式携带 `semanticGateState=BLOCKED` 与阻断原因，而不是静默换用别的规则版本。

---

## 2. 本地启动

### 2.1 前置运行时（本容器已预装）

| 运行时 | 入口 |
| --- | --- |
| JDK 21+ | `/opt/jdk-25/bin/java` |
| Maven | `/opt/maven/bin/mvn` |
| Node 22 | `/opt/node-22/bin/node`、`/opt/node-22/bin/npm` |
| PostgreSQL 18 | `127.0.0.1:5432`，库/用户/密码均为 `ccdev` |

### 2.2 启动后端（默认 8080）

```bash
cd backend
/opt/maven/bin/mvn package -DskipTests
DB_URL='jdbc:postgresql://127.0.0.1:5432/ccdev' DB_USER=ccdev DB_PASSWORD=ccdev \
  /opt/jdk-25/bin/java -jar target/labroute-1.0.0.jar
```

首次启动自动：建控制面 `lab.*` 表 → 建示例租户（`lab-harbor` / `lab-lumen` / `lab-orion`）→ 建其 old/new 物理 schema → 写入一条基线样本+结果+报告。

- 健康检查：`curl -s http://127.0.0.1:8080/api/health`
- OpenAPI 文档：`http://127.0.0.1:8080/v3/api-docs`
- **Swagger UI**：`http://127.0.0.1:8080/swagger-ui/index.html`

### 2.3 启动前端控制台（默认 5173）

```bash
cd frontend
npm install            # 若镜像不可用：npm config set registry https://registry.npmmirror.com
npm run dev            # http://127.0.0.1:5173 ，/api 反代到 8080
```

生产构建：`npm run build`（产物在 `frontend/dist`，由 nginx 托管，见 `frontend/Dockerfile`）。

### 2.4 一键 Docker Compose（Postgres + 后端 + 前端）

```bash
docker compose up --build
# 前端 http://localhost:5173     后端 http://localhost:8080     Swagger http://localhost:8080/swagger-ui/index.html
```

- `compose.yaml` 编排 `db`（postgres:18）、`backend`（多阶段构建）、`frontend`（nginx 托管 + 反代）。
- 已验证：`backend/Dockerfile`、`frontend/Dockerfile`、`frontend/nginx.conf`、`compose.yaml` 语法与构建步骤；**本执行容器内无 Docker CLI**，`docker compose up` 未在此容器实际执行（见 §6 验证结论）。

---

## 3. 测试账号 / 凭据

| 用途 | 值 |
| --- | --- |
| 控制面运维令牌 | 请求头 `X-Operator-Token: labroute-operator-demo`（或 Swagger 授权按钮） |
| 来源签名密钥 | `GET /api/demo/tenants/{tenant}/signing-material` 返回 `key_id` 与 `secret`（演示租户已预置；生产应改为受控分发） |
| 示例租户 | `lab-harbor`(FENCE_REPLAY，来源 SOURCE-A/B)、`lab-lumen`(WATERMARK_ONLINE)、`lab-orion`(FENCE_REPLAY) |

接入端点要求 5 个签名头 + 幂等键：

```
X-Source-Code, X-Key-Id, X-Nonce, X-Signed-At, X-Signature, Idempotency-Key
X-Signature = HMAC_SHA256(secret,
    "POST\n/api/intake/<kind>\n<tenant>\n<sourceCode>\n<keyId>\n<nonce>\n<signedAt>\nsha256(canonicalJson(body))"
)  # 小写 hex
```

签名窗口随租户隔离级别收紧（`LOGICAL` 900s / `STRICT` 120s）。`X-Nonce` 一次性；字节相同的重试请复用同一 `Idempotency-Key`，会拿回原回执。

---

## 4. 本题验证方式

### 4.1 端到端脚本（仅走真实 HTTP / 真实签名）

```bash
# 需先启动后端
python3 scripts/e2e_verify.py                     # BASE 默认 http://127.0.0.1:8080
BASE=http://host:8080 TENANT=lab-harbor python3 scripts/e2e_verify.py
```

脚本自签 HMAC、自管幂等键，不 import 任何 Java 代码、不直连数据库；**通过即证明网关鉴权、路由闸门、发布接口三者真实一致**。覆盖题目指定的五组样例：

1. **仪器迟到结果**：先发布报告→后到结果；验证回执可持久化、结果进入当前读空间、重发幂等、报告来源空间可命名。
2. **报告修订**：修订 2 取代 1；发布服务最新已验证版本、历史可回放、证据链完整；非法修订关系被 422 拒收但持久化。
3. **租户隔离策略变化**：`policy_version` 递增、隔离级别生效、跨租户密钥被 `AUTH_KEY_UNKNOWN` 拒绝、四象限空间无共享主键。
4. **切换围栏内三连消息**：连续三条消息按 `seq` 回执并 `BUFFERED`；围栏内发布返回 `202 PENDING_VERIFICATION`；按序排空→一次性放行→读/写切到 `NEW`；报告转为 `NEW` 上的 `VERIFIED`；证据回放 `OLD→NEW`；回退后读空间回到 `OLD` 且基线报告仍 `VERIFIED`、证据链完整。
5. **统一语义路由**：新建带儿童/成人 GLU 区间与双仪器（AU5800/BS2000）规则的规则集，
   - **儿童与成人区间交接**：10 岁患儿 5.55 mmol/L 在旧成人区间为 HIGH，目录儿童区间（3.3–5.6）求值为 NORMAL，影子渲染产出 `AGE_BOUNDARY` + `FLAG_CHANGE`；
   - **仪器更换**：成人样本在新仪器 BS2000 上 6.2，旧 AU5800 区间 HIGH、目录 BS2000 区间（4.0–6.3）NORMAL，产出 `INSTRUMENT_SWITCH`；
   - **规则发布中撤回**：闸门 ENABLED 后撤回生效规则集，解释路由 409 fail-closed、闸门翻 BLOCKED，而报告发布仍 200 VERIFIED；并核验 REPLAYED 权威回放与 MATERIALIZED 历史旧解释（HIGH）冻结。

### 4.2 后端自动化测试

```bash
cd backend && /opt/maven/bin/mvn test      # 需要本机 PostgreSQL；测试库 ccdev_test
```

- `SemanticValidatorTest`：主键 / 单位 / 参考区间 / 修订关系规则（8 项）。
- `CryptoSupportTest`：规范化 JSON、HMAC、签名串绑定、常量时间比较，以及**整数规范化不得产生科学计数法**的跨语言签名回归（5 项）。
- `ShadowDiffTriageTest`：年龄交接、仪器更换、撤回阻断、无规则/单位阻断、纯漂移、纯渲染差异分类（7 项，纯单元测试）。
- `RoutingGateIntegrationTest`：围栏路线、在线路线先切读后切写、回退恢复旧读空间、双写幂等（4 项，真实 PG）。
- `SemanticRoutingIntegrationTest`：儿童/成人交接、仪器更换、撤回 fail-closed、阻断拒绝启用、两种历史策略、`SEMANTIC_ROUTING` 全场景（5 项，真实 PG）。

### 4.3 控制台内置「验证实验室」

前端「验证实验室」页可逐个 / 一键运行上述五场景，逐条断言附带证据（`/api/scenarios/...`）。已保存的实跑证据见 `artifacts/*_run.json`（含 `semantic_routing_run.json`，12 断言）。

### 4.4 前端渲染冒烟

`scripts/smoke-dom.mjs` 用 jsdom 加载**真实生产构建产物**、`fetch` 指向真实后端，断言七个页面（含「语义路由」）挂载出正确标题并渲染真实租户数据、无运行时错误。运行前先 `cd frontend && npm run build`，再从 `frontend/` 目录执行（jsdom 由前端的 `devDependencies` 提供，产物路径可用 `DIST` 覆盖，默认 `$PWD/dist`）：

```bash
cd frontend && cp ../scripts/smoke-dom.mjs . && node smoke-dom.mjs && rm smoke-dom.mjs
```

---

## 5. 两条路线操作手册（UI 或 API）

控制面均需 `X-Operator-Token`。

### FENCE_REPLAY
1. `POST /api/tenants/{t}/migration/start {"strategy":"FENCE_REPLAY"}` → 快照式初拷到 NEW。
2. `POST /api/tenants/{t}/migration/fence/open` → `phase=FENCED`，`write=FENCE`，`read=OLD`；接入照常回执（`BUFFERED`）。
3. 需 `POST .../migration/fence/drain`（按 `seq` 排空到 NEW）。
4. `POST .../migration/fence/release` → 释放前做增量复制 + 影子比对（OLD ⊆ NEW，须 PASS）→ `phase=STEADY_NEW`，`read=write=NEW`。
5. 任一步 `POST .../migration/rollback` 可回退：读回 OLD，水位快照与影响报告写入 `rollback_record` 与证据账。

### WATERMARK_ONLINE
1. `.../migration/start {"strategy":"WATERMARK_ONLINE"}`。
2. `.../migration/online/dual-write` → `write=DUAL`，`read=OLD`（接入同时幂等写 OLD+NEW）。
3. `.../migration/online/shadow-check` → 影子比对（OLD 每行须在 NEW 存在且一致）。
4. `.../migration/online/switch-read` → 先追平水位，`read=NEW`，`write` 仍 `DUAL`。
5. `.../migration/online/switch-write` → 无未复制行且比对通过后 `write=NEW`，完成。
6. `.../migration/rollback` 回退到 OLD。

### 5.1 统一语义路由（控制面均需 `X-Operator-Token`，前缀 `/api/tenants/{t}/semantic`）

1. `POST /rule-sets`（带 `rules[]`：analyte/instrument/ageLowDays/ageHighDays/refLow/refHigh/unit）→ DRAFT 规则集；`POST /rule-sets/{id}/publish` 发布、`/withdraw` 撤回（撤回即关生效窗）。
2. `GET /gate` 查看闸门；`POST /gate/init {"historyMode":"REPLAYED|MATERIALIZED"}` 初始化为 SHADOW。
3. `POST /shadow/render {"space":"OLD","reportId":...,"ruleSetId":...}` 渲染基线 vs 候选，结果写 `shadow_render`，差异写 `shadow_triage`；`GET /triage` 查看，`POST /triage/{id}/resolve {"decision":"ACCEPT|DISMISS"}` 分诊。
4. `POST /gate/begin {"ruleSetId":...,"historyMode":...}` → GATED 钉住候选版本；`POST /gate/enable` 仅在无 OPEN BLOCKING 分诊且候选仍 PUBLISHED 时放行，否则 409 `ROUTE_GATE_BLOCKED`。
5. 历史策略：`POST /history/materialize`（物化旧解释）或 `POST /history/replay`（按报告时点回放）；`GET /reports/{reportId}/interpretation` 取受闸门约束的解释（BLOCKED 时 409）。
6. 撤回生效规则集：`POST /rule-sets/{activeId}/withdraw` → 闸门立即/在下次服务时 fail-closed 到 BLOCKED；`POST /gate/reset` 可回到 SHADOW 重新评估新版本。

---

## 6. 验证结论（仅记录实际执行过的结果）

以下为在本容器内、对 `127.0.0.1:8080` 的**真实运行**结果：

- ✅ **后端测试**：`mvn test` → `Tests run: 29, Failures: 0, Errors: 0`（SemanticValidator 8 / CryptoSupport 5 / ShadowDiffTriage 7 / RoutingGate 集成 4 / SemanticRouting 集成 5）。
- ✅ **端到端脚本**：`python3 scripts/e2e_verify.py` → `47/47 assertions passed`，`E2E_RESULT: PASS`；在**清空数据库后冷启动**再跑仍 `47/47 PASS`，且可重复运行（回退、策略恢复与场景首尾清理保证收敛）。
- ✅ **语义路由三样例**：儿童/成人交接产出 `AGE_BOUNDARY`（HIGH→NORMAL）、仪器更换产出 `INSTRUMENT_SWITCH`、规则撤回后解释路由 409 且闸门 `BLOCKED` 而报告仍 200 `VERIFIED`；REPLAYED 权威回放为 NORMAL、MATERIALIZED 历史冻结为 HIGH。
- ✅ **验证实验室**：五场景 `run-all` 全部 `PASSED`（5/7/6/12/12 断言）；`artifacts/semantic_routing_run.json` 为新场景 12 断言实跑证据。
- ✅ **Swagger/OpenAPI**：`/swagger-ui/index.html` 返回 200；`/v3/api-docs` 为 `openapi 3.1.0`，71 条路径。
- ✅ **前端渲染冒烟**：`SMOKE_RESULT: PASS`，七页（含「语义路由」）标题正确、渲染真实数据、无运行时错误。
- ⚠️ **未执行**：`docker compose up`（本执行容器无 Docker CLI）。Dockerfile / Compose / nginx 配置已按多阶段构建与反代写好，构建步骤为本地等价命令所验证的同一流程。

> 迁移期间的连续性由两处保证：网关在 `FENCE`/`DUAL`/`NEW` 任一写空间下都先持久化再回执；发布接口在任一阶段都返回“已验证版本 + 来源空间”或“显式待验证 + 原因”。语义路由额外保证：目录解释仅在闸门 ENABLED 时权威，撤回即 fail-closed，但报告发布永不因语义阻断而停服。冻结的是**写入排序**，不是接收或发布。

---

## 7. 目录结构

```
backend/                 Spring Boot 3.5 / Java 21
  src/main/java/com/labroute/
    db/SchemaService     控制面 + 每租户物理 schema DDL、自愈式建空间/补列
    service/             Auth, Intake, SpaceWriter, Migration, Replication,
                         Publication, SemanticValidator, Evidence, Policy,
                         TemplateCatalog, ReferenceIntervalEvaluator, ShadowRenderer,
                         ShadowDiffTriage, TemplateRouteGate, SemanticRouting,
                         Scenario, DemoData, Crypto
    web/                 控制器（含 SemanticRoutingController）+ 异常映射 + 运维令牌
  src/test/              单元 + 集成测试（29 项）
  Dockerfile
frontend/                Vue 3 + Vite 控制台（七视图，含语义路由）
  Dockerfile, nginx.conf
scripts/
  e2e_verify.py          独立端到端验证（真实 HTTP + HMAC，47 断言）
  smoke-dom.mjs          jsdom 渲染冒烟（真实构建产物）
  screenshot.mjs         截图脚本（需 Chromium，本容器缺系统库未执行）
compose.yaml             Postgres + 后端 + 前端
artifacts/               本次实跑的场景 JSON 证据（含 semantic_routing_run.json）
```
