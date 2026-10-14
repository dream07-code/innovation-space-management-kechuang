<script setup lang="ts">
/**
 * 强制修改密码页。
 *
 * 契约约束（docs/api/README.md §3.1、§3.2）：
 *   - 首次/重置后登录拿到的是**受限会话**（无 refresh token，access token 仅 300 秒），
 *     它只能访问 /auth/me、PUT /auth/password、/auth/logout；
 *   - 新密码 12–64 个字符，不接受全空白、当前密码或已配置的共享初始口令；
 *   - 用户输入不静默 trim；
 *   - 改密成功后全部会话失效且**不返回新 token**，因此必须重新登录，
 *     不能继续使用当前会话。
 *
 * 该页面对两类用户开放：受限会话（被强制）与正常会话（主动改密）。
 */
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormItemRule, type FormRules } from 'element-plus'

import { ApiError, describeError } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'
import { useDirectionsStore } from '@/stores/directions'

const auth = useAuthStore()
const directions = useDirectionsStore()
const router = useRouter()

const formRef = ref<FormInstance>()

const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

/** 契约要求的最小长度。 */
const MIN_LENGTH = 12
const MAX_LENGTH = 64

const validateNewPassword: FormItemRule['validator'] = (_rule, value: unknown, callback) => {
  const password = typeof value === 'string' ? value : ''
  if (!password) {
    callback(new Error('请输入新密码'))
    return
  }
  if (password.length < MIN_LENGTH || password.length > MAX_LENGTH) {
    callback(new Error(`新密码需为 ${MIN_LENGTH}–${MAX_LENGTH} 个字符`))
    return
  }
  // 不接受全空白：契约明确禁止。
  if (password.trim().length === 0) {
    callback(new Error('新密码不能全为空白字符'))
    return
  }
  // 契约：新密码不能与当前密码相同。
  if (password === form.oldPassword) {
    callback(new Error('新密码不能与当前密码相同'))
    return
  }
  callback()
}

const validateConfirm: FormItemRule['validator'] = (_rule, value: unknown, callback) => {
  if (value !== form.newPassword) {
    callback(new Error('两次输入的新密码不一致'))
    return
  }
  callback()
}

const rules: FormRules<typeof form> = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [{ validator: validateNewPassword, trigger: 'blur' }],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' },
  ],
}

const submitting = ref(false)
const errorMessage = ref('')

/** 受限会话：被强制改密，不能自行离开。 */
const isForced = computed(() => auth.mustChangePassword)

const directionName = computed(() => directions.nameOf(auth.user?.directionId))

async function handleSubmit(): Promise<void> {
  errorMessage.value = ''

  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    // 两个字段都原样透传，不做 trim —— 契约要求不静默修改用户输入。
    await auth.changePassword(form.oldPassword, form.newPassword)

    // 改密成功后所有会话失效，且响应不返回新 token，必须重新登录。
    directions.reset()
    ElMessage.success('密码修改成功，请使用新密码重新登录')
    await router.replace({ name: 'login' })
  } catch (error) {
    const apiError = error instanceof ApiError ? error : null
    errorMessage.value = apiError ? describeError(apiError) : '修改失败，请稍后重试'
    // 旧密码错误时，契约要求保留受限状态，因此不做任何跳转。
  } finally {
    submitting.value = false
  }
}

async function handleLogout(): Promise<void> {
  await auth.logout()
  directions.reset()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <div class="change-password">
    <el-card class="change-password__card" shadow="always">
      <div class="change-password__header">
        <h1 class="change-password__title">修改密码</h1>
        <p class="change-password__subtitle">
          {{ auth.displayName ? `当前账号：${auth.displayName}` : '请设置新的登录密码' }}
          <template v-if="directionName !== '—'"> · {{ directionName }}</template>
        </p>
      </div>

      <el-alert
        v-if="isForced"
        type="warning"
        :closable="false"
        show-icon
        title="首次登录或密码已被重置，需先设置新密码"
        description="在完成修改之前，系统只允许访问本页面与退出登录，其余功能暂不可用。"
        class="change-password__notice"
      />

      <el-alert
        v-if="errorMessage"
        type="error"
        :closable="false"
        show-icon
        :title="errorMessage"
        class="change-password__notice"
      />

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="handleSubmit"
      >
        <el-form-item label="当前密码" prop="oldPassword">
          <el-input
            v-model="form.oldPassword"
            type="password"
            placeholder="请输入当前使用的密码"
            autocomplete="current-password"
            show-password
            data-test="old-password"
          />
        </el-form-item>

        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="form.newPassword"
            type="password"
            :placeholder="`${MIN_LENGTH}–${MAX_LENGTH} 个字符`"
            autocomplete="new-password"
            show-password
            data-test="new-password"
          />
          <div class="change-password__tip">长度 12–64 个字符，不能全为空白，不能与当前密码相同</div>
        </el-form-item>

        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="请再次输入新密码"
            autocomplete="new-password"
            show-password
            data-test="confirm-password"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <div class="change-password__actions">
          <el-button
            type="primary"
            :loading="submitting"
            data-test="submit"
            @click="handleSubmit"
          >
            确认修改
          </el-button>
          <el-button data-test="logout" @click="handleLogout">退出登录</el-button>
        </div>
      </el-form>

      <p class="change-password__hint">
        修改成功后，所有已登录的会话都会失效，需要用新密码重新登录。
      </p>
    </el-card>
  </div>
</template>

<style scoped>
.change-password {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-fill-color-light);
  padding: 1.5rem;
}

.change-password__card {
  width: 100%;
  max-width: 460px;
}

.change-password__header {
  text-align: center;
  margin-bottom: 1.25rem;
}

.change-password__title {
  font-size: 1.25rem;
  margin: 0 0 0.5rem;
}

.change-password__subtitle {
  color: var(--el-text-color-secondary);
  font-size: 0.875rem;
  margin: 0;
}

.change-password__notice {
  margin-bottom: 1rem;
}

.change-password__tip {
  color: var(--el-text-color-secondary);
  font-size: 0.75rem;
  line-height: 1.5;
}

.change-password__actions {
  display: flex;
  gap: 0.75rem;
}

.change-password__hint {
  color: var(--el-text-color-secondary);
  font-size: 0.8125rem;
  line-height: 1.6;
  margin: 1.25rem 0 0;
}
</style>
