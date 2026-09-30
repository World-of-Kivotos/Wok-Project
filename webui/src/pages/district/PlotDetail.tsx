import { RotateCcwIcon, UserPlusIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { useState } from 'react'
import {
  Button,
  ConfirmDangerDialog,
  DataTable,
  type DataTableColumn,
  ErrorBlock,
  Hint,
  LoadingBlock,
  Panel,
  Stat,
  Surface,
  Tag,
  TextInput,
  Toggle,
} from '@/components/kit'
import { isMockActive } from '@/lib/bridge'
import { invalidateAll } from '@/lib/refresh'
import { DISTRICT_PREVIEW_ADD_HINTS, callMock, nowMs, useMockAction } from '@/mock'
import type {
  PlotAudience,
  PlotDetailResult,
  PlotFriend,
  PlotLogEntry,
  PlotPermissionItem,
} from '@/lib/types'
import { AreaChangeText } from './DistrictLogPanel'
import { DistrictToastSlot, useDistrictToast } from './DistrictToast'
import {
  OVERRIDE_TAG,
  PLOT_ACTOR_LABEL,
  PLOT_AUDIENCE_LABEL,
  PLOT_LOG_ACTION_LABEL,
  PLOT_LOG_ACTION_TONE,
  RECLAIM_LEFTOVER_NOTE,
  boundsSize,
  describeFailure,
  dimensionLabel,
  failureCode,
  formatArea,
  formatBounds,
  formatDateTime,
  formatRelative,
  formatShortDate,
  freezeLeftText,
  onOffLabel,
} from './format'
import {
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
 * 一块地块的详情: 地块信息 / 朋友名单 / 三列权限 (朋友 · 其他住户 · 外人) / 地块记录。
 *
 * 两处复用: 户主本人的"我的地块"页签, 与管理员在"本区地块"页签里点开的任一地块。差别全来自回执:
 *   - viewerRelation = owner: 户主本人, 能改 (editable);
 *   - viewerRelation = admin: 管理员看别人的地块。回执 editable 为真表示**允许代改**, 但界面默认只读 ——
 *     户主的家由户主做主, 要先打开"代改"才解锁, 且每次改动都记成"管理员代改"写进地块记录;
 *   - 空置地块 editable 恒为假 (没有户主, 朋友无从谈起, 三列恒为默认);
 *   - 冻结中的地块 editable 也为假: 朋友名单与三列是原户主存下的设置, 冻结期间实际全关, 解除冻结后照原样恢复。
 * 区务长看别人的地块服务端直接拒绝 (plot.detail PERMISSION_DENIED), 走不到这里。
 * 朋友因违反区规被移出本区时会自动暂停 (名单上标"已暂停"), 户主可以恢复 (plot.restoreFriend)。
 *
 * 三列权限表与公共区域的两列表共用 PermissionRows (行版式、开关格、改一格的状态机), 条目也是同一份目录去掉区域规则。
 * 三列的值在回执里都是明确的开或关, 没有"跟随自管区" —— 见 lib/types.ts K11 段头"防漏"。
 *
 * 游戏内打不了中文: 这里唯一的输入是朋友的玩家 ID, 本来就只有英文数字下划线。
 */

/** Minecraft 正版 ID 的字符集与长度。只用来提前把按钮置灰, 服务端照样会再验一遍。 */
const PLAYER_NAME_PATTERN = /^[A-Za-z0-9_]{3,16}$/

const PLOT_AUDIENCES: readonly PlotAudience[] = ['friend', 'resident', 'outsider']
const PLOT_COLUMNS: readonly PermissionColumn[] = PLOT_AUDIENCES.map((id) => ({ id, label: PLOT_AUDIENCE_LABEL[id] }))

export function PlotDetail({ districtId, plotId }: { districtId: string; plotId: string }): ReactElement {
  const query = useMockAction('plot.detail', { districtId, plotId })
  // 管理员的"代改"开关。换一块地时由调用方以 key 重建本组件, 不会把上一块地的解锁带过来。
  const [overriding, setOverriding] = useState(false)

  if (query.status === 'error') {
    return <ErrorBlock code="plot.detail" message={query.error.message} onRetry={query.reload} />
  }
  if (query.data === null) {
    return <LoadingBlock label="正在读取地块" />
  }

  const data = query.data
  const isOwner = data.viewerRelation === 'owner'
  const canEdit = data.editable && (isOwner || overriding)

  return (
    <div className="flex flex-col gap-4">
      <PlotInfoPanel data={data} />
      {!isOwner && data.editable && data.plot.ownerName !== null ? (
        <OverrideBar onChange={setOverriding} overriding={overriding} ownerName={data.plot.ownerName} />
      ) : null}
      <PlotFriendsPanel data={data} districtId={districtId} editable={canEdit} />
      <PlotPermissionTable data={data} districtId={districtId} editable={canEdit} />
      <PlotLogPanel log={data.log} />
    </div>
  )
}

// ============================================================
// 地块信息
// ============================================================

function PlotInfoPanel({ data }: { data: PlotDetailResult }): ReactElement {
  const { plot } = data
  const isOwner = data.viewerRelation === 'owner'
  const failed = plot.syncStatus === 'failed'
  // 住户和区务长视角不出现"Flan": 他们只需要知道领地权限生没生效。
  const statusTag = failed ? (isOwner ? '领地权限未生效' : 'Flan 写入失败') : isOwner ? '领地权限已生效' : 'Flan 已生效'

  const frozen = plot.frozen

  return (
    <Panel
      actions={<Tag tone={failed ? 'danger' : 'success'}>{statusTag}</Tag>}
      description={
        isOwner
          ? '这是你的地块，你是户主'
          : frozen !== null
            ? `冻结中，原户主 ${frozen.formerOwnerName} · 本区 Flan 领地下的子领地`
            : plot.ownerName === null
              ? '空置，没有户主 · 本区 Flan 领地下的子领地'
              : `户主 ${plot.ownerName} · 本区 Flan 领地下的子领地`
      }
      title={`地块 ${plot.code}`}
    >
      <div className="flex flex-col gap-4">
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
          <Stat
            label={frozen === null ? '户主' : '原户主'}
            value={
              frozen !== null
                ? frozen.formerOwnerName
                : (plot.ownerName ?? <span className="text-muted-foreground">空置</span>)
            }
          />
          <Stat hint={boundsSize(plot.bounds)} label="面积" value={formatArea(plot.area)} />
          <Stat
            className="col-span-2"
            hint={dimensionLabel(plot.bounds.dimension)}
            label="范围坐标"
            value={formatBounds(plot.bounds)}
          />
        </div>
        {isOwner ? (
          <Surface tone="brand">
            <p className="text-foreground text-sm">
              你是户主：朋友名单和下面三列只有你能改（管理员代改会在地块记录里标成“管理员代改”）。地块范围区务长改不了，只有管理员能调，调了会写进地块记录通知你。管理员（OP）可以进入所有地块并拥有全部权限，不受下面这些开关限制。
            </p>
          </Surface>
        ) : null}
        {!isOwner && plot.status === 'vacant' ? (
          <Surface>
            <p className="text-foreground text-sm">
              这块地现在空置：没有户主，朋友名单是空的，三列都是默认值，不能改。本区开放购买时，没有地块的住户可以直接买下，先到先得。
            </p>
          </Surface>
        ) : null}
        {frozen === null ? null : (
          <Surface tone="warning">
            <div className="flex flex-col gap-1">
              <p className="font-medium text-foreground text-sm">{freezeLeftText(frozen.reclaimAt, nowMs())}</p>
              <p className="text-foreground text-sm">
                {`原户主 ${frozen.formerOwnerName} 已被移出本区。地块里的东西不动，除管理员外所有人（包括原来的朋友）都不能进出和操作；${formatDateTime(frozen.reclaimAt)} 到期由服务器收回变空置。${RECLAIM_LEFTOVER_NOTE}下面的朋友和三列是原户主存下的设置，冻结期间不生效也不能改，解除冻结后照原样恢复。`}
              </p>
            </div>
          </Surface>
        )}
        {failed ? (
          <Surface tone="warning">
            <p className="text-foreground text-sm">
              {isOwner
                ? '服务器上一次没能把这块地的设置写进领地，有几项可能还没生效。下一次改动时会整块重新写入；一直不行请找管理员。'
                : `Flan 子领地写入失败：${plot.syncError ?? '服务端没有给出失败原因'}。下一次有人改这块地时，服务器会把整块地重新写一遍；单独的“重新写入”按钮还没做。`}
            </p>
          </Surface>
        ) : null}
      </div>
    </Panel>
  )
}

/** 管理员看别人的地块: 默认只读, 打开"代改"才解锁。开着时整条换成警示色, 免得忘了自己在改别人的家。 */
function OverrideBar({
  ownerName,
  overriding,
  onChange,
}: {
  ownerName: string
  overriding: boolean
  onChange: (next: boolean) => void
}): ReactElement {
  return (
    <Surface tone={overriding ? 'warning' : 'neutral'}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-0.5">
          <span className="font-medium text-foreground text-sm">
            {overriding ? `正在代改 ${ownerName} 的地块` : `只读：这是 ${ownerName} 的地块`}
          </span>
          <span className="text-muted-foreground text-xs">
            户主的家由户主做主，默认只读。打开代改后，每一次改动都记成“管理员代改”写进这块地的记录，户主看得到。
          </span>
        </div>
        <Toggle checked={overriding} label="代改" onChange={onChange} />
      </div>
    </Surface>
  )
}

// ============================================================
// 朋友
// ============================================================

/**
 * 暂停的朋友此刻在这块地按哪一列算。暂停不等于一律按外人: 被移出之后又被加回本区的, 现在是本区住户,
 * 按"其他住户"那一列算 (服务端下发的 isResident 已是现在的身份)。
 */
function suspendedStandingNote(suspended: readonly PlotFriend[]): string {
  const back = suspended.filter((friend) => friend.isResident).map((friend) => friend.playerName)
  if (back.length === 0) {
    return '暂停期间按外人算'
  }
  if (back.length === suspended.length) {
    return '现在又是本区住户了，暂停期间按其他住户算'
  }
  return `暂停期间按外人算；其中 ${back.join('、')} 现在又是本区住户，按其他住户算`
}

function PlotFriendsPanel({
  districtId,
  data,
  editable,
}: {
  districtId: string
  data: PlotDetailResult
  editable: boolean
}): ReactElement {
  const { toast, show, clear } = useDistrictToast()
  const { plot, friends, friendLimit } = data
  const adminEdit = data.viewerRelation === 'admin'
  const isOwner = data.viewerRelation === 'owner'
  // 移出住户时对区务长说了"户主已收到通知": 户主这边必须真有一处显眼的通知, 不能只靠名单上一个小标签。
  const suspendedFriends = friends.filter((friend) => friend.suspended)
  const suspendedNames = suspendedFriends.map((friend) => friend.playerName)

  const [addName, setAddName] = useState('')
  const [adding, setAdding] = useState(false)
  /** 服务端说"这人没进过服"之后, 等操作者确认的那个名字。输入框一改就作废。 */
  const [neverJoinedName, setNeverJoinedName] = useState<string | null>(null)
  /** 待移除的朋友与弹窗开合分开存: 关窗时若一并清空, 收起动画那几帧标题会闪成空白。 */
  const [removeTarget, setRemoveTarget] = useState<PlotFriend | null>(null)
  const [removeOpen, setRemoveOpen] = useState(false)
  const [removing, setRemoving] = useState(false)
  /** 正在恢复的那位朋友。 */
  const [restoring, setRestoring] = useState<string | null>(null)

  /*
   * 失去编辑资格 (管理员关掉了代改) 时丢掉悬着的"仍然添加"确认: 否则界面已写着只读, 那个按钮却还能把朋友
   * 写进别人的地块。按 React 文档"根据上一次渲染调整状态"的写法在渲染期处理, 不等 effect 慢一帧。
   */
  const [prevEditable, setPrevEditable] = useState(editable)
  if (prevEditable !== editable) {
    setPrevEditable(editable)
    if (!editable) {
      setNeverJoinedName(null)
    }
  }

  const trimmed = addName.trim()
  const nameValid = PLAYER_NAME_PATTERN.test(trimmed)
  const full = friends.length >= friendLimit

  async function submitAdd(allowNeverJoined: boolean): Promise<void> {
    const name = trimmed
    setAdding(true)
    try {
      const result = await callMock('plot.addFriend', {
        districtId,
        plotId: plot.plotId,
        playerName: name,
        allowNeverJoined,
      })
      invalidateAll()
      setAddName('')
      setNeverJoinedName(null)
      if (result.friend.syncStatus === 'pending') {
        show(
          'warning',
          `${result.friend.playerName} 还没进过服务器，要等 TA 第一次登录才会生效。在那之前名单上显示“待生效”。`,
          `已把 ${result.friend.playerName} 加成朋友`,
        )
      } else {
        show('success', `${result.friend.playerName} 现在是这块地的朋友了，按“朋友”那一列的设置生效。`)
      }
    } catch (error: unknown) {
      if (failureCode(error) === 'PLAYER_NEVER_JOINED') {
        // 不是失败, 是服务端要操作者确认一件事: 留在原地等选择, 不弹红色回执 (同住户管理)。
        setNeverJoinedName(name)
        return
      }
      show('danger', describeFailure(error))
      // 失败也重拉: 被拒多半是手上那份已经过期 (地块被收回、朋友已满…), 同 PermissionRows 的 commit。
      invalidateAll()
    } finally {
      setAdding(false)
    }
  }

  async function restore(friend: PlotFriend): Promise<void> {
    setRestoring(friend.playerName)
    try {
      await callMock('plot.restoreFriend', { districtId, plotId: plot.plotId, playerName: friend.playerName })
      invalidateAll()
      show(
        'success',
        `已恢复 ${friend.playerName} 的朋友身份，TA 在这块地里重新按“朋友”那一列算。${adminEdit ? '这次改动记成了“管理员代改”。' : ''}`,
      )
    } catch (error: unknown) {
      show('danger', describeFailure(error))
      invalidateAll()
    } finally {
      setRestoring(null)
    }
  }

  async function confirmRemove(): Promise<void> {
    if (removeTarget === null) {
      return
    }
    const target = removeTarget
    setRemoving(true)
    try {
      await callMock('plot.removeFriend', { districtId, plotId: plot.plotId, playerName: target.playerName })
      invalidateAll()
      show(
        'success',
        `已移除 ${target.playerName}。TA 在这块地里改按“${target.isResident ? '其他住户' : '外人'}”那一列算。`,
      )
    } catch (error: unknown) {
      show('danger', describeFailure(error))
      invalidateAll()
    } finally {
      setRemoving(false)
      // 成功失败都关: 失败回执画在弹窗背后, 不关就把服务端那句话藏起来了 (同住户管理)。
      setRemoveOpen(false)
    }
  }

  const addButton = (
    <Button
      disabled={!editable || !nameValid || full || neverJoinedName !== null}
      loading={adding && neverJoinedName === null}
      onClick={() => {
        void submitAdd(false)
      }}
      size="sm"
    >
      <UserPlusIcon aria-hidden="true" />
      添加朋友
    </Button>
  )

  return (
    <Panel
      actions={<Tag tone={full ? 'warning' : 'neutral'}>{`${String(friends.length)} / ${String(friendLimit)} 人`}</Tag>}
      description="朋友在这块地里按下面“朋友”那一列的设置行事；朋友不能再把权限转给别人"
      title="朋友"
    >
      <div className="flex flex-col gap-4">
        {plot.ownerName === null ? null : (
          <div className="flex flex-col gap-2">
            <div className="flex flex-wrap items-center gap-2">
              <TextInput
                className="w-56"
                disabled={!editable || adding || full}
                invalid={trimmed !== '' && !nameValid}
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
              {full ? <Hint content={`朋友已满 ${String(friendLimit)} 人，先移除一位再加`}>{addButton}</Hint> : addButton}
            </div>
            {trimmed !== '' && !nameValid ? (
              <p className="text-destructive text-xs">玩家 ID 只能是 3-16 位英文字母、数字或下划线</p>
            ) : (
              <p className="text-muted-foreground text-xs">
                朋友可以是任何玩家，不一定是本区住户。最多 {friendLimit} 人（上限按地块档位定，还在定）。
                {adminEdit ? '你加的朋友会记成“管理员代改”。' : ''}
              </p>
            )}
            {isMockActive() && editable ? (
              <p className="text-muted-foreground text-xs">
                假数据提示：进过服的 {DISTRICT_PREVIEW_ADD_HINTS.joined.join(' / ')}；从没进过服的{' '}
                {DISTRICT_PREVIEW_ADD_HINTS.neverJoined}
              </p>
            ) : null}

            {neverJoinedName === null || !editable ? null : (
              <Surface tone="warning">
                <div className="flex flex-col gap-2">
                  <p className="font-medium text-foreground text-sm">没有找到 {neverJoinedName} 的登录记录</p>
                  <p className="text-muted-foreground text-xs">
                    TA 可能还没进过服务器，也可能是 ID 拼错了，请再核对一遍。确认添加的话，名单上会先记下
                    TA，但要等 TA 第一次登录服务器时才会生效。
                  </p>
                  <div className="flex flex-wrap gap-2">
                    <Button
                      disabled={!editable || full}
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
        )}

        <DistrictToastSlot onClear={clear} toast={toast} />

        {isOwner && suspendedNames.length > 0 ? (
          <Surface tone="warning">
            <p className="text-foreground text-sm">
              {`${suspendedNames.join('、')} 因违反区规被移出过本区，在这块地的朋友身份已自动暂停（${suspendedStandingNote(suspendedFriends)}）。可以点下面的「恢复」，也可以直接移除。`}
            </p>
          </Surface>
        ) : null}

        {plot.frozen === null ? null : (
          <p className="text-muted-foreground text-xs">
            冻结中：下面是原户主存下的朋友名单，冻结期间除管理员外谁都不能进出，解除冻结后照原样恢复；到期收回则清空。
          </p>
        )}

        {friends.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            {plot.status === 'vacant' ? '空置的地块没有朋友。' : '还没有朋友。'}
          </p>
        ) : (
          <ul className="grid gap-2 md:grid-cols-2">
            {friends.map((friend) => (
              <li
                className="flex items-center justify-between gap-2 rounded-lg border px-3 py-2"
                key={friend.playerName}
              >
                <div className="flex min-w-0 flex-col gap-0.5">
                  <span className="flex flex-wrap items-center gap-1.5">
                    <span className="truncate font-medium text-foreground text-sm">{friend.playerName}</span>
                    {friend.syncStatus === 'pending' ? (
                      <Hint content="TA 还没进过服务器，第一次登录时自动生效">
                        <Tag size="sm" tone="warning">
                          待生效
                        </Tag>
                      </Hint>
                    ) : null}
                    {friend.syncStatus === 'failed' ? (
                      <Hint content="服务器没能把 TA 写进这块地的朋友组，下一次改动这块地时会重写">
                        <Tag size="sm" tone="danger">
                          未生效
                        </Tag>
                      </Hint>
                    ) : null}
                    {friend.suspended ? (
                      <Hint
                        content={`TA 因违反区规被移出过本区，朋友身份自动暂停：暂停期间在这块地按${
                          friend.isResident ? '其他住户算（TA 现在又是本区住户了）' : '外人算'
                        }。${isOwner ? '你可以恢复，也可以直接移除' : '户主可以恢复，也可以直接移除'}`}
                      >
                        <Tag size="sm" tone="warning">
                          已暂停
                        </Tag>
                      </Hint>
                    ) : null}
                    {friend.isResident ? (
                      <Hint
                        content={
                          friend.suspended
                            ? '本区住户：朋友身份暂停期间在这块地按其他住户算，恢复后重新按朋友算'
                            : '本区住户被加成朋友，在这块地里按朋友算，不再按其他住户算'
                        }
                      >
                        <Tag size="sm" tone="neutral">
                          本区住户
                        </Tag>
                      </Hint>
                    ) : null}
                  </span>
                  <span className="text-muted-foreground text-xs">
                    {formatShortDate(friend.addedAt, nowMs())}{' '}
                    {plot.ownerName !== null && friend.addedBy !== plot.ownerName
                      ? `由 ${friend.addedBy} 代加`
                      : '添加'}
                    {friend.suspendedAt === null ? '' : ` · ${formatShortDate(friend.suspendedAt, nowMs())} 暂停`}
                  </span>
                </div>
                <div className="flex shrink-0 flex-wrap justify-end gap-1.5">
                  {friend.suspended ? (
                    <Button
                      disabled={!editable || restoring !== null}
                      loading={restoring === friend.playerName}
                      onClick={() => {
                        void restore(friend)
                      }}
                      size="xs"
                      variant="outline"
                    >
                      恢复
                    </Button>
                  ) : null}
                  <Button
                    disabled={!editable}
                    onClick={() => {
                      setRemoveTarget(friend)
                      setRemoveOpen(true)
                    }}
                    size="xs"
                    variant="destructive-outline"
                  >
                    移除
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>

      <ConfirmDangerDialog
        confirmLabel="移除"
        loading={removing}
        message={
          removeTarget === null
            ? ''
            : `${
                removeTarget.suspended
                  ? `${removeTarget.playerName} 的朋友身份本来就暂停着；移除后从名单上去掉，腾出一个名额。`
                  : `移除后马上生效：${removeTarget.playerName} 在这块地里改按“${removeTarget.isResident ? '其他住户' : '外人'}”那一列算。`
              }以后想加回来可以再加。${adminEdit ? '这次改动会记成“管理员代改”。' : ''}`
        }
        onConfirm={() => {
          void confirmRemove()
        }}
        onOpenChange={setRemoveOpen}
        open={removeOpen && removeTarget !== null}
        title={removeTarget === null ? '移除朋友' : `移除朋友 ${removeTarget.playerName}？`}
      />
    </Panel>
  )
}

// ============================================================
// 三列权限
// ============================================================

interface PlotCellChange {
  item: PlotPermissionItem
  audience: PlotAudience
  from: boolean
  to: boolean
}

/** 开启前要先确认的一格 (给其他住户或外人开高风险项; 朋友那一列不拦)。 */
interface PlotPendingRisk {
  item: PlotPermissionItem
  audience: Exclude<PlotAudience, 'friend'>
  risk: string
}

/**
 * 一行里自相矛盾的组合。Flan 里组权限写了"关"就是明确禁止, 不会因为外人那一格开着就放行:
 * 朋友关着而别人开着 = 朋友反而比路人做得少, 多半是手滑, 提一句。
 */
function plotRowWarning(friend: boolean, resident: boolean, outsider: boolean): string | null {
  if (!friend && (resident || outsider)) {
    return '别人能做、朋友反而不能'
  }
  if (!resident && outsider) {
    return '外人能做、其他住户反而不能'
  }
  return null
}

function PlotPermissionTable({
  districtId,
  data,
  editable,
}: {
  districtId: string
  data: PlotDetailResult
  editable: boolean
}): ReactElement {
  const { toast, show, clear } = useDistrictToast()
  const cells = usePermissionCells(data, '已生效，已记入地块记录')
  const [pendingRisk, setPendingRisk] = useState<PlotPendingRisk | null>(null)
  const [riskOpen, setRiskOpen] = useState(false)
  const [resetOpen, setResetOpen] = useState(false)
  const [resetting, setResetting] = useState(false)

  const table = cells.table
  const { plot } = table
  const isOwner = table.viewerRelation === 'owner'
  const adminEditNote = isOwner ? '' : '这次改动会记成“管理员代改”。'

  function valueFor(item: PlotPermissionItem, audience: PlotAudience): boolean {
    return cells.valueFor(cellKey(item.permissionId, audience), item.current[audience])
  }

  const changes: PlotCellChange[] = table.groups.flatMap((group) =>
    group.items.flatMap((item) =>
      PLOT_AUDIENCES.flatMap((audience): PlotCellChange[] => {
        const now = valueFor(item, audience)
        const fallback = item.defaults[audience]
        return now === fallback ? [] : [{ item, audience, from: now, to: fallback }]
      }),
    ),
  )

  function commit(item: PlotPermissionItem, audience: PlotAudience, enabled: boolean): void {
    void cells.commit(cellKey(item.permissionId, audience), enabled, async () => {
      const result = await callMock('plot.setPermission', {
        districtId,
        plotId: plot.plotId,
        permissionId: item.permissionId,
        audience,
        enabled,
      })
      return { settled: result.item.current[audience], changed: result.logEntry !== null }
    })
  }

  function requestChange(item: PlotPermissionItem, audience: PlotAudience, enabled: boolean): void {
    // 给其他住户或外人开高风险项要先确认: 这是别人的家。给朋友开、或是关掉, 都不拦。
    if (enabled && audience !== 'friend' && item.risk !== null) {
      setPendingRisk({ item, audience, risk: item.risk })
      setRiskOpen(true)
      return
    }
    commit(item, audience, enabled)
  }

  async function reset(): Promise<void> {
    setResetting(true)
    cells.clearSaved()
    try {
      const result = await callMock('plot.resetPermissions', { districtId, plotId: plot.plotId })
      cells.replaceTable(result.detail)
      invalidateAll()
      show(
        'success',
        result.changedCount === 0
          ? '本来就是默认设置，没有需要恢复的项。'
          : `已把 ${String(result.changedCount)} 处恢复成默认值，都记进了地块记录。`,
      )
    } catch (error: unknown) {
      show('danger', describeFailure(error))
      invalidateAll()
    } finally {
      setResetting(false)
      setResetOpen(false)
    }
  }

  const resetButton = (
    <Button
      disabled={!editable || changes.length === 0 || cells.anyBusy || resetting}
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
        changes.length === 0 ? <Hint content="三列都已经是默认设置">{resetButton}</Hint> : resetButton
      }
      description="逐项决定朋友、其他住户、外人在这块地里能做什么，改动马上生效"
      title="地块权限"
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-col gap-1 text-muted-foreground text-xs">
          <p>
            {isOwner
              ? '你是户主，在这块地里永远拥有全部权限（改地块范围这类除外），所以表里没有你。'
              : plot.frozen !== null
                ? `这块地冻结中：下面是原户主 ${plot.frozen.formerOwnerName} 存下的设置，冻结期间实际三列全关，除管理员外谁都不能进出和操作；解除冻结后照原样恢复。`
                : plot.ownerName === null
                  ? '这块地空置，三列都是默认值。'
                  : `户主 ${plot.ownerName} 在这块地里永远拥有全部权限（改地块范围这类除外），所以表里没有户主。`}
            {'管理员（OP）可以进入所有地块并拥有全部权限，不受这张表限制。'}
            {'朋友 = 户主加的朋友；其他住户 = 本区别的住户；外人 = 不是本区住户的任何玩家。本区住户被加成朋友，就按朋友那一列算；名单上“待生效”“同步失败”的住户，在地块里也暂时按外人算。'}
          </p>
          <p>
            {changes.length === 0
              ? '现在全部是默认设置。'
              : `现在有 ${String(changes.length)} 处不是默认值，那几行标着“非默认”。`}
            每一次改动都会写进地块记录{isOwner ? '' : '，管理员改的标成“管理员代改”'}。
          </p>
          {isOwner ? null : (
            <p>
              {'这块地是本区 Flan 领地下的子领地：户主在“户主”组，朋友在“朋友”组，其他住户在“居民”组，外人是子领地的全域权限。地块内每项都写成明确值，不沿用自管区设置；只有区域规则跟着自管区。'}
            </p>
          )}
        </div>

        <DistrictToastSlot onClear={clear} toast={toast} />

        {table.groups.map((group) => (
          <section className="flex flex-col" key={group.groupId}>
            <div className={`${PERMISSION_ROW_GRID[3]} border-b pb-1.5`}>
              <h3 className="font-medium text-foreground text-sm">{group.label}</h3>
              {PLOT_COLUMNS.map((column) => (
                <span className="text-center text-muted-foreground text-xs" key={column.id}>
                  {column.label}
                </span>
              ))}
            </div>
            <ul className="flex flex-col divide-y">
              {group.items.map((item) => (
                <li className={`${PERMISSION_ROW_GRID[3]} py-2`} key={item.permissionId}>
                  <ItemText
                    diffTag={columnsDiffTag(
                      PLOT_AUDIENCES.map((audience) => ({
                        label: PLOT_AUDIENCE_LABEL[audience],
                        now: valueFor(item, audience),
                        fallback: item.defaults[audience],
                      })),
                    )}
                    error={noticeFor(cells.rowError, item.permissionId, PLOT_COLUMNS, false)}
                    item={item}
                    saved={noticeFor(cells.lastSaved, item.permissionId, PLOT_COLUMNS, true)}
                    warning={plotRowWarning(
                      valueFor(item, 'friend'),
                      valueFor(item, 'resident'),
                      valueFor(item, 'outsider'),
                    )}
                  />
                  {PLOT_AUDIENCES.map((audience) => (
                    <div className="flex items-center justify-center" key={audience}>
                      <ToggleCell
                        ariaLabel={`${PLOT_AUDIENCE_LABEL[audience]}：${item.label}`}
                        disabled={
                          !editable || resetting || cells.busy[cellKey(item.permissionId, audience)] !== undefined
                        }
                        onChange={(next) => {
                          requestChange(item, audience, next)
                        }}
                        value={valueFor(item, audience)}
                      />
                    </div>
                  ))}
                </li>
              ))}
            </ul>
          </section>
        ))}

        <p className="border-t pt-3 text-muted-foreground text-xs">
          PvP、爆炸这些区域规则全区统一，只有管理员能在自管区里改，地块里不能单独改。改领地范围、管理权限组这些谁都不能在这里开。
        </p>
      </div>

      <ConfirmDangerDialog
        confirmLabel="仍然开启"
        message={
          pendingRisk === null
            ? ''
            : `${pendingRisk.risk}${
                pendingRisk.audience === 'resident'
                  ? '这一格管的是本区除户主和朋友以外的所有住户'
                  : '这一格管的是不是本区住户的任何玩家，包括路过的人'
              }。开了马上生效，随时可以再关掉。${adminEditNote}`
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
            : `给${PLOT_AUDIENCE_LABEL[pendingRisk.audience]}开启“${pendingRisk.item.label}”？`
        }
      />

      <ConfirmDangerDialog
        confirmLabel="恢复默认"
        loading={resetting}
        message={`朋友、其他住户、外人三列都会回到地块的默认值。下面 ${String(changes.length)} 处会变，马上生效；朋友名单不动。${adminEditNote}`}
        onConfirm={() => {
          void reset()
        }}
        onOpenChange={setResetOpen}
        open={resetOpen}
        title={`把地块 ${plot.code} 的权限恢复默认？`}
      >
        <ul className="flex max-h-40 flex-col gap-1 overflow-y-auto text-xs">
          {changes.map((change) => (
            <li
              className="flex items-center justify-between gap-3"
              key={cellKey(change.item.permissionId, change.audience)}
            >
              <span className="min-w-0 text-foreground">
                {PLOT_AUDIENCE_LABEL[change.audience]}：{change.item.label}
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
// 地块记录
// ============================================================

function plotLogTarget(row: PlotLogEntry): string {
  if (row.permission !== null) {
    return `对${PLOT_AUDIENCE_LABEL[row.permission.audience]}：${row.permission.label}`
  }
  if (row.targetName === null) {
    return '—'
  }
  // 冻结、解冻、收回的对象是原户主, 买地的是买家: 不写明的话一个光秃秃的名字读不出是谁。
  if (row.action === 'vacate' || row.action === 'freeze' || row.action === 'unfreeze') {
    return `原户主 ${row.targetName}`
  }
  return row.action === 'purchase' ? `买家 ${row.targetName}` : row.targetName
}

function PlotLogWhat({ row }: { row: PlotLogEntry }): ReactElement {
  return (
    <span className="flex flex-col items-start gap-0.5">
      <Tag size="sm" tone={PLOT_LOG_ACTION_TONE[row.action]}>
        {PLOT_LOG_ACTION_LABEL[row.action]}
      </Tag>
      {row.area === null ? <span className="text-foreground text-xs">{plotLogTarget(row)}</span> : null}
    </span>
  )
}

/**
 * 管理员替户主改了设置的那几条 (服务端标 onBehalfOfOwner) 标"管理员代改"。冻结、收回、暂停朋友是移出住户时连带
 * 发生的, 划地块、立即收回是管理身份的事, 都不是改户主的设置, 不标。
 */
function PlotLogActor({ row }: { row: PlotLogEntry }): ReactElement {
  return (
    <span className="flex flex-col items-start gap-0.5">
      <span className="text-foreground">{row.actorName}</span>
      {row.onBehalfOfOwner ? (
        <Tag size="sm" tone="warning">
          {OVERRIDE_TAG}
        </Tag>
      ) : (
        <span className="text-muted-foreground text-xs">{PLOT_ACTOR_LABEL[row.actorRole]}</span>
      )}
    </span>
  )
}

function PlotLogDetail({ row }: { row: PlotLogEntry }): ReactElement {
  if (row.permission !== null) {
    // 改成的值不上色 (同本区操作记录): "开"不等于"好"。
    return (
      <span className="flex items-center gap-1.5">
        <span className="text-muted-foreground">{onOffLabel(row.permission.from)}</span>
        <span aria-hidden="true" className="text-muted-foreground">
          →
        </span>
        <span className="text-foreground">{onOffLabel(row.permission.to)}</span>
        {row.reason === null ? null : <span className="text-muted-foreground text-xs">（{row.reason}）</span>}
      </span>
    )
  }
  if (row.area !== null) {
    return (
      <span className="block max-w-56 whitespace-normal">
        <AreaChangeText change={row.area} />
      </span>
    )
  }
  if (row.reason === null) {
    return <span className="text-muted-foreground">—</span>
  }
  // 冻结、收回、暂停朋友只带一句固定说明 (移出原因不进地块记录, 见 lib/types.ts PlotLogEntry);
  // 仍允许折行, 免得撑出横向滚动。
  return <span className="block max-w-56 whitespace-normal">{row.reason}</span>
}

const PLOT_LOG_COLUMNS: readonly DataTableColumn<PlotLogEntry>[] = [
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
    render: (row) => <PlotLogActor row={row} />,
    sortValue: (row) => row.actorName.toLowerCase(),
  },
  {
    key: 'what',
    header: '操作',
    render: (row) => <PlotLogWhat row={row} />,
    sortValue: (row) => row.action,
  },
  {
    key: 'detail',
    header: '说明',
    render: (row) => <PlotLogDetail row={row} />,
  },
]

/** 地块记录表。管理员看已删除地块的墓碑时也用它 (换个标题)。 */
export function PlotLogPanel({
  log,
  title = '地块记录',
  description = '谁在什么时候加、移除、恢复了朋友，改了哪一项、改了范围；管理员代改会单独标出来',
}: {
  log: readonly PlotLogEntry[]
  title?: string | undefined
  description?: string | undefined
}): ReactElement {
  return (
    <Panel
      actions={<Tag tone="neutral">{`${String(log.length)} 条`}</Tag>}
      description={description}
      padded={false}
      title={title}
    >
      <div className="max-h-80 overflow-y-auto">
        <DataTable columns={PLOT_LOG_COLUMNS} emptyHint="这块地还没有任何记录" rowKey={(row) => row.entryId} rows={log} />
      </div>
    </Panel>
  )
}
