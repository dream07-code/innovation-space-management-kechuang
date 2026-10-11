<script setup lang="ts">
/**
 * 404。
 *
 * 契约 §4.3 说明：越权查询 ID 也可统一返回 NOT_FOUND 以隐藏对象是否存在，
 * 因此这一页既用于路径不存在，也用于「资源存在但无权访问」的一致呈现。
 */
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

function goHome(): void {
  void router.replace(auth.isAuthenticated ? { name: 'dashboard' } : { name: 'login' })
}
</script>

<template>
  <div class="not-found">
    <el-result icon="info" title="页面不存在" sub-title="请检查访问地址是否正确。">
      <template #extra>
        <el-button type="primary" @click="goHome">返回首页</el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.not-found {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-fill-color-light);
  padding: 1.5rem;
}
</style>
