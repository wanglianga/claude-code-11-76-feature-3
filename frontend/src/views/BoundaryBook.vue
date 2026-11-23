<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api, useStore, stateTone, formatTime, toast } from '../store'

const store = useStore()
const entries = ref([])
const findings = ref([])
const policyEvents = ref([])
const level = ref('STRICT')
const reason = ref('监管要求提升隔离级别')
const sampleId = ref('')
const chain = ref(null)
const crossA = ref('')
const crossB = ref('')
const isolation = ref(null)

const tenant = computed(() => store.tenant)

async function load() {
  if (!tenant.value) return
  try {
    const [b, f, d] = await Promise.all([
      api.get(`/api/tenants/${tenant.value}/boundary-book`),
      api.get(`/api/tenants/${tenant.value}/findings?limit=60`),
      api.get(`/api/tenants/${tenant.value}`)
    ])
    entries.value = b.entries || []
    findings.value = f.findings || []
    policyEvents.value = d.policyEvents || []
  } catch (e) {
    toast(`加载边界册失败：${e.message}`, 'bad')
  }
}

watch(tenant, load)
onMounted(async () => {
  if (!crossA.value) crossA.value = store.tenants[0]?.code || ''
  if (!crossB.value) crossB.value = store.tenants[1]?.code || ''
  await load()
})

async function changePolicy() {
  try {
    await api.post(`/api/tenants/${tenant.value}/isolation`, { level: level.value, reason: reason.value })
    toast(`隔离策略已切换到 ${level.value}`, 'ok')
    await load()
  } catch (e) {
    toast(`策略变更失败：${e.message}`, 'bad')
  }
}

async function loadChain() {
  if (!sampleId.value) return
  try {
    chain.value = await api.get(`/api/tenants/${tenant.value}/samples/${encodeURIComponent(sampleId.value)}/chain`)
  } catch (e) {
    toast(`样本链加载失败：${e.message}`, 'bad')
  }
}

async function verifyIsolation() {
  try {
    isolation.value = await api.get(`/api/isolation/verify?a=${crossA.value}&b=${crossB.value}`)
    toast(isolation.value.isolated ? '两租户物理隔离成立' : '发现跨租户键冲突', isolation.value.isolated ? 'ok' : 'bad')
  } catch (e) {
    toast(`隔离校验失败：${e.message}`, 'bad')
  }
}
</script>

<template>
  <div class="topbar">
    <div>
      <h2>租户边界册 <span class="sub">{{ tenant }}</span></h2>
      <div class="sub">样本链、来源签名、授权范围；密钥按租户严格绑定，跨租户一律拒绝</div>
    </div>
    <div class="spacer"></div>
    <button class="small" @click="load">刷新</button>
  </div>

  <div class="grid cols-2">
    <div class="panel">
      <h3>隔离策略变更 <span class="hint">变更即提升 policy_version 并收紧在途凭据</span></h3>
      <div class="row" style="margin-bottom: 10px">
        <select v-model="level" style="max-width: 180px">
          <option value="LOGICAL">LOGICAL 逻辑隔离</option>
          <option value="STRICT">STRICT 严格隔离</option>
          <option value="DEDICATED_ROLE">DEDICATED_ROLE 专属角色</option>
        </select>
      </div>
      <div class="field">
        <label>变更原因</label>
        <input v-model="reason" />
      </div>
      <button class="primary" @click="changePolicy">提交策略变更</button>
      <div class="section-title" style="margin-top: 16px">策略事件</div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>版本</th><th>从</th><th>到</th><th>原因</th><th>时间</th></tr></thead>
          <tbody>
            <tr v-for="e in policyEvents" :key="e.sequence">
              <td>v{{ e.policy_version }}</td>
              <td>{{ e.from_level }}</td>
              <td>{{ e.to_level }}</td>
              <td class="small-muted">{{ e.reason }}</td>
              <td class="small-muted">{{ formatTime(e.created_at) }}</td>
            </tr>
            <tr v-if="!policyEvents.length"><td colspan="5" class="empty">尚无策略变更</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="panel">
      <h3>跨租户隔离校验</h3>
      <div class="row" style="margin-bottom: 10px">
        <input v-model="crossA" placeholder="租户 A" style="max-width: 180px" />
        <input v-model="crossB" placeholder="租户 B" style="max-width: 180px" />
        <button @click="verifyIsolation">校验</button>
      </div>
      <div v-if="isolation">
        <div class="kv">
          <div class="k">隔离结论</div>
          <div><span class="tag" :class="isolation.isolated ? 'ok' : 'bad'">{{ isolation.isolated ? '物理隔离成立' : '存在冲突' }}</span></div>
          <div class="k">跨租户键冲突</div><div>{{ isolation.crossTenantKeyCollisions }}</div>
        </div>
        <pre class="json" style="margin-top: 10px">{{ JSON.stringify(isolation.probes, null, 2) }}</pre>
      </div>
      <div v-else class="empty">选择两个租户进行四象限空间交叉比对</div>
    </div>
  </div>

  <div class="panel">
    <h3>样本链回放 <span class="hint">按业务样本号查看当前读空间内的样本、结果与报告</span></h3>
    <div class="row" style="margin-bottom: 10px">
      <input v-model="sampleId" placeholder="样本号 sampleId" style="max-width: 320px" />
      <button @click="loadChain">加载</button>
    </div>
    <div v-if="chain">
      <div class="small-muted" style="margin-bottom: 8px">空间 {{ chain.space }} · schema {{ chain.schema }}</div>
      <pre class="json">{{ JSON.stringify(chain, null, 2) }}</pre>
    </div>
  </div>

  <div class="panel">
    <h3>边界册条目 <span class="hint">样本链版本、来源签名、授权范围</span></h3>
    <div class="table-wrap">
      <table>
        <thead><tr><th>样本</th><th>链版本</th><th>来源</th><th>key</th><th>授权</th><th>seq</th><th>空间</th><th>签名</th></tr></thead>
        <tbody>
          <tr v-for="e in entries" :key="e.entryId">
            <td class="mono">{{ e.sampleId }}</td>
            <td>{{ e.chainVersion }}</td>
            <td>{{ e.sourceCode }}</td>
            <td class="mono small-muted">{{ e.keyId }}</td>
            <td class="mono small-muted">{{ e.scope }}</td>
            <td>{{ e.messageSeq }}</td>
            <td><span class="space-pill space-{{ e.spaceRole || 'NONE' }}">{{ e.spaceRole || '—' }}</span></td>
            <td class="mono small-muted" :title="e.signature">{{ (e.signature || '').slice(0, 12) }}…</td>
          </tr>
          <tr v-if="!entries.length"><td colspan="8" class="empty">尚无边界册条目</td></tr>
        </tbody>
      </table>
    </div>
  </div>

  <div class="panel">
    <h3>语义校验发现 <span class="hint">主键 / 单位 / 参考区间 / 修订关系</span></h3>
    <div class="table-wrap">
      <table>
        <thead><tr><th>规则</th><th>严重度</th><th>样本</th><th>结果</th><th>报告</th><th>说明</th><th>时间</th></tr></thead>
        <tbody>
          <tr v-for="f in findings" :key="f.findingId">
            <td class="mono">{{ f.ruleCode }}</td>
            <td><span class="tag" :class="f.severity === 'ERROR' ? 'bad' : f.severity === 'WARN' ? 'warn' : 'muted'">{{ f.severity }}</span></td>
            <td class="mono small-muted">{{ f.sampleId || '—' }}</td>
            <td class="mono small-muted">{{ f.resultId || '—' }}</td>
            <td class="mono small-muted">{{ f.reportId || '—' }}</td>
            <td class="small-muted">{{ f.message }}</td>
            <td class="small-muted">{{ formatTime(f.detectedAt) }}</td>
          </tr>
          <tr v-if="!findings.length"><td colspan="7" class="empty">暂无校验发现</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
