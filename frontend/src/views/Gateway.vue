<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api, useStore, stateTone, formatTime, toast } from '../store'

const store = useStore()
const messages = ref([])
const stats = ref({})
const routing = ref(null)
const busy = ref(false)
const kind = ref('sample')
const sampleId = ref('')
const reportId = ref('')
const customId = ref('')
const selected = ref(null)

const tenant = computed(() => store.tenant)

async function load() {
  if (!tenant.value) return
  try {
    const [m, r] = await Promise.all([
      api.get(`/api/tenants/${tenant.value}/intake/messages?limit=60`),
      api.get(`/api/tenants/${tenant.value}/routing`)
    ])
    messages.value = m.messages || []
    stats.value = m.stats || {}
    routing.value = r
  } catch (e) {
    toast(`加载接入消息失败：${e.message}`, 'bad')
  }
}

watch(tenant, load)
onMounted(load)

async function inject() {
  busy.value = true
  try {
    const body = {}
    if (customId.value) body.id = customId.value
    if (kind.value === 'result') {
      if (!sampleId.value) throw new Error('请填写样本号')
      body.sampleId = sampleId.value
      body.analyteCode = 'GLU'
      body.value = '5.2'
      body.unit = 'mmol/L'
      body.refLow = '3.9'
      body.refHigh = '5.5'
    }
    if (kind.value === 'report') {
      if (!sampleId.value) throw new Error('请填写样本号')
      body.sampleId = sampleId.value
      body.revision = 1
    }
    if (kind.value === 'object') {
      if (!reportId.value) throw new Error('请填写报告号')
      body.reportId = reportId.value
    }
    const receipt = await api.post(`/api/demo/tenants/${tenant.value}/messages/${kind.value}`, body)
    toast(`回执 ${receipt.status} · seq ${receipt.seq} · 空间 ${receipt.appliedSpace || '缓冲中'}`, 'ok')
    await load()
  } catch (e) {
    toast(`投递失败：${e.message}`, 'bad')
  } finally {
    busy.value = false
  }
}

async function inspect(row) {
  selected.value = await api.get(`/api/tenants/${tenant.value}/intake/messages/${row.messageId}`)
}

const findings = (row) => {
  if (!row.findings) return []
  return Array.isArray(row.findings) ? row.findings : []
}
</script>

<template>
  <div class="topbar">
    <div>
      <h2>样本接收网关 <span class="sub">{{ tenant }}</span></h2>
      <div class="sub">整个迁移期间持续可用；回执即持久化。每条消息带来源 HMAC 签名与幂等键</div>
    </div>
    <div class="spacer"></div>
    <button class="small" @click="load">刷新</button>
  </div>

  <div class="grid cols-4" style="margin-bottom: 16px">
    <div class="stat"><div class="label">接入消息</div><div class="value">{{ stats.total ?? 0 }}</div></div>
    <div class="stat"><div class="label">围栏缓冲中</div><div class="value" style="color: var(--warn)">{{ stats.buffered ?? 0 }}</div></div>
    <div class="stat"><div class="label">语义标注</div><div class="value" style="color: var(--warn)">{{ stats.flagged ?? 0 }}</div></div>
    <div class="stat"><div class="label">语义拒收（已持久化）</div><div class="value" style="color: var(--bad)">{{ stats.rejected ?? 0 }}</div></div>
  </div>

  <div v-if="routing" class="panel">
    <h3>当前写入路由</h3>
    <div class="route-bar">
      <div class="route-chip">阶段<b><span class="tag" :class="stateTone(routing.phase)">{{ routing.phase }}</span></b></div>
      <div class="route-chip">写空间<b class="space-pill space-{{ routing.writeSpace }}">{{ routing.writeSpace }}</b></div>
      <div class="route-chip">读空间<b class="space-pill space-{{ routing.readSpace }}">{{ routing.readSpace }}</b></div>
    </div>
  </div>

  <div class="grid cols-2">
    <div class="panel">
      <h3>演示投递 <span class="hint">经真实签名网关路径注入，不绕过鉴权</span></h3>
      <div class="row" style="margin-bottom: 10px">
        <select v-model="kind" style="max-width: 160px">
          <option value="sample">样本 SAMPLE</option>
          <option value="result">仪器结果 RESULT</option>
          <option value="report">报告修订 REPORT</option>
          <option value="object">对象 OBJECT</option>
        </select>
      </div>
      <div v-if="kind === 'result' || kind === 'report'" class="field">
        <label>样本号 sampleId</label>
        <input v-model="sampleId" placeholder="例如 LAB_HARBOR-S0001" />
      </div>
      <div v-if="kind === 'object'" class="field">
        <label>报告号 reportId</label>
        <input v-model="reportId" placeholder="例如 LAB_HARBOR-S0001-REP" />
      </div>
      <div class="field">
        <label>自定义业务主键（可选）</label>
        <input v-model="customId" placeholder="留空自动生成" />
      </div>
      <button class="primary" :disabled="busy" @click="inject">签署并投递</button>
    </div>

    <div class="panel">
      <h3>消息详情 <span class="hint">签名、幂等键、校验发现</span></h3>
      <div v-if="selected">
        <div class="kv" style="margin-bottom: 12px">
          <div class="k">消息 ID</div><div class="mono">{{ selected.messageId }}</div>
          <div class="k">seq</div><div>{{ selected.seq }}</div>
          <div class="k">类型</div><div>{{ selected.messageType }}</div>
          <div class="k">自然键</div><div class="mono">{{ selected.naturalKey }}</div>
          <div class="k">状态</div>
          <div><span class="tag" :class="stateTone(selected.status)">{{ selected.status }}</span></div>
          <div class="k">校验</div>
          <div><span class="tag" :class="stateTone(selected.validationStatus)">{{ selected.validationStatus }}</span></div>
          <div class="k">来源 / key</div><div class="mono">{{ selected.sourceCode }} / {{ selected.keyId }}</div>
          <div class="k">幂等键</div><div class="mono">{{ selected.idempotencyKey }}</div>
          <div class="k">payload hash</div><div class="mono" style="word-break: break-all">{{ selected.payloadHash }}</div>
        </div>
        <div class="section-title">Payload</div>
        <pre class="json">{{ JSON.stringify(selected.payload, null, 2) }}</pre>
      </div>
      <div v-else class="empty">在上方列表点击一条消息查看详情</div>
    </div>
  </div>

  <div class="panel">
    <h3>接入缓冲账 <span class="hint">按租户 seq 单调，围栏内按序排空</span></h3>
    <div class="table-wrap">
      <table>
        <thead>
          <tr><th>seq</th><th>类型</th><th>自然键</th><th>状态</th><th>应用空间</th><th>校验</th><th>围栏</th><th>接收时间</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="row in messages" :key="row.messageId">
            <td class="mono">{{ row.seq }}</td>
            <td>{{ row.messageType }}</td>
            <td class="mono">{{ row.naturalKey }}</td>
            <td><span class="tag" :class="stateTone(row.status)">{{ row.status }}</span></td>
            <td><span class="space-pill space-{{ row.appliedSpace || 'NONE' }}">{{ row.appliedSpace || '—' }}</span></td>
            <td><span class="tag" :class="stateTone(row.validationStatus)">{{ row.validationStatus }}</span></td>
            <td class="mono small-muted">{{ row.fenceId ? row.fenceId.slice(0, 8) : '—' }}</td>
            <td class="small-muted">{{ formatTime(row.receivedAt) }}</td>
            <td><button class="small" @click="inspect(row)">详情</button></td>
          </tr>
          <tr v-if="!messages.length"><td colspan="9" class="empty">暂无接入消息</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
