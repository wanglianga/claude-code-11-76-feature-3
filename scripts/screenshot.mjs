import pw from '/tmp/node_modules/playwright-core/index.js'
const { chromium } = pw

const base = process.env.BASE || 'http://127.0.0.1:5173'
const outDir = process.env.OUT || '/workspace/76/artifacts'

const pages = [
  { path: '/#/', name: 'overview' },
  { path: '/#/migration', name: 'migration' },
  { path: '/#/gateway', name: 'gateway' },
  { path: '/#/publishing', name: 'publishing' },
  { path: '/#/boundary', name: 'boundary' },
  { path: '/#/lab', name: 'lab' }
]

const browser = await chromium.launch({
  executablePath: '/tmp/pw-browsers/chromium-1148/chrome-linux/chrome',
  args: ['--no-sandbox', '--disable-dev-shm-usage']
})
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1 })
const errors = []
page.on('console', (msg) => { if (msg.type() === 'error') errors.push(msg.text()) })
page.on('pageerror', (err) => errors.push(`pageerror: ${err.message}`))

for (const p of pages) {
  await page.goto(base + p.path, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)
  await page.screenshot({ path: `${outDir}/${p.name}.png`, fullPage: true })
  const h2 = await page.locator('h2').first().innerText().catch(() => '(no h2)')
  console.log(`${p.name}: ${h2.replace(/\n/g, ' ')}`)
}
console.log('console errors:', errors.length ? JSON.stringify(errors, null, 2) : 'none')
await browser.close()
