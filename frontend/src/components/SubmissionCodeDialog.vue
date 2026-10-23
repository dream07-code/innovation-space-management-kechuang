<script setup lang="ts">
/**
 * 提交代码查看弹窗。
 *
 * 仅用于管理员、方向负责人和提交者本人；内容只读，便于人工评分时对照。
 */
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import CodeEditor from '@/components/CodeEditor.vue'
import { getSubmissionCode } from '@/api/modules/oj'
import { describeError, toApiError } from '@/api/errors'
import { languageLabel } from '@/components/ojMeta'
import type { AssessmentLanguage } from '@/api/types'

const props = defineProps<{
  modelValue: boolean
  submissionId: string | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const code = ref('')
const language = ref<AssessmentLanguage>('cpp')
const loading = ref(false)

watch(
  () => [props.modelValue, props.submissionId] as const,
  async ([visible, id]) => {
    if (!visible || !id) return
    loading.value = true
    try {
      const data = await getSubmissionCode(id)
      code.value = data.code
      language.value = data.language
    } catch (error) {
      ElMessage.error(describeError(toApiError(error)))
    } finally {
      loading.value = false
    }
  },
  { immediate: true },
)
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="提交代码"
    width="900px"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="submission-code">
      <div class="submission-code__toolbar">
        <el-tag effect="plain">{{ languageLabel(language) }}</el-tag>
        <span class="submission-code__hint">只读查看，用于人工评分</span>
      </div>
      <CodeEditor :model-value="code" :language="language" readonly height="460px" />
    </div>
  </el-dialog>
</template>

<style scoped>
.submission-code__toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.submission-code__hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
