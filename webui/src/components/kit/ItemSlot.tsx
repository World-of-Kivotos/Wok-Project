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
  /**
   * NBT 变体件的贴图选择码, 由服务端随物品下发。缺省即普通物品。
   * 不传的话枪匠零件那 195 种变体会画成同一张图, 见 ItemIcon 的同名 prop。
   */
  customModelData?: number | undefined
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
  customModelData,
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

  /*
   * 按压反馈只缩 3%, 且刻意不配悬停缩放。
   *
   * 频率决定幅度: 挂单页一屏就是几十格, 选货时连点是常态。这个频率下动效要传达的只有"这一下点到了"
   * 一条信息, 幅度再大或者多一层悬停缩放, 就从反馈变成了每次经过都要重看一遍的噪音。
   *
   * 缩放连 ItemIcon 一起缩, 没有给图标做反向补偿。图标是 16x16 位图按整数倍放大, 0.97 确实是非整数
   * 缩放, 但 image-rendering: pixelated 走最近邻取样 —— 代价是某一列像素窄一格, 不是糊, 且松手即回到
   * 整数倍。反向补偿则要在图标上写死 1/0.97 这个与外框耦合的倒数, 日后有人调了外框倍率而漏改它,
   * 图标就**永久**偏离整数像素网格 (src/components/ItemIcon.tsx 为此专门删掉过一个档位), 那是比
   * 120ms 瞬态严重得多的失败模式。
   *
   * transition-property 只能有一条声明生效 (后写的整条覆盖前一条), 故与原 transition-colors 合并;
   * 这里列出的三个颜色属性就是 stateClass 实际会切的全部, ring 走 box-shadow 本来也不在 colors 里。
   *
   * 闸门看的是 onClick 而不只是 disabled: 本组件的 onClick 是可选 prop, 于是存在一整类"既不 disabled
   * 也不可点"的纯展示格子 (开箱条带的奖池格、皮肤图鉴、收件箱待领物品、军火商零件展示)。只按 disabled
   * 过滤的话, 这些格子按下去会缩 3% 却什么都不发生 —— 那是在给不可点的东西做出可点的暗示, 比没有反馈更糟。
   */
  const pressable = onClick !== undefined && !disabled

  return (
    <button
      aria-label={label ?? itemId ?? '空格子'}
      aria-pressed={selected}
      className={`relative flex shrink-0 items-center justify-center rounded-md border transition-[color,background-color,border-color,scale] duration-(--duration-press) ease-out-soft outline-none focus-visible:ring-2 focus-visible:ring-ring ${
        pressable ? 'active:scale-97 motion-reduce:active:scale-99' : ''
      } ${SLOT_SIZE_CLASS[scale]} ${stateClass}${
        className === undefined ? '' : ` ${className}`
      }`}
      disabled={disabled}
      onClick={onClick}
      tabIndex={tabIndex}
      title={label ?? itemId}
      type="button"
    >
      {itemId === undefined ? null : (
        <ItemIcon
          customModelData={customModelData}
          itemId={itemId}
          label={label ?? itemId}
          scale={scale}
        />
      )}
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
  customModelData?: number | undefined
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
