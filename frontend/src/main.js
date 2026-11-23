import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import App from './App.vue'
import Overview from './views/Overview.vue'
import Migration from './views/Migration.vue'
import Gateway from './views/Gateway.vue'
import Publishing from './views/Publishing.vue'
import BoundaryBook from './views/BoundaryBook.vue'
import Lab from './views/Lab.vue'
import SemanticRouting from './views/SemanticRouting.vue'
import './style.css'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'overview', component: Overview, meta: { title: '总览' } },
    { path: '/migration', name: 'migration', component: Migration, meta: { title: '迁移控制' } },
    { path: '/gateway', name: 'gateway', component: Gateway, meta: { title: '接入网关' } },
    { path: '/publishing', name: 'publishing', component: Publishing, meta: { title: '发布台' } },
    { path: '/semantic', name: 'semantic', component: SemanticRouting, meta: { title: '语义路由' } },
    { path: '/boundary', name: 'boundary', component: BoundaryBook, meta: { title: '边界册' } },
    { path: '/lab', name: 'lab', component: Lab, meta: { title: '验证实验室' } }
  ]
})

createApp(App).use(router).mount('#app')
