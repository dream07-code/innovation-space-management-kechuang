<script setup lang="ts">
/**
 * 公告管理：管理员与负责人发布/撤回两级公告。
 *
 * 权限（契约）：管理员可发学院级（不带方向）与任意方向级；负责人只能发
 * 本人所辖方向（表单方向锁定）；撤回仅限本人发布或管理员任意撤回。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'

import {
  createAnnouncement,
  listAnnouncements,
  withdrawAnnouncement,
} from '@/api/modules/announcements'
import { describeError, toApiError } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'
import { useDirectionsStore } from '@/stores/directions'
import type { AnnouncementItem } from '@/api/types'
import { newIdempotencyKey } from '@/api/idempotency'
import { formatDateTime } from '@/utils/datetime'

const auth = useAuthStore()
const directions = useDirectionsStore()

const items = ref<AnnouncementItem[]>([])
const loading = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({ title: '', content: '', directionId: '' as '' | string })

const rules: FormRules = {
  title: [{ required: true, message: '请输入标题', trigger: 'blur', max: 120 }],
  content: [{ required: true, message: '请输入内容', trigger: 'blur', max: 2000 }],
}

const isAdmin = computed(() => auth.isAdmin)
/** 管理员：可选学院级或任意方向；负责人：锁定为所辖方向（单个时隐藏选择）。 */
const directionOptions = computed(() =>
  isAdmin.value
    ? directions.items.map((item) => ({ value: item.id, label: item.name }))
    : (auth.user?.managedDirectionIds ?? [])
        .map((id) => directions.items.find((item) => item.id === id))
        .filter((item) => item !== undefined)
        .map((item) => ({ value: item.id, label: item.name })),
)

function scopeLabel(row: AnnouncementItem): string {
  return row.scope === 'COLLEGE' ? '学院级' : (row.directionName ?? '方向级')
}

async function load(): Promise<void> {
  loading.value = true
  try {
    items.value = (await listAnnouncements()).items
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

async function publish(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await createAnnouncement(
      {
        title: form.title.trim(),
        content: form.content.trim(),
        ...(form.directionId ? { directionId: form.directionId } : {}),
      },
      newIdempotencyKey(),
    )
    ElMessage.success('公告已发布')
    form.title = ''
    form.content = ''
    await load()
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    submitting.value = false
  }
}

async function withdraw(row: AnnouncementItem): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    '撤回后所有成员将不再看到该公告，操作不可撤销。',
    '确认撤回公告？',
    { type: 'warning', confirmButtonText: '确认撤回', cancelButtonText: '取消' },
  )
    .then(() => true)
    .catch(() => false)
  if (!confirmed) return
  try {
    await withdrawAnnouncement(row.id, newIdempotencyKey())
    ElMessage.success('公告已撤回')
    await load()
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  }
}

onMounted(async () => {
  await directions.ensureLoaded().catch(() => undefined)
  if (!isAdmin.value && directionOptions.value.length > 0) {
    form.directionId = directionOptions.value[0]!.value
  }
  await load()
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <span>公告管理</span>
    </template>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      class="announcement-form"
      data-test="announcement-form"
    >
      <el-form-item label="标题" prop="title">
        <el-input v-model="form.title" maxlength="120" show-word-limit data-test="announcement-title" />
      </el-form-item>
      <el-form-item label="内容" prop="content">
        <el-input
          v-model="form.content"
          type="textarea"
          :rows="4"
          maxlength="2000"
          show-word-limit
          data-test="announcement-content"
        />
      </el-form-item>
      <el-form-item :label="isAdmin ? '作用范围' : '发布方向'">
        <el-select
          v-if="directionOptions.length > 1 || isAdmin"
          v-model="form.directionId"
          data-test="announcement-scope"
          style="width: 240px"
        >
          <el-option v-if="isAdmin" label="学院级（全体可见）" value="" />
          <el-option
            v-for="option in directionOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <el-tag v-else type="info" effect="plain">{{ directionOptions[0]?.label ?? '所辖方向' }}</el-tag>
      </el-form-item>
      <el-button
        type="primary"
        :loading="submitting"
        data-test="publish-announcement"
        @click="publish"
      >
        发布公告
      </el-button>
    </el-form>

    <el-table v-loading="loading" :data="items" row-key="id" class="announcement-table">
      <el-table-column label="标题" min-width="180">
        <template #default="{ row }">{{ row.title }}</template>
      </el-table-column>
      <el-table-column label="内容" min-width="260" show-overflow-tooltip>
        <template #default="{ row }">{{ row.content }}</template>
      </el-table-column>
      <el-table-column label="范围" width="140">
        <template #default="{ row }">
          <el-tag :type="row.scope === 'COLLEGE' ? 'warning' : 'primary'" effect="plain">
            {{ scopeLabel(row) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="发布时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button
            link
            type="danger"
            :data-test="`withdraw-${row.id}`"
            @click="withdraw(row)"
          >
            撤回
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<style scoped>
.announcement-form {
  max-width: 640px;
  margin-bottom: 16px;
}

.announcement-table {
  margin-top: 8px;
}
</style>
