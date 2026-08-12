import type { ReactElement, ReactNode } from 'react'
import { Badge } from '@/components/ui/badge'
import type { Tone } from './tokens'

/**
 * 标签-数值对。业务页里到处都是的"等级 12""在线 34/80""手续费 4%"这类只读展示。
 *
 * 收成组件的理由不是省字数, 是对齐: 手写时标签与数值的字号/颜色/间距每处都可能差一档,
 * 而它们往往并排出现在同一行里, 差一档立刻显形。
 */

export interface StatProps {
  label: string
  /** 数值。允许放 Currency / Badge 这类元素, 不限于字符串。 */
  value: ReactNode
  /** 数值下方的一行小字说明。 */
  hint?: string | undefined
  /** 横排 (标签在左、值在右, 用于紧凑信息行) 还是竖排 (标签在上, 用于卡片里的关键指标)。默认竖排。 */
  layout?: 'stacked' | 'inline' | undefined
  className?: string | undefined
}

export function Stat({ label, value, hint, layout = 'stacked', className }: StatProps): ReactElement {
  if (layout === 'inline') {
    return (
      <div
        className={`flex items-baseline justify-between gap-3 text-sm${
          className === undefined ? '' : ` ${className}`
        }`}
      >
        <span className="text-muted-foreground">{label}</span>
        <span className="text-right font-medium text-foreground tabular-nums">{value}</span>
      </div>
    )
  }

  return (
    <div className={`flex flex-col gap-1${className === undefined ? '' : ` ${className}`}`}>
      <span className="text-muted-foreground text-xs">{label}</span>
      <span className="font-medium text-base text-foreground tabular-nums">{value}</span>
      {hint === undefined ? null : <span className="text-muted-foreground text-xs">{hint}</span>}
    </div>
  )
}

// ============================================================
// 语义徽标
// ============================================================

const TONE_BADGE_VARIANT: Record<
  Tone,
  'secondary' | 'default' | 'success' | 'warning' | 'error' | 'info'
> = {
  neutral: 'secondary',
  brand: 'default',
  success: 'success',
  warning: 'warning',
  danger: 'error',
  info: 'info',
}

export interface TagProps {
  tone?: Tone | undefined
  size?: 'sm' | 'default' | 'lg' | undefined
  children: ReactNode
  className?: string | undefined
}

/**
 * 状态标记。叫 Tag 而不是 Badge, 是为了与 Coss 的 Badge 区分 —— 后者是原语, 这个是本项目
 * 按 Tone 词汇收敛过的档位, 业务页只该用这一个。
 */
export function Tag({ tone = 'neutral', size = 'default', children, className }: TagProps): ReactElement {
  return (
    <Badge className={className} size={size} variant={TONE_BADGE_VARIANT[tone]}>
      {children}
    </Badge>
  )
}
