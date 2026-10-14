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
