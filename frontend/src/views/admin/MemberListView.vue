<script setup lang="ts">
/**
 * 人员管理：成员列表。
 *
 * 契约：GET /members。负责人只能看到所辖方向的普通学生；管理员看到全量。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { listMembers, type ListMembersParams } from '@/api/modules/members'
import { describeError, toApiError } from '@/api/errors'
import { useDirectionsStore } from '@/stores/directions'
import type { MemberPage } from '@/api/types'

const router = useRouter()
const directions = useDirectionsStore()

const data = ref<MemberPage | null>(null)
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const filter = ref<ListMembersParams>({})

async function load(): Promise<void> {
  loading.value = true
  try {
    data.value = await listMembers({ page: page.value, size: size.value, ...filter.value })
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

function onPageChange(next: number): void {
  page.value = next
  void load()
}

function onFilterChange(): void {
  page.value = 1
  void load()
}

function goDetail(row: { id: string }): void {
  void router.push({ name: 'admin-member-detail', params: { memberId: row.id } })
}

function goCreate(): void {
  void router.push({ name: 'admin-member-create' })
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
        <span>人员管理</span>
        <el-button type="primary" data-test="create-member" @click="goCreate">添加学生</el-button>
      </div>
    </template>

    <el-form inline class="page__filter">
      <el-form-item label="方向">
        <el-select v-model="filter.directionId" clearable placeholder="全部" style="width: 160px" @change="onFilterChange">
          <el-option v-for="d in directions.items" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="filter.status" clearable placeholder="全部" style="width: 120px" @change="onFilterChange">
          <el-option label="在册" value="ACTIVE" />
          <el-option label="已移出" value="REMOVED" />
        </el-select>
      </el-form-item>
      <el-form-item label="搜索">
        <el-input v-model="filter.keyword" placeholder="姓名/学号" clearable style="width: 180px" @clear="onFilterChange" @keyup.enter="onFilterChange" />
      </el-form-item>
      <el-form-item>
        <el-button @click="onFilterChange">查询</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="data?.items ?? []" row-key="id">
      <el-table-column prop="studentNo" label="学号" width="140" />
      <el-table-column prop="name" label="姓名" width="100" />
      <el-table-column label="方向" width="120">
        <template #default="{ row }">{{ directions.nameOf(row.directionId) }}</template>
      </el-table-column>
      <el-table-column label="身份" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.role === 'DIRECTION_LEADER'" type="warning" effect="plain">负责人</el-tag>
          <el-tag v-else type="info" effect="plain">学生</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.membershipStatus === 'REMOVED'" type="danger" effect="plain">已移出</el-tag>
          <el-tag v-else-if="row.accountStatus === 'DISABLED'" type="info" effect="plain">已停用</el-tag>
          <el-tag v-else type="success" effect="plain">在册</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="totalPoints" label="积分" width="80" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button link type="primary" @click="goDetail(row)">详情</el-button>
        </template>
      </el-table-column>
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
.page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page__filter {
  margin-bottom: 16px;
}

.page__pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
