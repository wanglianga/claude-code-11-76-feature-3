import { reactive, computed } from 'vue'

const state = reactive({
  token: localStorage.getItem('labroute.operatorToken') || 'labroute-operator-demo',
  tenant: localStorage.getItem('labroute.tenant') || '',
  tenants: [],
  toast: null
})

export function useStore() {
  return state
}

export function setToken(value) {
  state.token = value
  localStorage.setItem('labroute.operatorToken', value)
}

export function setTenant(code) {
  state.tenant = code
  localStorage.setItem('labroute.tenant', code)
}

export const tenant = computed(() => state.tenants.find((t) => t.code === state.tenant) || state.tenants[0] || null)

let toastTimer = null
export function toast(message, kind = 'info') {
  state.toast = { message, kind, id: Date.now() }
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { state.toast = null }, 4200)
}

async function request(path, options = {}) {
  const headers = { ...(options.headers || {}) }
  if (options.body) headers['Content-Type'] = 'application/json'
  if (options.operator) headers['X-Operator-Token'] = state.token
  const res = await fetch(path, { ...options, headers })
  const text = await res.text()
  let body = null
  try { body = text ? JSON.parse(text) : null } catch { body = { raw: text } }
  if (!res.ok) {
    const err = new Error(body?.message || `HTTP ${res.status}`)
    err.code = body?.code
    err.status = res.status
    err.body = body
    throw err
  }
  return body
}

export const api = {
  get: (path) => request(path),
  post: (path, body, operator = true) =>
    request(path, { method: 'POST', operator, body: body ? JSON.stringify(body) : undefined }),

  async loadTenants() {
    const data = await request('/api/tenants')
    state.tenants = data.tenants || []
    if (!state.tenant && state.tenants.length) setTenant(state.tenants[0].code)
    return state.tenants
  }
}

export function formatTime(value) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  return d.toLocaleString('zh-CN', { hour12: false })
}

export function stateTone(value) {
  const v = String(value || '').toUpperCase()
  if (['VERIFIED', 'PASSED', 'PASS', 'STEADY_NEW', 'APPLIED', 'APPLIED_DUAL', 'COMPLETED', 'VALID',
    'ENABLED', 'PUBLISHED', 'MATCH', 'ACCEPTED', 'DISMISSED', 'NORMAL', 'OK', 'BENIGN'].includes(v)) return 'ok'
  if (['PENDING_VERIFICATION', 'FLAGGED', 'BUFFERED', 'FENCED', 'DRAINING', 'LAGGING', 'DUAL_WRITE',
    'READ_NEW', 'SHADOW_CHECK', 'GATED', 'SHADOW', 'DRAFT', 'DIVERGED', 'INCONCLUSIVE', 'OPEN',
    'EXPECTED', 'ACTION_REQUIRED', 'LOW', 'HIGH', 'MATERIALIZED', 'REPLAYED'].includes(v)) return 'warn'
  if (['REJECTED', 'FAILED', 'FAIL', 'ROLLED_BACK', 'BLOCKED', 'WITHDRAWN', 'BLOCKING',
    'INDETERMINATE'].includes(v)) return 'bad'
  return 'muted'
}
