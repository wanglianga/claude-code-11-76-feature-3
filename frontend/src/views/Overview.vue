<script setup>
import { onMounted, ref, computed } from 'vue'
import { api, useStore, stateTone, formatTime, setTenant, toast } from '../store'

const store = useStore()
const detail = ref(null)
const loading = ref(false)

const tenant = computed(() => store.tenants.find((t) => t.code === store.tenant) || store.tenants[0])

async function load() {
  if (!store.tenant) return
  loading.value = true
  try {
    detail.value = await api.get(`/api/tenants/${store.tenant}`)
  } catch (e) {
    toast(`加载失败：${e.message}`, 'bad')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  if (!store.tenants.length) await api.loadTenants()
  if (!store.tenant && store.tenants.length) setTenant(store.tenants[0].code)
  await load()
})

function select(code) {
  setTenant(code)
  load()
}

const chain = computed(() => detail.value?.evidenceChain || {})
</script>

<template>
  <div class="topbar">
    <div>
      <h2>租户空间总览</h2>
      <div class="sub">每个租户拥有物理隔离的旧/新两套空间；读空间与写空间恒为单一显式取值</div>
    </div>
    <div class="spacer"></div>
    <button class="small" @click="load" :disabled="loading">刷新</button>
  </div>

  <div class="grid cols-3" style="margin-bottom: 16px">
    <div
      v-for="t in store.tenants"
      :key="t.code"
      class="stat"
      :style="{ cursor: 'pointer', borderColor: t.code === store.tenant ? 'rgba(77,163,255,.55)' : '' }"
      @click="select(t.code)"
    >
      <div class="label">{{ t.name }}</div>
      <div class="value" style="font-size: 16px">{{ t.code }}</div>
      <div class="row" style="margin-top: 8px; gap: 6px">
        <span class="tag muted">{{ t.strategy }}</span>
        <span class="tag" :class="stateTone(t.phase)">{{ t.phase }}</span>
      </div>
      <div class="small-muted" style="margin-top: 8px">
        读 <b>{{ t.readSpace }}</b> · 写 <b>{{ t.writeSpace }}</b> · 代际 {{ t.generation }}
      </div>
    </div>
  </div>

  <div v-if="detail" class="grid cols-2">
    <div class="panel">
      <h3>路由闸门状态 <span class="hint">{{ detail.code }}</span></h3>
      <div class="route-bar" style="margin-bottom: 14px">
        <div class="route-chip">阶段<b>{{ detail.phase }}</b></div>
        <div class="route-chip">隔离级别<b>{{ detail.isolationLevel }} · v{{ detail.policyVersion }}</b></div>
        <div class="route-chip">读空间<b class="space-pill space-{{ detail.readSpace }}">{{ detail.readSpace }}</b></div>
        <div class="route-chip">写空间<b class="space-pill space-{{ detail.writeSpace }}">{{ detail.writeSpace }}</b></div>
        <div class="route-chip">代际<b>{{ detail.generation }}</b></div>
      </div>
      <div class="kv">
        <div class="k">旧空间 schema</div><div class="mono">{{ detail.spaces.OLD }}</div>
        <div class="k">新空间 schema</div><div class="mono">{{ detail.spaces.NEW }}</div>
        <div class="k">任务策略</div><div>{{ detail.strategy }}</div>
        <div class="k">接入消息</div>
        <div>{{ detail.intakeStats.total }} 条 · 缓冲 {{ detail.intakeStats.buffered }} · 拒收 {{ detail.intakeStats.rejected }}</div>
        <div class="k">证据链</div>
        <div>
          <span class="tag" :class="chain.valid ? 'ok' : 'bad'">{{ chain.valid ? '完整' : '断裂' }}</span>
          <span class="small-muted" style="margin-left: 8px">已校验 {{ chain.checked }} 条</span>
        </div>
      </div>
    </div>

    <div class="panel">
      <h3>空间数据量</h3>
      <div class="table-wrap">
        <table>
          <thead>
            <tr><th>表</th><th>旧空间 OLD</th><th>新空间 NEW</th></tr>
          </thead>
          <tbody>
            <tr v-for="(counts, table) in detail.counts.OLD" :key="table">
              <td class="mono">{{ table }}</td>
              <td>{{ counts }}</td>
              <td>{{ detail.counts.NEW[table] }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="panel">
      <h3>来源凭据 <span class="hint">每个密钥严格绑定单一租户</span></h3>
      <div class="table-wrap">
        <table>
          <thead><tr><th>来源</th><th>Key</th><th>版本</th><th>授权范围</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="c in detail.credentials" :key="c.keyId">
              <td>{{ c.sourceCode }}</td>
              <td class="mono">{{ c.keyId }}</td>
              <td>{{ c.keyVersion }}</td>
              <td class="mono" style="max-width: 240px; word-break: break-all">{{ c.scopes }}</td>
              <td><span class="tag" :class="c.active ? 'ok' : 'bad'">{{ c.active ? 'active' : 'revoked' }}</span></td>
            </tr>
            <tr v-if="!detail.credentials.length"><td colspan="5" class="empty">暂无凭据</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="panel">
      <h3>最近发布记录</h3>
      <div class="table-wrap">
        <table>
          <thead><tr><th>报告</th><th>修订</th><th>来源空间</th><th>可见状态</th><th>更新时间</th></tr></thead>
          <tbody>
            <tr v-for="p in detail.publications" :key="p.reportId + p.revision">
              <td class="mono">{{ p.reportId }}</td>
              <td>{{ p.revision }}</td>
              <td><span class="space-pill space-{{ p.sourceSpace }}">{{ p.sourceSpace }}</span></td>
              <td><span class="tag" :class="stateTone(p.visibleState)">{{ p.visibleState }}</span></td>
              <td>{{ formatTime(p.updatedAt) }}</td>
            </tr>
            <tr v-if="!detail.publications.length"><td colspan="5" class="empty">尚未发布报告</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
  <div v-else class="empty">选择或创建租户后查看详情</div>
</template>
