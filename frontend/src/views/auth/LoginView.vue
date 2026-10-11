<script setup lang="ts">
/**
 * 登录页。
 *
 * 契约约束（docs/api/README.md §2、§3.1）：
 *   - 不提供注册入口，也不允许首次登录自动创建账号；
 *   - 错误账号、错误密码与被停用账号统一返回 401 INVALID_CREDENTIALS，
 *     因此提示必须通用，不能显示「账号不存在」，以免被用来枚举学号；
 *   - 首次/重置后登录会拿到受限会话，必须先去改密页。
 */
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import { ApiError, describeError } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const formRef = ref<FormInstance>()

const form = reactive({
  account: '',
  password: '',
})

const rules: FormRules<typeof form> = {
  account: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const submitting = ref(false)
const errorMessage = ref('')

async function handleSubmit(): Promise<void> {
  errorMessage.value = ''

  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    // 密码不 trim：契约明确要求用户输入不静默 trim。
    const session = await auth.login(form.account, form.password)

    if (session.mustChangePassword) {
      ElMessage.warning('首次登录需先修改密码')
      // 受限会话不允许带 redirect 去业务页，改密是唯一出口。
      await router.replace({ name: 'change-password' })
      return
    }

    // 有来源则跳回来源；没有则去该角色的落地页
    // （管理员没有个人首页，落地管理首页）。
    const redirect =
      typeof route.query.redirect === 'string' ? route.query.redirect : { name: auth.homeRouteName }
    await router.replace(redirect)
  } catch (error) {
    const apiError = error instanceof ApiError ? error : null
    errorMessage.value = apiError ? describeError(apiError) : '登录失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login">
    <el-card class="login__card" shadow="always">
      <div class="login__header">
        <h1 class="login__title">学院科创空间管理系统</h1>
        <p class="login__subtitle">学生与方向负责人使用学号登录，学院管理员使用指定账号</p>
      </div>

      <el-alert
        v-if="errorMessage"
        type="error"
        :closable="false"
        show-icon
        :title="errorMessage"
        class="login__error"
      />

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="handleSubmit"
      >
        <el-form-item label="账号" prop="account">
          <el-input
            v-model="form.account"
            placeholder="学号或管理员账号"
            autocomplete="username"
            size="large"
            data-test="account"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
            show-password
            size="large"
            data-test="password"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <el-button
          type="primary"
          size="large"
          class="login__submit"
          :loading="submitting"
          data-test="submit"
          @click="handleSubmit"
        >
          登录
        </el-button>
      </el-form>

      <p class="login__hint">
        账号由学院管理员或方向负责人添加，本系统不提供自助注册。忘记密码请联系学院管理员重置。
      </p>
    </el-card>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-fill-color-light);
  padding: 1.5rem;
}

.login__card {
  width: 100%;
  max-width: 420px;
}

.login__header {
  text-align: center;
  margin-bottom: 1.5rem;
}

.login__title {
  font-size: 1.25rem;
  margin: 0 0 0.5rem;
}

.login__subtitle {
  color: var(--el-text-color-secondary);
  font-size: 0.875rem;
  margin: 0;
}

.login__error {
  margin-bottom: 1rem;
}

.login__submit {
  width: 100%;
}

.login__hint {
  color: var(--el-text-color-secondary);
  font-size: 0.8125rem;
  line-height: 1.6;
  margin: 1.25rem 0 0;
}
</style>
