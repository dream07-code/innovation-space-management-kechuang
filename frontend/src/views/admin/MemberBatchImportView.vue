<script setup lang="ts">
/**
 * 批量导入学生：解析 Excel 后逐行复用 POST /members 创建。
 *
 * 设计要点：
 * - 后端不新增接口，逐行走 createMember（与手动添加同一条路径，权限/校验/审计完全一致）。
 * - 每行独立幂等键：某行失败不影响其他行，且同一行重试不重复创建。
 * - 前端先做与后端 validateProfile 对齐的本地校验，尽量在提交前拦下格式错误，
 *   但学号重复等只能由后端判定的错误会在逐行提交时捕获并汇总展示。
 */
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as XLSX from 'xlsx'

import { createMember } from '@/api/modules/members'
import { describeError, toApiError } from '@/api/errors'
import { newIdempotencyKey } from '@/api/idempotency'
import { useDirectionsStore } from '@/stores/directions'
import type { CreateMemberRequest } from '@/api/types'

const router = useRouter()
const directions = useDirectionsStore()

type RowStatus = 'pending' | 'ok' | 'fail'
interface PreviewRow {
  line: number
  studentNo: string
  name: string
  directionName: string
  directionId: string | null
  major: string
  className: string
  grade: number | null
  contact: string
  key: string
  status: RowStatus
  message: string
}

const fileInput = ref<HTMLInputElement>()
const fileName = ref('')
const previewRows = ref<PreviewRow[]>([])
const importing = ref(false)
const imported = ref(false)

/** Excel 表头（按顺序）。方向一列填名称或 code，不填数字 ID。 */
const HEADERS = ['学号', '姓名', '方向', '专业', '班级', '年级', '联系方式'] as const

const byName = computed(() => {
  const map = new Map<string, string>()
  for (const d of directions.items) {
    map.set(d.name.trim().toLowerCase(), d.id)
    map.set(d.code.trim().toLowerCase(), d.id)
  }
  return map
})

const stats = computed(() => {
  const rows = previewRows.value
  return {
    total: rows.length,
    ok: rows.filter((r) => r.status === 'ok').length,
    fail: rows.filter((r) => r.status === 'fail').length,
    pending: rows.filter((r) => r.status === 'pending').length,
  }
})

function pickFile(): void {
  fileInput.value?.click()
}

async function onFileChange(e: Event): Promise<void> {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  input.value = ''

  try {
    await directions.ensureLoaded()
  } catch {
    ElMessage.error('方向目录加载失败，无法解析「方向」列，请稍后重试')
    return
  }

  try {
    const buf = await file.arrayBuffer()
    const wb = XLSX.read(buf, { type: 'array' })
    const sheet = wb.Sheets[wb.SheetNames[0]!]
    if (!sheet) {
      ElMessage.error('Excel 里没有找到工作表')
      return
    }
    // raw:false 让单元格统一按字符串处理，避免学号被 Excel 吃掉前导零或转成科学计数法。
    const matrix = XLSX.utils.sheet_to_json<string[]>(sheet, { header: 1, raw: false, defval: '' })
    const rows = parseMatrix(matrix)
    if (rows.length === 0) {
      previewRows.value = []
      ElMessage.warning('没有解析到任何数据行，请确认表格结构')
      return
    }
    fileName.value = file.name
    previewRows.value = rows
    imported.value = false
    ElMessage.success(`已解析 ${rows.length} 行，请核对后点击导入`)
  } catch {
    ElMessage.error('文件解析失败，请确认是有效的 .xlsx 文件')
  }
}

function parseMatrix(matrix: unknown[][]): PreviewRow[] {
  if (matrix.length === 0) return []
  // 跳过表头行：第一行若包含「学号」则视为表头，否则当作数据从第 1 行开始。
  const first = matrix[0]!.map((c) => String(c ?? '').trim())
  const startIndex = first.includes('学号') ? 1 : 0

  const out: PreviewRow[] = []
  for (let i = startIndex; i < matrix.length; i++) {
    const cells = matrix[i]!.map((c) => String(c ?? '').trim())
    const [studentNo = '', name = '', directionName = '', major = '', className = '', gradeRaw = '', contact = ''] = cells
    // 整行空白则跳过（Excel 末尾常带空行）。
    if (![studentNo, name, directionName, major, className, gradeRaw, contact].some((v) => v !== '')) {
      continue
    }
    const grade = gradeRaw === '' ? null : Number(gradeRaw)
    out.push({
      line: i + 1,
      studentNo,
      name,
      directionName,
      directionId: byName.value.get(directionName.toLowerCase()) ?? null,
      major,
      className,
      grade: grade !== null && Number.isFinite(grade) ? grade : null,
      contact,
      key: newIdempotencyKey(),
      status: 'pending',
      message: '',
    })
  }
  return out
}

/** 与后端 validateProfile / create 对齐的本地校验。返回错误信息，空串表示通过。 */
function validateRow(r: PreviewRow): string {
  if (!r.studentNo) return '学号不能为空'
  if (r.studentNo.length > 32) return '学号不能超过 32 个字符'
  if (!/^[A-Za-z0-9_-]+$/.test(r.studentNo)) return '学号只能包含字母、数字、下划线和连字符'
  if (!r.name) return '姓名不能为空'
  if (r.name.length > 64) return '姓名不能超过 64 个字符'
  if (!r.directionName) return '方向不能为空'
  if (!r.directionId) return `方向「${r.directionName}」不存在，应填写方向名称或代码`
  for (const [label, v] of [
    ['专业', r.major],
    ['班级', r.className],
    ['联系方式', r.contact],
  ] as const) {
    if (v.length > 100) return `${label}不能超过 100 个字符`
  }
  if (r.grade !== null && (!Number.isInteger(r.grade) || r.grade < 1900 || r.grade > 2200)) {
    return '年级需为 1900–2200 之间的整数'
  }
  return ''
}

async function startImport(): Promise<void> {
  const rows = previewRows.value.filter((r) => r.status !== 'ok')
  if (rows.length === 0) {
    ElMessage.warning('没有待导入的数据')
    return
  }
  importing.value = true
  let okCount = 0
  for (const r of rows) {
    const localError = validateRow(r)
    if (localError) {
      r.status = 'fail'
      r.message = localError
      continue
    }
    const payload: CreateMemberRequest = {
      studentNo: r.studentNo,
      name: r.name,
      directionId: r.directionId!,
      major: r.major || null,
      className: r.className || null,
      grade: r.grade,
      contact: r.contact || null,
    }
    try {
      await createMember(payload, r.key)
      r.status = 'ok'
      r.message = '已创建'
      okCount++
    } catch (error) {
      r.status = 'fail'
      r.message = describeError(toApiError(error))
    }
  }
  importing.value = false
  imported.value = true
  if (stats.value.fail === 0) {
    ElMessage.success(`全部 ${okCount} 名学生导入成功`)
  } else {
    ElMessage.warning(`成功 ${okCount} 名，失败 ${stats.value.fail} 名，见下表`)
  }
}

function downloadTemplate(): void {
  const header = [...HEADERS]
  const example = ['MOCK20260001', '示例同学', 'AI 全栈', '软件工程', '计科 1 班', 2026, '13800000000']
  const ws = XLSX.utils.aoa_to_sheet([header, example])
  // 学号列设为文本格式，避免 Excel 把它当数字处理。
  ws['!cols'] = [{ wch: 16 }, { wch: 10 }, { wch: 16 }, { wch: 14 }, { wch: 12 }, { wch: 8 }, { wch: 16 }]
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, '学生导入')
  XLSX.writeFile(wb, '学生批量导入模板.xlsx')
}

function cancel(): void {
  void router.push({ name: 'admin-members' })
}
</script>

<template>
  <el-card shadow="never">
    <template #header>批量导入学生</template>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="支持 .xlsx。方向一列填写方向名称（如 AI 全栈）或代码（如 AI_FULLSTACK）。学号需唯一，重复的行会失败但不影响其他行。初始密码为统一初始口令。"
      class="import__tip"
    />

    <div class="import__toolbar">
      <el-button type="primary" :disabled="importing" @click="pickFile">选择 Excel 文件</el-button>
      <el-button :disabled="importing" @click="downloadTemplate">下载模板</el-button>
      <el-button
        v-if="previewRows.length > 0"
        type="success"
        :loading="importing"
        @click="startImport"
      >
        开始导入{{ stats.pending > 0 || stats.fail > 0 ? `（待处理 ${stats.pending + stats.fail} 行）` : '' }}
      </el-button>
      <el-button @click="cancel">返回列表</el-button>
      <span v-if="fileName" class="import__file">{{ fileName }}</span>
    </div>

    <input
      ref="fileInput"
      type="file"
      accept=".xlsx"
      class="import__hidden"
      @change="onFileChange"
    />

    <el-alert
      v-if="imported && stats.ok > 0"
      type="success"
      :closable="false"
      show-icon
      :title="`本次成功导入 ${stats.ok} 名学生`"
      class="import__tip"
    />

    <el-table v-if="previewRows.length > 0" :data="previewRows" class="import__table">
      <el-table-column prop="line" label="行号" width="70" />
      <el-table-column prop="studentNo" label="学号" min-width="130" />
      <el-table-column prop="name" label="姓名" min-width="90" />
      <el-table-column prop="directionName" label="方向" min-width="130" />
      <el-table-column prop="major" label="专业" min-width="110" />
      <el-table-column prop="className" label="班级" min-width="100" />
      <el-table-column prop="grade" label="年级" width="80" />
      <el-table-column prop="contact" label="联系方式" min-width="130" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.status === 'ok'" type="success" size="small">成功</el-tag>
          <el-tag v-else-if="row.status === 'fail'" type="danger" size="small">失败</el-tag>
          <el-tag v-else type="info" size="small">待导入</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="message" label="说明" min-width="180" />
    </el-table>
  </el-card>
</template>

<style scoped>
.import__tip {
  margin-bottom: 1rem;
}

.import__toolbar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
  margin-bottom: 1rem;
}

.import__file {
  color: var(--el-text-color-secondary);
  font-size: 0.875rem;
}

.import__hidden {
  display: none;
}

.import__table {
  width: 100%;
}
</style>
