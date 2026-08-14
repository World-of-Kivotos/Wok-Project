import { useEffect, useState } from 'react'
import type { JobStatUnit } from '../../../lib/types'
import { nowMs } from '../../../mock'

/**
 * 八个职业面板共用的极小工具集, 以及它们必须逐字照抄的排版骨架约定。
 *
 * 工具集抽出来的理由只有一条: 冷却倒计时的展示口径 (取整方式/"已就绪"文案) 与 JobStatLine 数值单位的
 * 格式化规则若在各个文件里各写一遍, 面板就会长出好几种数字观感, 与本设计系统"同一屏用同一套语言"的
 * 前提冲突。这里只沉淀纯展示逻辑, 不碰任何业务规则或世界状态写入 —— 那些仍归各自面板处理。
 *
 * tick -> 本地到期时刻的折算 (MS_PER_TICK / tickDeadline) **不在这里**, 在 @/hooks/use-live-updates:
 * 矿洞页与婚姻页也要用同一份折算, 而它们不是职业面板。
 *
 * 排版骨架 (八个面板是玩家来回切换的兄弟页, 任一处分区结构/字号/间距不一致都会立刻显形):
 *   - 根容器恒为 <div className="flex flex-col gap-4">;
 *   - 首个分区恒为 <Panel title="{职业名}">, 内含 grid grid-cols-3 gap-4 的 <Stat>, 第一格恒为"职业等级";
 *     契约缺口说明紧跟在 Stat 网格下方, 用 <p className="text-muted-foreground text-xs">;
 *   - 其余每个分区一律 <Panel title="...">, 分区内纵向排布统一 flex flex-col gap-3;
 *   - 分区内的小标题用 <h3 className="font-medium text-foreground text-sm">, 不自造第三级标题;
 *   - 未达成的等级门用 <Surface tone="warning">, 写操作回执用 <FeedbackAlert>;
 *   - 页名由 TabletShell 按 ROUTE_TITLES 统一渲染, 面板内不再画一遍页名。
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
 * JobStatLine.unit 的展示格式化, 与量纲无关的纯排版逻辑 —— 数值本身一律信任服务端/mock 给的原值,
 * 不在这里做任何四舍五入之外的加工。default 分支理论不可达 (union 已在下方各 case 穷举), 保留它只是
 * 为满足 noImplicitReturns, 不是给未知量纲一个静默兜底。
 *
 * 入参是 job.* 系列共用的 JobStatUnit。铸甲师的护甲特效数值走的是另一套量纲词表 (EngineerStatUnit,
 * 多一档 'count' 且没有 multiplier/blocks/credit), 它在 EngineerPanel 内自带格式化函数 ——
 * 两个互不相交的量纲词表塞进同一个 switch 只会让"这个 unit 属于哪一组"变成读码才知道的事。
 */
export function formatStatValue(value: number, unit: JobStatUnit): string {
  switch (unit) {
    case 'percent':
      return `${(value * 100).toFixed(1)}%`
    case 'multiplier':
      return `x${value.toFixed(2)}`
    case 'seconds':
      return `${String(value)}s`
    // 服务端只有 game tick 这一种时间量纲 (20 tick = 1s), 折算成秒是纯展示。
    case 'ticks':
      return `${(value / 20).toFixed(1)}s`
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
