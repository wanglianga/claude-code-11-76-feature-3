<script setup>
import { computed, onMounted, ref } from 'vue'
import { api, useStore, stateTone, formatTime, toast } from '../store'

const store = useStore()
const scenarios = ref([])
const history = ref([])
const running = ref('')
const current = ref(null)
const evidence = ref(null)

const tenant = computed(() => store.tenant)

const META = {
  LATE_RESULT: { title: '仪器迟到结果', desc: '结果在报告发布后到达；回执持久化、报告可回放、来源空间可命名。' },
  REPORT_REVISION: { title: '报告修订', desc: '修订 2 取代修订 1；发布服务最新已验证版本，历史完整；非法修订关系被拒收但保留。' },
  ISOLATION_CHANGE: { title: '租户隔离策略变化', desc: '策略版本提升、凭据收紧、跨租户密钥被拒、空间无共享主键。' },
  FENCE_BURST: { title: '切换围栏内三连消息', desc: '围栏内连续三条消息按序回执并缓冲，发布显式待验证，按序排空后放行，证据可回放并回退。' },
  SEMANTIC_ROUTING: { title: '统一语义路由', desc: '儿童/成人区间交接、仪器更换、规则发布后撤回：影子差异、历史显示与启用闸门路由阻断。' }
}

async function load() {
  try {
    const data = await api.get('/api/scenarios')
    scenarios.value = data.scenarios || []
    history.value = data.history || []
  } catch (e) {
    toast(`加载场景失败：${e.message}`, 'bad')
  }
}

onMounted(load)

async function run(code) {
  running.value = code
  current.value = null
  try {
    current.value = await api.post(`/api/scenarios/${code}/run?tenant=${tenant.value}`, {})
    if (current.value.status === 'PASSED') toast(`${META[code]?.title || code} 通过`, 'ok')
    else toast(`${META[code]?.title || code} 未通过：${current.value.summary}`, 'bad')
    await load()
    await loadEvidence()
  } catch (e) {
    toast(`场景执行失败：${e.message}`, 'bad')
  } finally {
    running.value = ''
  }
}

async function runAll() {
  running.value = 'ALL'
  try {
    const result = await api.post(`/api/scenarios/run-all?tenant=${tenant.value}`, {})
    const failed = Object.entries(result).filter(([k, v]) => k !== 'allPassed' && v.status !== 'PASSED')
    current.value = {
      scenario: 'ALL',
      status: result.allPassed ? 'PASSED' : 'FAILED',
      summary: result.allPassed ? '全部场景通过' : `失败：${failed.map(([k]) => k).join(', ')}`,
      assertions: Object.entries(result).filter(([k]) => k !== 'allPassed').flatMap(([k, v]) =>
        (v.assertions || []).map((a) => ({ ...a, name: `[${k}] ${a.name}` })))
    }
    toast(result.allPassed ? '全部场景通过' : '存在失败场景', result.allPassed ? 'ok' : 'bad')
    await load()
  } catch (e) {
    toast(`批量执行失败：${e.message}`, 'bad')
  } finally {
    running.value = ''
  }
}

async function loadEvidence() {
  if (!tenant.value) return
  try {
    evidence.value = await api.get(`/api/tenants/${tenant.value}/evidence?limit=40`)
  } catch {
    evidence.value = null
  }
}

const passed = computed(() => (current.value?.assertions || []).filter((a) => a.passed).length)
const total = computed(() => (current.value?.assertions || []).length)
</script>

<template>
  <div class="topbar">
    <div>
      <h2>验证实验室 <span class="sub">{{ tenant }}</span></h2>
      <div class="sub">四个确定性场景经真实签名网关与服务执行，逐条断言均附证据</div>
    </div>
    <div class="spacer"></div>
    <button class="primary" :disabled="!!running" @click="runAll">运行全部</button>
  </div>

  <div class="grid cols-2" style="margin-bottom: 16px">
    <div v-for="code in scenarios" :key="code" class="panel" style="margin-bottom: 0">
      <h3>{{ META[code]?.title || code }} <span class="hint">{{ code }}</span></h3>
      <p class="small-muted" style="margin-top: 0">{{ META[code]?.desc }}</p>
      <button :disabled="!!running" @click="run(code)">
        {{ running === code ? '执行中…' : '运行场景' }}
      </button>
    </div>
  </div>

  <div v-if="current" class="panel">
    <h3>
      执行结果
      <span class="tag" :class="stateTone(current.status)">{{ current.status }}</span>
      <span class="hint">{{ passed }} / {{ total }} 断言通过 · {{ current.summary }}</span>
    </h3>
    <div class="table-wrap">
      <table>
        <thead><tr><th style="width: 70px">结果</th><th>断言</th><th>证据</th></tr></thead>
        <tbody>
          <tr v-for="a in current.assertions" :key="a.name">
            <td><span :class="a.passed ? 'pass' : 'fail'">{{ a.passed ? '通过' : '失败' }}</span></td>
            <td class="mono">{{ a.name }}</td>
            <td class="small-muted">{{ a.detail }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <div class="grid cols-2">
    <div class="panel">
      <h3>历史运行</h3>
      <div class="table-wrap">
        <table>
          <thead><tr><th>场景</th><th>状态</th><th>摘要</th><th>时间</th></tr></thead>
          <tbody>
            <tr v-for="r in history" :key="r.runId">
              <td>{{ r.scenario }}</td>
              <td><span class="tag" :class="stateTone(r.status)">{{ r.status }}</span></td>
              <td class="small-muted">{{ r.summary }}</td>
              <td class="small-muted">{{ formatTime(r.startedAt) }}</td>
            </tr>
            <tr v-if="!history.length"><td colspan="4" class="empty">尚无运行记录</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="panel">
      <h3>证据账 <span class="hint">哈希链回放</span></h3>
      <div v-if="evidence">
        <div class="row" style="margin-bottom: 10px">
          <span class="tag" :class="evidence.chain?.valid ? 'ok' : 'bad'">{{ evidence.chain?.valid ? '链完整' : '链断裂' }}</span>
          <span class="small-muted">已校验 {{ evidence.chain?.checked ?? 0 }} 条</span>
        </div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>seq</th><th>事件</th><th>空间</th><th>可见状态</th></tr></thead>
            <tbody>
              <tr v-for="e in (evidence.entries || []).slice(0, 18)" :key="e.seq">
                <td class="mono">{{ e.seq }}</td>
                <td>{{ e.event_type }}</td>
                <td><span class="space-pill space-{{ e.source_space || 'NONE' }}">{{ e.source_space || '—' }}</span></td>
                <td><span class="tag" :class="stateTone(e.visible_state)">{{ e.visible_state || '—' }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <div v-else class="empty">暂无证据</div>
      <button class="small" style="margin-top: 10px" @click="loadEvidence">刷新证据</button>
    </div>
  </div>
</template>
