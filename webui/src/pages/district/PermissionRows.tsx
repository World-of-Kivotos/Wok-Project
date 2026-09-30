import type { ReactElement } from 'react'
import { useEffect, useRef, useState } from 'react'
import { Hint, Tag, Toggle } from '@/components/kit'
import { invalidateAll } from '@/lib/refresh'
import { describeFailure, onOffLabel } from './format'

/**
 * 权限开关表的共用零件: 公共区域 (PermissionSettings, 住户 / 外人两列 + 区域规则) 与地块 (PlotPermissionTable,
 * 朋友 / 其他住户 / 外人三列) 共用同一套行版式、同一个开关格和同一套"改一格"的状态机, 不各写一份 ——
 * 两张表的交互 (在途锁格、行内回执、重拉前不闪回旧值) 必须一模一样, 分开写迟早一边修了另一边没修。
 *
 * 版式 (平板 1366x768 档内容区约 765px 宽): 网格 = 条目名 (吃掉剩余宽度、可折行) + N 列定宽开关。
 * 不用 DataTable —— 它的单元格一律 nowrap, 条目说明一长就把最右那列开关顶出可视区 (住户表上一轮就栽在这)。
 */

/** 条目名 + N 列定宽开关。写全类名而不是按列数拼接: Tailwind 只认源码里原样出现过的完整类名。 */
export const PERMISSION_ROW_GRID = {
  2: 'grid grid-cols-[minmax(0,1fr)_4rem_4rem] items-center gap-x-2',
  3: 'grid grid-cols-[minmax(0,1fr)_4rem_4rem_4rem] items-center gap-x-2',
} as const

/** 行内"已生效"回执停留多久。 */
const SAVED_NOTICE_MS = 4000

export function cellKey(permissionId: string, column: string): string {
  return `${permissionId}:${column}`
}

/** 某一格的行内回执 (成功或失败), 键为 cellKey。 */
export interface CellNotice {
  key: string
  message: string
}

/** 一列开关: id 是写进 cellKey 的列名, label 是给人看的名字 (住户 / 外人 / 朋友 / 其他住户…)。 */
export interface PermissionColumn {
  id: string
  label: string
}

/** 一次单格写入的回执要点。 */
export interface CellResult {
  /** 服务端回执里这一格改完之后的值; null = 回执里没有这一格 (不叠遮罩, 等重拉)。 */
  settled: boolean | null
  /** false = 服务端说本来就是这个值 (比如两个人同时点了同一格), 没改动也没写记录。 */
  changed: boolean
}

/**
 * 写操作回执在重拉回来之前的临时遮罩。只对**叠上去时手上那份**回执生效 (按引用比): 重拉的新回执一到
 * (每次都是新对象), 遮罩自动失效, 以服务端为准 —— 同 DistrictPage 删区后的 HiddenAfterDelete。
 */
interface CellOverlay<TData> {
  from: TData
  /** 恢复默认回来的整张表; null = 没恢复过, 用 from 本身。 */
  base: TData | null
  /** 单格改动的回执值, 键为 cellKey。 */
  cells: Readonly<Record<string, boolean>>
}

export interface PermissionCells<TData> {
  /** 该画的那份表: 恢复默认回来、重拉还没到时是恢复回执里的那份, 否则就是 data。 */
  table: TData
  /** 某一格现在该显示的值: 在途的目标值 > 回执遮罩 > 服务端值。服务端值不可能为 null 时 (地块三列), 结果也不会是 null。 */
  valueFor: <T extends boolean | null>(key: string, server: T) => boolean | T
  /** 在途的格子 -> 要改成的值。在途期间那格先显示目标值并锁住, 别的格子照常可点。 */
  busy: Readonly<Record<string, boolean>>
  anyBusy: boolean
  rowError: CellNotice | null
  /** 最近一次改成功的那格。下一次改动开始、或停留 SAVED_NOTICE_MS 之后收起。 */
  lastSaved: CellNotice | null
  commit: (key: string, enabled: boolean, send: () => Promise<CellResult>) => Promise<void>
  /** 恢复默认成功后, 在重拉回来之前先画回执里的整张表。 */
  replaceTable: (next: TData) => void
  clearSaved: () => void
}

/**
 * "改一格即时写入"的状态机。成败都 invalidateAll —— 操作记录、页签计数、"我能做什么"都是别的查询画的;
 * 被拒多半说明手上那份已经过期 (刚被撤了区务长、地块被收回、别人先改了这一格), 不重拉就一直停在旧状态。
 * 重拉回来之前, 刚改的那格先按回执显示 (遮罩), 不闪回旧值。成败都回在那一行下面: 表有三十来行,
 * 页顶的回执条在底部点开关时根本看不见。
 */
export function usePermissionCells<TData>(data: TData, savedMessage: string): PermissionCells<TData> {
  const [overlay, setOverlay] = useState<CellOverlay<TData> | null>(null)
  const [busy, setBusy] = useState<Readonly<Record<string, boolean>>>({})
  const [rowError, setRowError] = useState<CellNotice | null>(null)
  const [lastSaved, setLastSaved] = useState<CellNotice | null>(null)

  // 回执回来时要把遮罩叠在"那一刻屏幕上的那份"回执上, 而异步回调里闭包住的 data 是点击那一刻的。
  const latestData = useRef(data)
  useEffect(() => {
    latestData.current = data
  }, [data])

  useEffect(() => {
    if (lastSaved === null) {
      return undefined
    }
    const timer = window.setTimeout(() => {
      setLastSaved(null)
    }, SAVED_NOTICE_MS)
    return () => {
      window.clearTimeout(timer)
    }
  }, [lastSaved])

  const live = overlay !== null && overlay.from === data ? overlay : null

  async function commit(key: string, enabled: boolean, send: () => Promise<CellResult>): Promise<void> {
    setBusy((prev) => ({ ...prev, [key]: enabled }))
    setRowError((prev) => (prev !== null && prev.key === key ? null : prev))
    setLastSaved(null)
    try {
      const { settled, changed } = await send()
      const from = latestData.current
      if (settled !== null) {
        setOverlay((prev) => {
          const keep = prev !== null && prev.from === from ? prev : null
          return { from, base: keep?.base ?? null, cells: { ...keep?.cells, [key]: settled } }
        })
      }
      setLastSaved({ key, message: changed ? savedMessage : '本来就是这个设置，没有改动' })
      invalidateAll()
    } catch (error: unknown) {
      setRowError({ key, message: `没改成：${describeFailure(error)}` })
      invalidateAll()
    } finally {
      setBusy((prev) => Object.fromEntries(Object.entries(prev).filter(([candidate]) => candidate !== key)))
    }
  }

  return {
    table: live?.base ?? data,
    valueFor: (key, server) => busy[key] ?? live?.cells[key] ?? server,
    busy,
    anyBusy: Object.keys(busy).length > 0,
    rowError,
    lastSaved,
    commit,
    replaceTable: (next) => {
      setOverlay({ from: latestData.current, base: next, cells: {} })
    },
    clearSaved: () => {
      setLastSaved(null)
    },
  }
}

/**
 * 某一行要显示的格子回执。withColumn: 一行有好几格时, 成功回执带上是哪一格, 不然分不清改的是哪边。
 */
export function noticeFor(
  notice: CellNotice | null,
  permissionId: string,
  columns: readonly PermissionColumn[],
  withColumn: boolean,
): string | null {
  if (notice === null) {
    return null
  }
  const hit = columns.find((column) => cellKey(permissionId, column.id) === notice.key)
  if (hit === undefined) {
    return null
  }
  return withColumn ? `${hit.label}：${notice.message}` : notice.message
}

/** 与默认不同时行名旁的标签: 写明是哪一列不同, 悬停看默认值。 */
export interface DiffTag {
  text: string
  hint: string
}

/** 一行各列"现在 / 默认"的对照, 算出行名旁的"非默认"标签。任何一列没有默认值 (回执里是 null) 就不标。 */
export function columnsDiffTag(
  cells: readonly { label: string; now: boolean | null; fallback: boolean | null }[],
): DiffTag | null {
  const defaults: { label: string; fallback: boolean }[] = []
  for (const cell of cells) {
    if (cell.fallback === null) {
      return null
    }
    defaults.push({ label: cell.label, fallback: cell.fallback })
  }
  const differing = cells.filter((cell) => cell.now !== null && cell.now !== cell.fallback)
  const [first] = differing
  if (first === undefined) {
    return null
  }
  const text =
    differing.length === 1
      ? `${first.label}非默认`
      : differing.length === cells.length
        ? '都非默认'
        : `${differing.map((cell) => cell.label).join('、')}非默认`
  return {
    text,
    hint: `默认：${defaults.map((cell) => `${cell.label}${onOffLabel(cell.fallback)}`).join('，')}`,
  }
}

/** 条目名一侧要显示的内容 (公共区域与地块的条目都满足这个形状)。 */
export interface PermissionItemText {
  label: string
  detail: string | null
  /** Flan id 只下发给管理员 (排障用), 其余身份是 null, 这一行自然不画。 */
  flanIds: readonly string[] | null
  flanInverted: boolean | null
}

export function ItemText({
  item,
  diffTag,
  warning,
  error,
  saved,
}: {
  item: PermissionItemText
  /** 与默认不同时的标签; null = 与默认一致, 不标。 */
  diffTag: DiffTag | null
  warning: string | null
  error: string | null
  saved: string | null
}): ReactElement {
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <span className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
        <span className="text-foreground text-sm">{item.label}</span>
        {diffTag === null ? null : (
          <Hint content={diffTag.hint}>
            <Tag size="sm" tone="info">
              {diffTag.text}
            </Tag>
          </Hint>
        )}
      </span>
      {item.detail === null ? null : <span className="text-muted-foreground text-xs">{item.detail}</span>}
      {item.flanIds === null ? null : (
        <span className="break-all font-mono text-muted-foreground text-xs">
          {item.flanIds.join(' + ')}
          {item.flanInverted === true ? '（反向：本项开 = Flan 那条关）' : ''}
        </span>
      )}
      {warning === null ? null : <span className="text-warning text-xs">{warning}</span>}
      {saved === null ? null : (
        <span className="text-success text-xs" role="status">
          {saved}
        </span>
      )}
      {error === null ? null : (
        <span className="text-destructive text-xs" role="alert">
          {error}
        </span>
      )}
    </div>
  )
}

/** 一格开关。value 为 null = 回执与身份对不上 (该给的列没给), 如实画"未知"而不是画一个关着的开关。 */
export function ToggleCell({
  value,
  ariaLabel,
  disabled,
  onChange,
}: {
  value: boolean | null
  /** 读屏念的名字, 如"外人：开箱子等容器" —— 一整列的可见文字都是"开 / 关", 不给就分不清是哪一格。 */
  ariaLabel: string
  disabled: boolean
  onChange: (next: boolean) => void
}): ReactElement {
  if (value === null) {
    return <span className="text-muted-foreground text-xs">未知</span>
  }
  return (
    <Toggle
      ariaLabel={ariaLabel}
      checked={value}
      disabled={disabled}
      label={onOffLabel(value)}
      // 开 / 关按状态上色: 一列列扫过去就知道哪些开着。
      labelClassName={value ? 'text-success' : 'text-muted-foreground'}
      onChange={onChange}
      size="sm"
    />
  )
}
