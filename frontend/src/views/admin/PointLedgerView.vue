<script setup lang="ts">
/**
 * 积分流水（管理端）。
 *
 * 契约：/points/ledger 为只读接口，管理员必须指定 memberId。
 * 为满足管理端“全量流水”查看，本页按当前权限范围逐成员收集后稳定排序分页；
 * 不修改积分，也不绕过服务端的对象级权限。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { collectPointEntries } from '@/api/modules/points'
import { listMembers } from '@/api/modules/members'
import { describeError, toApiError } from '@/api/errors'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/datetime'
import type { Member, PageData, PointEntry, PointSource } from '@/api/types'

const auth = useAuthStore()

const data = ref<PageData<PointEntry> | null>(null)
const members = ref<Member[]>([])
const memberNames = ref(new Map<string, string>())
const loading = ref(false)
const loadingMembers = ref(false)
const page = ref(1)
const size = ref(20)
const month = ref('')

const ledgerMemberIds = computed(() =>
  [...new Set([auth.user?.memberId, ...members.value.map((member) => member.id)].filter((id): id is string => Boolean(id)))],
)

const SOURCE_LABELS: Record<PointSource, string> = {
  SUBMISSION_BASE: '提交保底',
  AI_ADJUSTMENT: '评分调整',
  MANUAL_ADJUSTMENT: '人工调整',
  REVERSAL: '冲正',
}

async function loadMembers(): Promise<void> {
  loadingMembers.value = true
  try {
    const collected: Member[] = []
    let currentPage = 1
    let total = 0
    do {
      const result = await listMembers({ page: currentPage, size: 200 })
      collected.push(...result.items)
      total = result.total
      currentPage += 1
    } while (collected.length < total)

    members.value = collected
    memberNames.value = new Map(collected.map((member) => [member.id, member.name]))
    if (auth.user?.memberId) {
      memberNames.value.set(auth.user.memberId, auth.user.displayName)
    }
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loadingMembers.value = false
  }
}

async function loadLedger(): Promise<void> {
  loading.value = true
  try {
    data.value = await collectPointEntries(
      ledgerMemberIds.value,
      { month: month.value || undefined },
      page.value,
      size.value,
    )
  } catch (error) {
    ElMessage.error(describeError(toApiError(error)))
  } finally {
    loading.value = false
  }
}

function memberLabel(memberId: string): string {
  const name = memberNames.value.get(memberId)
  return name ? `${memberId} · ${name}` : memberId
}

function onPageChange(next: number): void {
  page.value = next
  void loadLedger()
}

function onMonthChange(): void {
  page.value = 1
  void loadLedger()
}

onMounted(async () => {
  await loadMembers()
  await loadLedger()
})
</script>

<template>
  <el-card v-loading="loading || loadingMembers" shadow="never">
    <template #header>
      <div class="page__header">
        <span>积分流水</span>
        <el-date-picker
          v-model="month"
          type="month"
          format="YYYY-MM"
          value-format="YYYY-MM"
          placeholder="全部月份"
          clearable
          @change="onMonthChange"
        />
      </div>
    </template>

    <el-alert type="info" :closable="false" show-icon class="page__notice">
      按当前权限范围汇总成员流水；积分只读，页面不会修改积分或触发淘汰。
    </el-alert>

    <el-table v-loading="loading" :data="data?.items ?? []" row-key="id">
      <el-table-column label="记录时间" width="150">
        <template #default="{ row }">{{ formatDateTime(row.recordedAt) }}</template>
      </el-table-column>
      <el-table-column label="成员" width="150">
        <template #default="{ row }">{{ memberLabel(row.memberId) }}</template>
      </el-table-column>
      <el-table-column prop="delta" label="变动" width="80">
        <template #default="{ row }">
          <span :class="row.delta >= 0 ? 'points__positive' : 'points__negative'">
            {{ row.delta >= 0 ? `+${row.delta}` : row.delta }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="110">
        <template #default="{ row }">{{ SOURCE_LABELS[row.source as PointSource] ?? row.source }}</template>
      </el-table-column>
      <el-table-column label="关联报告" width="180">
        <template #default="{ row }">{{ row.reportType }} · {{ row.reportId }} · v{{ row.reportVersion }}</template>
      </el-table-column>
      <el-table-column label="生效时间" width="150">
        <template #default="{ row }">{{ formatDateTime(row.effectiveAt) }}</template>
      </el-table-column>
      <el-table-column prop="ruleVersion" label="规则版本" width="120" />
      <el-table-column prop="reason" label="说明" min-width="220" show-overflow-tooltip />
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

.page__notice {
  margin-bottom: 16px;
}

.page__pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.points__positive {
  color: var(--el-color-success);
  font-weight: 600;
}

.points__negative {
  color: var(--el-color-danger);
  font-weight: 600;
}
</style>
