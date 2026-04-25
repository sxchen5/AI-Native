<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { User, Lock, Key } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

import * as authApi from '../api/authApi'
import { useAuthStore } from '../stores/auth'

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const captchaCode = ref('')
const captchaId = ref('')
const captchaUrl = ref('')
const loading = ref(false)

async function loadCaptcha() {
  if (captchaUrl.value.startsWith('blob:')) {
    URL.revokeObjectURL(captchaUrl.value)
  }
  const { blobUrl, captchaId: id } = await authApi.fetchCaptcha()
  captchaUrl.value = blobUrl
  captchaId.value = id
  captchaCode.value = ''
}

onMounted(() => {
  void loadCaptcha().catch((e) => ElMessage.error((e as Error).message))
})

watch(
  () => auth.authenticated,
  (v) => {
    if (!v) void loadCaptcha().catch(() => {})
  },
)

async function onSubmit() {
  if (!username.value.trim() || !password.value) {
    ElMessage.warning(t('login.fillAll'))
    return
  }
  if (!captchaCode.value.trim()) {
    ElMessage.warning(t('login.captchaRequired'))
    return
  }
  loading.value = true
  try {
    await auth.login(username.value.trim(), password.value, captchaId.value, captchaCode.value.trim())
    ElMessage.success(t('login.success'))
    await router.replace({ name: 'chat' })
  } catch (e: unknown) {
    ElMessage.error((e as Error).message || t('login.failed'))
    await loadCaptcha().catch(() => {})
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="card">
      <h1>{{ t('login.title') }}</h1>
      <p class="sub">{{ t('login.subtitle') }}</p>
      <el-form label-position="top" @submit.prevent="onSubmit">
        <el-form-item :label="t('login.username')">
          <el-input v-model="username" :prefix-icon="User" autocomplete="username" />
        </el-form-item>
        <el-form-item :label="t('login.password')">
          <el-input v-model="password" type="password" show-password :prefix-icon="Lock" autocomplete="current-password" />
        </el-form-item>
        <el-form-item :label="t('login.captcha')">
          <div class="captcha-row">
            <el-input v-model="captchaCode" :prefix-icon="Key" maxlength="8" :placeholder="t('login.captchaPlaceholder')" />
            <img v-if="captchaUrl" :src="captchaUrl" class="captcha-img" alt="captcha" @click="loadCaptcha" />
          </div>
          <div class="hint">{{ t('login.clickRefresh') }}</div>
        </el-form-item>
        <el-button type="primary" class="submit" native-type="submit" :loading="loading" block>
          {{ t('login.submit') }}
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100%;
  display: grid;
  place-items: center;
  padding: 24px;
  background: var(--login-page-bg);
}
.card {
  width: 100%;
  max-width: 400px;
  padding: 28px 28px 32px;
  border-radius: 16px;
  background: var(--login-card-bg);
  box-shadow: 0 12px 40px rgba(15, 23, 42, 0.08);
  border: 1px solid var(--login-card-border);
}
h1 {
  margin: 0 0 8px;
  font-size: 22px;
  font-weight: 700;
  color: var(--login-heading);
}
.sub {
  margin: 0 0 20px;
  font-size: 13px;
  color: var(--login-muted);
}
.captcha-row {
  display: flex;
  gap: 10px;
  align-items: center;
}
.captcha-img {
  height: 40px;
  border-radius: 8px;
  cursor: pointer;
  border: 1px solid var(--border-subtle);
}
.hint {
  font-size: 12px;
  color: var(--login-hint);
  margin-top: 4px;
}
.submit {
  margin-top: 8px;
  width: 100%;
  height: 42px;
  font-weight: 600;
}
</style>
