import { LockIcon, RotateCcwIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { useState } from 'react'
import { Button, ConfirmDangerDialog, ErrorBlock, Hint, LoadingBlock, Panel, Tag } from '@/components/kit'
import { invalidateAll } from '@/lib/refresh'
import { callMock, useMockAction } from '@/mock'
import type {
  DistrictFixedRule,
  DistrictPermissionAudience,
  DistrictPermissionGroup,
  DistrictPermissionItem,
  DistrictPermissionsResult,
} from '@/lib/types'
import { DistrictToastSlot, useDistrictToast } from './DistrictToast'
import { AUDIENCE_LABEL, describeFailure, onOffLabel } from './format'
import {
  type CellNotice,
  ItemText,
  PERMISSION_ROW_GRID,
  type PermissionColumn,
  ToggleCell,
  cellKey,
  columnsDiffTag,
  noticeFor,
  usePermissionCells,
} from './PermissionRows'

/**
 * 公共区域权限: 本区住户 / 外人两列开关 + 仅管理员可改的"区域规则"。区务长与管理员共用这一块,
 * 能改哪部分全看回执里的 editable (区务长 member = true、region = false), 本组件不认 role。
 *
 * 按户分地之后, 住户 / 外人两列**只管公共区域** (自管区里地块以外的地方); 各户地块由户主在"我的地块"里自己设置,
 * 与这里互不影响 (地块里每一格都是明确值, 不沿用这里)。区域规则仍管整片自管区, 地块也跟着。
 *
 * 行版式、开关格与"改一格"的状态机都在 PermissionRows (与地块的三列表共用)。
 * 要先确认才落地的: 给外人开高风险项 (outsiderRisk), 管理员给全区开高风险的区域规则 (districtRisk)。
 *
 * 游戏内打不了中文: 本面板只有开关和按钮, 不需要任何输入。
 */

type MemberAudience = Exclude<DistrictPermissionAudience, 'district'>
const MEMBER_AUDIENCES: readonly MemberAudience[] = ['resident', 'outsider']
const MEMBER_COLUMNS: readonly PermissionColumn[] = MEMBER_AUDIENCES.map((id) => ({ id, label: AUDIENCE_LABEL[id] }))
const REGION_COLUMNS: readonly PermissionColumn[] = [{ id: 'district', label: AUDIENCE_LABEL.district }]

function audiencesOf(group: DistrictPermissionGroup): readonly DistrictPermissionAudience[] {
  return group.scope === 'region' ? ['district'] : MEMBER_AUDIENCES
}

interface CellChange {
  item: DistrictPermissionItem
  audience: DistrictPermissionAudience
  from: boolean
  to: boolean
}

/** 开启前要先确认的一格。 */
interface PendingRisk {
  item: DistrictPermissionItem
  audience: Exclude<DistrictPermissionAudience, 'resident'>
  risk: string
}

export function PermissionSettings({
  districtId,
  districtName,
}: {
  districtId: string
  districtName: string
}): ReactElement {
  const query = useMockAction('district.permissions', { districtId })
  if (query.status === 'error') {
    return <ErrorBlock code="district.permissions" message={query.error.message} onRetry={query.reload} />
  }
  if (query.data === null) {
    return <LoadingBlock label="正在读取本区公共区域的权限" />
  }
  return <PermissionTable data={query.data} districtId={districtId} districtName={districtName} />
}

function PermissionTable({
  data,
  districtId,
  districtName,
}: {
  data: DistrictPermissionsResult
  districtId: string
  districtName: string
}): ReactElement {
  const { toast, show, clear } = useDistrictToast()
  const cells = usePermissionCells(data, '已生效，已记入操作记录')
  /**
   * 待确认的那一格与弹窗开合分开存: 关窗时若把内容一并清空, 收起动画那几帧标题和说明会闪成空白。
   * pendingRisk 只在下一次要确认时被覆盖, 开没开窗看 riskOpen。
   */
  const [pendingRisk, setPendingRisk] = useState<PendingRisk | null>(null)
  const [riskOpen, setRiskOpen] = useState(false)
  const [resetOpen, setResetOpen] = useState(false)
  const [resetting, setResetting] = useState(false)

  const table = cells.table
  const { editable } = table

  function valueFor(item: DistrictPermissionItem, audience: DistrictPermissionAudience): boolean | null {
    return cells.valueFor(cellKey(item.permissionId, audience), item.current[audience])
  }

  function changesFromDefault(groups: readonly DistrictPermissionGroup[]): CellChange[] {
    return groups.flatMap((group) =>
      group.items.flatMap((item) =>
        audiencesOf(group).flatMap((audience): CellChange[] => {
          const now = valueFor(item, audience)
          const fallback = item.defaults[audience]
          return now !== null && fallback !== null && now !== fallback
            ? [{ item, audience, from: now, to: fallback }]
            : []
        }),
      ),
    )
  }

  const memberDiff = changesFromDefault(table.groups.filter((group) => group.scope === 'member'))
  const regionDiffCount = changesFromDefault(table.groups.filter((group) => group.scope === 'region')).length
  const diffCount = memberDiff.length + regionDiffCount
  // 区务长的"恢复默认"只动住户、外人两列; 区域规则只有管理员能改, 也只有管理员的恢复默认会碰它。
  const resetChanges = editable.region ? changesFromDefault(table.groups) : memberDiff

  function commit(item: DistrictPermissionItem, audience: DistrictPermissionAudience, enabled: boolean): void {
    void cells.commit(cellKey(item.permissionId, audience), enabled, async () => {
      const result = await callMock('district.setPermission', {
        districtId,
        permissionId: item.permissionId,
        audience,
        enabled,
      })
      return { settled: result.item.current[audience], changed: result.logEntry !== null }
    })
  }

  function requestChange(
    item: DistrictPermissionItem,
    audience: DistrictPermissionAudience,
    enabled: boolean,
  ): void {
    // 给外人开高风险项、给全区开高风险的区域规则, 都要先确认; 关掉、或是给住户开, 都不拦。
    if (enabled && audience === 'outsider' && item.outsiderRisk !== null) {
      setPendingRisk({ item, audience, risk: item.outsiderRisk })
      setRiskOpen(true)
      return
    }
    if (enabled && audience === 'district' && item.districtRisk !== null) {
      setPendingRisk({ item, audience, risk: item.districtRisk })
      setRiskOpen(true)
      return
    }
    commit(item, audience, enabled)
  }

  async function reset(): Promise<void> {
    setResetting(true)
    cells.clearSaved()
    try {
      const result = await callMock('district.resetPermissions', {
        districtId,
        scope: editable.region ? 'all' : 'member',
      })
      cells.replaceTable(result.permissions)
      invalidateAll()
      show(
        'success',
        result.changedCount === 0
          ? '本来就是默认设置，没有需要恢复的项。'
          : `已把 ${String(result.changedCount)} 处恢复成默认值，都记进了操作记录。`,
      )
    } catch (error: unknown) {
      show('danger', describeFailure(error))
      // 失败也重拉: 被拒多半是手上那份已经过期 (同 PermissionRows 的 commit)。
      invalidateAll()
    } finally {
      setResetting(false)
      // 成功失败都关: 失败回执画在弹窗背后, 不关就把服务端那句话藏起来了 (同 ResidentManager)。
      setResetOpen(false)
    }
  }

  const rowProps: RowProps = {
    valueFor,
    busy: cells.busy,
    locked: resetting,
    rowError: cells.rowError,
    lastSaved: cells.lastSaved,
    onChange: requestChange,
  }

  const resetButton = (
    <Button
      disabled={resetChanges.length === 0 || cells.anyBusy || resetting}
      onClick={() => {
        setResetOpen(true)
      }}
      size="sm"
      variant="outline"
    >
      <RotateCcwIcon aria-hidden="true" />
      恢复默认
    </Button>
  )

  return (
    <Panel
      actions={
        resetChanges.length === 0 ? (
          // 禁用的按钮本身不响应悬停, 说明挂在外面这层 (同 ResidentManager 的"移出")。
          <Hint content={editable.region ? '全部已经是默认设置' : '住户、外人的开关已经是默认设置'}>
            {resetButton}
          </Hint>
        ) : (
          resetButton
        )
      }
      description="这里管的是本区公共区域，也就是地块以外的地方；各户地块由户主自己设置"
      title="公共区域权限"
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-col gap-1 text-muted-foreground text-xs">
          <p>
            外人 = 不是本区住户的任何玩家，包括别的学院的人。名单上"待生效""同步失败"的住户，在游戏里暂时也按外人算。
            {editable.region
              ? '住户这一列也管区务长自己。'
              : '住户这一列也管你自己：关掉了，你在公共区域也做不了。'}
          </p>
          <p>
            {diffCount === 0
              ? '现在全部是默认设置。'
              : `现在有 ${String(diffCount)} 处不是默认值，那几行标着"非默认"。`}
            {!editable.region && regionDiffCount > 0
              ? `其中 ${String(regionDiffCount)} 处是区域规则，只有管理员能恢复。`
              : ''}
            每一次改动都会写进操作记录。
          </p>
          {editable.region ? (
            <p>
              {'住户列写进 Flan 领地的居民组，外人列是领地的全域权限，区域规则是 Flan 的全局类权限。各户地块是下面的子领地：地块内每项都写成明确值，不沿用自管区设置，所以这里的住户、外人两列改了也漏不进地块；只有区域规则地块跟着走。'}
            </p>
          ) : null}
        </div>

        <DistrictToastSlot onClear={clear} toast={toast} />

        {table.groups.map((group) =>
          group.scope === 'region' ? (
            <RegionGroup
              editable={editable.region}
              fixedRules={table.fixedRules}
              group={group}
              key={group.groupId}
              {...rowProps}
            />
          ) : (
            <MemberGroup editable={editable.member} group={group} key={group.groupId} {...rowProps} />
          ),
        )}

        <p className="border-t pt-3 text-muted-foreground text-xs">
          改领地范围、管理权限组这些不在这里开，区务长永远拿不到。
        </p>
      </div>

      <ConfirmDangerDialog
        confirmLabel="仍然开启"
        message={
          pendingRisk === null
            ? ''
            : pendingRisk.audience === 'outsider'
              ? `${pendingRisk.risk}外人指不是本区住户的任何玩家，开了马上在公共区域生效。`
              : `${pendingRisk.risk}区域规则管整片自管区，各户地块也跟着，开了马上生效。`
        }
        onConfirm={() => {
          setRiskOpen(false)
          if (pendingRisk !== null) {
            commit(pendingRisk.item, pendingRisk.audience, true)
          }
        }}
        onOpenChange={setRiskOpen}
        open={riskOpen && pendingRisk !== null}
        title={
          pendingRisk === null
            ? '确认开启？'
            : `给${pendingRisk.audience === 'outsider' ? '外人' : '全区'}开启"${pendingRisk.item.label}"？`
        }
      />

      <ConfirmDangerDialog
        confirmLabel="恢复默认"
        loading={resetting}
        message={
          editable.region
            ? `公共区域住户、外人的开关和区域规则都会回到默认值。下面 ${String(resetChanges.length)} 处会变，马上生效。各户地块不受影响。`
            : `公共区域住户、外人的开关会回到默认值。下面 ${String(resetChanges.length)} 处会变，马上生效。区域规则只有管理员能改，这次不动；各户地块也不受影响。`
        }
        onConfirm={() => {
          void reset()
        }}
        onOpenChange={setResetOpen}
        open={resetOpen}
        title={`把${districtName}公共区域的权限恢复默认？`}
      >
        <ul className="flex max-h-40 flex-col gap-1 overflow-y-auto text-xs">
          {resetChanges.map((change) => (
            <li
              className="flex items-center justify-between gap-3"
              key={cellKey(change.item.permissionId, change.audience)}
            >
              <span className="min-w-0 text-foreground">
                {AUDIENCE_LABEL[change.audience]}：{change.item.label}
              </span>
              <span className="shrink-0 text-muted-foreground">
                {onOffLabel(change.from)} → {onOffLabel(change.to)}
              </span>
            </li>
          ))}
        </ul>
      </ConfirmDangerDialog>
    </Panel>
  )
}

// ============================================================
// 分组与行
// ============================================================

interface RowProps {
  valueFor: (item: DistrictPermissionItem, audience: DistrictPermissionAudience) => boolean | null
  busy: Readonly<Record<string, boolean>>
  /** 恢复默认在途: 整张表锁住。 */
  locked: boolean
  rowError: CellNotice | null
  lastSaved: CellNotice | null
  onChange: (item: DistrictPermissionItem, audience: DistrictPermissionAudience, enabled: boolean) => void
}

interface GroupProps extends RowProps {
  group: DistrictPermissionGroup
  editable: boolean
}

function MemberGroup({ group, editable, ...rowProps }: GroupProps): ReactElement {
  return (
    <section className="flex flex-col">
      <div className={`${PERMISSION_ROW_GRID[2]} border-b pb-1.5`}>
        <h3 className="font-medium text-foreground text-sm">{group.label}</h3>
        {MEMBER_COLUMNS.map((column) => (
          <span className="text-center text-muted-foreground text-xs" key={column.id}>
            {column.label}
          </span>
        ))}
      </div>
      <ul className="flex flex-col divide-y">
        {group.items.map((item) => {
          const resident = rowProps.valueFor(item, 'resident')
          const outsider = rowProps.valueFor(item, 'outsider')
          return (
            <li className={`${PERMISSION_ROW_GRID[2]} py-2`} key={item.permissionId}>
              <ItemText
                diffTag={columnsDiffTag(
                  MEMBER_AUDIENCES.map((audience) => ({
                    label: AUDIENCE_LABEL[audience],
                    now: rowProps.valueFor(item, audience),
                    fallback: item.defaults[audience],
                  })),
                )}
                error={noticeFor(rowProps.rowError, item.permissionId, MEMBER_COLUMNS, false)}
                item={item}
                saved={noticeFor(rowProps.lastSaved, item.permissionId, MEMBER_COLUMNS, true)}
                // 外人开着、住户关着: Flan 里住户在居民组被明确禁止, 反而比路人做得少。多半是手滑, 提一句。
                warning={resident === false && outsider === true ? '外人能做、住户反而不能' : null}
              />
              {MEMBER_AUDIENCES.map((audience) => (
                <div className="flex items-center justify-center" key={audience}>
                  <SwitchCell audience={audience} editable={editable} item={item} {...rowProps} />
                </div>
              ))}
            </li>
          )
        })}
      </ul>
    </section>
  )
}

/**
 * 区域规则: 只有一列"全区"。区务长看得到但改不了 —— 锁着并写明"仅管理员", 而不是藏掉 (理由同 AdminOnlyCard:
 * 藏掉会让区务长以为 PvP、爆炸这些没人管)。
 * 末尾另有服务器写死的几行 (fixedRules, 如"机械动力：机器禁用"): 不是 Flan 开关, 管理员也改不了, 画成锁定的只读行。
 */
function RegionGroup({
  group,
  editable,
  fixedRules,
  ...rowProps
}: GroupProps & { fixedRules: readonly DistrictFixedRule[] }): ReactElement {
  return (
    <section className="flex flex-col">
      <div className={`${PERMISSION_ROW_GRID[2]} border-b pb-1.5`}>
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <h3 className="font-medium text-foreground text-sm">{group.label}</h3>
          {editable ? null : (
            <Tag size="sm" tone="neutral">
              <LockIcon aria-hidden="true" className="size-3" />
              仅管理员
            </Tag>
          )}
        </div>
        <span className="col-span-2 text-center text-muted-foreground text-xs">全区</span>
      </div>
      <p className="pt-1.5 text-muted-foreground text-xs">
        这几项管整片自管区，公共区域和各户地块都一样，不分住户和外人，地块里也不能单独改。
        {editable ? '' : '你只能看，要改请找管理员。'}
      </p>
      <ul className="flex flex-col divide-y">
        {group.items.map((item) => {
          const value = rowProps.valueFor(item, 'district')
          const fallback = item.defaults.district
          return (
            <li className={`${PERMISSION_ROW_GRID[2]} py-2`} key={item.permissionId}>
              <ItemText
                diffTag={
                  value !== null && fallback !== null && value !== fallback
                    ? { text: '非默认', hint: `默认：${onOffLabel(fallback)}` }
                    : null
                }
                error={noticeFor(rowProps.rowError, item.permissionId, REGION_COLUMNS, false)}
                item={item}
                saved={noticeFor(rowProps.lastSaved, item.permissionId, REGION_COLUMNS, false)}
                warning={null}
              />
              <div className="col-span-2 flex items-center justify-center gap-1.5">
                <SwitchCell audience="district" editable={editable} item={item} {...rowProps} />
                {editable ? null : (
                  <LockIcon aria-label="仅管理员可改" className="size-3.5 shrink-0 text-muted-foreground" />
                )}
              </div>
            </li>
          )
        })}
        {fixedRules.map((rule) => (
          <li className={`${PERMISSION_ROW_GRID[2]} py-2`} key={rule.ruleId}>
            <div className="flex min-w-0 flex-col gap-0.5">
              <span className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
                <span className="text-foreground text-sm">{rule.label}</span>
                <Tag size="sm" tone="neutral">
                  <LockIcon aria-hidden="true" className="size-3" />
                  固定规则
                </Tag>
              </span>
              {rule.detail === null ? null : <span className="text-muted-foreground text-xs">{rule.detail}</span>}
            </div>
            <div className="col-span-2 flex items-center justify-center gap-1.5 text-center">
              <span className="text-foreground text-xs">{rule.valueText}</span>
              <LockIcon aria-label="谁都改不了" className="size-3.5 shrink-0 text-muted-foreground" />
            </div>
          </li>
        ))}
      </ul>
      <p className="pt-1.5 text-muted-foreground text-xs">
        {'个人圈地：自管区外，玩家可以自己用 Flan 圈个人领地、自己管权限（与本面板无关）；自管区内和外围 8 格内不能圈个人领地。'}
      </p>
    </section>
  )
}

function SwitchCell({
  item,
  audience,
  editable,
  valueFor,
  busy,
  locked,
  onChange,
}: RowProps & {
  item: DistrictPermissionItem
  audience: DistrictPermissionAudience
  editable: boolean
}): ReactElement {
  return (
    <ToggleCell
      ariaLabel={`${AUDIENCE_LABEL[audience]}：${item.label}`}
      disabled={!editable || locked || busy[cellKey(item.permissionId, audience)] !== undefined}
      onChange={(next) => {
        onChange(item, audience, next)
      }}
      value={valueFor(item, audience)}
    />
  )
}
