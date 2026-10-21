<script setup lang="ts">
/**
 * 负责人管理：列表 + 任命 + 调整管辖 + 撤销。
 *
 * 契约：/direction-leaders。全部仅学院管理员可用。
 * 任命时按学号定位成员（学号是业务唯一标识），不要求管理员记住内部成员 ID。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { appointLeader, listLeaders, revokeLeader, updateLeader } from '@/api/modules/leaders'
import { listMembers } from '@/api/modules/members'
import { describeError, toApiError } from '@/api/errors'
import { useDirectionsStore } from '@/stores/directions'
import type { AppointLeaderRequest, Member, MemberPage, UpdateLeaderRequest } from '@/api/types'
import { newIdempotencyKey } from '@/api/idempotency'

const directions = useDirectionsStore()

const data = ref<MemberPage | null>(null)
const loading = ref(false)
const page = ref(1)
const size = ref(20)

// 任命负责人：按学号定位成员，避免让管理员查内部成员 ID。
const appointVisible = ref(false)
const appointSubmitting = ref(false)
const lookingUp = ref(false)
const appointTarget = ref<Member | null>(null)
const lookupError = ref('')
const appointForm = reactive({
  studentNo: '',
  managedDirectionIds: [] as string[],
  reason: '',
})

function openAppoint(): void {
  appointForm.studentNo = ''
  appointForm.managedDirectionIds = []
  appointForm.reason = ''
  appointTarget.value = null
  lookupError.value = ''
  appointVisible.value = true
}

/** 学号是业务唯一标识；keyword 是模糊搜索，必须再按学号精确匹配一次。 */
async function lookupByStudentNo(): Promise<Member | null> {
  const studentNo = appointForm.studentNo.trim()
  appointTarget.value = null
  lookupError.value = ''
  if (!studentNo) return null
  lookingUp.value = true
  try {
    const result = await listMembers({ keyword: studentNo, size: 20 })
    const matched = result.items.find((item) => item.studentNo === studentNo)
    if (!matched) {
      lookupError.value = `未找到学号为 ${studentNo} 的成员，请确认学号或先在人员管理中创建`
      return null
    }
    if (matched.membershipStatus !== 'ACTIVE') {
      lookupError.value = `${matched.name}（${studentNo}）不是在籍成员，不能任命`
      return null
    }
    appointTarget.value = matched
    return matched
  } catch (error) {
    lookupError.value = describeError(toApiError(error))
    return null
  } finally {
    lookingUp.value = false
  }
}

function onStudentNoInput(): void {
  appointTarget.value = null
  lookupError.value = ''
}

async function submitAppoint(): Promise<void> {
  if (!appointForm.managedDirectionIds.length) {
    ElMessage.warning('请至少选择一个管辖方向')
    return
  }
  if (appointForm.reason.trim().length < 5) {
    ElMessage.warning('任命原因至少 5 个字符')
    return
  }
  appointSubmitting.value = true
  try {
    const target = appointTarget.value ?? (await lookupByStudentNo())
    if (!target) return
    const payload: AppointLeaderRequest = {
      memberId: target.id,
      version: target.version,
      managedDirectionIds: appointForm.managedDirectionIds,
      reason: appointForm.reason.trim(),
    }
    await appointLeader(payload, newIdempotencyKey())
    ElMessage.success(`已任命 ${target.name} 为方向负责人`)
    appointVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    appointSubmitting.value = false
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    data.value = await listLeaders({ page: page.value, size: size.value })
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

async function handleUpdate(row: Member): Promise<void> {
  const { value: managedDirectionIds } = await ElMessageBox.prompt(
    `当前管辖方向：${row.managedDirectionIds.join(', ')}。请输入新的方向 ID（多个用逗号分隔）`,
    '调整管辖方向',
    {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      inputValue: row.managedDirectionIds.join(', '),
      inputValidator: (v) => (v && v.trim().length > 0 ? true : '请输入方向 ID'),
    },
  ).catch(() => ({ value: null as string | null }))
  if (!managedDirectionIds) return

  const { value: reason } = await ElMessageBox.prompt('请输入调整原因（至少 5 个字符）', '调整管辖方向', {
    confirmButtonText: '确认调整',
    cancelButtonText: '取消',
    inputValidator: (v) => (v && v.trim().length >= 5 ? true : '原因至少 5 个字符'),
  }).catch(() => ({ value: null as string | null }))
  if (!reason) return

  try {
    const payload: UpdateLeaderRequest = {
      version: row.version,
      reason: reason.trim(),
      managedDirectionIds: managedDirectionIds.split(',').map((s) => s.trim()).filter(Boolean),
    }
    await updateLeader(row.id, payload)
    ElMessage.success('已调整')
    await load()
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  }
}

async function handleRevoke(row: Member): Promise<void> {
  const { value: reason } = await ElMessageBox.prompt(`请输入撤销 ${row.name} 负责人身份的原因（至少 5 个字符）`, '撤销负责人', {
    confirmButtonText: '确认撤销',
    cancelButtonText: '取消',
    inputValidator: (v) => (v && v.trim().length >= 5 ? true : '原因至少 5 个字符'),
  }).catch(() => ({ value: null as string | null }))
  if (!reason) return

  try {
    await revokeLeader(row.id, { version: row.version, reason }, newIdempotencyKey())
    ElMessage.success('已撤销')
    await load()
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  }
}

onMounted(async () => {
  await directions.ensureLoaded()
  await load()
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="page__header">
        <span>负责人管理</span>
        <el-button type="primary" @click="openAppoint">任命负责人</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="data?.items ?? []" row-key="id">
      <el-table-column prop="studentNo" label="学号" width="140" />
      <el-table-column prop="name" label="姓名" width="100" />
      <el-table-column label="所属方向" width="120">
        <template #default="{ row }">{{ directions.nameOf(row.directionId) }}</template>
      </el-table-column>
      <el-table-column label="管辖方向" min-width="200">
        <template #default="{ row }">
          {{ row.managedDirectionIds.map((id: string) => directions.nameOf(id)).join('、') }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button link type="primary" @click="handleUpdate(row)">调整管辖</el-button>
          <el-button link type="danger" @click="handleRevoke(row)">撤销</el-button>
        </template>
      </el-table-column>
    </el-table>


    <el-dialog v-model="appointVisible" title="任命负责人" width="520px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="学号" required>
          <el-input
            v-model="appointForm.studentNo"
            placeholder="输入学号，失焦后自动查找成员"
            clearable
            @input="onStudentNoInput"
            @blur="lookupByStudentNo"
            @keyup.enter="lookupByStudentNo"
          />
          <div v-if="lookingUp" class="appoint-hint">查找中…</div>
          <div v-else-if="lookupError" class="appoint-hint appoint-hint--error">
            {{ lookupError }}
          </div>
          <div v-else-if="appointTarget" class="appoint-hint">
            已找到：{{ appointTarget.name }} · {{ directions.nameOf(appointTarget.directionId) }}
          </div>
        </el-form-item>
        <el-form-item label="管辖方向" required>
          <el-select
            v-model="appointForm.managedDirectionIds"
            multiple
            placeholder="可多选"
            style="width: 100%"
          >
            <el-option v-for="d in directions.items" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="任命原因" required>
          <el-input
            v-model="appointForm.reason"
            type="textarea"
            :rows="3"
            maxlength="1000"
            show-word-limit
            placeholder="至少 5 个字符"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="appointVisible = false">取消</el-button>
        <el-button type="primary" :loading="appointSubmitting" @click="submitAppoint">
          确认任命
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.appoint-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}

.appoint-hint--error {
  color: var(--el-color-danger);
}
</style>
