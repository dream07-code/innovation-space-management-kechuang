<script setup lang="ts">
/**
 * 看板错误状态：只展示安全、可操作的信息。
 */
import { computed } from 'vue'
import { RefreshRight, WarningFilled } from '@element-plus/icons-vue'

const props = withDefaults(
  defineProps<{
    message?: string
    showBack?: boolean
  }>(),
  {
    message: '数据暂时无法加载，请稍后重试。',
    showBack: false,
  },
)

const emit = defineEmits<{ retry: []; back: [] }>()
const safeMessage = computed(() => props.message || '数据暂时无法加载，请稍后重试。')
</script>

<template>
  <section class="dashboard-error" role="alert">
    <el-icon><WarningFilled /></el-icon>
    <div>
      <strong>看板加载失败</strong>
      <p>{{ safeMessage }}</p>
    </div>
    <div class="dashboard-error__actions">
      <el-button :icon="RefreshRight" type="primary" @click="emit('retry')">重新加载</el-button>
      <el-button v-if="showBack" @click="emit('back')">返回首页</el-button>
    </div>
  </section>
</template>

<style scoped>
.dashboard-error {
  display: grid;
  min-height: 220px;
  place-items: center;
  padding: 28px;
  text-align: center;
  border: 1px solid #f1c8c8;
  border-radius: 8px;
  background: #fff8f8;
}

.dashboard-error > .el-icon {
  color: var(--el-color-danger);
  font-size: 30px;
}

.dashboard-error strong {
  color: var(--app-ink);
}

.dashboard-error p {
  margin: 8px 0 0;
  color: var(--app-muted);
}

.dashboard-error__actions {
  display: flex;
  gap: 10px;
}
</style>
