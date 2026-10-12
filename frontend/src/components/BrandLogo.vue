<script setup lang="ts">
/**
 * 科创空间品牌标志。
 *
 * 使用项目提供的原创品牌图，背景已做透明化处理；
 * 组件只负责尺寸、文字和无障碍语义，不修改图形本身。
 */
import brandIcon from '@/assets/brand-icon.png'
withDefaults(
  defineProps<{
    size?: number
    showText?: boolean
  }>(),
  {
    size: 42,
    showText: true,
  },
)
</script>

<template>
  <div class="brand-logo">
    <img
      class="brand-logo__mark"
      :src="brandIcon"
      :width="size"
      :height="size"
      alt="科创空间标志"
    >

    <span v-if="showText" class="brand-copy">
      <strong>科创空间</strong>
      <small>INNOVATION SPACE</small>
    </span>
  </div>
</template>

<style scoped>
.brand-logo {
  display: inline-flex;
  min-width: 0;
  align-items: center;
  gap: 11px;
}

.brand-logo__mark {
  display: block;
  flex: 0 0 auto;
  filter: drop-shadow(0 8px 14px rgba(23, 47, 79, 0.18));
}

.brand-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  line-height: 1.15;
}

.brand-copy strong {
  overflow: hidden;
  color: inherit;
  font-size: 15px;
  font-weight: 760;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.brand-copy small {
  margin-top: 5px;
  overflow: hidden;
  color: inherit;
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0;
  opacity: 0.58;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 960px) {
  .brand-copy {
    display: none;
  }
}
</style>
