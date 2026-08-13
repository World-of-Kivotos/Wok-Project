import type { KeyboardEvent, ReactElement } from 'react'
import { useRef } from 'react'
import type { PixelSlotScale } from './PixelSlot'
import { PixelSlot } from './PixelSlot'

/**
 * 9xN 背包网格: 把一组槽位排成矩形并接管方向键导航。真源: conventions.md 十, PixelSlotGrid 行。
 *
 * `slots` 是稠密数组 (每个格子, 含空槽, 都要有一条记录) 而不是"只列出非空槽位": 网格要先知道总格数
 * 才能排布, 空槽同样是合法的落点 (放物品要落在空格上)。数组下标即几何位置 (行优先, 由 columns 折行),
 * `entry.slot` 只是服务端槽位号, 两者语义不同, 不要混用。
 *
 * 方向键导航靠事件委托而非给每个 PixelSlot 塞一个仅供网格使用的 onKeyDown: keydown 天然从被聚焦的
 * 按钮冒泡到容器, 委托到容器只需一个监听器, 也避免了往 PixelSlot 的冻结 props 表外新增回调。
 * 当前聚焦的格子靠 cellRefs 数组反查下标 (indexOf), 不额外维护一份"当前下标"状态——
 * 那会与 selectedSlot 形成两份可能不同步的真相, 违反 conventions 4.2 的受控原则。
 */

export interface PixelSlotGridEntry {
  slot: number
  itemId?: string
  count?: number
  label?: string
}

export interface PixelSlotGridProps {
  slots: readonly PixelSlotGridEntry[]
  columns: number
  selectedSlot?: number
  onSelect: (slot: number) => void
  scale?: PixelSlotScale
  /** 网格整体的无障碍名 (如"背包"/"共享背包"), 用于 role=group 的 aria-label。 */
  label?: string
  className?: string
}

/** 四个方向键换算成数组下标位移; 其余按键交还给浏览器与 PixelSlot 自身处理 (Tab/Enter 等)。 */
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

export function PixelSlotGrid({
  slots,
  columns,
  selectedSlot,
  onSelect,
  scale = 1,
  label,
  className,
}: PixelSlotGridProps): ReactElement {
  const cellRefs = useRef<(HTMLButtonElement | null)[]>([])

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>): void {
    const step = arrowStep(event.key, columns)
    if (step === null) {
      return
    }
    const activeIndex = cellRefs.current.indexOf(event.target as HTMLButtonElement)
    if (activeIndex < 0) {
      return
    }
    // 行首/行尾拦住左右环绕: 不拦的话 ArrowLeft 在第一列会跳到上一行末尾, 打破"从左到右"的直觉。
    const atRowStart = activeIndex % columns === 0
    const atRowEnd = activeIndex % columns === columns - 1
    if ((event.key === 'ArrowLeft' && atRowStart) || (event.key === 'ArrowRight' && atRowEnd)) {
      return
    }
    const nextIndex = activeIndex + step
    const nextEntry = slots[nextIndex]
    if (nextEntry === undefined) {
      return
    }
    // 方向键 (尤其 ArrowUp/Down) 在此处等价于原生的页面滚动键, 必须挡住默认行为, 移动语义全由本组件接管。
    event.preventDefault()
    cellRefs.current[nextIndex]?.focus()
    onSelect(nextEntry.slot)
  }

  const activeIndex =
    selectedSlot === undefined ? -1 : slots.findIndex((entry) => entry.slot === selectedSlot)
  // roving tabIndex 落到当前选中格; 没有选中项时落到第一格, 保证 Tab 总能进入网格而不是整体被跳过。
  const rovingIndex = activeIndex < 0 ? 0 : activeIndex

  const gridClassName = className === undefined ? 'grid gap-1' : `grid gap-1 ${className}`

  return (
    <div
      role="group"
      aria-label={label}
      className={gridClassName}
      style={{ gridTemplateColumns: `repeat(${String(columns)}, max-content)` }}
      onKeyDown={handleKeyDown}
    >
      {slots.map((entry, index) => (
        <PixelSlot
          key={entry.slot}
          ref={(element) => {
            cellRefs.current[index] = element
          }}
          // exactOptionalPropertyTypes 下 itemId?: string 不接受显式 undefined, 稀疏字段一律按
          // conventions.md 十二-1 的展开写法传, 缺席即整个不传这个键 (而不是传 itemId: undefined)。
          {...(entry.itemId === undefined ? {} : { itemId: entry.itemId })}
          {...(entry.count === undefined ? {} : { count: entry.count })}
          {...(entry.label === undefined ? {} : { label: entry.label })}
          selected={entry.slot === selectedSlot}
          scale={scale}
          tabIndex={index === rovingIndex ? 0 : -1}
          onClick={() => {
            onSelect(entry.slot)
          }}
        />
      ))}
    </div>
  )
}

/** 组件预览页/面板 agent 复用的示例数据: 27 格 (3 行 x 9 列 —— 与 MC 背包同宽), 穿插占用与空格。 */
const DEMO_POPULATED_SLOTS: Readonly<Record<number, { itemId: string; count: number; label: string }>> = {
  0: { itemId: 'minecraft:diamond', count: 12, label: '钻石' },
  3: { itemId: 'minecraft:iron_ingot', count: 64, label: '铁锭' },
  4: { itemId: 'minecraft:emerald', count: 3, label: '绿宝石' },
  9: { itemId: 'minecraft:bread', count: 1, label: '面包' },
  15: { itemId: 'minecraft:oak_log', count: 32, label: '橡木原木' },
}

export const PIXEL_SLOT_GRID_DEMO_COLUMNS = 9

export const PIXEL_SLOT_GRID_DEMO_SLOTS: readonly PixelSlotGridEntry[] = Array.from(
  { length: 27 },
  (_placeholder, index) => {
    const populated = DEMO_POPULATED_SLOTS[index]
    return populated === undefined ? { slot: index } : { slot: index, ...populated }
  },
)
