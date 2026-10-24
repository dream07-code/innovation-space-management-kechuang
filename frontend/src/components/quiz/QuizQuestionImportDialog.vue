<script setup lang="ts">
/**
 * 客观题 Excel 批量导入对话框（仅负责人/管理员）。
 *
 * 前端解析 .xlsx（模板列契约见 utils/quizImport），逐行本地校验，
 * 坏行不拖累好行；确认后逐行调用创建接口，行级结果即时回显。
 * 任一行成功即触发 imported 事件，供列表页刷新。
 */
import { computed, ref, watch } from 'vue'
import { Upload } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import { createQuizQuestion } from '@/api/modules/quiz'
import { describeError, toApiError } from '@/api/errors'
import {
  downloadQuizImportTemplate,
  readQuizImportFile,
  type QuizImportRow,
} from '@/utils/quizImport'
import { QUIZ_TYPE_META } from '@/components/quizMeta'
import { newIdempotencyKey } from '@/api/idempotency'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void; (e: 'imported'): void }>()

interface ResultRow extends QuizImportRow {
  /** 导入执行后的落库结果；null 表示尚未执行。 */
  result: 'ok' | 'fail' | null
  failReason: string | null
}

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const fileInput = ref<HTMLInputElement>()
const fileName = ref('')
const rows = ref<ResultRow[]>([])
const importing = ref(false)
const imported = ref(false)

const stats = computed(() => ({
  ok: rows.value.filter((r) => r.result === 'ok').length,
  fail: rows.value.filter((r) => r.result === 'fail').length,
  pending: rows.value.filter((r) => r.payload !== null && r.result === null).length,
  invalid: rows.value.filter((r) => r.error !== null).length,
}))
const canImport = computed(() => !importing.value && !imported.value && stats.value.pending > 0)

watch(visible, (open) => {
  if (open) reset()
})

function reset(): void {
  fileName.value = ''
  rows.value = []
  importing.value = false
  imported.value = false
}

function chooseFile(): void {
  fileInput.value?.click()
}

async function onFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  try {
    const parsed = await readQuizImportFile(file)
    if (parsed.length === 0) {
      ElMessage.warning('没有解析到任何数据行，请确认表格结构')
      return
    }
    fileName.value = file.name
    rows.value = parsed.map((row) => ({ ...row, result: null, failReason: null }))
    imported.value = false
    ElMessage.success(`已解析 ${parsed.length} 行，请核对后点击开始导入`)
  } catch {
    ElMessage.error('文件解析失败，请使用模板生成的 .xlsx 文件')
  }
}

async function runImport(): Promise<void> {
  if (!canImport.value) return
  importing.value = true
  for (const row of rows.value) {
    if (!row.payload) continue
    try {
      await createQuizQuestion(row.payload, newIdempotencyKey())
      row.result = 'ok'
    } catch (error) {
      row.result = 'fail'
      row.failReason = describeError(toApiError(error))
    }
  }
  importing.value = false
  imported.value = true
  if (stats.value.ok > 0) {
    ElMessage.success(`导入完成：成功 ${stats.value.ok} 行`)
    emit('imported')
  } else {
    ElMessage.error('全部行导入失败，请检查后重试')
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="批量导入客观题" width="760px">
    <el-alert type="info" :closable="false" class="hint">
      <p>模板列：题型（单选/多选/判断/填空）、题干、选项A–F、答案、解析、标签。</p>
      <p>单选答案填单个字母；多选填字母串如 ABD；判断填 对 或 错；填空多个备选用 | 分隔。坏行不会影响其他行。</p>
    </el-alert>

    <div class="actions">
      <el-button @click="downloadQuizImportTemplate">下载模板</el-button>
      <input ref="fileInput" type="file" accept=".xlsx" class="hidden-input" @change="onFileChange" />
      <el-button type="primary" :icon="Upload" @click="chooseFile">选择文件</el-button>
      <span v-if="fileName" class="file-name">{{ fileName }}</span>
    </div>

    <el-table v-if="rows.length" :data="rows" max-height="360" row-key="line">
      <el-table-column prop="line" label="行" width="60" />
      <el-table-column label="题型" width="80">
        <template #default="{ row }">
          {{ row.payload ? QUIZ_TYPE_META[row.payload.type as keyof typeof QUIZ_TYPE_META].label : '—' }}
        </template>
      </el-table-column>
      <el-table-column prop="payload.stem" label="题干" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.payload?.stem ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="结果" width="150">
        <template #default="{ row }">
          <span v-if="row.error" class="invalid">{{ row.error }}</span>
          <span v-else-if="row.result === 'ok'" class="ok">成功</span>
          <span v-else-if="row.result === 'fail'" class="invalid">失败：{{ row.failReason }}</span>
          <span v-else class="pending">待导入</span>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="rows.length && imported" class="summary">
      成功 {{ stats.ok }} / 失败 {{ stats.fail + stats.invalid }}（含 {{ stats.invalid }} 行校验未过）
    </div>

    <template #footer>
      <el-button :disabled="importing" @click="visible = false">关闭</el-button>
      <el-button
        data-test="start-import"
        type="primary"
        :loading="importing"
        :disabled="!canImport"
        @click="runImport"
      >
        开始导入
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin-bottom: 12px;
}

.hint p {
  margin: 0;
  line-height: 1.7;
}

.actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.hidden-input {
  display: none;
}

.file-name {
  color: var(--el-text-color-secondary);
}

.ok {
  color: var(--el-color-success);
}

.invalid {
  color: var(--el-color-danger);
}

.pending {
  color: var(--el-text-color-secondary);
}

.summary {
  margin-top: 12px;
  color: var(--el-text-color-regular);
}
</style>
