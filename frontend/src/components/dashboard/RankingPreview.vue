<script setup lang="ts">
/**
 * 排行榜预览：直接渲染后端 items/rank/points，不自行过滤或计算。
 */
import type { RankingEntry } from '@/api/types'

defineProps<{ items: RankingEntry[] }>()
</script>

<template>
  <div class="table-scroll" tabindex="0" aria-label="排行榜预览，可横向滚动">
    <table>
      <thead>
        <tr>
          <th>排名</th>
          <th>姓名</th>
          <th>方向</th>
          <th>积分</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.memberId">
          <td><strong>{{ item.rank }}</strong></td>
          <td>{{ item.displayName }}</td>
          <td>{{ item.directionName }}</td>
          <td>{{ item.points }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.table-scroll {
  overflow-x: auto;
  border: 1px solid var(--app-border);
  border-radius: 6px;
}

table {
  width: 100%;
  min-width: 520px;
  border-collapse: collapse;
}

th,
td {
  height: 42px;
  padding: 8px 11px;
  text-align: left;
  border-bottom: 1px solid var(--app-border);
}

th {
  color: var(--app-muted);
  font-size: 10px;
  background: var(--app-surface-soft);
}

td {
  color: var(--app-ink);
  font-size: 12px;
}

tbody tr:last-child td {
  border-bottom: 0;
}
</style>
