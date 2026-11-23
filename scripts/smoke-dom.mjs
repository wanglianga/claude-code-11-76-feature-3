// Headless DOM smoke test: loads the real production bundle into jsdom,
// polyfills fetch so relative /api calls hit the real backend, and asserts
// each route renders its title and real data without runtime errors.
import { readFileSync, readdirSync } from 'node:fs'
import { JSDOM, VirtualConsole } from 'jsdom'

const API = process.env.API || 'http://127.0.0.1:8080'
const dist = process.env.DIST || `${process.cwd()}/dist`
const html = readFileSync(`${dist}/index.html`, 'utf8').replace(/<script[^>]*src="[^"]*"[^>]*><\/script>/, '')
const asset = readdirSync(`${dist}/assets`).find((f) => f.endsWith('.js'))
const bundle = readFileSync(`${dist}/assets/${asset}`, 'utf8')

const errors = []
const virtualConsole = new VirtualConsole()
virtualConsole.on('jsdomError', (e) => errors.push(`jsdomError: ${e.message}`))
virtualConsole.on('error', (m) => errors.push(`console.error: ${m}`))

const dom = new JSDOM(html, {
  url: 'http://127.0.0.1:5173/',
  runScripts: 'outside-only',
  pretendToBeVisual: true,
  virtualConsole
})
const { window } = dom

window.fetch = (input, init) => {
  const url = typeof input === 'string' && input.startsWith('/') ? API + input : input
  return fetch(url, init)
}
window.matchMedia = window.matchMedia || (() => ({ matches: false, addListener() {}, removeListener() {} }))

try {
  window.eval(bundle)
} catch (e) {
  errors.push(`bundle eval failed: ${e.message}`)
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms))

const routes = [
  ['/', '租户空间总览'],
  ['/migration', '迁移控制'],
  ['/gateway', '样本接收网关'],
  ['/publishing', '连续发布台'],
  ['/semantic', '统一语义路由'],
  ['/boundary', '租户边界册'],
  ['/lab', '验证实验室']
]

let failures = 0
for (const [path, expected] of routes) {
  window.location.hash = `#${path}`
  await wait(900)
  const h2 = window.document.querySelector('h2')?.textContent || ''
  const bodyText = window.document.body.textContent || ''
  const ok = h2.includes(expected)
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'} ${path.padEnd(12)} h2="${h2.trim()}" navItems=${window.document.querySelectorAll('.nav-item').length} textLen=${bodyText.length}`)
}

// Confirm the overview really rendered tenant data from the API, not an empty shell.
window.location.hash = '#/'
await wait(900)
const stats = window.document.querySelectorAll('.stat').length
const hasTenantCode = (window.document.body.textContent || '').includes('lab-harbor')
console.log(`overview stats=${stats} tenantCodeRendered=${hasTenantCode}`)
if (stats < 3 || !hasTenantCode) failures++

const realErrors = errors.filter((e) => !/Not implemented: (window\.)?(alert|scrollTo)/.test(e))
console.log('runtime errors:', realErrors.length ? JSON.stringify(realErrors, null, 2) : 'none')
if (realErrors.length) failures++

console.log(failures === 0 ? 'SMOKE_RESULT: PASS' : `SMOKE_RESULT: FAIL (${failures})`)
process.exit(failures === 0 ? 0 : 1)
