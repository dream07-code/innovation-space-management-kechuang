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
    <span class="brand-logo__badge" :style="{ width: `${size}px`, height: `${size}px` }">
      <img class="brand-logo__mark" :src="brandIcon" alt="科创空间标志">
    </span>

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

.brand-logo__badge {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  padding: 3px;
  border: 1px solid rgba(230, 106, 76, 0.22);
  border-radius: 10px;
  background: #fff1ec;
  box-shadow:
    0 8px 18px rgba(37, 99, 235, 0.16),
    inset 0 0 0 1px rgba(255, 255, 255, 0.72);
}

.brand-logo__mark {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
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
