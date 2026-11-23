<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api, useStore, stateTone, formatTime, toast } from '../store'

const store = useStore()
const routing = ref(null)
const migration = ref(null)
const watermarks = ref([])
const comparisons = ref([])
const timeline = ref([])
const busy = ref('')
const rollbackReason = ref('运维回退演练')

const tenant = computed(() => store.tenant)
const strategy = computed(() => routing.value?.strategy || '')
const phase = computed(() => routing.value?.phase || '')
const fence = computed(() => routing.value?.fence)

async function load() {
  if (!tenant.value) return
  const code = tenant.value
  try {
    const [r, m, w, c, t] = await Promise.all([
      api.get(`/api/tenants/${code}/routing`),
      api.get(`/api/tenants/${code}/migration`),
      api.get(`/api/tenants/${code}/watermarks`),
      api.get(`/api/tenants/${code}/migration/shadow-comparisons`),
      api.get(`/api/tenants/${code}/migration/fence-timeline`)
    ])
    routing.value = r
    migration.value = m
    watermarks.value = w.watermarks || []
    comparisons.value = c.comparisons || []
    timeline.value = Array.isArray(t) ? t : []
  } catch (e) {
    toast(`加载迁移状态失败：${e.message}`, 'bad')
  }
}

watch(tenant, load)
onMounted(load)

async function act(label, path, body) {
  busy.value = label
  try {
    const result = await api.post(path, body || {})
    toast(`${label} 完成`, 'ok')
    await load()
    return result
  } catch (e) {
    const details = e.body?.details ? ` · ${JSON.stringify(e.body.details)}` : ''
    toast(`${label} 失败：${e.message}${details}`, 'bad')
  } finally {
    busy.value = ''
  }
}

const start = () => act('启动迁移', `/api/tenants/${tenant.value}/migration/start`, { strategy: routing.value?.strategy })
const openFence = () => act('开启切换围栏', `/api/tenants/${tenant.value}/migration/fence/open`)
const drain = () => act('按序排空围栏', `/api/tenants/${tenant.value}/migration/fence/drain`)
const release = () => act('一次性放行闸门', `/api/tenants/${tenant.value}/migration/fence/release`)
const dualWrite = () => act('开启双写', `/api/tenants/${tenant.value}/migration/online/dual-write`)
const shadow = () => act('影子比对', `/api/tenants/${tenant.value}/migration/online/shadow-check`)
const switchRead = () => act('读切换到新空间', `/api/tenants/${tenant.value}/migration/online/switch-read`)
const switchWrite = () => act('写切换到新空间', `/api/tenants/${tenant.value}/migration/online/switch-write`)
const replicate = () => act('增量复制', `/api/tenants/${tenant.value}/migration/replicate`)
const rollback = () => act('回退', `/api/tenants/${tenant.value}/migration/rollback`, { reason: rollbackReason.value })

const maxLag = computed(() => Math.max(0, ...watermarks.value.map((w) => Number(w.lag || 0))))
const latestComparison = computed(() => comparisons.value[0])
</script>

<template>
  <div class="topbar">
    <div>
      <h2>迁移控制 <span class="sub">{{ tenant }}</span></h2>
      <div class="sub">切换点前后由旧/新空间服务；围栏内消息按序排空后放行，读/写路由恒为单一显式取值</div>
    </div>
    <div class="spacer"></div>
    <button class="small" @click="load">刷新</button>
  </div>

  <div v-if="routing" class="panel">
    <h3>路由闸门</h3>
    <div class="route-bar">
      <div class="route-chip">策略<b>{{ strategy }}</b></div>
      <div class="route-chip">阶段<b><span class="tag" :class="stateTone(phase)">{{ phase }}</span></b></div>
      <div class="route-chip">读空间<b class="space-pill space-{{ routing.readSpace }}">{{ routing.readSpace }}</b></div>
      <div class="route-chip">写空间<b class="space-pill space-{{ routing.writeSpace }}">{{ routing.writeSpace }}</b></div>
      <div class="route-chip">代际<b>{{ routing.generation }}</b></div>
      <div class="route-chip">最大水位滞后<b>{{ maxLag }}</b></div>
    </div>
  </div>

  <div class="grid cols-2">
    <div class="panel">
      <h3>路线一 · 接入缓冲 + 一次性路由闸门 <span class="hint">FENCE_REPLAY</span></h3>
      <p class="small-muted" style="margin-top: 0">
        围栏开启后写入进入租户级有序缓冲账，仍即时落库并回执；按序排空到新空间后一次性放行；
        围栏内首次出现的报告显式显示待验证。
      </p>
      <div class="row">
        <button class="primary" :disabled="busy || strategy !== 'FENCE_REPLAY' || phase !== 'STEADY_OLD'" @click="start">
          启动迁移
        </button>
        <button :disabled="busy || strategy !== 'FENCE_REPLAY' || phase !== 'STEADY_OLD'" @click="openFence">开启围栏</button>
        <button :disabled="busy || strategy !== 'FENCE_REPLAY' || !['FENCED', 'DRAINING'].includes(phase)" @click="drain">排空缓冲</button>
        <button class="primary" :disabled="busy || strategy !== 'FENCE_REPLAY' || !['FENCED', 'DRAINING'].includes(phase)" @click="release">
          放行闸门 → NEW
        </button>
      </div>
      <div v-if="fence" class="stat" style="margin-top: 14px">
        <div class="label">围栏 {{ fence.state }} · 高水位 seq {{ fence.highSeq ?? '—' }}</div>
        <div class="row" style="margin-top: 8px; gap: 18px">
          <span>已缓冲 <b>{{ fence.counts?.buffered ?? 0 }}</b></span>
          <span>已应用 <b>{{ fence.counts?.applied ?? 0 }}</b></span>
          <span>失败 <b>{{ fence.counts?.failed ?? 0 }}</b></span>
          <span class="small-muted">开启于 {{ formatTime(fence.openedAt) }}</span>
        </div>
        <div class="bar" style="margin-top: 10px">
          <span :style="{ width: (fence.counts?.total ? (100 * (fence.counts.applied || 0)) / fence.counts.total : 0) + '%' }"></span>
        </div>
      </div>
      <div v-else class="empty">当前没有打开的围栏</div>
    </div>

    <div class="panel">
      <h3>路线二 · 双写 + 水位增量复制 + 分阶段路由 <span class="hint">WATERMARK_ONLINE</span></h3>
      <p class="small-muted" style="margin-top: 0">
        接入同时幂等写入旧/新空间并记录受控日志；影子比对通过且水位追平后先切读、后切写；
        任一步失败可回退，回退范围与水位快照进入证据账。
      </p>
      <div class="row">
        <button class="primary" :disabled="busy || strategy !== 'WATERMARK_ONLINE' || phase !== 'STEADY_OLD'" @click="start">
          启动迁移
        </button>
        <button :disabled="busy || strategy !== 'WATERMARK_ONLINE' || phase !== 'STEADY_OLD'" @click="dualWrite">开启双写</button>
        <button :disabled="busy || strategy !== 'WATERMARK_ONLINE' || phase !== 'DUAL_WRITE'" @click="shadow">影子比对</button>
        <button :disabled="busy || strategy !== 'WATERMARK_ONLINE' || phase !== 'SHADOW_CHECK'" @click="switchRead">读切换</button>
        <button :disabled="busy || strategy !== 'WATERMARK_ONLINE' || phase !== 'READ_NEW'" @click="switchWrite">写切换</button>
        <button :disabled="busy || !['DUAL_WRITE', 'SHADOW_CHECK', 'READ_NEW'].includes(phase)" @click="replicate">增量复制</button>
      </div>
    </div>

    <div class="panel">
      <h3>复制水位账 <span class="hint">表 / 对象 / 消息确认位置</span></h3>
      <div class="table-wrap">
        <table>
          <thead><tr><th>流</th><th>源位置</th><th>目标位置</th><th>已校验</th><th>滞后</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="w in watermarks" :key="w.streamType + w.streamName">
              <td class="mono">{{ w.streamType }}:{{ w.streamName }}</td>
              <td>{{ w.sourcePosition }}</td>
              <td>{{ w.targetPosition }}</td>
              <td>{{ w.verifiedPosition ?? '—' }}</td>
              <td>{{ w.lag }}</td>
              <td><span class="tag" :class="stateTone(w.state)">{{ w.state }}</span></td>
            </tr>
            <tr v-if="!watermarks.length"><td colspan="6" class="empty">尚无水位记录，先启动迁移</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="panel">
      <h3>影子比对 <span class="hint">旧空间每一行必须在新空间存在且一致</span></h3>
      <div v-if="latestComparison" class="kv" style="margin-bottom: 12px">
        <div class="k">最近结果</div>
        <div><span class="tag" :class="stateTone(latestComparison.result)">{{ latestComparison.result }}</span>
          <span class="small-muted"> · 模式 {{ latestComparison.mode }} · 比对 {{ latestComparison.comparedRows }} 行 · 不一致 {{ latestComparison.mismatchedRows }}</span>
        </div>
      </div>
      <pre v-if="latestComparison" class="json">{{ JSON.stringify(latestComparison.details, null, 2) }}</pre>
      <div v-else class="empty">尚无比对记录</div>
    </div>

    <div class="panel">
      <h3>围栏排空时间线 <span class="hint">按租户 seq 排序</span></h3>
      <div class="table-wrap">
        <table>
          <thead><tr><th>seq</th><th>类型</th><th>自然键</th><th>状态</th><th>应用空间</th></tr></thead>
          <tbody>
            <tr v-for="row in timeline" :key="row.seq + row.natural_key">
              <td class="mono">{{ row.seq }}</td>
              <td>{{ row.message_type }}</td>
              <td class="mono">{{ row.natural_key }}</td>
              <td><span class="tag" :class="stateTone(row.status)">{{ row.status }}</span></td>
              <td><span class="space-pill space-{{ row.applied_space || 'NONE' }}">{{ row.applied_space || '—' }}</span></td>
            </tr>
            <tr v-if="!timeline.length"><td colspan="5" class="empty">尚无围栏缓冲记录</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="panel">
      <h3>回退 <span class="hint">回退后读空间立即回到 OLD，水位快照与影响报告进入证据账</span></h3>
      <div class="field">
        <label>回退原因</label>
        <input v-model="rollbackReason" />
      </div>
      <button class="danger" :disabled="busy" @click="rollback">执行回退</button>
    </div>

    <div class="panel">
      <h3>迁移历史</h3>
      <div class="table-wrap">
        <table>
          <thead><tr><th>策略</th><th>状态</th><th>开始</th><th>完成</th><th>回退</th></tr></thead>
          <tbody>
            <tr v-for="m in migration?.migrations || []" :key="m.migrationId">
              <td>{{ m.strategy }}</td>
              <td><span class="tag" :class="stateTone(m.state)">{{ m.state }}</span></td>
              <td>{{ formatTime(m.startedAt) }}</td>
              <td>{{ formatTime(m.completedAt) }}</td>
              <td>{{ formatTime(m.rolledBackAt) }}</td>
            </tr>
            <tr v-if="!migration?.migrations?.length"><td colspan="5" class="empty">尚无迁移记录</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>
