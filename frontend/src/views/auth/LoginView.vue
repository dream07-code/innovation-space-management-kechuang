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
import { ArrowRight, Cpu, Medal } from '@element-plus/icons-vue'

import BrandLogo from '@/components/BrandLogo.vue'
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
  <main class="login-shell">
    <section class="login-brand-panel" aria-label="系统介绍">
      <div class="login-brand">
        <BrandLogo :size="46" />
      </div>

      <div class="login-brand__content">
        <p class="login-brand__eyebrow">面向编程教学的 AI 教育与学习平台</p>
        <h1>把判题、评分与学情反馈，收进一条教学闭环。</h1>
        <p class="login-brand__summary">
          在线写代码交题，判题机真实编译执行；客观题自动判分，主观题由大模型给出档位与依据，教师复核后发布成绩。日报周报、积分与排行记录每个人的训练过程。
        </p>

        <div class="login-feature-list">
          <div class="login-feature">
            <el-icon><Cpu /></el-icon>
            <div><strong>真实判题</strong><span>隔离沙箱编译执行，提交即反馈</span></div>
          </div>
          <div class="login-feature">
            <el-icon><Medal /></el-icon>
            <div><strong>AI 评分</strong><span>给分有档位，依据可复核</span></div>
          </div>
        </div>
      </div>

      <p class="login-brand__footer">面向编程教学 · 学生账号由教师创建</p>
    </section>

    <section class="login-form-panel">
      <div class="login-form-wrap">
        <div class="login-mobile-brand">
          <BrandLogo :size="38" />
        </div>

        <div class="login-header">
          <p class="login-header__eyebrow">欢迎回来</p>
          <h2>登录 AI 编程实训助手</h2>
          <p>学生与方向负责人使用学号登录，学院管理员使用指定账号</p>
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
            :icon="ArrowRight"
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
      </div>
    </section>
  </main>
</template>

<style scoped>
.login-shell {
  display: grid;
  min-height: 100dvh;
  grid-template-columns: minmax(380px, 0.9fr) minmax(480px, 1.1fr);
  background: var(--app-bg);
}

.login-brand-panel {
  display: flex;
  min-height: 100dvh;
  flex-direction: column;
  justify-content: space-between;
  padding: 42px 48px;
  color: #edf8f5;
  background: var(--app-brand);
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.login-brand__content {
  max-width: 580px;
  padding: 72px 0;
}

.login-brand__eyebrow {
  margin: 0 0 18px;
  color: #f0bd68;
  font-size: 12px;
  font-weight: 750;
  letter-spacing: 0;
}

.login-brand__content h1 {
  max-width: 560px;
  margin: 0;
  color: #fff;
  font-size: 48px;
  font-weight: 760;
  line-height: 1.12;
  letter-spacing: 0;
}

.login-brand__summary {
  max-width: 520px;
  margin: 24px 0 0;
  color: rgba(237, 248, 245, 0.7);
  font-size: 16px;
  line-height: 1.8;
}

.login-feature-list {
  display: grid;
  margin-top: 38px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.login-feature {
  display: flex;
  min-height: 92px;
  align-items: flex-start;
  gap: 12px;
  padding: 17px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.055);
}

.login-feature .el-icon {
  margin-top: 2px;
  color: #f0bd68;
  font-size: 20px;
}

.login-feature strong,
.login-feature span {
  display: block;
}

.login-feature strong {
  color: #fff;
  font-size: 13px;
}

.login-feature span {
  margin-top: 6px;
  color: rgba(237, 248, 245, 0.56);
  font-size: 12px;
  line-height: 1.5;
}

.login-brand__footer {
  margin: 0;
  color: rgba(237, 248, 245, 0.42);
  font-size: 11px;
}

.login-form-panel {
  display: grid;
  min-height: 100dvh;
  place-items: center;
  padding: 40px;
  background: var(--app-bg);
}

.login-form-wrap {
  width: min(100%, 430px);
  padding: 38px 40px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-surface);
  box-shadow: var(--app-shadow);
}

.login-mobile-brand {
  display: none;
  align-items: center;
  gap: 10px;
  margin-bottom: 28px;
}

.login-mobile-brand .login-brand__mark {
  width: 36px;
  height: 36px;
  flex-basis: 36px;
}

.login-header {
  margin-bottom: 26px;
}

.login-header__eyebrow {
  margin: 0 0 6px;
  color: var(--app-brand);
  font-size: 11px;
  font-weight: 750;
}

.login-header h2 {
  margin: 0;
  color: var(--app-ink);
  font-size: 28px;
  line-height: 1.25;
  letter-spacing: 0;
}

.login-header p:last-child {
  margin: 10px 0 0;
  color: var(--app-muted);
  line-height: 1.7;
}

.login__error {
  margin-bottom: 18px;
}

.login__submit {
  width: 100%;
  margin-top: 4px;
}

.login__hint {
  margin: 22px 0 0;
  padding-top: 18px;
  color: var(--app-muted);
  font-size: 12px;
  line-height: 1.75;
  border-top: 1px solid var(--app-border);
}

@media (max-width: 920px) {
  .login-brand__content h1 {
    font-size: 38px;
  }

  .login-shell {
    grid-template-columns: 1fr;
  }

  .login-brand-panel {
    display: none;
  }

  .login-form-panel {
    padding: 24px;
  }

  .login-mobile-brand {
    display: flex;
  }
}

@media (max-width: 520px) {
  .login-form-panel {
    align-items: start;
    padding: 14px;
  }

  .login-form-wrap {
    padding: 28px 22px;
  }

  .login-header h2 {
    font-size: 24px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .login-form-wrap {
    box-shadow: none;
  }
}
</style>
