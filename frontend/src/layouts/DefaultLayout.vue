<script setup lang="ts">
/**
 * 默认布局：左侧导航 + 顶栏。
 *
 * 菜单按角色生成（见 @/config/menu）。注意这只是呈现层 ——
 * 真正的权限由后端逐对象校验，前端隐藏菜单不是安全控制。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Calendar,
  DataLine,
  Document,
  HomeFilled,
  List,
  Medal,
  Setting,
  Star,
  Trophy,
  User,
  UserFilled,
} from '@element-plus/icons-vue'

import { useAuthStore } from '@/stores/auth'
import { useDirectionsStore } from '@/stores/directions'
import { menuForRole, ROLE_LABELS, type MenuIconKey } from '@/config/menu'

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

/** 当前高亮项：取最长匹配前缀，避免 /admin 同时点亮子页面。 */
const activePath = computed(() => {
  const path = route.path
  const all = groups.value.flatMap((g) => g.items.map((i) => i.path))
  const matches = all.filter((p) => p !== '/' && path.startsWith(p))
  if (matches.length > 0) {
    return matches.reduce((a, b) => (a.length >= b.length ? a : b))
  }
  return path === '/' ? '/' : path
})

const directionName = computed(() => directions.nameOf(auth.user?.directionId))

const roleLabel = computed(() => (auth.role ? ROLE_LABELS[auth.role] : ''))

const loggingOut = ref(false)

async function handleLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('退出后需要重新输入账号密码。', '确认退出登录？', {
      confirmButtonText: '退出登录',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return // 用户取消
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
  // 方向目录是多个筛选器的取值来源，登录后先取一次。
  void directions.ensureLoaded().catch(() => {
    // 失败不阻断界面；各页面在使用时会再次尝试并给出提示。
  })
})
</script>

<template>
  <el-container class="layout">
    <el-aside width="220px" class="layout__aside">
      <div class="layout__brand">学院科创空间</div>
      <el-scrollbar>
        <el-menu :default-active="activePath" router class="layout__menu">
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

    <el-container>
      <el-header class="layout__header">
        <div class="layout__header-left">
          <span class="layout__page-title">{{ route.meta.title ?? '' }}</span>
        </div>

        <div class="layout__header-right">
          <el-tag v-if="auth.role" size="small" effect="plain">{{ roleLabel }}</el-tag>
          <span v-if="auth.user?.directionId" class="layout__direction">{{ directionName }}</span>
          <span class="layout__name">{{ auth.displayName }}</span>
          <el-button
            link
            type="primary"
            :loading="loggingOut"
            data-test="logout"
            @click="handleLogout"
          >
            退出登录
          </el-button>
        </div>
      </el-header>

      <el-main class="layout__main">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}

.layout__aside {
  border-right: 1px solid var(--el-border-color-light);
  display: flex;
  flex-direction: column;
}

.layout__brand {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  font-size: 1rem;
  border-bottom: 1px solid var(--el-border-color-light);
  flex-shrink: 0;
}

.layout__menu {
  border-right: none;
}

.layout__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
}

.layout__page-title {
  font-weight: 600;
}

.layout__header-right {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.layout__direction,
.layout__name {
  color: var(--el-text-color-regular);
  font-size: 0.875rem;
}

.layout__main {
  background: var(--el-fill-color-light);
}
</style>
