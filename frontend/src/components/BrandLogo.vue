<script setup lang="ts">
/**
 * AI 编程实训助手品牌标志（纯图形，位图矢量化；不带文字）。
 *
 * 图形填满白底徽标框（框内无留白，图高 = 框高）；组件只负责尺寸与
 * 无障碍语义。showText 保留参数兼容旧调用但不再渲染文字。
 */
import brandGraphic from '@/assets/brand-graphic.svg'
withDefaults(
  defineProps<{
    size?: number
    showText?: boolean
  }>(),
  {
    size: 42,
    showText: false,
  },
)
</script>

<template>
  <div class="brand-logo">
    <span class="brand-logo__badge" :style="{ height: `${size}px` }">
      <img
        class="brand-logo__mark"
        :src="brandGraphic"
        alt="AI 编程实训助手标志"
        :style="{ height: `${size}px` }"
      >
    </span>
  </div>
</template>

<style scoped>
.brand-logo {
  display: inline-flex;
  min-width: 0;
  align-items: center;
}

/* 图形撑满徽标：无内边距，框尺寸由方形图形决定。 */
.brand-logo__badge {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  overflow: hidden;
  border: 1px solid rgba(41, 167, 224, 0.25);
  border-radius: 10px;
  background: #ffffff;
  box-shadow:
    0 8px 18px rgba(37, 99, 235, 0.16),
    inset 0 0 0 1px rgba(255, 255, 255, 0.72);
}

.brand-logo__mark {
  display: block;
  width: auto;
  object-fit: contain;
}
</style>
