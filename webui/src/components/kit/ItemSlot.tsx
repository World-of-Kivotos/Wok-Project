import type { KeyboardEvent, ReactElement } from 'react'
import { useEffect, useRef, useState } from 'react'
import { ItemIcon, type ItemIconScale } from '@/components/ItemIcon'
import { formatAmount } from './Currency'

/**
 * MC 物品格。背包、共享背包、开箱奖池这类场景里的最小单元。
 *
 * 与普通按钮的区别只有一条但很要紧: 格子必须**始终占位**, 空格子也要画出边框。
 * 背包是网格布局, 空格子塌陷会让后面的物品整体前移, 表现为"点了一下背包里的东西全跳了"。
 */

export type ItemSlotScale = ItemIconScale

/** 格子外框边长。比图标本身大 8px, 留出内边距, 使物品不贴边。 */
const SLOT_SIZE_CLASS: Record<ItemSlotScale, string> = {
  1: 'size-10',
  2: 'size-14',
  3: 'size-18',
}

export interface ItemSlotProps {
  /** 缺省即空格子, 仍占位并画边框。 */
  itemId?: string | undefined
  /** 堆叠数。1 或缺省时不渲染角标 —— MC 的惯例是单个物品不显示数字。 */
  count?: number | undefined
  /** 物品显示名, 供读屏与悬停提示。 */
  label?: string | undefined
  selected?: boolean | undefined
  disabled?: boolean | undefined
  onClick?: (() => void) | undefined
  scale?: ItemSlotScale | undefined
  /** 网格键盘导航用: 只有当前焦点格是 0, 其余为 -1 (roving tabindex)。 */
  tabIndex?: number | undefined
  className?: string | undefined
}

export function ItemSlot({
  itemId,
  count,
  label,
  selected = false,
  disabled = false,
  onClick,
  scale = 1,
  tabIndex,
  className,
}: ItemSlotProps): ReactElement {
  const stateClass = disabled
    ? 'border-border bg-muted/30 opacity-56'
    : selected
      ? 'border-brand bg-brand/12 ring-2 ring-brand/32'
      : 'border-border bg-muted/40 hover:border-ring hover:bg-accent'

  return (
    <button
      aria-label={label ?? itemId ?? '空格子'}
      aria-pressed={selected}
      className={`relative flex shrink-0 items-center justify-center rounded-md border transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring ${SLOT_SIZE_CLASS[scale]} ${stateClass}${
        className === undefined ? '' : ` ${className}`
      }`}
      disabled={disabled}
      onClick={onClick}
      tabIndex={tabIndex}
      title={label ?? itemId}
      type="button"
    >
      {itemId === undefined ? null : <ItemIcon itemId={itemId} label={label ?? itemId} scale={scale} />}
      {count === undefined || count <= 1 ? null : (
        <span className="pointer-events-none absolute right-0.5 bottom-0.5 rounded-sm bg-background/80 px-1 font-medium text-[0.625rem] text-foreground tabular-nums leading-tight">
          {formatAmount(count)}
        </span>
      )}
    </button>
  )
}

// ============================================================
// 网格
// ============================================================

export interface ItemSlotGridEntry {
  itemId?: string | undefined
  count?: number | undefined
  label?: string | undefined
  disabled?: boolean | undefined
}

export interface ItemSlotGridProps {
  slots: readonly ItemSlotGridEntry[]
  columns: number
  /** 当前选中下标。缺省即无选中。 */
  selectedSlot?: number | undefined
  onSelect: (slot: number) => void
  scale?: ItemSlotScale | undefined
  /** 网格整体的无障碍名 (如"背包"/"共享背包")。 */
  label?: string | undefined
  className?: string | undefined
}

/** 四个方向键换算成数组下标位移; 其余按键交还给浏览器 (Tab / Enter 等)。 */
function arrowStep(key: string, columns: number): number | null {
  switch (key) {
    case 'ArrowRight':
      return 1
    case 'ArrowLeft':
      return -1
    case 'ArrowDown':
      return columns
    case 'ArrowUp':
      return -columns
    default:
      return null
  }
}

export function ItemSlotGrid({
  slots,
  columns,
  selectedSlot,
  onSelect,
  scale = 1,
  label,
  className,
}: ItemSlotGridProps): ReactElement {
  const containerRef = useRef<HTMLDivElement>(null)

  /*
   * roving tabindex: 整个网格在 Tab 序里只占一站, 内部靠方向键移动。
   * 逐格可 Tab 的话, 一个 36 格背包要按 36 次 Tab 才能离开, 键盘用户实际上被困在里面。
   *
   * 焦点位置必须是本组件的**内部状态**, 不能直接取 selectedSlot。
   * 理由: 调用方常常拒绝某些格子的选中 (挂单页对空格子的 onSelect 直接 return)。
   * 焦点若跟着 selectedSlot 走, 方向键碰到第一个空格就再也推不动 —— 键盘用户被空格挡在原地,
   * 走不到后面的物品。焦点是"我在看哪一格", 选中是"我要哪一格", 两者本就不是一回事。
   */
  const [focusIndex, setFocusIndex] = useState(() => selectedSlot ?? 0)

  // 调用方从外部改选中 (如点了别处的"选中第一件可卖的") 时, 焦点跟过去; 反向不成立。
  useEffect(() => {
    if (selectedSlot !== undefined) {
      setFocusIndex(selectedSlot)
    }
  }, [selectedSlot])

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>): void {
    const step = arrowStep(event.key, columns)
    if (step === null) {
      return
    }
    const next = focusIndex + step
    if (next < 0 || next >= slots.length) {
      return
    }
    event.preventDefault()
    setFocusIndex(next)
    // 焦点先落到目标格再通知调用方: 调用方可能拒绝这次选中, 但焦点必须已经动了。
    // 按下标取按钮而不是维护一张 ref 表 —— 本容器的直接子元素只有格子按钮, 顺序即下标。
    containerRef.current?.querySelectorAll('button')[next]?.focus()
    onSelect(next)
  }

  return (
    <div
      aria-label={label}
      className={`grid gap-1${className === undefined ? '' : ` ${className}`}`}
      onKeyDown={handleKeyDown}
      ref={containerRef}
      role="group"
      style={{ gridTemplateColumns: `repeat(${String(columns)}, min-content)` }}
    >
      {slots.map((entry, index) => (
        <ItemSlot
          // 整体展开而不是逐个 itemId={entry.itemId} 传: exactOptionalPropertyTypes 下, 逐个传会把
          // "这个键不存在"变成"这个键的值是 undefined", 而后者不满足 itemId?: string。展开保留可选性。
          {...entry}
          // 格子下标就是它的身份: 空格子没有 itemId, 相邻两个同种物品的 itemId 也相同, 两者都不能当 key。
          key={index}
          onClick={() => {
            onSelect(index)
          }}
          scale={scale}
          selected={selectedSlot === index}
          tabIndex={index === focusIndex ? 0 : -1}
        />
      ))}
    </div>
  )
}
