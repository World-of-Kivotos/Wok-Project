import type { ReactElement } from 'react'
import { DataTable, type DataTableColumn, Panel, Tag } from '@/components/kit'
import { nowMs } from '@/mock'
import type { DistrictLogEntry, DistrictPermissionAudience, PlotAreaChange } from '@/lib/types'
import {
  AUDIENCE_LABEL,
  LOG_ACTION_LABEL,
  LOG_ACTION_TONE,
  LOG_ACTOR_LABEL,
  boundsSize,
  formatBounds,
  formatDateTime,
  formatRelative,
  onOffLabel,
} from './format'

/**
 * 操作记录: 谁在什么时候添加 / 移出了谁、改了哪一项公共区域权限、划 / 调 / 删了哪块地、谁买下了哪块地、
 * 冻结 / 解冻 / 收回了哪块地、改了地块定价或开关购买。区务长与管理员可见 (服务端对住户下发 log = null,
 * 调用方据此根本不渲染本组件)。只读, 记录由服务端在每次写操作时追加, 前端没有删改入口。
 * 各户地块自己的记录 (加朋友、改地块权限) 不在这里, 在那块地的"地块记录"里。地块类动作的对象列写地块编号。
 * 管理员看已解绑自管区的归档时也用它 (换个标题)。
 *
 * 改权限的记录没有"对象玩家": 对象列上一行写哪一项、下一行小字写"公共区域 · 对住户 / 对外人"(区域规则写
 * "区域规则 · 全区") —— 写明是公共区域, 与地块记录里户主改自己地块的"改权限"分得开; 说明列写
 * "开 → 关"(恢复默认的再带一句"恢复默认")。对象分两行写而不是"外人：打掉展示框和盔甲架"挤一行: 单元格一律
 * nowrap, 1366x768 档表宽约 763px, 长条目名加上长操作人名、长移出原因就会把"说明"列挤出可视区 ——
 * 故说明列允许折行、限宽。
 */

function targetText(row: DistrictLogEntry): string {
  if (row.permission !== null) {
    return `${AUDIENCE_LABEL[row.permission.audience]}：${row.permission.label}`
  }
  return row.targetName ?? '—'
}

/** 改权限那一行的小字: 写明改的是公共区域 (还是整片的区域规则), 免得和各户地块自己的改权限混为一谈。 */
function permissionScopeText(audience: DistrictPermissionAudience): string {
  return audience === 'district' ? '区域规则 · 全区' : `公共区域 · 对${AUDIENCE_LABEL[audience]}`
}

function Target({ row }: { row: DistrictLogEntry }): ReactElement {
  if (row.permission !== null) {
    return (
      <span className="flex flex-col">
        <span className="font-medium text-foreground">{row.permission.label}</span>
        <span className="text-muted-foreground text-xs">{permissionScopeText(row.permission.audience)}</span>
      </span>
    )
  }
  if (row.targetName === null) {
    return <span className="text-muted-foreground">本区设置</span>
  }
  return <span className="font-medium text-foreground">{targetText(row)}</span>
}

/** 范围变动的一行: 新划写新范围, 删除写原范围, 调整写"旧尺寸 → 新尺寸"再附新坐标。 */
export function AreaChangeText({ change }: { change: PlotAreaChange }): ReactElement {
  const { from, to } = change
  if (from !== null && to !== null) {
    return (
      <span className="flex flex-col">
        <span className="text-foreground">{`${boundsSize(from)} → ${boundsSize(to)}`}</span>
        <span className="text-muted-foreground text-xs">{`新：${formatBounds(to)}`}</span>
      </span>
    )
  }
  const only = to ?? from
  if (only === null) {
    return <span className="text-muted-foreground">—</span>
  }
  return (
    <span className="flex flex-col">
      <span className="text-foreground">{`${to === null ? '原' : ''}${boundsSize(only)}`}</span>
      <span className="text-muted-foreground text-xs">{formatBounds(only)}</span>
    </span>
  )
}

function Detail({ row }: { row: DistrictLogEntry }): ReactElement {
  if (row.permission !== null) {
    const { from, to } = row.permission
    // 改成的值不上色: "开"不等于"好", 给外人开了破坏方块染成绿色就成了在夸它。
    return (
      <span className="flex items-center gap-1.5">
        <span className="text-muted-foreground">{onOffLabel(from)}</span>
        <span aria-hidden="true" className="text-muted-foreground">
          →
        </span>
        <span className="text-foreground">{onOffLabel(to)}</span>
        {row.reason === null ? null : <span className="text-muted-foreground text-xs">（{row.reason}）</span>}
      </span>
    )
  }
  if (row.area !== null) {
    return (
      <span className="flex max-w-64 flex-col gap-0.5 whitespace-normal">
        <AreaChangeText change={row.area} />
        {row.reason === null ? null : <span className="text-muted-foreground text-xs">{row.reason}</span>}
      </span>
    )
  }
  return row.reason === null ? (
    <span className="text-muted-foreground">—</span>
  ) : (
    <span className="block max-w-64 whitespace-normal">{row.reason}</span>
  )
}

const COLUMNS: readonly DataTableColumn<DistrictLogEntry>[] = [
  {
    key: 'at',
    header: '时间',
    render: (row) => (
      <span className="flex flex-col">
        <span className="tabular-nums">{formatDateTime(row.at)}</span>
        <span className="text-muted-foreground text-xs">{formatRelative(row.at, nowMs())}</span>
      </span>
    ),
    sortValue: (row) => row.at,
  },
  {
    key: 'actor',
    header: '操作人',
    render: (row) => (
      <span className="flex flex-col">
        <span className="text-foreground">{row.actorName}</span>
        <span className="text-muted-foreground text-xs">{LOG_ACTOR_LABEL[row.actorRole]}</span>
      </span>
    ),
    sortValue: (row) => row.actorName.toLowerCase(),
  },
  {
    key: 'action',
    header: '操作',
    render: (row) => (
      <Tag size="sm" tone={LOG_ACTION_TONE[row.action]}>
        {LOG_ACTION_LABEL[row.action]}
      </Tag>
    ),
    sortValue: (row) => row.action,
  },
  {
    key: 'target',
    header: '对象',
    render: (row) => <Target row={row} />,
    sortValue: (row) => targetText(row).toLowerCase(),
  },
  {
    key: 'detail',
    header: '说明',
    render: (row) => <Detail row={row} />,
  },
]

export function DistrictLogPanel({
  log,
  title = '操作记录',
  description = '谁在什么时候添加、移出了谁，改了哪一项公共区域权限，划、调、卖、冻结、收回了哪块地；移出必须写原因',
}: {
  log: readonly DistrictLogEntry[]
  title?: string | undefined
  description?: string | undefined
}): ReactElement {
  return (
    <Panel actions={<Tag tone="neutral">{`${String(log.length)} 条`}</Tag>} description={description} padded={false} title={title}>
      <div className="max-h-96 overflow-y-auto">
        <DataTable columns={COLUMNS} emptyHint="还没有任何操作" rowKey={(row) => row.entryId} rows={log} />
      </div>
    </Panel>
  )
}
