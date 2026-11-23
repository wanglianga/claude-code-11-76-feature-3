<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api, useStore, stateTone, formatTime, toast } from '../store'

const store = useStore()
const reportId = ref('')
const inventories = ref({ OLD: [], NEW: [] })
const result = ref(null)
const publications = ref([])
const replay = ref(null)
const loading = ref(false)

const tenant = computed(() => store.tenant)

async function load() {
  if (!tenant.value) return
  try {
    const [oldSpace, newSpace, pubs] = await Promise.all([
      api.get(`/api/tenant/${tenant.value}/publish/reports?space=OLD`),
      api.get(`/api/tenant/${tenant.value}/publish/reports?space=NEW`),
      api.get(`/api/tenant/${tenant.value}/publications?limit=40`)
    ])
    inventories.value = { OLD: oldSpace.reports || [], NEW: newSpace.reports || [] }
    publications.value = pubs.publications || []
    if (!reportId.value) {
      const first = inventories.value.OLD[0] || inventories.value.NEW[0]
      if (first) reportId.value = first.reportId
    }
  } catch (e) {
    toast(`加载发布清单失败：${e.message}`, 'bad')
  }
}

watch(tenant, () => { reportId.value = ''; load() })
onMounted(load)

async function publish() {
  if (!reportId.value) return
  loading.value = true
  try {
    const res = await fetch(`/api/tenant/${tenant.value}/publish/reports/${encodeURIComponent(reportId.value)}`)
    const body = await res.json()
    result.value = { http: res.status, ...body }
    if (body.visibleState === 'PENDING_VERIFICATION') {
      toast('发布接口返回显式待验证状态（HTTP ' + res.status + '）', 'warn')
    } else {
      toast(`已发布 ${body.revision} 版，来源空间 ${body.sourceSpace}`, 'ok')
    }
    await load()
  } catch (e) {
    toast(`发布失败：${e.message}`, 'bad')
  } finally {
    loading.value = false
  }
}

async function loadReplay() {
  if (!reportId.value) return
  try {
    replay.value = await api.get(`/api/tenant/${tenant.value}/publish/reports/${encodeURIComponent(reportId.value)}/replay`)
  } catch (e) {
    toast(`证据回放失败：${e.message}`, 'bad')
  }
}

function choose(id) {
  reportId.value = id
  result.value = null
  replay.value = null
}
</script>

<template>
  <div class="topbar">
    <div>
      <h2>连续发布台 <span class="sub">{{ tenant }}</span></h2>
      <div class="sub">只返回已验证旧空间版本、已验证新空间版本，或显式 PENDING_VERIFICATION；绝不混读、绝不停服</div>
    </div>
    <div class="spacer"></div>
    <button class="small" @click="load">刷新</button>
  </div>

  <div class="panel">
    <h3>发布</h3>
    <div class="row">
      <input v-model="reportId" placeholder="报告号 reportId" style="max-width: 360px" />
      <button class="primary" :disabled="loading || !reportId" @click="publish">发布报告</button>
      <button :disabled="!reportId" @click="loadReplay">证据回放</button>
    </div>
  </div>

  <div v-if="result" class="panel">
    <h3>
      发布结果
      <span class="tag" :class="stateTone(result.visibleState)">{{ result.visibleState }}</span>
      <span class="hint">HTTP {{ result.http }}</span>
    </h3>
    <div class="kv" style="margin-bottom: 12px">
      <div class="k">来源空间</div>
      <div><span class="space-pill space-{{ result.sourceSpace }}">{{ result.sourceSpace }}</span></div>
      <div class="k">修订</div><div>{{ result.revision ?? '—' }}</div>
      <div class="k">内容哈希</div><div class="mono" style="word-break: break-all">{{ result.contentHash || result.lastVerifiedContentHash || '—' }}</div>
      <div class="k" v-if="result.pendingReason">待验证原因</div><div v-if="result.pendingReason">{{ result.pendingReason }}</div>
    </div>
    <pre v-if="result.content" class="json">{{ JSON.stringify(result.content, null, 2) }}</pre>
  </div>

  <div v-if="replay" class="panel">
    <h3>证据回放 <span class="hint">{{ replay.reportId }}</span></h3>
    <div class="kv" style="margin-bottom: 12px">
      <div class="k">曾服务空间</div><div>{{ (replay.spacesServed || []).join(' → ') || '—' }}</div>
      <div class="k">当前读空间</div><div>{{ replay.currentReadSpace }}</div>
      <div class="k">证据链</div>
      <div><span class="tag" :class="replay.chainIntegrity?.valid ? 'ok' : 'bad'">{{ replay.chainIntegrity?.valid ? '完整' : '断裂' }}</span></div>
    </div>
    <div class="table-wrap">
      <table>
        <thead><tr><th>事件</th><th>来源空间</th><th>可见状态</th><th>时间</th></tr></thead>
        <tbody>
          <tr v-for="e in replay.evidence" :key="e.seq">
            <td>{{ e.eventType }}</td>
            <td><span class="space-pill space-{{ e.sourceSpace || 'NONE' }}">{{ e.sourceSpace || '—' }}</span></td>
            <td><span class="tag" :class="stateTone(e.visibleState)">{{ e.visibleState || '—' }}</span></td>
            <td class="small-muted">{{ formatTime(e.createdAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <div class="grid cols-2">
    <div class="panel">
      <h3>旧空间报告清单 <span class="hint">OLD</span></h3>
      <div class="table-wrap">
        <table>
          <thead><tr><th>报告</th><th>最新修订</th><th>修订数</th><th></th></tr></thead>
          <tbody>
            <tr v-for="r in inventories.OLD" :key="r.reportId">
              <td class="mono">{{ r.reportId }}</td>
              <td>{{ r.latestRevision }}</td>
              <td>{{ r.revisionCount }}</td>
              <td><button class="small" @click="choose(r.reportId)">选择</button></td>
            </tr>
            <tr v-if="!inventories.OLD.length"><td colspan="4" class="empty">旧空间暂无报告</td></tr>
          </tbody>
        </table>
      </div>
    </div>
    <div class="panel">
      <h3>新空间报告清单 <span class="hint">NEW</span></h3>
      <div class="table-wrap">
        <table>
          <thead><tr><th>报告</th><th>最新修订</th><th>修订数</th><th></th></tr></thead>
          <tbody>
            <tr v-for="r in inventories.NEW" :key="r.reportId">
              <td class="mono">{{ r.reportId }}</td>
              <td>{{ r.latestRevision }}</td>
              <td>{{ r.revisionCount }}</td>
              <td><button class="small" @click="choose(r.reportId)">选择</button></td>
            </tr>
            <tr v-if="!inventories.NEW.length"><td colspan="4" class="empty">新空间暂无报告</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>

  <div class="panel">
    <h3>发布记录 <span class="hint">每份报告每次服务的来源空间与可见状态</span></h3>
    <div class="table-wrap">
      <table>
        <thead><tr><th>报告</th><th>修订</th><th>来源空间</th><th>可见状态</th><th>待验证原因</th><th>更新时间</th></tr></thead>
        <tbody>
          <tr v-for="p in publications" :key="p.reportId + p.revision + p.updatedAt">
            <td class="mono">{{ p.reportId }}</td>
            <td>{{ p.revision }}</td>
            <td><span class="space-pill space-{{ p.sourceSpace }}">{{ p.sourceSpace }}</span></td>
            <td><span class="tag" :class="stateTone(p.visibleState)">{{ p.visibleState }}</span></td>
            <td class="small-muted">{{ p.pendingReason || '—' }}</td>
            <td class="small-muted">{{ formatTime(p.updatedAt) }}</td>
          </tr>
          <tr v-if="!publications.length"><td colspan="6" class="empty">尚无发布记录</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
