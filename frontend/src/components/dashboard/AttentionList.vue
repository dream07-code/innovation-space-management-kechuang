<script setup lang="ts">
/**
 * 待处理事项列表。数据直接来自后端，前端不补字段或改变状态。
 */
import type { AttentionItem } from '@/api/types'

defineProps<{ items: AttentionItem[] }>()

const TYPE_LABELS: Record<AttentionItem['type'], string> = {
  SCORING_FAILED: '评分失败',
  PENDING_REVIEW: '待人工复核',
  PENDING_OJ: '待 OJ 判定',
  INACTIVITY_RISK: '提交风险',
}

function formatTime(value: string | null): string {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
</script>

<template>
  <ul class="attention-list">
    <li v-for="item in items" :key="`${item.type}-${item.memberId}-${item.occurredAt}`">
      <div class="attention-main">
        <strong>{{ TYPE_LABELS[item.type] }}</strong>
        <span>{{ item.displayName }} · {{ item.directionName }}</span>
      </div>
      <div class="attention-meta">
        <span>{{ item.status }}</span>
        <time>{{ formatTime(item.occurredAt) }}</time>
      </div>
    </li>
  </ul>
</template>

<style scoped>
.attention-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.attention-list li {
  display: flex;
  min-height: 58px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 11px 0;
  border-bottom: 1px solid var(--app-border);
}

.attention-list li:last-child {
  border-bottom: 0;
}

.attention-main,
.attention-meta {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.attention-main strong {
  color: var(--app-ink);
  font-size: 12px;
}

.attention-main span,
.attention-meta {
  color: var(--app-muted);
  font-size: 10px;
}

.attention-main span {
  margin-top: 4px;
}

.attention-meta {
  align-items: flex-end;
  flex: 0 0 auto;
  text-align: right;
}

.attention-meta span {
  color: var(--app-ink);
  font-weight: 650;
}

.attention-meta time {
  margin-top: 4px;
}

@media (max-width: 480px) {
  .attention-list li {
    align-items: flex-start;
    flex-direction: column;
  }

  .attention-meta {
    align-items: flex-start;
    text-align: left;
  }
}
</style>
