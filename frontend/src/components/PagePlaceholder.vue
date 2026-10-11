<script setup lang="ts">
/**
 * 骨架页面占位。
 *
 * 本轮只搭地基与认证，业务页面留待后续实现。该组件统一呈现「待实现」状态，
 * 并在功能依赖的业务规则尚未确认时额外提示「待确认」——
 * 依据契约 §7.2 与验收项 AC-09：规则未确认的功能可以做页面骨架，
 * 但不得启用真实执行，也不得用隐藏默认值代替确认过的规则。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const props = defineProps<{
  /** 页面用途说明，通常写上该页面对应的接口与主要操作。 */
  description?: string
  /** 依赖的业务规则是否尚未确认。 */
  pending?: boolean
  /** 该页面用到的接口，便于后续实现时对照。 */
  endpoints?: string[]
}>()

const route = useRoute()

const title = computed(() => route.meta.title ?? '页面')
const isPending = computed(() => props.pending ?? route.meta.pending ?? false)
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="placeholder__header">
        <span class="placeholder__title">{{ title }}</span>
        <el-tag v-if="isPending" type="warning" size="small" effect="plain">规则待确认</el-tag>
        <el-tag v-else type="info" size="small" effect="plain">待实现</el-tag>
      </div>
    </template>

    <el-alert
      v-if="isPending"
      type="warning"
      :closable="false"
      show-icon
      title="该功能依赖尚未确认的业务规则"
      description="按需求文档与接口契约的约定，规则确认齐备前只提供页面骨架，不启用真实执行，也不使用任何隐藏的默认参数。"
      class="placeholder__alert"
    />

    <p v-if="description" class="placeholder__desc">{{ description }}</p>

    <div v-if="endpoints?.length" class="placeholder__endpoints">
      <p class="placeholder__label">涉及接口（契约中尚未实现，当前由 Mock 提供）：</p>
      <ul>
        <li v-for="endpoint in endpoints" :key="endpoint">
          <code>{{ endpoint }}</code>
        </li>
      </ul>
    </div>

    <el-empty description="页面骨架已就绪，业务实现待后续开发" />
  </el-card>
</template>

<style scoped>
.placeholder__header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.placeholder__title {
  font-size: 1rem;
  font-weight: 600;
}

.placeholder__alert {
  margin-bottom: 1rem;
}

.placeholder__desc {
  color: var(--el-text-color-regular);
  margin: 0 0 1rem;
}

.placeholder__endpoints {
  margin-bottom: 1rem;
}

.placeholder__label {
  color: var(--el-text-color-secondary);
  font-size: 0.875rem;
  margin: 0 0 0.5rem;
}

.placeholder__endpoints ul {
  margin: 0;
  padding-left: 1.25rem;
  color: var(--el-text-color-regular);
  font-size: 0.875rem;
}
</style>
