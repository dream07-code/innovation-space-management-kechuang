<script setup lang="ts">
/**
 * 科创空间品牌标志。
 *
 * 图形由“创新星芒 + 空间窗口”组成：中央星芒表达创造，
 * 两侧窗口轮廓表达开放的科创空间。标志为项目原生矢量，不依赖图标库。
 */
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
    <svg
      class="brand-logo__mark"
      :width="size"
      :height="size"
      viewBox="0 0 48 48"
      role="img"
      aria-label="科创空间标志"
    >
      <rect width="48" height="48" rx="10" fill="#c58a2a" />
      <path
        d="M15 13.5H11.5V34.5H15"
        fill="none"
        stroke="#172f4f"
        stroke-width="3"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
      <path
        d="M33 13.5H36.5V34.5H33"
        fill="none"
        stroke="#172f4f"
        stroke-width="3"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
      <path
        d="M24 10L26.8 20.8L38 24L26.8 27.2L24 38L21.2 27.2L10 24L21.2 20.8Z"
        fill="#172f4f"
      />
      <circle cx="24" cy="24" r="3.2" fill="#fff8e8" />
    </svg>

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
