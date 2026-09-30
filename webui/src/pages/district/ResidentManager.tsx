import { RotateCwIcon, SearchIcon, UserPlusIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { useState } from 'react'
import {
  Button,
  ConfirmDangerDialog,
  DataTable,
  type DataTableColumn,
  Dropdown,
  type DropdownOption,
  Hint,
  Panel,
  Surface,
  Tag,
  TextInput,
} from '@/components/kit'
import { isMockActive } from '@/lib/bridge'
import { invalidateAll } from '@/lib/refresh'
import { DISTRICT_PREVIEW_ADD_HINTS, callMock, nowMs } from '@/mock'
import type {
  DistrictAbilities,
  DistrictResident,
  DistrictSyncStatus,
  RemoveReasonKind,
} from '@/lib/types'
import { DistrictToastSlot, useDistrictToast } from './DistrictToast'
import {
  RECLAIM_LEFTOVER_NOTE,
  SYNC_STATUS_LABEL,
  SYNC_STATUS_TONE,
  describeFailure,
  failureCode,
  formatRelative,
  formatShortDate,
} from './format'

/**
 * 住户管理: 住户表 + 搜索/筛选 + 添加 + 移出 (+ 管理员的同步重试)。区务长与管理员共用这一块,
 * 两者的差别全部来自服务端下发的 abilities (retrySync 只有管理员为真), 本组件不认 role。
 *
 * 每个写操作成功后调 invalidateAll (同 QuestsPage / AdminPage): 名单、页签计数、操作记录、总览人数都是
 * 别的查询画的, 只有全量作废才能让它们一起跟上。不靠假世界变化去触发 —— 接线后写操作根本不碰假世界。
 *
 * 表格只留四列 (玩家 | 领地权限 | 最近在线 | 操作), 加入时间与添加人并成玩家名下的一行小字: 平板在 1366x768
 * 默认档下内容区只有 480px 上下宽, 六列会把最要紧的"领地权限"和"移出"挤出可视区。
 * 中文输入 (docs/WebUI_ChineseIME_DesignSpec.md, DEFERRED): 游戏内 MCEF 目前打不了组字中文。
 *   - 玩家 ID 本来就只有英文数字下划线, 普通输入框够用;
 *   - 移出原因因此做成"必选一个预设 + 选填补充说明", 而不是一个必填的自由文本框 —— 后者在游戏里
 *     等于把"移出"这件事整个封死。预设覆盖常见情形, "其他"才要求补充说明。
 */

/** Minecraft 正版 ID 的字符集与长度。只用来提前把按钮置灰, 服务端照样会再验一遍。 */
const PLAYER_NAME_PATTERN = /^[A-Za-z0-9_]{3,16}$/

const REMOVE_REASONS = ['长期不上线', '违反区规', '本人申请退出', '其他'] as const
type RemoveReason = (typeof REMOVE_REASONS)[number]

/**
 * 预设原因 -> 服务端的原因种类。服务端按种类决定连带后果 (违反区规会暂停 TA 在本区别人地块的朋友身份),
 * 不去解析原因原文 —— 补充说明是自由文本, 靠它判后果迟早误判。
 */
const REASON_KIND: Record<RemoveReason, RemoveReasonKind> = {
  长期不上线: 'inactive',
  违反区规: 'violation',
  本人申请退出: 'selfRequest',
  其他: 'other',
}

type StatusFilter = 'all' | DistrictSyncStatus

function composeReason(reason: RemoveReason, note: string): string {
  const trimmed = note.trim()
  if (reason === '其他') {
    return trimmed
  }
  return trimmed === '' ? reason : `${reason}：${trimmed}`
}

export interface ResidentManagerProps {
  districtId: string
  districtName: string
  /** 学院简称 ("已加入千年"这类短句)。 */
  academyName: string
  /** 学院全称 ("千年学院的成员名单")。 */
  academyFullName: string
  residents: readonly DistrictResident[]
  abilities: DistrictAbilities
}

export function ResidentManager({
  districtId,
  districtName,
  academyName,
  academyFullName,
  residents,
  abilities,
}: ResidentManagerProps): ReactElement {
  const { toast, show, clear } = useDistrictToast()
  /** retrySync 只下发给管理员, 顺带决定这块能不能说"Flan"。 */
  const adminView = abilities.retrySync

  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all')

  const [addName, setAddName] = useState('')
  const [adding, setAdding] = useState(false)
  /** 服务端说"这人没进过服"之后, 等操作者确认的那个名字。输入框一改就作废。 */
  const [neverJoinedName, setNeverJoinedName] = useState<string | null>(null)

  const [removeTarget, setRemoveTarget] = useState<DistrictResident | null>(null)
  const [removeReason, setRemoveReason] = useState<RemoveReason | null>(null)
  const [removeNote, setRemoveNote] = useState('')
  const [removing, setRemoving] = useState(false)

  const [retrying, setRetrying] = useState<string | null>(null)

  const trimmedAddName = addName.trim()
  const addNameValid = PLAYER_NAME_PATTERN.test(trimmedAddName)

  const counts: Record<DistrictSyncStatus, number> = {
    synced: residents.filter((resident) => resident.syncStatus === 'synced').length,
    pending: residents.filter((resident) => resident.syncStatus === 'pending').length,
    failed: residents.filter((resident) => resident.syncStatus === 'failed').length,
  }
  const filterOptions: readonly DropdownOption<StatusFilter>[] = [
    { value: 'all', label: `全部状态 (${String(residents.length)})` },
    { value: 'synced', label: `${SYNC_STATUS_LABEL.synced} (${String(counts.synced)})` },
    { value: 'pending', label: `${SYNC_STATUS_LABEL.pending} (${String(counts.pending)})` },
    { value: 'failed', label: `${SYNC_STATUS_LABEL.failed} (${String(counts.failed)})` },
  ]

  const term = search.trim().toLowerCase()
  const visibleResidents = residents.filter(
    (resident) =>
      (term === '' || resident.playerName.toLowerCase().includes(term)) &&
      (statusFilter === 'all' || resident.syncStatus === statusFilter),
  )

  async function submitAdd(allowNeverJoined: boolean): Promise<void> {
    const name = trimmedAddName
    setAdding(true)
    try {
      const result = await callMock('district.addResident', { districtId, playerName: name, allowNeverJoined })
      invalidateAll()
      setAddName('')
      setNeverJoinedName(null)
      // 以前被移出时冻结的旧地块不会自动还回去, 在那之前 TA 也买不了别的地块 (服务端 HAS_FROZEN_PLOT): 如实说。
      const plotNote =
        result.frozenPlot === null
          ? '本区开放购买时，TA 可以在「本区地块」里直接买一块空置的。'
          : `TA 原来的地块 ${result.frozenPlot.code} 还在冻结中，不会自动还给 TA：找管理员解除冻结就能照原样还回去；在那之前 TA 不能另买地块。`
      if (result.resident.syncStatus === 'pending') {
        show(
          'warning',
          `${result.resident.playerName} 还没进过服务器，领地权限会在 TA 第一次登录时自动生效。在那之前名单上显示"待生效"。`,
          `已把 ${result.resident.playerName} 记入${academyName}名单`,
        )
      } else {
        show(
          result.frozenPlot === null ? 'success' : 'warning',
          `${result.resident.playerName} 已加入${academyName}，现在可以在本区公共区域建造了。${plotNote}`,
        )
      }
    } catch (error: unknown) {
      if (failureCode(error) === 'PLAYER_NEVER_JOINED') {
        // 不是失败, 是服务端要操作者确认一件事: 留在原地等选择, 不弹红色回执。
        setNeverJoinedName(name)
        return
      }
      show('danger', describeFailure(error))
      // 失败也重拉: 被拒多半是手上那份名单已经过期 (别人先加过、刚被撤了区务长…), 同 PermissionRows 的 commit。
      invalidateAll()
    } finally {
      setAdding(false)
    }
  }

  function openRemove(resident: DistrictResident): void {
    setRemoveTarget(resident)
    setRemoveReason(null)
    setRemoveNote('')
  }

  async function confirmRemove(): Promise<void> {
    if (removeTarget === null || removeReason === null) {
      return
    }
    const reason = composeReason(removeReason, removeNote)
    setRemoving(true)
    try {
      const result = await callMock('district.removeResident', {
        districtId,
        playerName: removeTarget.playerName,
        reasonKind: REASON_KIND[removeReason],
        reason,
      })
      invalidateAll()
      show(
        'success',
        `已把 ${removeTarget.playerName} 移出${districtName}，原因已写进操作记录。${
          result.frozenPlot === null
            ? ''
            : `TA 的地块 ${result.frozenPlot.code} 已原地冻结 7 天${
                result.reclaimAt === null ? '' : `（${formatShortDate(result.reclaimAt, nowMs())} 到期收回）`
              }。`
        }${
          result.suspendedFriendOfPlots === 0
            ? ''
            : `TA 在本区 ${String(result.suspendedFriendOfPlots)} 块地的朋友身份已暂停，户主已收到通知，可以自己恢复。`
        }${
          result.stillFriendOfPlots === 0
            ? ''
            : `TA 仍是本区 ${String(result.stillFriendOfPlots)} 块地的朋友，在那几块地里照旧按朋友算；要收回请找户主或管理员。`
        }`,
      )
    } catch (error: unknown) {
      show('danger', describeFailure(error))
      invalidateAll()
    } finally {
      setRemoving(false)
      // 成功失败都关: 失败回执画在弹窗背后的分区里, 不关就把服务端那句话藏起来了 (同 AdminPage)。
      setRemoveTarget(null)
    }
  }

  async function retrySync(playerName: string): Promise<void> {
    setRetrying(playerName)
    try {
      await callMock('admin.district.retrySync', { districtId, playerName })
      invalidateAll()
      show('success', `${playerName} 的 Flan 领地权限已重新写入。`)
    } catch (error: unknown) {
      show('danger', describeFailure(error))
      invalidateAll()
    } finally {
      setRetrying(null)
    }
  }

  const removeReasonReady =
    removeReason !== null && (removeReason !== '其他' || removeNote.trim() !== '')

  const columns: readonly DataTableColumn<DistrictResident>[] = [
    {
      key: 'name',
      header: '玩家',
      render: (row) => (
        <span className="flex flex-col gap-1">
          <span className="flex items-center gap-2">
            <span className="font-medium text-foreground">{row.playerName}</span>
            {row.isWarden ? (
              <Tag size="sm" tone="brand">
                区务长
              </Tag>
            ) : null}
          </span>
          <span className="text-muted-foreground text-xs">
            {formatShortDate(row.joinedAt, nowMs())} 由 {row.addedBy} 添加
            {row.plot === null ? '' : ` · 地块 ${row.plot.code}`}
          </span>
        </span>
      ),
      sortValue: (row) => row.playerName.toLowerCase(),
    },
    {
      key: 'sync',
      header: adminView ? 'Flan 生效状态' : '领地权限',
      render: (row) => (
        <SyncStatusCell
          adminView={adminView}
          onRetry={() => {
            void retrySync(row.playerName)
          }}
          resident={row}
          retrying={retrying === row.playerName}
        />
      ),
      sortValue: (row) => ['failed', 'pending', 'synced'].indexOf(row.syncStatus),
    },
    {
      key: 'lastSeen',
      header: '最近在线',
      render: (row) =>
        row.lastSeenAt === null ? (
          <span className="text-muted-foreground">从没进过服</span>
        ) : (
          formatRelative(row.lastSeenAt, nowMs())
        ),
      sortValue: (row) => row.lastSeenAt ?? -1,
    },
    {
      key: 'actions',
      header: '操作',
      render: (row) =>
        row.isWarden ? (
          <Hint content="区务长要先由管理员撤销，才能移出">
            <Button disabled size="xs" variant="destructive-outline">
              移出
            </Button>
          </Hint>
        ) : (
          <Button
            disabled={!abilities.manageResidents}
            onClick={() => {
              openRemove(row)
            }}
            size="xs"
            variant="destructive-outline"
          >
            移出
          </Button>
        ),
    },
  ]

  return (
    <Panel
      actions={<Tag tone="neutral">{`${String(residents.length)} 人`}</Tag>}
      description={`住户名单就是${academyFullName}的成员名单：加进来 = 加入学院，并获得本区居住权`}
      title="住户管理"
    >
      <div className="flex flex-col gap-4">
        {/* ==================== 添加住户 ==================== */}
        <div className="flex flex-col gap-2">
          <h3 className="font-medium text-foreground text-sm">添加住户</h3>
          <div className="flex flex-wrap items-center gap-2">
            <TextInput
              className="w-56"
              disabled={!abilities.manageResidents || adding}
              invalid={trimmedAddName !== '' && !addNameValid}
              maxLength={16}
              onChange={(next) => {
                setAddName(next)
                if (neverJoinedName !== null && next.trim().toLowerCase() !== neverJoinedName.toLowerCase()) {
                  setNeverJoinedName(null)
                }
              }}
              placeholder="玩家游戏 ID，如 Steve_01"
              value={addName}
            />
            <Button
              disabled={!abilities.manageResidents || !addNameValid || neverJoinedName !== null}
              loading={adding && neverJoinedName === null}
              onClick={() => {
                void submitAdd(false)
              }}
              size="sm"
            >
              <UserPlusIcon aria-hidden="true" />
              添加住户
            </Button>
          </div>
          {trimmedAddName !== '' && !addNameValid ? (
            <p className="text-destructive text-xs">玩家 ID 只能是 3-16 位英文字母、数字或下划线</p>
          ) : (
            <p className="text-muted-foreground text-xs">
              加进来就等于加入{academyFullName}。一个人只能属于一个学院。
            </p>
          )}
          {isMockActive() ? (
            <p className="text-muted-foreground text-xs">
              假数据提示：进过服的 {DISTRICT_PREVIEW_ADD_HINTS.joined.join(' / ')}；从没进过服的{' '}
              {DISTRICT_PREVIEW_ADD_HINTS.neverJoined}
            </p>
          ) : null}

          {neverJoinedName === null ? null : (
            <Surface tone="warning">
              <div className="flex flex-col gap-2">
                <p className="font-medium text-foreground text-sm">没有找到 {neverJoinedName} 的登录记录</p>
                <p className="text-muted-foreground text-xs">
                  TA 可能还没进过服务器，也可能是 ID 拼错了，请再核对一遍。确认添加的话，名单上会先记下
                  TA，但领地权限要等 TA 第一次登录服务器时才会生效。
                </p>
                <div className="flex flex-wrap gap-2">
                  <Button
                    loading={adding}
                    onClick={() => {
                      void submitAdd(true)
                    }}
                    size="sm"
                    variant="outline"
                  >
                    仍然添加（首次登录后生效）
                  </Button>
                  <Button
                    disabled={adding}
                    onClick={() => {
                      setNeverJoinedName(null)
                    }}
                    size="sm"
                    variant="ghost"
                  >
                    取消
                  </Button>
                </div>
              </div>
            </Surface>
          )}
        </div>

        <DistrictToastSlot onClear={clear} toast={toast} />

        {/* ==================== 名单 ==================== */}
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative">
            <SearchIcon
              aria-hidden="true"
              className="pointer-events-none absolute top-1/2 left-2.5 z-10 size-4 -translate-y-1/2 text-muted-foreground"
            />
            <TextInput
              className="w-56 pl-8"
              onChange={setSearch}
              placeholder="搜索玩家 ID"
              size="sm"
              type="search"
              value={search}
            />
          </div>
          <Dropdown
            className="w-44"
            onChange={(next) => {
              const matched = filterOptions.find((option) => option.value === next)
              if (matched !== undefined) {
                setStatusFilter(matched.value)
              }
            }}
            options={filterOptions}
            size="sm"
            value={statusFilter}
          />
          {counts.pending + counts.failed === 0 ? null : (
            <span className="text-muted-foreground text-xs">
              {counts.pending > 0 ? `${String(counts.pending)} 人待生效` : ''}
              {counts.pending > 0 && counts.failed > 0 ? '，' : ''}
              {counts.failed > 0 ? `${String(counts.failed)} 人同步失败` : ''}
            </span>
          )}
        </div>

        <div className="max-h-96 overflow-y-auto rounded-lg border">
          <DataTable
            columns={columns}
            emptyHint={residents.length === 0 ? '本区还没有住户' : '没有符合条件的住户'}
            rowKey={(row) => row.playerName}
            rows={visibleResidents}
          />
        </div>
      </div>

      <ConfirmDangerDialog
        confirmDisabled={!removeReasonReady}
        confirmLabel="确认移出"
        loading={removing}
        message={
          removeTarget === null
            ? ''
            : `${removalStanding(removeTarget.playerName, districtName, removeTarget.friendOfPlotCount, removeReason)}，并从${academyFullName}的成员名单里除名。以后想回来需要重新添加。${
                removeTarget.plot === null
                  ? ''
                  : `TA 的地块 ${removeTarget.plot.code} 会原地冻结 7 天：里面的东西不动，除管理员外谁都不能进出和操作（包括 TA 的朋友）；7 天后收回变空置。期间 TA 重新成为住户的话，管理员可以解除冻结还给 TA。${RECLAIM_LEFTOVER_NOTE}`
              }`
        }
        onConfirm={() => {
          void confirmRemove()
        }}
        onOpenChange={(open) => {
          if (!open) {
            setRemoveTarget(null)
          }
        }}
        open={removeTarget !== null}
        title={removeTarget === null ? '移出住户' : `移出 ${removeTarget.playerName}？`}
      >
        <div className="flex flex-col gap-1.5">
          <span className="text-muted-foreground text-xs">移出原因（必选，会写进操作记录）</span>
          <div className="flex flex-wrap gap-1.5">
            {REMOVE_REASONS.map((reason) => (
              <Button
                aria-pressed={removeReason === reason}
                disabled={removing}
                key={reason}
                onClick={() => {
                  setRemoveReason(reason)
                }}
                size="xs"
                variant={removeReason === reason ? 'default' : 'outline'}
              >
                {reason}
              </Button>
            ))}
          </div>
        </div>
        <div className="flex flex-col gap-1.5">
          <span className="text-muted-foreground text-xs">
            补充说明{removeReason === '其他' ? '（选了"其他"就必须写）' : '（选填）'}
          </span>
          <TextInput
            disabled={removing}
            invalid={removeReason === '其他' && removeNote.trim() === ''}
            maxLength={60}
            onChange={setRemoveNote}
            placeholder="比如：45 天没上线"
            size="sm"
            value={removeNote}
          />
          <span className="text-muted-foreground text-xs">游戏里暂时打不了中文，补充说明可以先用英文或数字。</span>
        </div>
        {removeTarget === null ? null : (
          <FriendConsequence friendOfPlotCount={removeTarget.friendOfPlotCount} reason={removeReason} />
        )}
      </ConfirmDangerDialog>
    </Panel>
  )
}

// ============================================================
// 移出后朋友身份怎么处理 (跟着所选原因实时变)
// ============================================================

/**
 * 移出确认框的第一句: TA 之后在本区按什么算。必须与下方 FriendConsequence 同口径 —— 只有违反区规才暂停朋友身份,
 * 其余原因 TA 在自己是朋友的那几块地里照旧按朋友算, 不能用一句"各户地块里改按外人算"把它也说没了。
 */
function removalStanding(
  playerName: string,
  districtName: string,
  friendOfPlotCount: number,
  reason: RemoveReason | null,
): string {
  const everywhere = `移出后，${playerName} 在${districtName}的公共区域和各户地块里`
  if (friendOfPlotCount === 0) {
    return `${everywhere}都改按外人算`
  }
  const plots = `那 ${String(friendOfPlotCount)} 块地`
  if (reason === null) {
    return `${everywhere}改按外人算；TA 是朋友的${plots}怎么算要看所选原因（见下方）`
  }
  return REASON_KIND[reason] === 'violation'
    ? `${everywhere}都改按外人算，TA 是朋友的${plots}也一样（朋友身份暂停，见下方）`
    : `移出后，${playerName} 在${districtName}的公共区域和其余各户地块里改按外人算；TA 是朋友的${plots}照旧按朋友算（见下方）`
}

/**
 * 已拍板: 因违反区规被移出的, TA 在本区别人地块的朋友身份自动暂停并通知户主; 其余原因保留朋友身份。
 * 只写块数不写是哪几块: 区务长看不到别人地块的朋友名单 (服务端也只给 friendOfPlotCount)。
 */
function FriendConsequence({
  friendOfPlotCount,
  reason,
}: {
  friendOfPlotCount: number
  reason: RemoveReason | null
}): ReactElement {
  if (friendOfPlotCount === 0) {
    return <p className="text-muted-foreground text-xs">TA 不是本区别人地块的朋友，朋友名单那边没有要处理的。</p>
  }
  if (reason === null) {
    return (
      <p className="text-muted-foreground text-xs">
        {`TA 是本区 ${String(friendOfPlotCount)} 块地的朋友。选好原因后，这里会写明朋友身份怎么处理。`}
      </p>
    )
  }
  if (REASON_KIND[reason] === 'violation') {
    return (
      <Surface tone="warning">
        <p className="text-foreground text-xs">
          {`因违反区规移出：TA 在本区 ${String(friendOfPlotCount)} 块地的朋友身份会暂停（暂停期间按外人算），户主会收到通知，可以自己恢复。`}
        </p>
      </Surface>
    )
  }
  return (
    <p className="text-muted-foreground text-xs">
      {`朋友身份保留：TA 仍是本区 ${String(friendOfPlotCount)} 块地的朋友，在那几块地里照旧按朋友算（朋友可以是任何玩家），要收回请找户主。`}
    </p>
  )
}

// ============================================================
// 生效状态单元格
// ============================================================

function SyncStatusCell({
  resident,
  adminView,
  retrying,
  onRetry,
}: {
  resident: DistrictResident
  adminView: boolean
  retrying: boolean
  onRetry: () => void
}): ReactElement {
  const tag = (
    <Tag size="sm" tone={SYNC_STATUS_TONE[resident.syncStatus]}>
      {SYNC_STATUS_LABEL[resident.syncStatus]}
    </Tag>
  )
  if (resident.syncStatus === 'synced') {
    return tag
  }
  if (resident.syncStatus === 'pending') {
    return <Hint content="TA 还没进过服务器，第一次登录时自动生效">{tag}</Hint>
  }
  if (!adminView) {
    return <Hint content="服务器没能写入领地权限，已交给管理员处理">{tag}</Hint>
  }
  /*
   * 失败原因只进悬停气泡, 不在格子里平铺: 单元格一律 nowrap, Flan 的真实报错常是一长串英文异常,
   * 平铺会把"移出"按钮顶出可视区或压在一起误点。完整原文在"管理员操作 -> Flan 同步"里逐条列着。
   */
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <Hint
        content={
          <span className="block max-w-64 whitespace-normal break-words">
            {resident.syncError ?? '服务端没有给出失败原因'}
          </span>
        }
      >
        {tag}
      </Hint>
      <Button loading={retrying} onClick={onRetry} size="xs" variant="outline">
        <RotateCwIcon aria-hidden="true" />
        重试
      </Button>
    </div>
  )
}
