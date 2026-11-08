<script setup lang="ts">
/**
 * 报告笔记图片的只读展示。
 *
 * 负责人/管理员在详情抽屉里查看正式报告时使用：缩略图按 3 列铺开，
 * 点击后进入 Element Plus 的图片预览（同一组内可左右翻）。图片已在服务端校验为
 * 本站存储域名，这里只负责渲染，不再做 URL 判断。
 */
import type { ReportImage } from '@/api/types'

const props = defineProps<{ images: ReportImage[] }>()

function previewList(): string[] {
  return props.images.map((item) => item.url)
}
</script>

<template>
  <div v-if="images.length" class="report-gallery" data-test="report-gallery">
    <el-image
      v-for="(item, index) in images"
      :key="item.url"
      class="report-gallery__thumb"
      :src="item.url"
      :alt="item.name"
      :preview-src-list="previewList()"
      :initial-index="index"
      fit="cover"
      loading="lazy"
      preview-teleported
      :data-test="`report-gallery-image-${index}`"
    />
  </div>
</template>

<style scoped>
.report-gallery {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-top: 8px;
}

.report-gallery__thumb {
  width: 100%;
  height: 108px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  cursor: zoom-in;
  background: var(--el-fill-color-light);
}
</style>
