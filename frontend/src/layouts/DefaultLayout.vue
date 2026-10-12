<script setup lang="ts">
/**
 * 默认业务布局：品牌侧栏 + 顶栏 + 工作区。
 *
 * 菜单按角色生成（见 @/config/menu）。此布局只负责呈现与返回导航，
 * 真正的数据权限仍由后端逐对象校验。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft,
  Calendar,
  DataLine,
  Document,
  HomeFilled,
  List,
  Medal,
  Setting,
  Star,
  SwitchButton,
  Trophy,
  User,
  UserFilled,
} from '@element-plus/icons-vue'

import BrandLogo from '@/components/BrandLogo.vue'
import { useAuthStore } from '@/stores/auth'
import { useDirectionsStore } from '@/stores/directions'
import { menuForRole, ROLE_LABELS, type MenuIconKey } from '@/config/menu'
import { backActionFor, isSubpagePath } from '@/layouts/backNavigation'

const auth = useAuthStore()
const directions = useDirectionsStore()
const route = useRoute()
const router = useRouter()

const iconMap: Record<MenuIconKey, unknown> = {
  home: HomeFilled,
  document: Document,
  calendar: Calendar,
  trophy: Trophy,
  medal: Medal,
  user: User,
  users: UserFilled,
  star: Star,
  setting: Setting,
  list: List,
  audit: DataLine,
}

const groups = computed(() => menuForRole(auth.role))

const activePath = computed(() => {
  const path = route.path
  const all = groups.value.flatMap((group) => group.items.map((item) => item.path))
  const matches = all.filter((itemPath) => itemPath !== '/' && path.startsWith(itemPath))
  if (matches.length > 0) {
    return matches.reduce((a, b) => (a.length >= b.length ? a : b))
  }
  return path === '/' ? '/' : path
})

const homePath = computed(() => (auth.isAdmin ? '/admin' : '/'))
const showBackButton = computed(() => isSubpagePath(route.path, homePath.value))
const backAction = computed(() => {
  const historyBack =
    typeof window.history.state?.back === 'string' ? window.history.state.back : null
  return backActionFor(route.path, historyBack, auth.homeRouteName)
})

const directionName = computed(() => directions.nameOf(auth.user?.directionId))
const roleLabel = computed(() => (auth.role ? ROLE_LABELS[auth.role] : ''))
const userInitial = computed(() => auth.displayName.trim().slice(0, 1) || 'U')
const loggingOut = ref(false)

function goBack(): void {
  const action = backAction.value
  if (action.kind === 'history') {
    router.back()
    return
  }
  void router.push({ name: action.name })
}

async function handleLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('退出后需要重新输入账号密码。', '确认退出登录？', {
      confirmButtonText: '退出登录',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }

  loggingOut.value = true
  try {
    await auth.logout()
    directions.reset()
    ElMessage.success('已退出登录')
    await router.replace({ name: 'login' })
  } finally {
    loggingOut.value = false
  }
}

onMounted(() => {
  void directions.ensureLoaded().catch(() => {
    // 页面使用方向数据时会再次加载并展示错误。
  })
})
</script>

<template>
  <el-container class="app-shell">
    <el-aside class="app-sidebar" width="260px">
      <div class="app-brand">
        <BrandLogo :size="42" />
      </div>

      <el-scrollbar class="app-nav-scroll">
        <el-menu :default-active="activePath" router class="app-nav">
          <template v-for="group in groups" :key="group.title">
            <el-menu-item-group :title="group.title">
              <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
                <el-icon><component :is="iconMap[item.icon]" /></el-icon>
                <span>{{ item.label }}</span>
              </el-menu-item>
            </el-menu-item-group>
          </template>
        </el-menu>
      </el-scrollbar>

    </el-aside>

    <el-container class="app-content">
      <el-header class="app-header">
        <div class="app-header__left">
          <el-button
            v-if="showBackButton"
            class="back-button"
            :icon="ArrowLeft"
            text
            data-test="back"
            @click="goBack"
          >
            返回
          </el-button>
          <div class="page-heading">
            <span class="page-heading__eyebrow">科创空间</span>
            <h1 class="page-heading__title">{{ route.meta.title ?? '工作台' }}</h1>
          </div>
        </div>

        <div class="app-header__right">
          <div class="user-summary">
            <span class="user-avatar">{{ userInitial }}</span>
            <span class="user-summary__text">
              <strong>{{ auth.displayName }}</strong>
              <small>{{ roleLabel }}<template v-if="directionName && auth.user?.directionId"> · {{ directionName }}</template></small>
            </span>
          </div>
          <el-tooltip content="退出登录" placement="bottom">
            <el-button
              class="logout-button"
              :icon="SwitchButton"
              circle
              text
              :loading="loggingOut"
              aria-label="退出登录"
              data-test="logout"
              @click="handleLogout"
            />
          </el-tooltip>
        </div>
      </el-header>

      <el-main class="app-main">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.app-shell {
  height: 100dvh;
  min-height: 640px;
  background: var(--app-bg);
}

.app-sidebar {
  display: flex;
  flex-direction: column;
  min-width: 0;
  color: #24324a;
  background: var(--app-sidebar);
  border-right: 1px solid var(--app-border);
}

.app-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 82px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--app-border);
}

.app-nav-scroll {
  flex: 1;
  padding: 16px 12px;
}

.app-nav {
  --el-menu-bg-color: transparent;
  --el-menu-text-color: rgba(36, 50, 74, 0.72);
  --el-menu-hover-bg-color: rgba(37, 99, 235, 0.08);
  --el-menu-active-color: #1d4ed8;
  border-right: none;
  background: transparent;
}

.app-nav :deep(.el-menu-item-group__title) {
  padding: 14px 12px 7px;
  color: #8794a8;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0;
  text-transform: uppercase;
}

.app-nav :deep(.el-menu-item) {
  height: 44px;
  margin: 3px 0;
  padding: 0 12px !important;
  border-radius: 7px;
  transition:
    color 160ms ease,
    background-color 160ms ease,
    transform 160ms ease;
}

.app-nav :deep(.el-menu-item:hover) {
  transform: translateX(2px);
}

.app-nav :deep(.el-menu-item.is-active) {
  color: #fff;
  font-weight: 650;
  background: #eaf2ff;
  box-shadow: inset 3px 0 0 var(--app-accent);
}

.app-nav :deep(.el-menu-item .el-icon) {
  margin-right: 11px;
  font-size: 17px;
}

.app-content {
  min-width: 0;
  background: var(--app-bg);
}

.app-header {
  display: flex;
  height: 82px;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 0 30px;
  background: rgba(255, 255, 255, 0.92);
  border-bottom: 1px solid var(--app-border);
  backdrop-filter: blur(14px);
}

.app-header__left {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 15px;
}

.back-button {
  flex: 0 0 auto;
  color: var(--app-muted);
}

.back-button:hover {
  color: var(--app-brand);
}

.page-heading {
  min-width: 0;
}

.page-heading__eyebrow {
  display: block;
  margin-bottom: 3px;
  overflow: hidden;
  color: var(--app-muted);
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 0;
  text-overflow: ellipsis;
  text-transform: uppercase;
  white-space: nowrap;
}

.page-heading__title {
  margin: 0;
  overflow: hidden;
  color: var(--app-ink);
  font-size: 21px;
  font-weight: 750;
  letter-spacing: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-header__right {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 12px;
}

.user-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-left: 12px;
  border-left: 1px solid var(--app-border);
}

.user-avatar {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  color: #fff;
  font-size: 14px;
  font-weight: 750;
  background: var(--app-brand);
  border-radius: 50%;
}

.user-summary__text {
  display: flex;
  min-width: 0;
  flex-direction: column;
  line-height: 1.25;
}

.user-summary__text strong {
  max-width: 150px;
  overflow: hidden;
  color: var(--app-ink);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-summary__text small {
  max-width: 180px;
  overflow: hidden;
  color: var(--app-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.logout-button {
  color: var(--app-muted);
}

.app-main {
  padding: 26px 30px 36px;
  overflow: auto;
  background: var(--app-bg);
}

@media (max-width: 960px) {
  .app-sidebar {
    width: 82px !important;
  }

  .app-brand {
    justify-content: center;
    padding: 18px 12px;
  }

  .app-nav :deep(.el-menu-item-group__title),
  .app-nav :deep(.el-menu-item span) {
    display: none;
  }

  .app-nav :deep(.el-menu-item) {
    justify-content: center;
    padding: 0 !important;
  }

  .app-nav :deep(.el-menu-item .el-icon) {
    margin: 0;
  }

  .app-header {
    padding: 0 20px;
  }

  .app-main {
    padding: 22px 20px 30px;
  }
}

@media (max-width: 640px) {
  .app-shell {
    min-height: 100dvh;
  }

  .app-sidebar {
    width: 66px !important;
  }

  .app-brand :deep(.brand-logo__badge) {
    width: 34px;
    height: 34px;
  }

  .app-header {
    height: 72px;
    padding: 0 14px;
  }

  .page-heading__eyebrow,
  .user-summary {
    display: none;
  }

  .page-heading__title {
    max-width: 45vw;
    font-size: 18px;
  }

  .app-main {
    padding: 16px 14px 24px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .app-nav :deep(.el-menu-item) {
    transition: none;
  }
}
</style>
