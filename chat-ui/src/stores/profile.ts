import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

const STORAGE_KEY = 'doubao-user-avatar-id'

export type AvatarOption = {
  id: string
  /** Element Plus Icons 组件名（已在 main 全局注册） */
  iconName: string
  color: string
}

/** 预设头像：彩色圆底 + 图标 */
export const AVATAR_PRESETS: AvatarOption[] = [
  { id: 'a1', iconName: 'UserFilled', color: '#3b6cff' },
  { id: 'a2', iconName: 'User', color: '#10b981' },
  { id: 'a3', iconName: 'StarFilled', color: '#f59e0b' },
  { id: 'a4', iconName: 'CoffeeCup', color: '#8b5cf6' },
  { id: 'a5', iconName: 'Compass', color: '#ec4899' },
  { id: 'a6', iconName: 'ChatDotRound', color: '#06b6d4' },
]

export const useProfileStore = defineStore('profile', () => {
  const avatarId = ref(AVATAR_PRESETS[0]!.id)

  const currentAvatar = computed(() => AVATAR_PRESETS.find((a) => a.id === avatarId.value) ?? AVATAR_PRESETS[0]!)

  function setAvatarId(id: string) {
    if (!AVATAR_PRESETS.some((a) => a.id === id)) return
    avatarId.value = id
    try {
      localStorage.setItem(STORAGE_KEY, id)
    } catch {
      // ignore
    }
  }

  function init() {
    try {
      const saved = localStorage.getItem(STORAGE_KEY)
      if (saved && AVATAR_PRESETS.some((a) => a.id === saved)) {
        avatarId.value = saved
      }
    } catch {
      // ignore
    }
  }

  return { avatarId, currentAvatar, setAvatarId, init }
})
