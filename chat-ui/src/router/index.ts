import { createRouter, createWebHistory } from 'vue-router'

import CanvasWorkspace from '../components/CanvasWorkspace.vue'
import ChatWorkspace from '../components/ChatWorkspace.vue'

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/chat' },
    {
      path: '/login',
      name: 'login',
      component: () => import('../components/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/chat/:sessionId?',
      name: 'chat',
      component: ChatWorkspace,
      props: false,
    },
    { path: '/canvas', name: 'canvas', component: CanvasWorkspace },
  ],
})

router.beforeEach(async (to) => {
  const { useAuthStore } = await import('../stores/auth')
  const auth = useAuthStore()
  if (!auth.checked) {
    await auth.refreshMe()
  }
  if (!auth.authenticated) {
    if (to.meta.public === true) {
      return true
    }
    return { name: 'login' }
  }
  if (to.name === 'login') {
    return { name: 'chat' }
  }
  return true
})
