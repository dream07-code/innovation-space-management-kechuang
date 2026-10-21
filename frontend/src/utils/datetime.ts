/**
 * 统一时间展示。
 *
 * 后端返回带微秒的 ISO 8601（如 2026-10-21T16:23:45.616641+08:00），
 * 表格里直接渲染会换行且难读，这里统一截到分钟。
 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}
