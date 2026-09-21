<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <div class="login-mark">R</div>
        <div class="login-brand-text">
          <h1 class="login-title">RCDIS Agent</h1>
          <p class="login-sub">实验室经费管理智能体</p>
        </div>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        class="login-form"
        @submit.prevent
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="请输入用户名"
            size="large"
            :prefix-icon="User"
            autocomplete="username"
          />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            size="large"
            :prefix-icon="Lock"
            show-password
            autocomplete="current-password"
            @keyup.enter="submit"
          />
        </el-form-item>

        <el-alert
          v-if="errorMessage"
          :title="errorMessage"
          type="error"
          show-icon
          :closable="false"
          class="login-error"
        />

        <el-button
          type="primary"
          size="large"
          class="login-btn"
          :loading="loading"
          @click="submit"
        >
          登录
        </el-button>
      </el-form>

      <p class="login-hint">使用管理员分配的账号登录</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'

import { toApiError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const errorMessage = ref('')
const form = reactive({ username: '', password: '' })

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  errorMessage.value = ''
  try {
    await authStore.login(form.username.trim(), form.password)
    ElMessage.success('登录成功')
    const redirect = route.query.redirect
    await router.replace(typeof redirect === 'string' && redirect ? redirect : '/')
  } catch (error) {
    errorMessage.value = toApiError(error).message
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px;
  background:
    radial-gradient(1200px 600px at 20% -10%, rgba(47, 84, 235, 0.12), transparent 60%),
    radial-gradient(1000px 500px at 100% 110%, rgba(94, 123, 247, 0.12), transparent 55%),
    #f5f7fb;
}

.login-card {
  width: 100%;
  max-width: 400px;
  padding: 36px 32px 28px;
  background: #ffffff;
  border: 1px solid var(--rc-line, #e5e8f0);
  border-radius: 16px;
  box-shadow: 0 18px 48px rgba(28, 35, 51, 0.1);
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 28px;
}

.login-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 12px;
  background: linear-gradient(135deg, #2f54eb, #5e7bf7);
  color: #ffffff;
  font-family: Georgia, 'Times New Roman', serif;
  font-size: 24px;
  font-weight: 700;
  box-shadow: 0 8px 20px rgba(47, 84, 235, 0.35);
}

.login-title {
  margin: 0;
  font-size: 19px;
  font-weight: 700;
  color: var(--rc-text, #1c2333);
  letter-spacing: 0.01em;
}

.login-sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--rc-text-muted, #8a93a6);
  letter-spacing: 0.04em;
}

.login-form {
  margin-top: 4px;
}

.login-error {
  margin-bottom: 16px;
}

.login-btn {
  width: 100%;
  margin-top: 4px;
  letter-spacing: 0.08em;
}

.login-hint {
  margin: 18px 0 0;
  text-align: center;
  font-size: 12px;
  color: var(--rc-text-faint, #a7adbd);
}
</style>
