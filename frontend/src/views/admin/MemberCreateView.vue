<script setup lang="ts">
/**
 * 添加学生：管理员或负责人创建新成员账号。
 *
 * 契约：POST /members。固定创建 STUDENT，初始积分 0、mustChangePassword=true；
 * 禁止提交 role/password/points（Jackson 严格模式，多一个字段 400）。
 */
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import { createMember } from '@/api/modules/members'
import { describeError, toApiError } from '@/api/errors'
import { useDirectionsStore } from '@/stores/directions'

const router = useRouter()
const directions = useDirectionsStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive({
  studentNo: '',
  name: '',
  directionId: '',
  major: '',
  className: '',
  grade: null as number | null,
  contact: '',
})

const rules: FormRules = {
  studentNo: [
    { required: true, message: '请输入学号', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9-]+$/, message: '学号只能包含字母、数字和连字符', trigger: 'blur' },
  ],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  directionId: [{ required: true, message: '请选择方向', trigger: 'change' }],
}

async function submit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const created = await createMember(
      {
        studentNo: form.studentNo,
        name: form.name,
        directionId: form.directionId,
        major: form.major || undefined,
        className: form.className || undefined,
        grade: form.grade ?? undefined,
        contact: form.contact || undefined,
      },
      crypto.randomUUID(),
    )
    ElMessage.success(`学生 ${created.name} 已创建，初始密码为统一初始口令`)
    await router.push({ name: 'admin-member-detail', params: { memberId: created.id } })
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    submitting.value = false
  }
}

function cancel(): void {
  void router.push({ name: 'admin-members' })
}
</script>

<template>
  <el-card shadow="never">
    <template #header>添加学生</template>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="学生只能由管理员或方向负责人添加，不提供注册入口。初始密码为统一初始口令，首次登录需修改。"
      class="create__tip"
    />

    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item label="学号" prop="studentNo">
        <el-input v-model="form.studentNo" placeholder="例如 MOCK20260010" />
      </el-form-item>

      <el-form-item label="姓名" prop="name">
        <el-input v-model="form.name" />
      </el-form-item>

      <el-form-item label="方向" prop="directionId">
        <el-select v-model="form.directionId" style="width: 100%">
          <el-option v-for="d in directions.items" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
      </el-form-item>

      <el-form-item label="专业">
        <el-input v-model="form.major" />
      </el-form-item>

      <el-form-item label="班级">
        <el-input v-model="form.className" />
      </el-form-item>

      <el-form-item label="年级">
        <el-input-number v-model="form.grade" :min="2020" :max="2030" style="width: 100%" />
      </el-form-item>

      <el-form-item label="联系方式">
        <el-input v-model="form.contact" />
      </el-form-item>

      <div class="create__actions">
        <el-button type="primary" :loading="submitting" @click="submit">创建</el-button>
        <el-button @click="cancel">取消</el-button>
      </div>
    </el-form>
  </el-card>
</template>

<style scoped>
.create__tip {
  margin-bottom: 1rem;
}

.create__actions {
  margin-top: 1rem;
  display: flex;
  gap: 0.75rem;
}
</style>
