/**
 * tokenStore 的 sessionStorage 持久化测试。
 *
 * 契约 §3.2 修订后：refreshToken 允许存 sessionStorage（tab 关闭即清），
 * access token 与过期时刻仍只放内存。restore() 模拟刷新页面后的读回。
 */
import { beforeEach, describe, expect, it } from 'vitest'

import {
  clearTokens,
  getRefreshToken,
  restore,
  rotateTokens,
  setTokens,
} from '@/api/tokenStore'

const STORAGE_KEY = 'is.refresh'

describe('tokenStore sessionStorage 持久化', () => {
  beforeEach(() => {
    sessionStorage.clear()
    clearTokens()
  })

  it('setTokens 写入 refreshToken 到 sessionStorage，access token 不落盘', () => {
    setTokens({ accessToken: 'at-1', refreshToken: 'rt-1', expiresIn: 900 })

    expect(sessionStorage.getItem(STORAGE_KEY)).toBe('rt-1')
    expect(getRefreshToken()).toBe('rt-1')
  })

  it('受限会话（无 refresh token）不写盘且清空旧值', () => {
    setTokens({ accessToken: 'at-0', refreshToken: 'rt-0', expiresIn: 900 })
    setTokens({ accessToken: 'at-1', refreshToken: null, expiresIn: 300 })

    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull()
    expect(getRefreshToken()).toBeNull()
  })

  it('rotateTokens 轮换后 sessionStorage 同步为新值', () => {
    setTokens({ accessToken: 'at-1', refreshToken: 'rt-1', expiresIn: 900 })
    rotateTokens({ accessToken: 'at-2', refreshToken: 'rt-2', expiresIn: 900 })

    expect(sessionStorage.getItem(STORAGE_KEY)).toBe('rt-2')
  })

  it('clearTokens 清空内存与 sessionStorage', () => {
    setTokens({ accessToken: 'at-1', refreshToken: 'rt-1', expiresIn: 900 })
    clearTokens()

    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull()
    expect(getRefreshToken()).toBeNull()
  })

  it('restore 把 sessionStorage 里的 refreshToken 读回内存（模拟刷新页面）', () => {
    sessionStorage.setItem(STORAGE_KEY, 'rt-saved')

    expect(getRefreshToken()).toBeNull()
    restore()

    expect(getRefreshToken()).toBe('rt-saved')
  })
})
