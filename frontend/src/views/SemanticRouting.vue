<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api, useStore, stateTone, formatTime, toast } from '../store'

const store = useStore()
const tenant = computed(() => store.tenant)

const gate = ref(null)
const ruleSets = ref([])
const templates = ref([])
const triage = ref([])
const triageCounts = ref({})
const renders = ref([])
const interpretation = ref(null)
const lastShadow = ref(null)
const busy = ref('')

const ruleSetCode = ref('RS-NEW')
const rulesJson = ref(JSON.stringify([
  { analyteCode: 'GLU', instrumentCode: 'AU5800', ageLowDays: 0, ageHighDays: 17 * 365, refLow: '3.3', refHigh: '5.6', unit: 'mmol/L', flagLabel: 'PEDIATRIC', priority: 10 },
  { analyteCode: 'GLU', instrumentCode: 'AU5800', ageLowDays: 18 * 365, ageHighDays: null, refLow: '3.9', refHigh: '6.1', unit: 'mmol/L', flagLabel: 'ADULT', priority: 20 }
], null, 2))
const beginRuleSetId = ref('')
const historyMode = ref('REPLAYED')
const shadowForm = ref({ space: 'OLD', reportId: '', revision: '', ruleSetId: '' })
const interpReportId = ref('')

async function load() {
  if (!tenant.value) return
  const code = tenant.value
  try {
    const base = `/api/tenants/${code}/semantic`
    const [g, rs, tpl, tr] = await Promise.all([
      api.get(`${base}/gate`),
      api.get(`${base}/rule-sets`),
      api.get(`${base}/templates`),
      api.get(`${base}/triage`)
    ])
    gate.value = g
    ruleSets.value = rs.ruleSets || []
    templates.value = tpl.templates || []
    triage.value = tr.findings || []
    triageCounts.value = tr.openCounts || {}
    beginRuleSetId.value = beginRuleSetId.value || g.candidateRuleSetId || ''
    if (beginRuleSetId.value === 'null') beginRuleSetId.value = ''
  } catch (e) {
    toast(`加载语义路由失败：${e.message}`, 'bad')
  }
}

watch(tenant, () => { gate.value = null; load() })
onMounted(load)

async function act(label, path, body, reload = true) {
  busy.value = label
  try {
    const result = await api.post(path, body || {})
    toast(`${label} 完成`, 'ok')
    if (reload) await load()
    return result
  } catch (e) {
    const details = e.body?.details ? ` · ${JSON.stringify(e.body.details).slice(0, 200)}` : ''
    toast(`${label} 失败：${e.message}${details}`, 'bad')
  } finally {
    busy.value = ''
  }
}

const base = () => `/api/tenants/${tenant.value}/semantic`

async function createRuleSet() {
  let rules
  try {
    rules = JSON.parse(rulesJson.value)
  } catch {
    toast('规则 JSON 格式错误', 'bad')
    return
  }
  await act('创建规则集', `${base()}/rule-sets`, { ruleSetCode: ruleSetCode.value, rules })
}
const publishSet = (id) => act('发布规则集', `${base()}/rule-sets/${id}/publish`, {})
const withdrawSet = (id) => act('撤回规则集', `${base()}/rule-sets/${id}/withdraw`,
  { reason: '控制台撤回：规则发布后召回' })
const initGate = () => act('初始化闸门', `${base()}/gate/init`, { historyMode: historyMode.value })
const beginGate = () => act('进入评估(GATED)', `${base()}/gate/begin`,
  { ruleSetId: beginRuleSetId.value || null, historyMode: historyMode.value })
const enableGate = () => act('启用闸门', `${base()}/gate/enable`)
const blockGate = () => act('阻断闸门', `${base()}/gate/block`, { reason: '运维手动保持有' })
const resetGate = () => act('重置为 SHADOW', `${base()}/gate/reset`, { reason: '控制台重置' })

async function renderShadow() {
  if (!shadowForm.value.reportId) return toast('请填写报告号', 'warn')
  const body = { ...shadowForm.value, revision: shadowForm.value.revision ? Number(shadowForm.value.revision) : null,
    ruleSetId: shadowForm.value.ruleSetId || null, reason: 'CONSOLE_SHADOW' }
  const r = await act('影子渲染', `${base()}/shadow/render`, body)
  if (r) {
    lastShadow.value = r
    const rd = await api.get(`${base()}/shadow/renders?reportId=${encodeURIComponent(shadowForm.value.reportId)}`)
    renders.value = rd.renders || []
  }
}
const materialize = () => act('物化历史解释', `${base()}/history/materialize`, { space: 'OLD', limit: 200 })
const replay = () => act('按报告时点回放', `${base()}/history/replay`, { space: 'OLD', limit: 200 })

async function resolveFinding(id, decision) {
  await act(decision === 'ACCEPT' ? '受理差异' : '驳回差异',
    `${base()}/triage/${id}/resolve`, { decision, note: '控制台分诊操作' })
}

async function queryInterpretation() {
  if (!interpReportId.value) return
  try {
    interpretation.value = await api.get(
      `${base()}/reports/${encodeURIComponent(interpReportId.value)}/interpretation`)
  } catch (e) {
    interpretation.value = { blocked: true, code: e.code, message: e.message, body: e.body }
  }
}

const tone = stateTone
</script>

<template>
  <div>
    <div class="topbar">
      <div>
        <h2>统一语义路由 <span class="sub">{{ tenant }}</span></h2>
        <div class="sub">模板语义目录 · 参考区间求值 · 影子渲染 · 差异分诊 · 启用闸门；同一分析物可随仪器/年龄/生效日期给出不同解释</div>
      </div>
      <div class="spacer"></div>
      <button class="small" @click="load">刷新</button>
    </div>

    <!-- Gate -->
    <div class="panel" v-if="gate">
      <h3>启用闸门
        <span class="tag" :class="tone(gate.state)">{{ gate.state }}</span>
        <span class="hint">历史策略 {{ gate.historyMode }}</span>
      </h3>
      <div class="kv" style="margin-bottom: 12px">
        <div class="k">候选规则集</div><div class="mono">{{ gate.candidateRuleSetId || '—' }}</div>
        <div class="k">生效规则集</div><div class="mono">{{ gate.activeRuleSetId || '—' }}</div>
        <div class="k">未关闭分诊</div><div>{{ gate.openTriageCount }}（阻断 {{ gate.blockingTriageCount }}）</div>
        <div class="k">最近影子结果</div><div><span class="tag" :class="tone(gate.lastShadowResult)">{{ gate.lastShadowResult || '—' }}</span></div>
        <div class="k">阻断原因</div><div>{{ gate.lastGateReason || '—' }}</div>
      </div>
      <div class="row">
        <label>历史策略
          <select v-model="historyMode">
            <option value="REPLAYED">REPLAYED（保留规则版本，按报告时点重建）</option>
            <option value="MATERIALIZED">MATERIALIZED（物化旧解释，查询稳定）</option>
          </select>
        </label>
        <label>候选规则集
          <select v-model="beginRuleSetId" style="min-width: 320px">
            <option value="">（不指定）</option>
            <option v-for="r in ruleSets" :key="r.ruleSetId" :value="r.ruleSetId">
              {{ r.ruleSetCode }} v{{ r.version }} · {{ r.status }}
            </option>
          </select>
        </label>
      </div>
      <div class="row" style="margin-top: 10px">
        <button :disabled="!!busy" @click="initGate">初始化 SHADOW</button>
        <button :disabled="!!busy" @click="beginGate">进入评估 GATED</button>
        <button class="primary" :disabled="!!busy" @click="enableGate">启用（阻断项为 0 时）</button>
        <button :disabled="!!busy" @click="blockGate">手动阻断</button>
        <button :disabled="!!busy" @click="resetGate">重置 SHADOW</button>
      </div>
    </div>

    <div class="grid cols-2">
      <!-- Rule sets -->
      <div class="panel">
        <h3>参考区间规则集（语义目录）</h3>
        <div class="table-wrap">
          <table>
            <thead><tr><th>代码</th><th>版本</th><th>状态</th><th>规则数</th><th>生效</th><th></th></tr></thead>
            <tbody>
              <tr v-for="r in ruleSets" :key="r.ruleSetId">
                <td class="mono">{{ r.ruleSetCode }}</td>
                <td>v{{ r.version }}</td>
                <td><span class="tag" :class="tone(r.status)">{{ r.status }}</span></td>
                <td>{{ r.ruleCount }}</td>
                <td class="small-muted">{{ formatTime(r.effectiveFrom) }}</td>
                <td>
                  <button v-if="r.status === 'DRAFT'" class="small" @click="publishSet(r.ruleSetId)">发布</button>
                  <button v-if="r.status !== 'WITHDRAWN'" class="small" @click="withdrawSet(r.ruleSetId)">撤回</button>
                </td>
              </tr>
              <tr v-if="!ruleSets.length"><td colspan="6" class="empty">暂无规则集</td></tr>
            </tbody>
          </table>
        </div>
        <h4 style="margin-top: 14px">新建规则集版本</h4>
        <div class="row">
          <input v-model="ruleSetCode" placeholder="ruleSetCode" style="max-width: 180px" />
          <button :disabled="!!busy" @click="createRuleSet">创建 DRAFT 规则集</button>
        </div>
        <textarea v-model="rulesJson" rows="8" class="json" style="width: 100%; font-family: monospace; font-size: 12px"></textarea>
      </div>

      <!-- Templates + history -->
      <div class="panel">
        <h3>报告模板目录</h3>
        <div class="table-wrap">
          <table>
            <thead><tr><th>代码</th><th>分析物</th><th>版本</th><th>状态</th></tr></thead>
            <tbody>
              <tr v-for="t in templates" :key="t.templateId">
                <td class="mono">{{ t.templateCode }}</td>
                <td>{{ t.analyteCode }}</td>
                <td>v{{ t.version }}</td>
                <td><span class="tag" :class="tone(t.status)">{{ t.status }}</span></td>
              </tr>
              <tr v-if="!templates.length"><td colspan="4" class="empty">暂无模板</td></tr>
            </tbody>
          </table>
        </div>
        <h4 style="margin-top: 14px">历史解释策略</h4>
        <p class="small-muted" style="margin-top: 0">
          MATERIALIZED 为每份历史报告物化旧解释（查询稳定、维护重）；REPLAYED 保留规则版本并按报告时点重建（存储少、依赖可靠回放）。
        </p>
        <div class="row">
          <button :disabled="!!busy" @click="materialize">物化旧解释（OLD）</button>
          <button :disabled="!!busy" @click="replay">按报告时点回放（OLD）</button>
        </div>
      </div>
    </div>

    <!-- Shadow render -->
    <div class="panel">
      <h3>影子渲染器</h3>
      <div class="row">
        <label>空间 <select v-model="shadowForm.space"><option>OLD</option><option>NEW</option></select></label>
        <input v-model="shadowForm.reportId" placeholder="报告号 reportId" style="min-width: 260px" />
        <input v-model="shadowForm.revision" placeholder="修订(可空=最新)" style="max-width: 130px" />
        <input v-model="shadowForm.ruleSetId" placeholder="候选规则集ID(可空=时点解析)" style="min-width: 300px" />
        <button class="primary" :disabled="!!busy" @click="renderShadow">渲染并比对</button>
      </div>

      <div v-if="lastShadow" style="margin-top: 14px">
        <div class="kv" style="margin-bottom: 10px">
          <div class="k">结果</div>
          <div><span class="tag" :class="tone(lastShadow.result)">{{ lastShadow.result }}</span></div>
          <div class="k">差异数</div><div>{{ lastShadow.diffCount }}</div>
          <div class="k">基线哈希</div><div class="mono" style="word-break: break-all">{{ lastShadow.baselineHash }}</div>
          <div class="k">候选哈希</div><div class="mono" style="word-break: break-all">{{ lastShadow.candidateHash }}</div>
        </div>
        <div class="grid cols-2">
          <div>
            <h4>基线（旧空间解释）</h4>
            <div class="table-wrap"><table>
              <thead><tr><th>结果</th><th>仪器</th><th>值</th><th>区间</th><th>标志</th></tr></thead>
              <tbody>
                <tr v-for="(r, i) in lastShadow.baselineRows" :key="'b' + i">
                  <td class="mono">{{ r.resultId }}</td><td>{{ r.instrumentCode }}</td>
                  <td>{{ r.value }} {{ r.unit }}</td><td>{{ r.refLow }}–{{ r.refHigh }}</td>
                  <td><span class="tag" :class="tone(r.flag)">{{ r.flag }}</span></td>
                </tr>
              </tbody>
            </table></div>
          </div>
          <div>
            <h4>候选（语义目录解释）</h4>
            <div class="table-wrap"><table>
              <thead><tr><th>结果</th><th>仪器</th><th>值</th><th>区间</th><th>标志/状态</th></tr></thead>
              <tbody>
                <tr v-for="(r, i) in lastShadow.candidateRows" :key="'c' + i">
                  <td class="mono">{{ r.resultId }}</td><td>{{ r.instrumentCode }}</td>
                  <td>{{ r.value }} {{ r.unit }}</td><td>{{ r.refLow }}–{{ r.refHigh }}</td>
                  <td><span class="tag" :class="tone(r.flag)">{{ r.flag }}</span>
                    <span class="tag" :class="r.status === 'OK' ? 'ok' : 'bad'">{{ r.status }}</span></td>
                </tr>
              </tbody>
            </table></div>
          </div>
        </div>
      </div>
    </div>

    <!-- Triage -->
    <div class="panel">
      <h3>差异分诊
        <span class="hint">OPEN {{ triageCounts.open || 0 }} · BLOCKING {{ triageCounts.blocking || 0 }}
          · ACTION {{ triageCounts.action || 0 }} · EXPECTED {{ triageCounts.expected || 0 }}</span>
      </h3>
      <div class="table-wrap">
        <table>
          <thead><tr><th>报告</th><th>分析物</th><th>差异类型</th><th>严重度</th><th>状态</th><th>详情</th><th></th></tr></thead>
          <tbody>
            <tr v-for="f in triage" :key="f.triageId">
              <td class="mono">{{ f.reportId }}</td>
              <td>{{ f.analyteCode }}</td>
              <td>{{ f.diffType }}</td>
              <td><span class="tag" :class="tone(f.severity)">{{ f.severity }}</span></td>
              <td><span class="tag" :class="tone(f.status)">{{ f.status }}</span></td>
              <td class="small-muted" style="max-width: 360px">{{ JSON.stringify(f.detail) }}</td>
              <td>
                <template v-if="f.status === 'OPEN'">
                  <button class="small" @click="resolveFinding(f.triageId, 'ACCEPT')">受理</button>
                  <button class="small" @click="resolveFinding(f.triageId, 'DISMISS')">驳回</button>
                </template>
                <span v-else class="small-muted">{{ f.resolutionNote || f.status }}</span>
              </td>
            </tr>
            <tr v-if="!triage.length"><td colspan="7" class="empty">暂无分诊发现</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Served interpretation -->
    <div class="panel">
      <h3>历史/当前解释查询（受闸门约束）</h3>
      <div class="row">
        <input v-model="interpReportId" placeholder="报告号 reportId" style="min-width: 280px" />
        <button class="primary" @click="queryInterpretation">查询解释</button>
      </div>
      <div v-if="interpretation" style="margin-top: 12px">
        <div v-if="interpretation.blocked" class="panel" style="border: 1px solid var(--bad, #c0392b)">
          <span class="tag bad">{{ interpretation.code }}</span>
          <span class="small-muted">{{ interpretation.message }}</span>
        </div>
        <div v-else>
          <div class="kv" style="margin-bottom: 10px">
            <div class="k">闸门状态</div><div><span class="tag" :class="tone(interpretation.gateState)">{{ interpretation.gateState }}</span></div>
            <div class="k">权威性</div><div>{{ interpretation.authoritative ? '是（目录解释）' : '否（影子，仅供比对）' }}</div>
            <div class="k">解释来源</div><div>{{ interpretation.interpretationSource }}</div>
          </div>
          <div class="table-wrap"><table>
            <thead><tr><th>结果</th><th>分析物</th><th>仪器</th><th>值</th><th>区间</th><th>标志</th><th>状态</th></tr></thead>
            <tbody>
              <tr v-for="(r, i) in interpretation.interpretations" :key="i">
                <td class="mono">{{ r.resultId }}</td><td>{{ r.analyteCode }}</td><td>{{ r.instrumentCode }}</td>
                <td>{{ r.value }} {{ r.unit }}</td><td>{{ r.refLow }}–{{ r.refHigh }}</td>
                <td><span class="tag" :class="tone(r.flag)">{{ r.flag }}</span></td>
                <td><span class="tag" :class="r.status === 'OK' ? 'ok' : 'bad'">{{ r.status }}</span></td>
              </tr>
            </tbody>
          </table></div>
        </div>
      </div>
    </div>
  </div>
</template>
