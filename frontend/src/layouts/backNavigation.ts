/** 子页面返回动作：优先使用浏览器历史，缺失时回到角色首页。 */
export type BackAction =
  | { kind: 'history' }
  | { kind: 'route'; name: string }

export function backActionFor(
  currentPath: string,
  historyBack: string | null | undefined,
  homeRouteName: string,
): BackAction {
  if (historyBack && historyBack !== currentPath) return { kind: 'history' }
  return { kind: 'route', name: homeRouteName }
}

export function isSubpagePath(currentPath: string, homePath: string): boolean {
  return currentPath !== homePath
}

/**
 * 人工评分完成后的返回动作。
 *
 * 从结果页/提交详情点进评分页时，要回的目标通常就是进入前的那条历史。此时
 * 用 back() 弹出评分页，而不是 replace：replace 会把它替换成同一 URL，令
 * 历史栈出现两条连续相同记录，返回键会因 history.state.back === route.fullPath
 * 被误判为无历史而兜底跳角色首页。深链接直达（没有匹配历史）时才 replace。
 */
export function reviewReturnAction(
  targetPath: string,
  historyBack: string | null | undefined,
): 'history' | 'replace' {
  return historyBack === targetPath ? 'history' : 'replace'
}
