<script setup lang="ts">
/**
 * 数据看板趋势图。
 *
 * 使用分组柱状图展示日报提交、评分完成与积分新增。
 * 图形、tooltip、文字摘要和完整数据表同时提供，避免只依赖颜色。
 */
import { computed, ref } from 'vue'

import DashboardEmptyState from '@/components/dashboard/DashboardEmptyState.vue'
import type { DashboardTrendPoint } from '@/api/types'

const props = withDefaults(
  defineProps<{
    points: DashboardTrendPoint[]
    ariaLabel?: string
    emptyTitle?: string
    emptyDescription?: string
    actionLabel?: string
  }>(),
  {
    ariaLabel: '提交、评分与积分趋势图',
    emptyTitle: '暂无趋势数据',
    emptyDescription: '当前时间范围内还没有可展示的数据。',
    actionLabel: '',
  },
)

defineEmits<{ action: [] }>()

const activeIndex = ref<number | null>(null)

const totals = computed(() =>
  props.points.reduce(
    (result, point) => ({
      dailySubmitted: result.dailySubmitted + point.dailySubmitted,
      dailyScored: result.dailyScored + point.dailyScored,
      pointsAdded: result.pointsAdded + point.pointsAdded,
    }),
    { dailySubmitted: 0, dailyScored: 0, pointsAdded: 0 },
  ),
)

const maxValue = computed(() =>
  Math.max(
    1,
    ...props.points.flatMap((point) => [point.dailySubmitted, point.dailyScored, point.pointsAdded]),
  ),
)

const chartPoints = computed(() => {
  const left = 48
  const right = 18
  const top = 22
  const baseline = 236
  const width = 960 - left - right
  const groupWidth = props.points.length > 0 ? width / props.points.length : width
  const barWidth = Math.max(2.5, Math.min(11, groupWidth / 4.6))

  return props.points.map((point, index) => {
    const center = left + groupWidth * (index + 0.5)
    const bar = (value: number) => {
      const height = Math.max(1, (value / maxValue.value) * (baseline - top))
      return { y: baseline - height, height }
    }
    return {
      ...point,
      center,
      barWidth,
      dailySubmittedBar: bar(point.dailySubmitted),
      dailyScoredBar: bar(point.dailyScored),
      pointsAddedBar: bar(point.pointsAdded),
    }
  })
})

const activePoint = computed(() =>
  activeIndex.value === null ? null : chartPoints.value[activeIndex.value] ?? null,
)

const tooltipLeft = computed(() => {
  if (!activePoint.value) return '50%'
  return `${(activePoint.value.center / 960) * 100}%`
})

function pointLabel(point: DashboardTrendPoint): string {
  return `${point.date}：日报提交 ${point.dailySubmitted}，评分完成 ${point.dailyScored}，积分新增 ${point.pointsAdded}`
}
</script>

<template>
  <div class="trend-chart">
    <div class="trend-summary" aria-label="趋势摘要">
      <span>日报提交 <strong>{{ totals.dailySubmitted }}</strong></span>
      <span>评分完成 <strong>{{ totals.dailyScored }}</strong></span>
      <span>积分新增 <strong>{{ totals.pointsAdded }}</strong></span>
    </div>

    <DashboardEmptyState
      v-if="points.length === 0"
      class="dashboard-empty"
      :title="emptyTitle"
      :description="emptyDescription"
      :action-label="actionLabel"
      @action="$emit('action')"
    />

    <template v-else>
      <div class="chart-frame" aria-busy="false">
        <svg
          class="trend-svg"
          viewBox="0 0 960 280"
          role="img"
          :aria-label="ariaLabel"
          @mouseleave="activeIndex = null"
        >
          <g class="grid">
            <line x1="48" y1="22" x2="942" y2="22" />
            <line x1="48" y1="129" x2="942" y2="129" />
            <line x1="48" y1="236" x2="942" y2="236" />
          </g>
          <g class="axis-labels" aria-hidden="true">
            <text x="38" y="27" text-anchor="end">{{ maxValue }}</text>
            <text x="38" y="134" text-anchor="end">{{ Math.round(maxValue / 2) }}</text>
            <text x="38" y="241" text-anchor="end">0</text>
          </g>

          <g v-for="(point, index) in chartPoints" :key="point.date">
            <rect
              class="bar bar--submitted"
              :x="point.center - point.barWidth * 1.65"
              :y="point.dailySubmittedBar.y"
              :width="point.barWidth"
              :height="point.dailySubmittedBar.height"
            />
            <rect
              class="bar bar--scored"
              :x="point.center - point.barWidth / 2"
              :y="point.dailyScoredBar.y"
              :width="point.barWidth"
              :height="point.dailyScoredBar.height"
            />
            <rect
              class="bar bar--points"
              :x="point.center + point.barWidth * 0.65"
              :y="point.pointsAddedBar.y"
              :width="point.barWidth"
              :height="point.pointsAddedBar.height"
            />
            <rect
              class="focus-target"
              :x="point.center - Math.max(8, point.barWidth * 2.2)"
              y="18"
              :width="Math.max(16, point.barWidth * 4.4)"
              height="222"
              tabindex="0"
              role="img"
              :aria-label="pointLabel(point)"
              @mouseenter="activeIndex = index"
              @focus="activeIndex = index"
              @mouseleave="activeIndex = null"
              @blur="activeIndex = null"
            />
            <title>{{ pointLabel(point) }}</title>
          </g>
        </svg>

        <div v-if="activePoint" class="chart-tooltip" :style="{ left: tooltipLeft }">
          <strong>{{ activePoint.date }}</strong>
          <span>日报提交 {{ activePoint.dailySubmitted }}</span>
          <span>评分完成 {{ activePoint.dailyScored }}</span>
          <span>积分新增 {{ activePoint.pointsAdded }}</span>
        </div>
      </div>

      <div class="chart-legend" aria-label="图例">
        <span><i class="legend-dot legend-dot--submitted"></i>日报提交</span>
        <span><i class="legend-dot legend-dot--scored"></i>评分完成</span>
        <span><i class="legend-dot legend-dot--points"></i>积分新增</span>
      </div>

      <details class="chart-data">
        <summary>查看趋势数据表</summary>
        <div class="table-scroll" tabindex="0">
          <table>
            <thead>
              <tr>
                <th>日期</th>
                <th>日报提交</th>
                <th>评分完成</th>
                <th>积分新增</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="point in points" :key="point.date">
                <td>{{ point.date }}</td>
                <td>{{ point.dailySubmitted }}</td>
                <td>{{ point.dailyScored }}</td>
                <td>{{ point.pointsAdded }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </details>
    </template>
  </div>
</template>

<style scoped>
.trend-chart {
  min-width: 0;
}

.trend-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 9px 18px;
  margin-bottom: 14px;
  color: var(--app-muted);
  font-size: 12px;
}

.trend-summary strong {
  margin-left: 4px;
  color: var(--app-ink);
  font-size: 15px;
}

.chart-frame {
  position: relative;
  width: 100%;
  min-height: 280px;
  overflow: hidden;
  border: 1px solid var(--app-border);
  border-radius: 7px;
  background: #fbfcfe;
}

.trend-svg {
  display: block;
  width: 100%;
  height: 280px;
}

.grid line {
  stroke: #dfe6ef;
  stroke-width: 1;
}

.axis-labels {
  fill: var(--app-muted);
  font-size: 11px;
}

.bar--submitted {
  fill: var(--app-brand);
}

.bar--scored {
  fill: var(--app-accent);
}

.bar--points {
  fill: #7c8da8;
}

.focus-target {
  fill: transparent;
  outline: none;
}

.focus-target:focus {
  fill: rgba(37, 99, 235, 0.07);
  stroke: rgba(37, 99, 235, 0.45);
  stroke-width: 2;
}

.chart-tooltip {
  position: absolute;
  top: 12px;
  display: flex;
  width: 150px;
  padding: 10px 12px;
  pointer-events: none;
  transform: translateX(-50%);
  flex-direction: column;
  gap: 3px;
  color: var(--app-ink);
  font-size: 11px;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: var(--app-shadow);
}

.chart-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 18px;
  margin-top: 12px;
  color: var(--app-muted);
  font-size: 11px;
}

.legend-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  margin-right: 6px;
  border-radius: 2px;
}

.legend-dot--submitted {
  background: var(--app-brand);
}

.legend-dot--scored {
  background: var(--app-accent);
}

.legend-dot--points {
  background: #7c8da8;
}

.chart-data {
  margin-top: 14px;
  color: var(--app-muted);
  font-size: 12px;
}

.chart-data summary {
  cursor: pointer;
  color: var(--app-brand);
  font-weight: 700;
}

.table-scroll {
  max-height: 260px;
  margin-top: 10px;
  overflow: auto;
  border: 1px solid var(--app-border);
  border-radius: 6px;
}

table {
  width: 100%;
  border-collapse: collapse;
  background: var(--app-surface);
}

th,
td {
  padding: 9px 11px;
  text-align: left;
  border-bottom: 1px solid var(--app-border);
}

th {
  color: var(--app-muted);
  font-size: 11px;
  background: var(--app-surface-soft);
}

@media (max-width: 560px) {
  .chart-frame,
  .trend-svg {
    min-height: 240px;
    height: 240px;
  }
}
</style>
