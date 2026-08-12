import { useEffect, useState } from 'react'
import { nowMs } from '../../../mock'
import type { PlannedStatUnit } from '../../../mock'

/**
 * 战斗/特殊职业四个面板 (塔罗/特勤/军火商/工程师) 共用的极小工具集。
 *
 * 抽出来的理由只有一条: 冷却倒计时的展示口径 (取整方式/"已就绪"文案) 与 PlannedStatLine 数值单位的
 * 格式化规则若在四个文件里各写一遍, 四个面板会长出四种数字观感, 与本设计系统"同一屏用同一套语言"的
 * 前提冲突。这里只沉淀纯展示逻辑, 不碰任何业务规则或世界状态写入 —— 那些仍归各自面板处理。
 */

/** 高频倒计时展示 (扫描 CD / 牌冷却 / 悬赏过期) 需要的活体时钟; 默认每秒刷新一次。 */
export function useLiveNow(intervalMs = 1000): number {
  const [now, setNow] = useState(nowMs)
  useEffect(() => {
    const timer = window.setInterval(() => {
      setNow(nowMs())
    }, intervalMs)
    return () => {
      window.clearInterval(timer)
    }
  }, [intervalMs])
  return now
}

/** targetMs 已过去时返回"已就绪", 否则按 分:秒 显示剩余时长 (向上取整, 避免显示 0:00 却仍不可用)。 */
export function formatCountdown(targetMs: number, now: number): string {
  const remainMs = targetMs - now
  if (remainMs <= 0) {
    return '已就绪'
  }
  const totalSeconds = Math.ceil(remainMs / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${String(minutes)}:${seconds.toString().padStart(2, '0')}`
}

/**
 * PlannedStatLine.unit 的展示格式化, 与量纲无关的纯排版逻辑 —— 数值本身一律信任服务端/mock 给的原值,
 * 不在这里做任何四舍五入之外的加工。default 分支理论不可达 (union 已在下方五个 case 穷举), 保留它只是
 * 为满足 noImplicitReturns, 不是给未知量纲一个静默兜底。
 */
export function formatStatValue(value: number, unit: PlannedStatUnit): string {
  switch (unit) {
    case 'percent':
      return `${(value * 100).toFixed(1)}%`
    case 'seconds':
      return `${String(value)}s`
    case 'blocks':
      return `${String(value)} 格`
    case 'credit':
      return String(value)
    case 'flat':
      return String(value)
    default:
      return String(value)
  }
}

export function toError(value: unknown): Error {
  return value instanceof Error ? value : new Error(String(value))
}
