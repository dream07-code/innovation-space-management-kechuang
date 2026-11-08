<script setup lang="ts">
/**
 * 日报/周报的笔记图片上传与排序。
 *
 * 两种用法都要支持：学生可以把图片统一放在文末（anchor=end，等于附件），
 * 也可以指定跟在某段正文之后。图片列表随报告内容一起提交，不是独立资源。
 */
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { uploadReportImage } from '@/api/modules/reports'
import { describeError, toApiError } from '@/api/errors'
import { compressImage } from '@/utils/imageCompress'
import type { ReportImage, ReportImageAnchor } from '@/api/types'

const MAX_IMAGES = 10
const MAX_BYTES = 10 * 1024 * 1024

const props = defineProps<{
  modelValue: ReportImage[]
  anchors: Array<{ value: ReportImageAnchor; label: string }>
  disabled?: boolean
}>()

const emit = defineEmits<{ 'update:modelValue': [ReportImage[]] }>()

const uploading = ref(false)

const images = computed(() => props.modelValue ?? [])
const canAdd = computed(() => !props.disabled && images.value.length < MAX_IMAGES)
const previewList = computed(() => images.value.map((item) => item.url))

function update(next: ReportImage[]): void {
  emit('update:modelValue', next)
}

function setAnchor(index: number, anchor: ReportImageAnchor): void {
  update(images.value.map((item, i) => (i === index ? { ...item, anchor } : item)))
}

function remove(index: number): void {
  update(images.value.filter((_, i) => i !== index))
}

async function onPick(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const picked = Array.from(input.files ?? [])
  input.value = ''
  if (!picked.length) return

  const remain = MAX_IMAGES - images.value.length
  if (remain <= 0) {
    ElMessage.warning(`最多上传 ${MAX_IMAGES} 张图片`)
    return
  }
  if (picked.length > remain) ElMessage.warning(`最多还能添加 ${remain} 张，多余的已忽略`)

  uploading.value = true
  try {
    // 逐张上传，任意一张失败不影响已成功的，学生可以只补失败的那张。
    let next = [...images.value]
    for (const file of picked.slice(0, remain)) {
      if (file.size > MAX_BYTES) {
        ElMessage.error(`${file.name} 超过 10MB，未上传`)
        continue
      }
      try {
        const compressed = await compressImage(file)
        const uploaded = await uploadReportImage(compressed)
        next = [
          ...next,
          { url: uploaded.url, name: uploaded.name, size: uploaded.size, anchor: 'end' },
        ]
        update(next)
      } catch (error) {
        const apiError = toApiError(error)
        // 存储未配置/不可用时后端返回 503，直说"服务不可用"学生会以为是自己的问题。
        const reason =
          apiError.code === 'SERVICE_UNAVAILABLE'
            ? '图片服务暂不可用，请联系管理员'
            : describeError(apiError)
        ElMessage.error(`${file.name} 上传失败：${reason}`)
      }
    }
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="report-images" data-test="report-images">
    <div v-if="images.length" class="report-images__grid">
      <div v-for="(item, index) in images" :key="item.url" class="report-images__item">
        <el-image
          :src="item.url"
          :preview-src-list="previewList"
          :initial-index="index"
          fit="cover"
          preview-teleported
          class="report-images__thumb"
        />
        <el-select
          v-if="!disabled && anchors.length"
          :model-value="item.anchor"
          size="small"
          class="report-images__anchor"
          :data-test="`image-anchor-${index}`"
          @change="(value: ReportImageAnchor) => setAnchor(index, value)"
        >
          <el-option v-for="anchor in anchors" :key="anchor.value" :label="anchor.label" :value="anchor.value" />
        </el-select>
        <el-tag v-else size="small" type="info" effect="plain">
          {{ anchors.find((a) => a.value === item.anchor)?.label ?? '文末' }}
        </el-tag>
        <el-button
          v-if="!disabled"
          class="report-images__remove"
          circle
          size="small"
          :data-test="`image-remove-${index}`"
          @click="remove(index)"
        >
          ×
        </el-button>
      </div>
    </div>

    <label v-if="canAdd" class="report-images__add" :class="{ 'is-busy': uploading }">
      <input
        type="file"
        accept="image/jpeg,image/png,image/webp"
        multiple
        hidden
        data-test="image-input"
        @change="onPick"
      />
      <span>{{ uploading ? '上传中…' : '+ 添加笔记图片' }}</span>
    </label>

    <p class="report-images__hint">
      最多 {{ MAX_IMAGES }} 张，单张不超过 10MB，支持 JPG/PNG/WebP；上传前会自动压缩到长边 1600px。
      <template v-if="!disabled">每张图可选择跟在正文哪一段之后，默认放在文末。</template>
    </p>
  </div>
</template>

<style scoped>
.report-images {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.report-images__grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.report-images__item {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 132px;
}
.report-images__thumb {
  width: 132px;
  height: 132px;
  border-radius: 6px;
  border: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-light);
}
.report-images__anchor {
  width: 132px;
}
.report-images__remove {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 22px;
  height: 22px;
  min-height: 22px;
  padding: 0;
  font-size: 14px;
  line-height: 1;
}
.report-images__add {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 132px;
  height: 132px;
  border: 1px dashed var(--el-border-color);
  border-radius: 6px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.2s;
}
.report-images__add:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}
.report-images__add.is-busy {
  cursor: progress;
  opacity: 0.7;
}
.report-images__hint {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
