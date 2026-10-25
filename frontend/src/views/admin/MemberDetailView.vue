<script setup lang="ts">
/**
 * 成员详情：查看 + 编辑资料 + 管理员专属操作（恢复、加分、重置积分、重置密码、更正学号、删除）。
 *
 * 契约权限：
 *   - 负责人只能看/改所辖方向的普通学生，不能改方向；
 *   - 恢复、加分、重置积分、更正学号、重置密码、删除：仅学院管理员；
 *   - 删除是物理删除：已有业务记录的学生会被 409 MEMBER_IN_USE 拦下，历史必须保留；
 *   - 手动移出（removeMember）契约中仍是 pending，不提供入口。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'

import {
  addMemberPoints,
  correctStudentNumber,
  deleteMember,
  getMember,
  resetMemberPassword,
  resetMemberPoints,
  restoreMember,
  updateMember,
} from '@/api/modules/members'
import { describeError, toApiError } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'
import { useDirectionsStore } from '@/stores/directions'
import type { Member, UpdateMemberRequest } from '@/api/types'
import { newIdempotencyKey } from '@/api/idempotency'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const directions = useDirectionsStore()

const memberId = computed(() => route.params.memberId as string)
const member = ref<Member | null>(null)
const loading = ref(false)
const saving = ref(false)

const formRef = ref<FormInstance>()
const form = reactive({
  name: '',
  major: '',
  className: '',
  grade: null as number | null,
  contact: '',
  directionId: '',
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
}

const isAdmin = computed(() => auth.isAdmin)
const isLeader = computed(() => auth.isLeader)
const canEdit = computed(() => isAdmin.value || isLeader.value)
const canRestore = computed(() => isAdmin.value && member.value?.membershipStatus === 'REMOVED')
const canAddPoints = computed(() => isAdmin.value && member.value?.membershipStatus === 'ACTIVE')
const canResetPoints = computed(() => isAdmin.value && member.value?.membershipStatus === 'ACTIVE')
const canResetPassword = computed(() => isAdmin.value && member.value?.accountStatus === 'ENABLED')
const canCorrectNo = computed(() => isAdmin.value)
const canDelete = computed(() => isAdmin.value && member.value?.role === 'STUDENT')

async function load(): Promise<void> {
  loading.value = true
  try {
    const m = await getMember(memberId.value)
    member.value = m
    form.name = m.name
    form.major = m.major ?? ''
    form.className = m.className ?? ''
    form.grade = m.grade
    form.contact = m.contact ?? ''
    form.directionId = m.directionId
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    const payload: UpdateMemberRequest = {
      version: member.value!.version,
      name: form.name,
      major: form.major || null,
      className: form.className || null,
      grade: form.grade,
      contact: form.contact || null,
    }
    // 负责人不能改方向。
    if (isAdmin.value) {
      payload.directionId = form.directionId
    }
    const updated = await updateMember(memberId.value, payload)
    member.value = updated
    ElMessage.success('已保存')
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.isVersionConflict) {
      await load()
      ElMessage.warning('成员信息已被他人修改，已为你刷新，请确认后再提交')
    } else {
      ElMessage.error(describeError(apiError))
    }
  } finally {
    saving.value = false
  }
}

async function handleRestore(): Promise<void> {
  const { value: reason } = await ElMessageBox.prompt('请输入恢复原因（至少 5 个字符）', '恢复学生', {
    confirmButtonText: '确认恢复',
    cancelButtonText: '取消',
    inputValidator: (v) => (v && v.trim().length >= 5 ? true : '原因至少 5 个字符'),
  }).catch(() => ({ value: null as string | null }))
  if (!reason) return

  try {
    const updated = await restoreMember(
      memberId.value,
      { version: member.value!.version, reason },
      newIdempotencyKey(),
    )
    member.value = updated
    ElMessage.success('已恢复，净积分已清零，开始新的 14 天观察期')
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  }
}

async function handleAddPoints(): Promise<void> {
  const { value: points } = await ElMessageBox.prompt('请输入要增加的积分（正整数）', '管理员加分', {
    confirmButtonText: '确认加分',
    cancelButtonText: '取消',
    inputValidator: (v) => {
      const n = Number(v)
      return Number.isInteger(n) && n > 0 ? true : '请输入正整数'
    },
  }).catch(() => ({ value: null as string | null }))
  if (!points) return

  try {
    const updated = await addMemberPoints(memberId.value, { points: Number(points) })
    member.value = updated
    ElMessage.success(`已加 ${points} 分`)
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  }
}

async function handleResetPassword(): Promise<void> {
  const { value: reason } = await ElMessageBox.prompt('请输入重置原因（至少 5 个字符）', '重置密码', {
    confirmButtonText: '确认重置',
    cancelButtonText: '取消',
    inputValidator: (v) => (v && v.trim().length >= 5 ? true : '原因至少 5 个字符'),
  }).catch(() => ({ value: null as string | null }))
  if (!reason) return

  try {
    const updated = await resetMemberPassword(
      memberId.value,
      { version: member.value!.version, reason },
      newIdempotencyKey(),
    )
    member.value = updated
    ElMessage.success('密码已重置为统一初始口令，该成员需重新登录并修改密码')
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  }
}

async function handleCorrectNo(): Promise<void> {
  const { value: newStudentNo } = await ElMessageBox.prompt('请输入新的学号', '更正学号', {
    confirmButtonText: '确认更正',
    cancelButtonText: '取消',
    inputValidator: (v) => (v && v.trim().length > 0 ? true : '请输入学号'),
  }).catch(() => ({ value: null as string | null }))
  if (!newStudentNo) return

  const { value: reason } = await ElMessageBox.prompt('请输入更正原因（至少 5 个字符）', '更正学号', {
    confirmButtonText: '确认',
    cancelButtonText: '取消',
    inputValidator: (v) => (v && v.trim().length >= 5 ? true : '原因至少 5 个字符'),
  }).catch(() => ({ value: null as string | null }))
  if (!reason) return

  try {
    const updated = await correctStudentNumber(
      memberId.value,
      { version: member.value!.version, newStudentNo, reason },
      newIdempotencyKey(),
    )
    member.value = updated
    ElMessage.success('学号已更正，该成员的全部会话已失效')
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  }
}

async function handleResetPoints(): Promise<void> {
  const { value: reason } = await ElMessageBox.prompt(
    '净积分将清零（历史流水保留，可审计），排行同步更新。请输入重置原因（至少 5 个字符）',
    '重置积分',
    {
      confirmButtonText: '确认重置',
      cancelButtonText: '取消',
      inputValidator: (v) => (v && v.trim().length >= 5 ? true : '原因至少 5 个字符'),
    },
  ).catch(() => ({ value: null as string | null }))
  if (!reason) return

  try {
    const updated = await resetMemberPoints(
      memberId.value,
      { version: member.value!.version, reason },
      newIdempotencyKey(),
    )
    member.value = updated
    ElMessage.success('净积分已清零，历史流水保留')
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  }
}

async function handleDelete(): Promise<void> {
  const { value: reason } = await ElMessageBox.prompt(
    '将永久删除该学生及其登录账号，无法恢复；已有日报/积分/考核记录的学生无法删除。请输入删除原因（至少 5 个字符）',
    '删除学生',
    {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
      inputValidator: (v) => (v && v.trim().length >= 5 ? true : '原因至少 5 个字符'),
    },
  ).catch(() => ({ value: null as string | null }))
  if (!reason) return

  try {
    await deleteMember(
      memberId.value,
      { version: member.value!.version, reason },
      newIdempotencyKey(),
    )
    ElMessage.success('学生已删除')
    void router.push({ name: 'admin-members' })
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
  <div v-loading="loading">
    <el-card v-if="member" shadow="never">
      <template #header>
        <div class="detail__header">
          <span>{{ member.name }} · {{ member.studentNo }}</span>
          <div class="detail__tags">
            <el-tag v-if="member.role === 'DIRECTION_LEADER'" type="warning" effect="plain">负责人</el-tag>
            <el-tag v-if="member.membershipStatus === 'REMOVED'" type="danger" effect="plain">已移出</el-tag>
            <el-tag v-else-if="member.accountStatus === 'DISABLED'" type="info" effect="plain">已停用</el-tag>
            <el-tag v-else type="success" effect="plain">在册</el-tag>
          </div>
        </div>
      </template>

      <!-- 已移出信息 -->
      <el-alert
        v-if="member.membershipStatus === 'REMOVED'"
        type="error"
        :closable="false"
        show-icon
        :title="`移出时间：${member.removedAt ?? '—'}`"
        :description="member.removalReason ?? ''"
        class="detail__alert"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="姓名" prop="name">
          <el-input v-model="form.name" :disabled="!canEdit" />
        </el-form-item>

        <el-form-item label="方向">
          <el-select v-model="form.directionId" :disabled="!isAdmin" style="width: 100%">
            <el-option v-for="d in directions.items" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
          <div class="detail__tip">只有学院管理员可以调整方向。</div>
        </el-form-item>

        <el-form-item label="专业">
          <el-input v-model="form.major" :disabled="!canEdit" />
        </el-form-item>

        <el-form-item label="班级">
          <el-input v-model="form.className" :disabled="!canEdit" />
        </el-form-item>

        <el-form-item label="年级">
          <el-input-number v-model="form.grade" :min="2020" :max="2030" :disabled="!canEdit" style="width: 100%" />
        </el-form-item>

        <el-form-item label="联系方式">
          <el-input v-model="form.contact" :disabled="!canEdit" />
        </el-form-item>

        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="累计积分">{{ member.totalPoints }}</el-descriptions-item>
          <el-descriptions-item label="加入时间">{{ member.joinedAt?.slice(0, 10) ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="版本号">{{ member.version }}</el-descriptions-item>
        </el-descriptions>

        <div class="detail__actions">
          <el-button v-if="canEdit" type="primary" :loading="saving" @click="save">保存资料</el-button>
          <el-button v-if="canRestore" type="warning" @click="handleRestore">恢复学生</el-button>
          <el-button v-if="canAddPoints" type="success" @click="handleAddPoints">加分</el-button>
          <el-button v-if="canResetPoints" type="warning" plain @click="handleResetPoints">重置积分</el-button>
          <el-button v-if="canResetPassword" @click="handleResetPassword">重置密码</el-button>
          <el-button v-if="canCorrectNo" @click="handleCorrectNo">更正学号</el-button>
          <el-button v-if="canDelete" type="danger" plain data-test="delete-member" @click="handleDelete">删除学生</el-button>
          <el-button @click="router.back()">返回</el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.detail__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.detail__tags {
  display: flex;
  gap: 0.5rem;
}

.detail__alert {
  margin-bottom: 1rem;
}

.detail__tip {
  color: var(--el-text-color-secondary);
  font-size: 0.75rem;
}

.detail__actions {
  margin-top: 1rem;
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
}
</style>
