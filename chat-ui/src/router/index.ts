import { createRouter, createWebHistory } from 'vue-router'

import CanvasWorkspace from '../components/CanvasWorkspace.vue'
import ChatWorkspace from '../components/ChatWorkspace.vue'

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', name: 'chat', component: ChatWorkspace },
    { path: '/canvas', name: 'canvas', component: CanvasWorkspace },
  ],
})
