import type { ReactElement, Ref } from 'react'
import { ItemIcon } from '../ItemIcon'
import type { PixelFrameTone } from './PixelFrame'
import { PixelFrame } from './PixelFrame'

/**
 * 物品格: 背包/交易/工作台等一切"一格放一件物品"场景的最小单元。真源: conventions.md 十, PixelSlot 行。
 *
 * 底用 PixelFrame variant="inset" 而不是自画边框: 物品格在原版 MC 里天然是"陷进面板里的一个凹槽",
 * inset 资产的斜面方向正对应这个视觉预期, 换成 window/panel 会看着像一个独立按钮而不是容器里的格子。
 *
 * 有 onClick 与没有 onClick 是两种不同的无障碍语义, 因此按需渲染 <button> 或 <div role="img">:
 * 前者原生具备 Tab/Enter/Space/disabled, 后者不该出现在 Tab 顺序里 (它只是个展示格, 不是控件)。
 * 两种情形都把 ItemIcon 与数量角标包进一个 aria-hidden 的直通层再由外层元素统一给一个 aria-label——
 * ItemIcon 自己也会独立暴露 role=img/alt, 不隔开会在同一个格子上被读屏念两遍名字。
 */

export type PixelSlotScale = 1 | 2 | 3

/** 与 ItemIcon/PixelIcon 同构但各自独立声明 (conventions 2.1): 16 源像素的整数倍取格, 避免半像素。 */
const SCALE_CLASS: Record<PixelSlotScale, string> = {
  1: 'h-16 w-16',
  2: 'h-32 w-32',
  3: 'h-48 w-48',
}

/** 空槽位的无障碍名。itemId 缺席时没有任何领域值可读, 只能给一句结构性描述, 不是业务文案字典。 */
const EMPTY_SLOT_LABEL = '空槽位'

export interface PixelSlotProps {
  itemId?: string
  count?: number
  label?: string
  selected?: boolean
  disabled?: boolean
  onClick?: () => void
  scale?: PixelSlotScale
  className?: string
  /** 供 PixelSlotGrid 做 roving tabIndex; 单独使用时不必传, 落回原生 Tab 顺序。 */
  tabIndex?: number
  /** 供 PixelSlotGrid 反查每个格子的按钮节点以便 focus() 跳转; 无 onClick 时组件不产出可聚焦元素, 挂不上。 */
  ref?: Ref<HTMLButtonElement>
}

/** 堆叠数为 1 (或缺省) 时不贴数字, 沿用 MC 原版习惯, 避免每格都糊一个多余的"1"。 */
function shouldShowCount(itemId: string | undefined, count: number | undefined): count is number {
  return itemId !== undefined && count !== undefined && count > 1
}

function buildAccessibleName(itemId: string | undefined, label: string | undefined, count: number | undefined): string {
  if (itemId === undefined) {
    return EMPTY_SLOT_LABEL
  }
  const name = label ?? itemId
  return count === undefined || count <= 1 ? name : `${name} x${count}`
}

export function PixelSlot({
  itemId,
  count,
  label,
  selected = false,
  disabled = false,
  onClick,
  scale = 1,
  className,
  tabIndex,
  ref,
}: PixelSlotProps): ReactElement {
  const sizeClass = SCALE_CLASS[scale]
  const tone: PixelFrameTone = !disabled && selected ? 'accent' : 'neutral'
  const interactive = onClick !== undefined
  const showCount = shouldShowCount(itemId, count)
  const accessibleName = buildAccessibleName(itemId, label, count)
  const countTextClass = disabled ? 'text-muted' : 'text-fg'
  const frameClassName = className === undefined ? sizeClass : `${sizeClass} ${className}`

  const content = (
    // 直通层: 只负责把 ItemIcon 与角标从读屏树里摘掉, display:contents 保证不额外占布局盒。
    <span aria-hidden="true" className="contents">
      {itemId === undefined ? null : <ItemIcon itemId={itemId} label={label ?? itemId} scale={scale} />}
      {showCount ? (
        <span className={`absolute bottom-0 right-0 bg-bg px-1 text-1x ${countTextClass}`}>{count}</span>
      ) : null}
    </span>
  )

  if (!interactive) {
    return (
      <PixelFrame variant="inset" tone={tone} className={`${frameClassName} flex items-center justify-center`}>
        <div
          role="img"
          aria-label={accessibleName}
          className="relative flex h-full w-full items-center justify-center"
        >
          {content}
        </div>
      </PixelFrame>
    )
  }

  return (
    <PixelFrame variant="inset" tone={tone} className={`${frameClassName} flex items-center justify-center`}>
      <button
        ref={ref}
        type="button"
        tabIndex={tabIndex}
        disabled={disabled}
        aria-pressed={selected}
        aria-label={accessibleName}
        className="relative flex h-full w-full items-center justify-center border-2 border-transparent outline-none hover:bg-raised focus-visible:border-border-strong"
        onClick={onClick}
      >
        {content}
      </button>
    </PixelFrame>
  )
}

/** 组件预览页/面板 agent 复用的示例数据: 覆盖占用/多堆叠/禁用/空槽四种视觉态。 */
export interface PixelSlotDemoEntry {
  itemId?: string
  count?: number
  label?: string
  selected?: boolean
  disabled?: boolean
}

export const PIXEL_SLOT_DEMO_ENTRIES: readonly PixelSlotDemoEntry[] = [
  { itemId: 'minecraft:diamond', count: 12, label: '钻石' },
  { itemId: 'minecraft:iron_ingot', count: 64, label: '铁锭', selected: true },
  { itemId: 'minecraft:bread', count: 1, label: '面包' },
  { itemId: 'minecraft:emerald', count: 3, label: '绿宝石', disabled: true },
  {},
]
