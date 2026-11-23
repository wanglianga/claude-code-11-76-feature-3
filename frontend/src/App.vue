<script setup>
import { onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useStore, api, setToken, setTenant, toast } from './store'

const store = useStore()
const route = useRoute()

const nav = [
  { to: '/', label: '总览', key: 'overview' },
  { to: '/migration', label: '迁移控制', key: 'migration' },
  { to: '/gateway', label: '接入网关', key: 'gateway' },
  { to: '/publishing', label: '发布台', key: 'publishing' },
  { to: '/semantic', label: '语义路由', key: 'semantic' },
  { to: '/boundary', label: '边界册', key: 'boundary' },
  { to: '/lab', label: '验证实验室', key: 'lab' }
]

onMounted(async () => {
  try {
    await api.loadTenants()
  } catch (e) {
    toast(`无法加载租户：${e.message}`, 'bad')
  }
})

watch(() => store.token, (v) => setToken(v))

function pickTenant(event) {
  setTenant(event.target.value)
}
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">
        <h1>LabRoute</h1>
        <p>多租户检验数据<br />搬迁与路由控制平台</p>
      </div>
      <router-link
        v-for="item in nav"
        :key="item.key"
        :to="item.to"
        class="nav-item"
        :class="{ active: route.path === item.to }"
      >
        <span class="nav-dot"></span>{{ item.label }}
      </router-link>
      <div class="sidebar-foot">
        <div style="margin-bottom: 8px">当前租户</div>
        <select :value="store.tenant" @change="pickTenant">
          <option v-for="t in store.tenants" :key="t.code" :value="t.code">
            {{ t.code }}
          </option>
        </select>
        <div style="margin: 12px 0 8px">运维令牌</div>
        <input v-model="store.token" type="password" />
        <div style="margin-top: 12px">
          <a href="/swagger-ui/index.html" target="_blank" rel="noreferrer">OpenAPI / Swagger UI ↗</a>
        </div>
      </div>
    </aside>
    <main class="main">
      <router-view />
    </main>
    <div v-if="store.toast" class="toast" :class="store.toast.kind">{{ store.toast.message }}</div>
  </div>
</template>
