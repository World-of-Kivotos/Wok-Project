import type { KeyboardEvent, ReactElement, ReactNode } from 'react'
import { useId, useState } from 'react'
import { PixelFrame } from './PixelFrame'

/**
 * 悬停详情浮层, MC 风格深底描边。真源: conventions.md 十 · L1 · PixelTooltip
 * (冻结 props: content/children/placement, 必须同时响应 hover 与 focus)。
 *
 * 浮层容器固定用 PixelFrame variant="window": conventions.md 十把 window 定义为"外凸窗口
 * (平板/弹窗)", 悬浮详情本质是叠在内容之上的独立层, 与弹窗同源, 复用同一档而不是新开一档 tone/variant。
 *
 * 触发元素包一层可聚焦的 span 而不是要求调用方自带焦点: children 可以是任意 ReactNode (文字/图标/
 * 徽标), 多数场景下都不是原生可聚焦元素, 不由本组件兜底 tabIndex 的话键盘用户在 MCEF 里永远够不到
 * 这条浮层——原生 title 属性明确被禁 (九-9), 提示只能走这里。
 */

export type PixelTooltipPlacement = 'top' | 'bottom' | 'left' | 'right'

export interface PixelTooltipProps {
  content: ReactNode
  children: ReactNode
  /** 默认 top。 */
  placement?: PixelTooltipPlacement
  className?: string
}

const PLACEMENT_CLASS: Record<PixelTooltipPlacement, string> = {
  top: 'bottom-full left-0 mb-2',
  bottom: 'top-full left-0 mt-2',
  left: 'right-full top-0 mr-2',
  right: 'left-full top-0 ml-2',
}

export function PixelTooltip({
  content,
  children,
  placement = 'top',
  className,
}: PixelTooltipProps): ReactElement {
  const tooltipId = useId()
  const [hovered, setHovered] = useState(false)
  const [focused, setFocused] = useState(false)
  // Esc 单独记一档而不是直接清 hovered/focused: 光标可能仍停在触发元素上, 若靠 hovered=false 关闭,
  // 下一次 mousemove (哪怕是同一位置的抖动) 会立刻把它判成"重新进入"而弹回来, 用户按了 Esc 却关不掉。
  const [dismissed, setDismissed] = useState(false)

  const open = (hovered || focused) && !dismissed

  const handleKeyDown = (event: KeyboardEvent<HTMLSpanElement>): void => {
    if (event.key === 'Escape' && open) {
      setDismissed(true)
    }
  }

  return (
    <span
      className={
        className === undefined
          ? 'relative inline-block border-2 border-transparent outline-none focus-visible:border-border-strong'
          : `relative inline-block border-2 border-transparent outline-none focus-visible:border-border-strong ${className}`
      }
      tabIndex={0}
      aria-describedby={open ? tooltipId : undefined}
      onMouseEnter={() => {
        setHovered(true)
        setDismissed(false)
      }}
      onMouseLeave={() => {
        setHovered(false)
      }}
      onFocus={() => {
        setFocused(true)
        setDismissed(false)
      }}
      onBlur={() => {
        setFocused(false)
      }}
      onKeyDown={handleKeyDown}
    >
      {children}
      {open ? (
        <span
          id={tooltipId}
          role="tooltip"
          className={`pointer-events-none absolute z-10 block max-w-64 ${PLACEMENT_CLASS[placement]}`}
        >
          <PixelFrame variant="window" className="block p-2 text-1x text-fg">
            {content}
          </PixelFrame>
        </span>
      ) : null}
    </span>
  )
}

export interface PixelTooltipDemoItem {
  readonly id: string
  readonly trigger: string
  readonly content: string
  readonly placement: PixelTooltipPlacement
}

/** 四个方位各一条, 供预览页与面板 agent 直接复用。 */
export const PIXEL_TOOLTIP_DEMO_ITEMS: readonly PixelTooltipDemoItem[] = [
  { id: 'durability', trigger: '耐久 42/100', content: '低于 20 时装备将出现磨损警示。', placement: 'top' },
  { id: 'listing-fee', trigger: '手续费 4%', content: '上架即扣, 撤单不退还。', placement: 'bottom' },
  { id: 'azure-locked', trigger: '青辉石', content: '不可转移, 仅限本账号消费。', placement: 'right' },
  { id: 'base-value', trigger: '基准价 320', content: '无管理员锚定值时按市场均价估算。', placement: 'left' },
]
