<script setup lang="ts">
/**
 * 角色首页路由适配器。
 *
 * 管理员、负责人和学生分别使用独立页面组件；
 * 此处只做角色到组件的映射，不承载看板业务逻辑。
 */
import { computed, type Component } from 'vue'

import AdminDashboardView from '@/views/admin/AdminDashboardView.vue'
import LeaderDashboardView from '@/views/leader/LeaderDashboardView.vue'
import StudentDashboardView from '@/views/member/StudentDashboardView.vue'
import { resolveDashboardComponent } from '@/views/dashboardRole'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const components: Record<string, Component> = {
  AdminDashboardView,
  LeaderDashboardView,
  StudentDashboardView,
}

const dashboardComponent = computed<Component | null>(() => {
  const name = resolveDashboardComponent(auth.role)
  return name ? components[name] ?? null : null
})
</script>

<template>
  <component :is="dashboardComponent" v-if="dashboardComponent" />
</template>
