<script setup lang="ts">
/**
 * 审计记录：仅学院管理员可见。
 *
 * 契约：GET /audits 只返回脱敏 summary，不包含密码、token、联系方式、
 * 报告全文或原始模型响应；完整敏感前后值由服务端受控保存。
 */
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { listAudits, type ListAuditsParams } from '@/api/modules/audits'
import { describeError, toApiError } from '@/api/errors'
import type { AuditEntry, PageData } from '@/api/types'

const data = ref<PageData<AuditEntry> | null>(null)
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const filter = ref<Pick<ListAuditsParams, 'action' | 'resourceId'>>({})

async function load(): Promise<void> {
  loading.value = true
  try {
    data.value = await listAudits({
      page: page.value,
      size: size.value,
      action: filter.value.action?.trim() || undefined,
      resourceId: filter.value.resourceId?.trim() || undefined,
    })
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

function onFilterChange(): void {
  page.value = 1
  void load()
}

function onPageChange(next: number): void {
  page.value = next
  void load()
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <template #header>审计记录</template>

    <el-form inline class="page__filter">
      <el-form-item label="操作">
        <el-input
          v-model="filter.action"
          placeholder="如 MEMBER_CREATED"
          clearable
          style="width: 190px"
          @clear="onFilterChange"
          @keyup.enter="onFilterChange"
        />
      </el-form-item>
      <el-form-item label="资源 ID">
        <el-input
          v-model="filter.resourceId"
          placeholder="资源 ID"
          clearable
          style="width: 150px"
          @clear="onFilterChange"
          @keyup.enter="onFilterChange"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="onFilterChange">查询</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="data?.items ?? []" row-key="id">
      <el-table-column prop="occurredAt" label="时间" width="180" />
      <el-table-column prop="actorUserId" label="操作者" width="110" />
      <el-table-column prop="action" label="操作" width="180" />
      <el-table-column label="资源" width="180">
        <template #default="{ row }">{{ row.resourceType }} · {{ row.resourceId ?? '—' }}</template>
      </el-table-column>
      <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
      <el-table-column prop="summary" label="脱敏摘要" min-width="240" show-overflow-tooltip />
    </el-table>

    <div class="page__pagination">
      <el-pagination
        background
        layout="total, prev, pager, next"
        :total="data?.total ?? 0"
        :current-page="page"
        :page-size="size"
        @current-change="onPageChange"
      />
    </div>
  </el-card>
</template>

<style scoped>
.page__filter {
  margin-bottom: 16px;
}

.page__pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
