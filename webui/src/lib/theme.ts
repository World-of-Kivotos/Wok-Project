import { useCallback, useEffect, useState } from 'react'

/**
 * 亮暗主题切换。色板本体在 src/styles/index.css, 本模块只负责把选择写到根元素的类名上。
 *
 * 两处与常规网页做法不同, 都是被 MCEF 的运行环境逼出来的:
 *
 * 1. 默认档是暗色, 且暗色写在无类的 :root 上。游戏内 HUD 叠在 3D 画面上, 亮色是少数派;
 *    更要紧的是 initTheme() 之前只有样式表生效, 谁是默认谁就不会闪, 所以默认档必须是常用的那一档。
 *    两个类都显式设 (dark / light) 而不是只设一个, 是为了让 <html class="dark"> 这种写法与
 *    "无类即暗色"同时成立 —— 排障时看根元素类名就能确定当前档, 不用回头查默认值。
 *
 * 2. 不读 prefers-color-scheme。CEF 的系统主题信号取自宿主进程而非 MC 客户端设置, 与玩家在游戏里的
 *    观感无关; 拿它当初值等于让界面跟着 Windows 的深色开关跳, 而玩家根本没在那儿做过选择。
 */

export type Theme = 'dark' | 'light'

/**
 * 持久化键。带 wok- 前缀是因为 MCEF 的 localStorage 按 origin 隔离, 而本站与将来可能同源加载的
 * 其它页面 (wiki / 官网调试页) 共用一个 origin, 裸 "theme" 会互相覆盖。
 */
const STORAGE_KEY = 'wok-theme'

function readStored(): Theme {
  return localStorage.getItem(STORAGE_KEY) === 'light' ? 'light' : 'dark'
}

function apply(theme: Theme): void {
  const root = document.documentElement
  root.classList.toggle('dark', theme === 'dark')
  root.classList.toggle('light', theme === 'light')
}

/**
 * 在 React 渲染前调用 (main.tsx 入口), 防首屏闪烁。
 *
 * 刻意不做 try/catch: localStorage 在 MCEF 里被禁用会直接抛, 那是宿主配置问题, 必须当场炸出来 ——
 * 静默吞掉的结果是主题偏好每次启动都丢, 而没人知道为什么。
 */
export function initTheme(): void {
  apply(readStored())
}

export interface ThemeControl {
  theme: Theme
  toggle: () => void
}

export function useTheme(): ThemeControl {
  const [theme, setTheme] = useState<Theme>(readStored)

  useEffect(() => {
    apply(theme)
    localStorage.setItem(STORAGE_KEY, theme)
  }, [theme])

  const toggle = useCallback(() => {
    setTheme((current) => (current === 'dark' ? 'light' : 'dark'))
  }, [])

  return { theme, toggle }
}
