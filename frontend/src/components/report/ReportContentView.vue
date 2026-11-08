<script setup lang="ts">
/**
 * 报告正文的只读展示：正文各段 + 笔记图片 + 相关链接。
 *
 * 批阅页与详情抽屉共用，保证「学生写的」在两处看起来一致。图片按 anchor
 * 跟在对应段落后面（end 归到文末），与学生的编排意图一致。
 */
import { computed } from 'vue'

import ReportImageGallery from '@/components/report/ReportImageGallery.vue'
import type { ReportKind } from '@/components/report/reportRubric'
import type { DailyReport, ReportImage, WeeklyReport } from '@/api/types'

const props = defineProps<{
  report: DailyReport | WeeklyReport
  kind: ReportKind
}>()

const daily = computed(() => (props.kind === 'DAILY' ? (props.report as DailyReport) : null))
const weekly = computed(() => (props.kind === 'WEEKLY' ? (props.report as WeeklyReport) : null))

/** 按 anchor 取图片：图片跟在它对应的段落后面。 */
function imagesFor(anchor: ReportImage['anchor']): ReportImage[] {
  return (props.report.images ?? []).filter((item) => item.anchor === anchor)
}
</script>

<template>
  <div class="report-content">
    <template v-if="daily">
      <div class="report-content__field">
        <span class="report-content__label">学习内容</span>
        <p class="report-content__text" data-test="learning-content">{{ daily.learningContent || '—' }}</p>
        <ReportImageGallery :images="imagesFor('learningContent')" />
      </div>
      <div class="report-content__field">
        <span class="report-content__label">学习收获</span>
        <p class="report-content__text" data-test="gains">{{ daily.gains || '—' }}</p>
        <ReportImageGallery :images="imagesFor('gains')" />
      </div>
      <div class="report-content__field">
        <span class="report-content__label">遇到的问题</span>
        <p class="report-content__text">{{ daily.problems || '—' }}</p>
        <ReportImageGallery :images="imagesFor('problems')" />
      </div>
      <div v-if="daily.nextPlan" class="report-content__field">
        <span class="report-content__label">下一步计划</span>
        <p class="report-content__text">{{ daily.nextPlan }}</p>
      </div>
    </template>

    <template v-else-if="weekly">
      <div class="report-content__field">
        <span class="report-content__label">本周总结</span>
        <p class="report-content__text" data-test="summary">{{ weekly.summary || '—' }}</p>
        <ReportImageGallery :images="imagesFor('summary')" />
      </div>
      <div class="report-content__field">
        <span class="report-content__label">主要成果</span>
        <p class="report-content__text" data-test="achievements">{{ weekly.achievements || '—' }}</p>
        <ReportImageGallery :images="imagesFor('achievements')" />
      </div>
      <div class="report-content__field">
        <span class="report-content__label">反思</span>
        <p class="report-content__text">{{ weekly.reflections || '—' }}</p>
        <ReportImageGallery :images="imagesFor('reflections')" />
      </div>
      <div v-if="weekly.nextWeekPlan" class="report-content__field">
        <span class="report-content__label">下周计划</span>
        <p class="report-content__text">{{ weekly.nextWeekPlan }}</p>
        <ReportImageGallery :images="imagesFor('nextWeekPlan')" />
      </div>
    </template>

    <div v-if="imagesFor('end').length" class="report-content__field">
      <span class="report-content__label">笔记图片</span>
      <ReportImageGallery :images="imagesFor('end')" />
    </div>

    <div v-if="report.links.length" class="report-content__field">
      <span class="report-content__label">相关链接</span>
      <ul class="report-content__links">
        <li v-for="link in report.links" :key="link">
          <a :href="link" target="_blank" rel="noopener noreferrer">{{ link }}</a>
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.report-content__field + .report-content__field {
  margin-top: 14px;
}

.report-content__label {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin-bottom: 4px;
}

.report-content__text {
  margin: 0;
  padding: 8px 12px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
}

.report-content__links {
  margin: 0;
  padding-left: 18px;
  word-break: break-all;
}
</style>
