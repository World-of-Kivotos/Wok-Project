import type { ReactElement, ReactNode } from 'react'
import type { PixelControlSize } from './controlSize'
import { PIXEL_CONTROL_PADDING_CLASS, PIXEL_CONTROL_TEXT_CLASS } from './controlSize'
import type { PixelFrameTone } from './PixelFrame'
import { PixelFrame } from './PixelFrame'

/**
 * 小型语义标记: 星级/品质/状态三类用途共用同一个容器, 真正变化的只是 tone 与 children 里的内容
 * (星级传星号或 PixelIcon 组合, 品质/状态传文字), 不为三种用途各开一个组件。
 * 真源: conventions.md 十 · L1 · PixelBadge (冻结 props: tone/size/children)。
 *
 * 默认档取 sm 而不是 md: 徽标语境下常常与正文并排或多个并列出现 (如背包格右上角的品质角标),
 * md/lg 的默认内边距会把行高撑得比周围文字高一截, 与"标记"应有的分量不符。
 */

export interface PixelBadgeProps {
  tone?: PixelFrameTone
  size?: PixelControlSize
  children: ReactNode
  className?: string
}

const TONE_TEXT_CLASS: Record<PixelFrameTone, string> = {
  neutral: 'text-fg',
  accent: 'text-accent',
  success: 'text-success',
  warning: 'text-warning',
  danger: 'text-danger',
  info: 'text-info',
}

export function PixelBadge({
  tone = 'neutral',
  size = 'sm',
  children,
  className,
}: PixelBadgeProps): ReactElement {
  const baseClass = `inline-flex items-center gap-1 ${PIXEL_CONTROL_PADDING_CLASS[size]} ${PIXEL_CONTROL_TEXT_CLASS[size]} ${TONE_TEXT_CLASS[tone]}`
  return (
    <PixelFrame
      variant="panel"
      tone={tone}
      className={className === undefined ? baseClass : `${baseClass} ${className}`}
    >
      {children}
    </PixelFrame>
  )
}

/** 星级 / 品质 / 状态三类典型用法各一条, 供预览页与面板 agent 直接渲染。 */
export interface PixelBadgeDemoItem {
  readonly id: string
  readonly tone: PixelFrameTone
  readonly label: string
}

export const PIXEL_BADGE_DEMO_ITEMS: readonly PixelBadgeDemoItem[] = [
  { id: 'rarity-gold', tone: 'warning', label: '★★★★★' },
  { id: 'quality-fine', tone: 'success', label: '上乘' },
  { id: 'status-low-stock', tone: 'danger', label: '库存紧张' },
  { id: 'status-listed', tone: 'info', label: '在售中' },
]
