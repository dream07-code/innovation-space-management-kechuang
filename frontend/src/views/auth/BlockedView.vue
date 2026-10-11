<script setup lang="ts">
/**
 * 访问受限页。
 *
 * 用于三类终态（契约 §4.3）：
 *   - ACCOUNT_DISABLED：账号被停用；
 *   - MEMBER_REMOVED：成员已被移出（月度末位评定或连续未提交日报）；
 *   - FORBIDDEN：角色不匹配访问了无权页面。
 *
 * 这些情况都不应重试。注意「移出后是否允许只读登录」是需求文档 §14 的待确认事项，
 * 因此这里只呈现阻断状态，不自行断定移出成员能否看到历史数据。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import { useDirectionsStore } from '@/stores/directions'

const auth = useAuthStore()
const directions = useDirectionsStore()
const router = useRouter()

const content = computed(() => {
  switch (auth.blockedReason) {
    case 'ACCOUNT_DISABLED':
      return {
        title: '账号已被停用',
        description: '该账号当前无法登录使用，请联系学院管理员了解具体情况。',
      }
    case 'MEMBER_REMOVED':
      return {
        title: '成员已不在籍',
        description:
          '该成员已被移出（可能由月度末位评定或连续未提交日报触发）。如需了解移出原因或申请恢复，请联系学院管理员。',
      }
    default:
      return {
        title: '没有访问权限',
        description: '当前角色无法访问该页面。如果认为这是误判，请联系学院管理员。',
      }
  }
})

async function handleLogout(): Promise<void> {
  await auth.logout()
  directions.reset()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <div class="blocked">
    <el-result icon="warning" :title="content.title" :sub-title="content.description">
      <template #extra>
        <el-button type="primary" data-test="logout" @click="handleLogout">退出登录</el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.blocked {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-fill-color-light);
  padding: 1.5rem;
}
</style>
