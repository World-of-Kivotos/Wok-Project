import type { KeyboardEvent, ReactElement, ReactNode } from 'react'
import { useState } from 'react'
import type { PixelIconName } from './PixelIcon'
import { PixelIcon } from './PixelIcon'
import { PixelScrollArea } from './PixelScrollArea'

/**
 * 订单簿式数据表: 列排序 + 行选中 + 长列表自带滚动。真源: conventions.md 十, PixelTable 行。
 *
 * 排序状态 (当前按哪列/哪个方向) 是纯本地视觉态, 不受 conventions 4.2 的"业务值必须受控"约束——
 * 它只决定 rows 这份已有数据"怎么摆出来看", 不产生任何服务端往返, 与 hover/滚动位置同一性质。
 * 真正的业务值 (rows 本身、selectedRowKey) 仍然全受控, 组件不私自保留一份副本。
 *
 * 用真实语义 <table> 而不是自绘 div 网格: 排序表头的 aria-sort、行列关系全部是浏览器免费给的读屏语义,
 * 重新发明一套 role=grid/row/gridcell 只是把这些语义手工搭一遍, 复杂度与收益不成比例。
 *
 * 滚动直接复用 PixelScrollArea 而不是自己再包一层 overflow: 两处都要"零原生滚动条",
 * 同一份实现只应该存在一次 (八荣八耻: 以创造接口为耻, 以复用现有为荣)。表头随内容一起滚动 (无粘性表头)
 * 是本批刻意简化: 粘性表头需要额外拆分滚动结构, 任务书未要求, 按 YAGNI 先不做。
 */

export interface PixelTableColumn<TRow> {
  key: string
  header: string
  render: (row: TRow) => ReactNode
  /** 提供即该列表头可点击排序; 省略即不可排序。比较值必须来自这里, 不从 render 产出的 JSX 反推。 */
  sortValue?: (row: TRow) => string | number
}

export interface PixelTableProps<TRow> {
  columns: readonly PixelTableColumn<TRow>[]
  rows: readonly TRow[]
  rowKey: (row: TRow) => string
  onRowClick?: (row: TRow) => void
  selectedRowKey?: string
  emptyHint?: string
  /** 滚动区域的固定高度, 走 spacing 键 (如 "h-96") 经此传入; 不给则表格随内容自然撑高, 不产生滚动。 */
  className?: string
}

type SortDirection = 'asc' | 'desc'

interface SortState {
  key: string
  direction: SortDirection
}

function compareSortValues(a: string | number, b: string | number): number {
  if (typeof a === 'number' && typeof b === 'number') {
    return a - b
  }
  return String(a).localeCompare(String(b), 'zh-Hans-CN')
}

function rowStateClassName(selected: boolean, clickable: boolean): string {
  if (selected) {
    return 'bg-accent text-on-accent'
  }
  return clickable ? 'outline-none hover:bg-raised focus-visible:bg-raised' : ''
}

interface SortIndicator {
  icon: PixelIconName
  ariaSort: 'none' | 'ascending' | 'descending'
}

/** 方向 -> (图标, aria-sort) 的唯一映射源, 避免表头渲染与无障碍属性各写一遍三态判断。 */
function sortIndicator(direction: SortDirection | undefined): SortIndicator {
  if (direction === undefined) {
    return { icon: 'sort', ariaSort: 'none' }
  }
  return direction === 'asc'
    ? { icon: 'arrow-up', ariaSort: 'ascending' }
    : { icon: 'arrow-down', ariaSort: 'descending' }
}

export function PixelTable<TRow,>({
  columns,
  rows,
  rowKey,
  onRowClick,
  selectedRowKey,
  emptyHint,
  className,
}: PixelTableProps<TRow>): ReactElement {
  const [sort, setSort] = useState<SortState | null>(null)

  const activeSortColumn = sort === null ? undefined : columns.find((column) => column.key === sort.key)
  const activeSortValue = activeSortColumn?.sortValue

  const displayRows =
    sort === null || activeSortValue === undefined
      ? rows
      : [...rows].sort((a, b) => {
          const diff = compareSortValues(activeSortValue(a), activeSortValue(b))
          return sort.direction === 'asc' ? diff : -diff
        })

  function handleHeaderClick(column: PixelTableColumn<TRow>): void {
    if (column.sortValue === undefined) {
      return
    }
    // 三态循环 asc -> desc -> 取消, 而不是 asc/desc 死循环: 让"恢复原始顺序"始终可达, 不必强留一个排序态。
    setSort((current) => {
      if (current === null || current.key !== column.key) {
        return { key: column.key, direction: 'asc' }
      }
      if (current.direction === 'asc') {
        return { key: column.key, direction: 'desc' }
      }
      return null
    })
  }

  return (
    // exactOptionalPropertyTypes 下 className?: string 不接受显式 undefined, 按 conventions.md
    // 十二-1 的展开写法传, 调用方未给高度约束时整个不传这个键。
    <PixelScrollArea {...(className === undefined ? {} : { className })} orientation="vertical">
      <table className="w-full border-collapse text-1x">
        <thead>
          <tr>
            {columns.map((column) => {
              const direction = sort !== null && sort.key === column.key ? sort.direction : undefined
              const { icon, ariaSort } = sortIndicator(direction)
              return (
                <th
                  key={column.key}
                  scope="col"
                  aria-sort={ariaSort}
                  className="border-b border-border bg-surface px-2 py-1 text-left"
                >
                  {column.sortValue === undefined ? (
                    column.header
                  ) : (
                    <button
                      type="button"
                      className="flex items-center gap-1 outline-none focus-visible:text-accent"
                      onClick={() => {
                        handleHeaderClick(column)
                      }}
                    >
                      <span>{column.header}</span>
                      <PixelIcon name={icon} />
                    </button>
                  )}
                </th>
              )
            })}
          </tr>
        </thead>
        <tbody>
          {displayRows.length === 0 ? (
            <tr>
              <td colSpan={columns.length} className="px-2 py-4 text-center text-muted">
                {emptyHint === undefined ? '无数据' : emptyHint}
              </td>
            </tr>
          ) : (
            displayRows.map((row) => {
              const key = rowKey(row)
              const selected = key === selectedRowKey
              const handleActivate = onRowClick === undefined ? undefined : () => { onRowClick(row) }
              return (
                <tr
                  key={key}
                  tabIndex={handleActivate === undefined ? undefined : 0}
                  className={rowStateClassName(selected, handleActivate !== undefined)}
                  onClick={handleActivate}
                  aria-selected={handleActivate === undefined ? undefined : selected}
                  onKeyDown={
                    handleActivate === undefined
                      ? undefined
                      : (event: KeyboardEvent<HTMLTableRowElement>) => {
                          if (event.key !== 'Enter' && event.key !== ' ') {
                            return
                          }
                          event.preventDefault()
                          handleActivate()
                        }
                  }
                >
                  {columns.map((column) => (
                    <td key={column.key} className="px-2 py-1">
                      {column.render(row)}
                    </td>
                  ))}
                </tr>
              )
            })
          )}
        </tbody>
      </table>
    </PixelScrollArea>
  )
}

/** 组件预览页/面板 agent 复用的示例列与行: 模拟跳蚤市场订单簿的四列展示。 */
export interface PixelTableDemoListing {
  id: number
  itemName: string
  count: number
  unitPrice: number
  sellerName: string
}

export const PIXEL_TABLE_DEMO_ROWS: readonly PixelTableDemoListing[] = [
  { id: 1, itemName: '钻石', count: 12, unitPrice: 340, sellerName: 'Steve' },
  { id: 2, itemName: '铁锭', count: 64, unitPrice: 18, sellerName: 'Alex' },
  { id: 3, itemName: '绿宝石', count: 5, unitPrice: 210, sellerName: 'Notch' },
  { id: 4, itemName: '面包', count: 32, unitPrice: 4, sellerName: 'Herobrine' },
]

export const PIXEL_TABLE_DEMO_COLUMNS: readonly PixelTableColumn<PixelTableDemoListing>[] = [
  { key: 'item', header: '物品', render: (row) => row.itemName, sortValue: (row) => row.itemName },
  { key: 'count', header: '数量', render: (row) => String(row.count), sortValue: (row) => row.count },
  {
    key: 'unitPrice',
    header: '单价',
    render: (row) => String(row.unitPrice),
    sortValue: (row) => row.unitPrice,
  },
  { key: 'seller', header: '卖家', render: (row) => row.sellerName, sortValue: (row) => row.sellerName },
]
