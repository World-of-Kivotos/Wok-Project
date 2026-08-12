import { ArrowDownIcon, ArrowUpDownIcon, ArrowUpIcon } from 'lucide-react'
import type { ReactElement, ReactNode } from 'react'
import { useState } from 'react'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'

/**
 * 订单簿式数据表: 列排序 + 行选中 + 长列表滚动。
 *
 * 排序状态 (当前按哪列/哪个方向) 是纯本地视觉态, 不受"业务值必须受控"约束 —— 它只决定 rows 这份
 * 已有数据"怎么摆出来看", 不产生任何服务端往返, 与 hover/滚动位置同一性质。真正的业务值
 * (rows 本身、selectedRowKey) 仍然全受控, 组件不私自保留一份副本。
 *
 * 用真实语义 <table>: 排序表头的 aria-sort、行列关系全部是浏览器免费给的读屏语义,
 * 重新发明一套 role=grid/row/gridcell 只是把这些语义手工搭一遍, 复杂度与收益不成比例。
 */

export interface DataTableColumn<TRow> {
  key: string
  header: string
  render: (row: TRow) => ReactNode
  /** 提供即该列表头可点击排序; 省略即不可排序。比较值必须来自这里, 不从 render 产出的 JSX 反推。 */
  sortValue?: ((row: TRow) => string | number) | undefined
  /** 该列内容右对齐 (金额、数量这类数字列)。 */
  numeric?: boolean | undefined
}

export interface DataTableProps<TRow> {
  columns: readonly DataTableColumn<TRow>[]
  rows: readonly TRow[]
  rowKey: (row: TRow) => string
  onRowClick?: ((row: TRow) => void) | undefined
  selectedRowKey?: string | undefined
  /** 行数为 0 时显示的一行提示。空态需要图标与行动入口时请改用 EmptyBlock 而不是这条。 */
  emptyHint?: string | undefined
  className?: string | undefined
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

export function DataTable<TRow>({
  columns,
  rows,
  rowKey,
  onRowClick,
  selectedRowKey,
  emptyHint = '暂无数据',
  className,
}: DataTableProps<TRow>): ReactElement {
  const [sort, setSort] = useState<SortState | null>(null)

  const activeColumn = sort === null ? undefined : columns.find((column) => column.key === sort.key)
  const activeSortValue = activeColumn?.sortValue

  /*
   * 排序前先复制: rows 是 readonly 且属于调用方, 原地 sort 会改到上游持有的那个数组 ——
   * 在 React 里表现为"父组件的 state 被子组件改了但没触发重渲染"。
   */
  const sortedRows =
    sort === null || activeSortValue === undefined
      ? rows
      : [...rows].sort((left, right) => {
          const result = compareSortValues(activeSortValue(left), activeSortValue(right))
          return sort.direction === 'asc' ? result : -result
        })

  function toggleSort(key: string): void {
    setSort((current) => {
      if (current === null || current.key !== key) {
        return { key, direction: 'asc' }
      }
      // 第三次点击回到无排序, 而不是在升降序之间死循环 —— 用户需要一条路回到服务端给的原始次序。
      return current.direction === 'asc' ? { key, direction: 'desc' } : null
    })
  }

  return (
    <Table className={className}>
      <TableHeader>
        <TableRow>
          {columns.map((column) => {
            const sortable = column.sortValue !== undefined
            const isActive = sort !== null && sort.key === column.key
            return (
              <TableHead
                aria-sort={isActive ? (sort.direction === 'asc' ? 'ascending' : 'descending') : 'none'}
                className={column.numeric === true ? 'text-right' : undefined}
                key={column.key}
              >
                {sortable ? (
                  <button
                    className="inline-flex items-center gap-1 rounded-sm outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring"
                    onClick={() => {
                      toggleSort(column.key)
                    }}
                    type="button"
                  >
                    {column.header}
                    {!isActive ? (
                      <ArrowUpDownIcon aria-hidden="true" className="size-3 opacity-40" />
                    ) : sort.direction === 'asc' ? (
                      <ArrowUpIcon aria-hidden="true" className="size-3" />
                    ) : (
                      <ArrowDownIcon aria-hidden="true" className="size-3" />
                    )}
                  </button>
                ) : (
                  column.header
                )}
              </TableHead>
            )
          })}
        </TableRow>
      </TableHeader>
      <TableBody>
        {sortedRows.length === 0 ? (
          <TableRow>
            <TableCell className="py-8 text-center text-muted-foreground" colSpan={columns.length}>
              {emptyHint}
            </TableCell>
          </TableRow>
        ) : (
          sortedRows.map((row) => {
            const key = rowKey(row)
            const selected = selectedRowKey === key
            const clickable = onRowClick !== undefined
            return (
              <TableRow
                aria-selected={selected}
                className={
                  selected
                    ? 'bg-brand/12'
                    : clickable
                      ? 'cursor-pointer outline-none hover:bg-accent focus-visible:bg-accent'
                      : undefined
                }
                key={key}
                onClick={
                  clickable
                    ? () => {
                        onRowClick(row)
                      }
                    : undefined
                }
                onKeyDown={
                  clickable
                    ? (event) => {
                        // 可点击的行必须能用键盘触发, 否则整张表对键盘用户是只读的。
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault()
                          onRowClick(row)
                        }
                      }
                    : undefined
                }
                tabIndex={clickable ? 0 : undefined}
              >
                {columns.map((column) => (
                  <TableCell
                    className={column.numeric === true ? 'text-right tabular-nums' : undefined}
                    key={column.key}
                  >
                    {column.render(row)}
                  </TableCell>
                ))}
              </TableRow>
            )
          })
        )}
      </TableBody>
    </Table>
  )
}
