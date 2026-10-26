<script setup lang="ts">
/**
 * 科创空间品牌标志（横版整图：图形 + 中英文文字，位图矢量化）。
 *
 * 白底圆角容器保证深色侧栏上黑字可读；组件只负责尺寸与无障碍语义。
 * showText 已无意义（文字在标志图内），保留参数兼容旧调用。
 */
import brandIcon from '@/assets/brand-logo.svg'
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
        class="brand-logo__mark brand-logo__mark--full"
        :src="brandIcon"
        alt="科创空间标志"
        :style="{ height: `${Math.max(size - 12, 16)}px` }"
      >
      <!-- 窄屏侧栏宽度放不下横版：切换为纯图形（无文字），避免溢出截断。 -->
      <img
        class="brand-logo__mark brand-logo__mark--graphic"
        :src="brandGraphic"
        alt="科创空间标志"
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

.brand-logo__badge {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  padding: 0 10px;
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

.brand-logo__mark--graphic {
  display: none;
}

@media (max-width: 640px) {
  .brand-logo__mark--full {
    display: none;
  }

  .brand-logo__mark--graphic {
    display: block;
    width: auto;
    height: calc(100% - 12px);
  }
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

@media (max-width: 960px) {
  .brand-copy {
    display: none;
  }
}
</style>
