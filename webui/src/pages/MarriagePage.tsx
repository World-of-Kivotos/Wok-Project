import { CheckIcon, CoinsIcon, HeartIcon, UsersIcon, XIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { useEffect, useMemo, useRef, useState } from 'react'
import {
  Button,
  ConfirmDangerDialog,
  Dropdown,
  EmptyBlock,
  ErrorBlock,
  FeedbackAlert,
  formatAmount,
  ItemIcon,
  ItemSlotGrid,
  LoadingBlock,
  Meter,
  Panel,
  Stat,
  Tag,
  TextInput,
} from '@/components/kit'
import type { DropdownOption, FeedbackTone, ItemSlotGridEntry, Tone } from '@/components/kit'
import { MS_PER_TICK, POLL_INTERVAL_MS, tickDeadline, usePolling } from '@/hooks/use-live-updates'
import { WebUiCallError } from '../lib/bridge'
import { callErrorText } from '../lib/errorText'
import { useItemDisplayNames } from '../lib/i18n'
import type {
  MarriageDivorceOutcomeCode,
  MarriageIncomingProposal,
  MarriageStatus,
  MarriageWedOutcomeCode,
  PlayerInventoryItem,
} from '../lib/types'
import { callMock, nowMs, useMockAction, useMockWorld } from '../mock'

/*
 * 婚姻 (`marriage.*`, Java 落点 com.miningdim.marriage.MarriageWebUiActions)。回执形状见 lib/types.ts。
 *
 * 五条必须照做的契约事实:
 *   1. **时间全是 overworld gameTime tick, 不是墙钟**: remarryCooldownTicks 是剩余 tick, 回执另发 nowTick。
 *      前端在收到那一刻折成本地基准再倒计时。1 天 = 1728000 tick。
 *   2. **求婚条目没有创建时刻也没有过期机制**: MarriageProposals 是一张不落盘的瞬态表, 只在服务端重启时
 *      随进程清空。旧版那句"剩余 X 分钟后过期"是编出来的, 已删。proposalId 就等于求婚方 UUID。
 *   3. **失败是正常业务结果**: wed / divorce 走 success=true 的回执体 (ok:false + outcomeCode), 服务端
 *      不下发中文句子 (只给 lang 键)。故本页自备两张按 outcomeCode 的文案表 —— 那是玩家看到的唯一出处。
 *   4. **离线玩家的名字一律是 null**: 全库零 GameProfileCache, 服务端拿不到离线玩家名。已婚但配偶离线时
 *      spouseUuid 恒有值而 spouseName 为 null, 必须有占位显示。
 *   5. **典礼可能要选人**: 有 2 份及以上已接受婚约时服务端拒绝替玩家猜, 回 INVALID_REQUEST +
 *      params{field:'partnerName', candidateCount:'N'}, 前端据此把按钮切成候选列表。
 *
 * 【中文输入 / 选人入口】marriage.propose 吃的是玩家名, 而中文输入 (W11) 已推迟 —— 只给一个输入框会让
 * 中文 ID 玩家在面板上永远求不了婚。故这里必须**同时**给出点选入口。但服务端至今没有"在线玩家名单"这条
 * action (清单 A16 的缺口), 真服里唯一可信的在线玩家名来源是 marriage.state 里那些**在线且带名字的求婚方**;
 * mock 世界的 otherPlayers 只是这个缺口的占位, 故只在假数据模式下并入候选, 免得真服里列出一串查无此人的名字。
 *
 * 缺口 (未在此臆造契约): "传送至配偶"没有任何对应 action, 下方那块是纯前端演示计时器, 不产生任何
 * 服务端状态变更, 文案已明说。
 */

const STATUS_LABEL: Record<MarriageStatus, string> = {
  single: '未婚',
  engaged: '已订婚',
  married: '已婚',
  cooldown: '再婚冷却中',
}

const STATUS_TONE: Record<MarriageStatus, Tone> = {
  single: 'neutral',
  engaged: 'info',
  married: 'success',
  cooldown: 'warning',
}

/** marriage.wed 的九态文案。服务端只发 lang 键 (且可能为 null), 这张表才是玩家看到的那句话。 */
const WED_OUTCOME_TEXT: Record<MarriageWedOutcomeCode, string> = {
  OK: '典礼完成, 你们结为夫妻',
  SELF_MARRIAGE: '不能和自己结婚',
  ALREADY_MARRIED: '你们中有人已经结婚了',
  NO_ENGAGEMENT_RING: '还没有婚戒, 先买一枚再来',
  INSUFFICIENT_FUNDS: '信用点不够办典礼',
  NO_ECONOMY: '经济子系统未就绪, 一分钱都没扣',
  REMARRY_COOLDOWN: '还在再婚冷却里, 等冷却结束再办',
  NO_ACCEPTED_PROPOSAL: '还没有人接受你的求婚',
  PARTNER_OFFLINE: '对方不在线, 典礼要求双方都在场',
}

/**
 * marriage.divorce 的四态文案 (OK 之外三条是失败)。OK 只表示"提交成功", **不代表关系已解除**——
 * 是否立即解除还是进入公示期由回执的 pending 字段决定, handleDivorceConfirm 据此另拼一句更具体的话,
 * 这里的 OK 文案只在两者都用不上的兜底路径出现 (理论上不会触发, 留着防 outcomeCode 穷尽访问漏字段)。
 */
const DIVORCE_OUTCOME_TEXT: Record<MarriageDivorceOutcomeCode, string> = {
  OK: '已提交离婚申请',
  NOT_MARRIED: '你现在没有婚姻关系',
  INSUFFICIENT_FUNDS: '信用点不够支付离婚费用, 一分未扣',
  NO_ECONOMY: '经济子系统未就绪, 一分未扣',
}

/**
 * 里程碑的中文名。服务端只发稳定 id (真实数据只有"领没领过"两个布尔, 没有 label 也没有达成时刻),
 * 全系统当前只定义了一个 id。
 */
const MILESTONE_LABEL: Record<string, string> = {
  first_marriage: '首次结婚福利',
}

type Banner = { tone: FeedbackTone; message: string } | null

type TeleportPhase = 'idle' | 'channeling' | 'cooldown'

/** 纯前端本地模拟, 数值无平衡依据, 只为让蓄力/冷却两种视觉态可被触发 (见文件头缺口说明)。 */
const TELEPORT_CHANNEL_MS = 3_000
const TELEPORT_COOLDOWN_MS = 20_000
const TELEPORT_TICK_MS = 100

/** 分钟级粒度即可: 再婚冷却动辄以天计, 不需要秒级刷新。 */
function formatDuration(remainingMs: number): string {
  if (remainingMs <= 0) {
    return '已结束'
  }
  const totalMinutes = Math.ceil(remainingMs / 60_000)
  if (totalMinutes < 60) {
    return `${String(totalMinutes)} 分钟`
  }
  const totalHours = Math.floor(totalMinutes / 60)
  const remMinutes = totalMinutes % 60
  if (totalHours < 24) {
    return remMinutes === 0 ? `${String(totalHours)} 小时` : `${String(totalHours)} 小时 ${String(remMinutes)} 分钟`
  }
  const days = Math.floor(totalHours / 24)
  const remHours = totalHours % 24
  return remHours === 0 ? `${String(days)} 天` : `${String(days)} 天 ${String(remHours)} 小时`
}

function toError(value: unknown): Error {
  return value instanceof Error ? value : new Error(String(value))
}

/** 离线玩家服务端拿不到名字 (全库零 GameProfileCache), 只能拿 UUID 前 8 位当占位, 不编一个假名字。 */
function playerLabel(name: string | null, uuid: string): string {
  return name ?? `离线玩家 ${uuid.slice(0, 8)}`
}

/** 同一条回退链在网格标签 (buildSharedSlots) 与详情面板两处都要用到, 抽出来避免两处各写一遍且悄悄漂移。 */
function resolveSharedItemName(
  item: PlayerInventoryItem,
  nameOf: (item: PlayerInventoryItem) => string,
): string {
  if (item.displayName !== undefined) {
    return item.displayName
  }
  return nameOf(item)
}

/** 格子在网格里的身份就是它的下标, 故这里按 slot 号逐位填充, 空位留空对象而不是跳过。 */
function buildSharedSlots(
  items: readonly PlayerInventoryItem[],
  totalSlots: number,
  nameOf: (item: PlayerInventoryItem) => string,
): ItemSlotGridEntry[] {
  return Array.from({ length: totalSlots }, (_unused, index) => {
    const item = items.find((entry) => entry.slot === index)
    if (item === undefined) {
      return {}
    }
    return {
      itemId: item.itemId,
      // 不带这个键的话, 195 种枪匠零件在共享背包网格里是同一张图。
      customModelData: item.customModelData,
      count: item.count,
      label: resolveSharedItemName(item, nameOf),
    }
  })
}

export function MarriagePage(): ReactElement {
  const world = useMockWorld()
  const stateQuery = useMockAction('marriage.state', {})
  const sharedQuery = useMockAction('marriage.sharedInv', {})
  /*
   * 求婚候选取自 player.roster (在线名册)。这条 action 存在的全部理由就是这里: 中文输入 (W11) 已推迟,
   * 只给一个输入框的话, 中文 ID 的玩家永远求不了婚。
   * 与另外两条查询并列放在最顶上而不是用到的地方: 下面有若干条 loading/error 的提前 return,
   * 在那之后调 Hook 会让每次渲染的 Hook 顺序不一致 (react-hooks/rules-of-hooks)。
   */
  const rosterQuery = useMockAction('player.roster', {})

  const [banner, setBannerValue] = useState<Banner>(null)
  /*
   * 回执的实例序号, 只用来当 React key。理由见 kit/Feedback.tsx 的退场闸门:
   * 退场那 140ms 内被同一句文案顶替时, 组件分辨不出这是新的一条, 旧的退场定时器会把它吞掉。
   */
  const bannerSeqRef = useRef(0)
  const setBanner = (next: Banner): void => {
    bannerSeqRef.current += 1
    setBannerValue(next)
  }
  const [busyAction, setBusyAction] = useState<string | null>(null)
  const [divorceConfirmOpen, setDivorceConfirmOpen] = useState(false)
  const [selectedSharedSlot, setSelectedSharedSlot] = useState<number | undefined>(undefined)
  const [proposeTarget, setProposeTarget] = useState('')
  const [manualTarget, setManualTarget] = useState('')
  /** 服务端以 candidateCount>=2 拒绝过一次自动定位之后置真, 典礼区从"办典礼"切成"选一位"。 */
  const [wedNeedsPartner, setWedNeedsPartner] = useState(false)
  const [wedPartner, setWedPartner] = useState('')

  /*
   * 求婚 / 应答都没有 S2C 推送 (服务端只发一条聊天消息), 对方面板只能靠重拉发现。
   * 间隔集中在 hooks/use-live-updates, 不在各页面自己写 setInterval。
   */
  usePolling(stateQuery.reload, POLL_INTERVAL_MS.marriageState)

  const [nowClock, setNowClock] = useState<number>(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => {
      setNowClock(Date.now())
    }, 30_000)
    return () => {
      window.clearInterval(timer)
    }
  }, [])

  const [teleportPhase, setTeleportPhase] = useState<TeleportPhase>('idle')
  const [teleportStartedAt, setTeleportStartedAt] = useState(0)
  const [teleportCooldownUntil, setTeleportCooldownUntil] = useState(0)
  const [teleportTick, setTeleportTick] = useState<number>(() => Date.now())

  useEffect(() => {
    if (teleportPhase === 'idle') {
      return
    }
    const timer = window.setInterval(() => {
      setTeleportTick(Date.now())
    }, TELEPORT_TICK_MS)
    return () => {
      window.clearInterval(timer)
    }
  }, [teleportPhase])

  useEffect(() => {
    if (teleportPhase === 'channeling' && teleportTick - teleportStartedAt >= TELEPORT_CHANNEL_MS) {
      setTeleportPhase('cooldown')
      setTeleportCooldownUntil(Date.now() + TELEPORT_COOLDOWN_MS)
      setBanner({ tone: 'info', message: '传送功能尚未开放, 刚才只是演示效果, 位置没有改变' })
    }
  }, [teleportPhase, teleportTick, teleportStartedAt])

  useEffect(() => {
    if (teleportPhase === 'cooldown' && teleportTick >= teleportCooldownUntil) {
      setTeleportPhase('idle')
    }
  }, [teleportPhase, teleportTick, teleportCooldownUntil])

  const sharedNameOf = useItemDisplayNames(
    sharedQuery.status === 'ready' ? sharedQuery.data.items : [],
  )

  const stateData = stateQuery.status === 'ready' ? stateQuery.data : null
  // 剩余 tick 只在收到回执那一刻有意义, 故在 data 换引用时折一次本地到期时刻。
  const remarryReadyAt = useMemo(
    () => (stateData === null ? 0 : tickDeadline(stateData.remarryCooldownTicks, nowMs())),
    [stateData],
  )
  // 待生效离婚 (spec 第六章闸 2 公示期): effectiveAtTick - nowTick 才是剩余 tick, 服务端发的是两个绝对 tick。
  const pendingDivorceReadyAt = useMemo(
    () =>
      stateData?.pendingDivorce == null
        ? 0
        : tickDeadline(stateData.pendingDivorce.effectiveAtTick - stateData.nowTick, nowMs()),
    [stateData],
  )

  async function handleBuyRing(): Promise<void> {
    setBusyAction('ring')
    try {
      const result = await callMock('marriage.buyRing', {})
      setBanner(
        result.engagementRingOwned
          ? { tone: 'success', message: `购买婚戒成功, 花费 ${formatAmount(result.costCredit)} 信用点` }
          : {
              // 背包满时引擎把戒指掉在脚下 (玩家已付费, 不吞货), 这一次 owned 是 false 而钱已扣。
              tone: 'warning',
              message: '钱已扣, 但背包里没找到婚戒 —— 多半是背包满了掉在脚下, 请检查脚下',
            },
      )
      stateQuery.reload()
    } catch (error) {
      setBanner({ tone: 'danger', message: callErrorText(toError(error)) })
    } finally {
      setBusyAction(null)
    }
  }

  async function handlePropose(targetName: string): Promise<void> {
    if (targetName === '') {
      setBanner({ tone: 'danger', message: '先选一位在线玩家, 或手动输入对方的玩家名' })
      return
    }
    setBusyAction('propose')
    try {
      const result = await callMock('marriage.propose', { targetName })
      setBanner({ tone: 'success', message: `已向 ${result.targetName} 发出求婚` })
      stateQuery.reload()
    } catch (error) {
      setBanner({ tone: 'danger', message: callErrorText(toError(error)) })
    } finally {
      setBusyAction(null)
    }
  }

  async function handleRespond(proposal: MarriageIncomingProposal, accept: boolean): Promise<void> {
    setBusyAction(`respond:${proposal.proposalId}`)
    const label = playerLabel(proposal.proposerName, proposal.proposerUuid)
    try {
      const result = await callMock('marriage.respond', {
        proposalId: proposal.proposalId,
        accept,
      })
      if (!accept) {
        setBanner({ tone: 'info', message: `已拒绝 ${label} 的求婚` })
      } else {
        // 接受求婚只是订婚 (status 通常是 engaged), 典礼是 marriage.wed 那一步 —— 不要说成"已结婚"。
        setBanner({
          tone: 'success',
          message: `已与 ${playerLabel(result.proposerName, result.proposerUuid)} 订婚, 双方在场时即可举行典礼`,
        })
      }
      stateQuery.reload()
    } catch (error) {
      setBanner({ tone: 'danger', message: callErrorText(toError(error)) })
    } finally {
      setBusyAction(null)
    }
  }

  async function handleWed(partnerName: string): Promise<void> {
    setBusyAction('wed')
    try {
      // 省略 partnerName 时服务端按"已接受婚约唯一确定"自动定位; 有 2 份及以上才要求指名。
      const result = await callMock('marriage.wed', partnerName === '' ? {} : { partnerName })
      setBanner({
        tone: result.ok ? 'success' : 'danger',
        message: WED_OUTCOME_TEXT[result.outcomeCode],
      })
      if (result.ok) {
        setWedNeedsPartner(false)
        setWedPartner('')
      }
      stateQuery.reload()
      sharedQuery.reload()
    } catch (error) {
      const thrown = toError(error)
      if (
        thrown instanceof WebUiCallError &&
        thrown.business !== null &&
        thrown.business.params?.field === 'partnerName'
      ) {
        const count = thrown.business.params.candidateCount
        setWedNeedsPartner(true)
        setBanner({
          tone: 'warning',
          message:
            count === undefined
              ? '有多份已接受的婚约, 请先选定伴侣再办典礼'
              : `有 ${count} 份已接受的婚约, 服务端不替你猜 —— 请先选定伴侣`,
        })
      } else {
        setBanner({ tone: 'danger', message: callErrorText(thrown) })
      }
    } finally {
      setBusyAction(null)
    }
  }

  async function handleDivorceConfirm(): Promise<void> {
    setBusyAction('divorce')
    try {
      const result = await callMock('marriage.divorce', {})
      if (!result.ok) {
        setBanner({ tone: 'danger', message: DIVORCE_OUTCOME_TEXT[result.outcomeCode] })
      } else if (result.alreadyPending) {
        // 幂等重复提交: 本次未二次扣费, 关系仍在公示期里, 不是新一轮离婚。
        setBanner({
          tone: 'info',
          message: '离婚申请已在公示期中, 未重复扣费; 可在游戏内聊天栏输入 /marriage divorce cancel 撤回',
        })
      } else if (result.pending) {
        // 关系尚未解除: 只是进入公示期, remarryCooldownTicks/divorceCount 是提交前的旧值, 不能拿来说"已离婚"。
        setBanner({
          tone: 'warning',
          message: `已提交离婚申请, 已扣 ${formatAmount(result.costCredit)} 信用点; 公示期 ${formatDuration(
            result.escrowTicks * MS_PER_TICK,
          )}内双方均可在聊天栏用 /marriage divorce cancel（发起方）或 confirm（对方）撤回或提前生效`,
        })
      } else {
        // escrowTicks 配 0 (公示期关闭) 或历史遗留场景才会立即结算, 此时才是真正的"已离婚"。
        setBanner({
          tone: 'warning',
          message: `已离婚, 已扣 ${formatAmount(result.costCredit)} 信用点; 再婚冷却 ${formatDuration(
            result.remarryCooldownTicks * MS_PER_TICK,
          )}`,
        })
      }
      setDivorceConfirmOpen(false)
      // 无论提交成立即结算还是进入公示期, 状态与共享背包 (公示期内被强制冻结) 都必须立刻重拉。
      stateQuery.reload()
      sharedQuery.reload()
    } catch (error) {
      setBanner({ tone: 'danger', message: callErrorText(toError(error)) })
    } finally {
      setBusyAction(null)
    }
  }

  if (stateQuery.status === 'loading') {
    return (
      <div className="flex flex-col gap-4">
        <LoadingBlock label="正在加载婚姻状态" size="lg" />
      </div>
    )
  }

  if (stateQuery.status === 'error') {
    return (
      <div className="flex flex-col gap-4">
        <ErrorBlock message={callErrorText(stateQuery.error)} onRetry={stateQuery.reload} />
      </div>
    )
  }

  const data = stateQuery.data

  const rosterNames = (rosterQuery.data?.players ?? []).map((entry) => entry.name)
  const proposerNames = data.incomingProposals
    .filter((proposal) => proposal.proposerOnline && proposal.proposerName !== null)
    .map((proposal) => proposal.proposerName ?? '')
  const proposeCandidates = [...new Set([...proposerNames, ...rosterNames])].filter(
    (name) => name !== '' && name !== world.player.name,
  )
  const proposeOptions: DropdownOption<string>[] = proposeCandidates.map((name) => ({
    value: name,
    label: name,
  }))

  /** 已接受的婚约 = 可以办典礼的对象。离线的仍列出来 (禁用), 否则玩家不知道自己在等谁上线。 */
  const acceptedPartners = data.incomingProposals.filter((proposal) => proposal.accepted)
  const wedOptions: DropdownOption<string>[] = acceptedPartners
    .filter((proposal) => proposal.proposerName !== null)
    .map((proposal) => ({
      value: proposal.proposerName ?? '',
      label: `${proposal.proposerName ?? ''}${proposal.proposerOnline ? '' : ' (离线, 典礼要求双方在场)'}`,
      disabled: !proposal.proposerOnline,
    }))

  const teleportChannelElapsed =
    teleportPhase === 'channeling' ? Math.min(TELEPORT_CHANNEL_MS, teleportTick - teleportStartedAt) : 0
  const teleportCooldownRemaining =
    teleportPhase === 'cooldown' ? Math.max(0, teleportCooldownUntil - teleportTick) : 0
  const teleportCooldownElapsed = TELEPORT_COOLDOWN_MS - teleportCooldownRemaining
  const canTeleport = data.status === 'married' && data.spouseOnline
  // 双方共用一个 pendingDivorce, 没有单独的"我的 uuid"字段可读: 发起方不等于配偶 uuid 即是自己。
  const isPendingDivorceInitiatedBySelf =
    data.pendingDivorce !== null && data.pendingDivorce.initiatorUuid !== data.spouseUuid

  const sharedSlots =
    sharedQuery.status === 'ready'
      ? buildSharedSlots(sharedQuery.data.items, sharedQuery.data.slots, sharedNameOf)
      : []
  const selectedSharedItem =
    sharedQuery.status === 'ready' && selectedSharedSlot !== undefined
      ? sharedQuery.data.items.find((item) => item.slot === selectedSharedSlot)
      : undefined

  function handleStartTeleport(): void {
    const now = Date.now()
    setTeleportStartedAt(now)
    setTeleportTick(now)
    setTeleportPhase('channeling')
  }

  return (
    <div className="flex flex-col gap-4">
      {banner === null ? null : (
        <FeedbackAlert
          key={bannerSeqRef.current}
          message={banner.message}
          onDismiss={() => {
            setBanner(null)
          }}
          tone={banner.tone}
        />
      )}

      {/* 状态摘要 */}
      <Panel actions={<Tag tone={STATUS_TONE[data.status]}>{STATUS_LABEL[data.status]}</Tag>} title="婚姻状态">
        <div className="flex flex-col gap-3">
          {data.status === 'married' && data.spouseUuid !== null ? (
            <>
              <Stat
                label="配偶"
                layout="inline"
                value={`${playerLabel(data.spouseName, data.spouseUuid)} (${data.spouseOnline ? '在线' : '离线'})`}
              />
              <Stat label="婚龄" layout="inline" value={`${String(data.marriedDays)} 天`} />
            </>
          ) : null}

          {data.status === 'married' || data.status === 'cooldown' ? (
            <Stat label="离婚次数" layout="inline" value={data.divorceCount} />
          ) : null}

          {/*
            冷却判据只看 remarryCooldownTicks: 四种 status 并非互斥 (冷却中照样能有已接受的婚约),
            服务端按 married > engaged > cooldown > single 取优先级, 只看 status 会漏掉"已订婚且冷却中"。
          */}
          {data.remarryCooldownTicks > 0 ? (
            <p className="text-warning text-sm">
              再婚冷却中, 还需 {formatDuration(remarryReadyAt - nowClock)} —— 冷却期间办典礼会被拒
            </p>
          ) : null}

          <div className="flex flex-wrap items-center gap-2">
            {data.engagementRingOwned ? (
              <Tag tone="success">已持有婚戒</Tag>
            ) : (
              <Button
                disabled={busyAction !== null}
                loading={busyAction === 'ring'}
                onClick={() => {
                  void handleBuyRing()
                }}
                variant="brand"
              >
                <CoinsIcon />
                购买婚戒 ({formatAmount(data.ringPriceCredit)} 信用点)
              </Button>
            )}
            <span className="text-muted-foreground text-xs">
              典礼 {formatAmount(data.weddingCostCredit)} 信用点 (双方各付一半) · 离婚{' '}
              {formatAmount(data.divorceCostCredit)} 信用点
            </span>
          </div>

          {data.milestones.length === 0 ? null : (
            <div className="flex flex-wrap items-center gap-2">
              {data.milestones.map((milestone) => (
                <Tag
                  key={milestone.milestoneId}
                  tone={milestone.claimedInCurrentMarriage ? 'success' : 'neutral'}
                >
                  {MILESTONE_LABEL[milestone.milestoneId] ?? milestone.milestoneId}
                  {milestone.claimedInCurrentMarriage
                    ? ' · 本段关系已领'
                    : /*
                       * claimedByPair 只在已婚时有意义 (单身时它与"没有关系记录"绑定, 恒 false),
                       * 此时不得拿它推断"首次结婚福利还能不能领"。
                       */
                      data.status === 'married' && milestone.claimedByPair
                      ? ' · 你们曾经领过, 复婚不重发'
                      : ' · 未领取'}
                </Tag>
              ))}
            </div>
          )}
        </div>
      </Panel>

      {/* 求婚 (仅单身可发起) */}
      {data.status === 'single' ? (
        <Panel title="求婚">
          <div className="flex flex-col gap-3">
            {data.outgoingProposal !== null ? (
              <p className="text-info text-sm">
                已向 {playerLabel(data.outgoingProposal.targetName, data.outgoingProposal.targetUuid)} 求婚
                {data.outgoingProposal.accepted ? ' · 对方已接受, 可以举行典礼了' : ' · 等待对方答复'}
                {data.outgoingProposal.targetOnline ? '' : ' (对方当前离线)'} —— 再求一次会覆盖这一条
              </p>
            ) : null}

            {data.engagementRingOwned ? null : <p className="text-warning text-sm">需先购买婚戒才能求婚</p>}

            {/*
              点选与手输**同时**提供: 中文输入未开放, 中文 ID 的玩家只能靠点选; 而点选的候选来源
              (在线玩家名单) 服务端还没有, 英文 ID 的玩家只能靠手输。少任何一半都会有一类玩家求不了婚。
            */}
            <div className="flex flex-wrap items-end gap-3">
              <div className="flex flex-col gap-1">
                <span className="text-muted-foreground text-xs">从在线玩家里点选</span>
                {proposeOptions.length === 0 ? (
                  <EmptyBlock
                    hint="服务端还没有在线玩家名单接口, 只有向你求过婚的在线玩家会出现在这里"
                    icon={<UsersIcon aria-hidden="true" />}
                    title="暂无可点选的对象"
                  />
                ) : (
                  <Dropdown
                    className="w-56"
                    disabled={!data.engagementRingOwned || busyAction !== null}
                    onChange={setProposeTarget}
                    options={proposeOptions}
                    placeholder="选择一位在线玩家"
                    value={proposeTarget}
                  />
                )}
              </div>
              <Button
                disabled={!data.engagementRingOwned || busyAction !== null || proposeTarget === ''}
                loading={busyAction === 'propose'}
                onClick={() => {
                  void handlePropose(proposeTarget)
                }}
                variant="brand"
              >
                <HeartIcon />
                向选中的人求婚
              </Button>
            </div>

            <div className="flex flex-wrap items-end gap-3">
              <div className="flex flex-col gap-1">
                <span className="text-muted-foreground text-xs">或手动输入玩家名 (只能输入英文/数字)</span>
                <TextInput
                  className="w-56"
                  disabled={!data.engagementRingOwned || busyAction !== null}
                  onChange={setManualTarget}
                  placeholder="对方的玩家名"
                  value={manualTarget}
                />
              </div>
              <Button
                disabled={!data.engagementRingOwned || busyAction !== null || manualTarget.trim() === ''}
                loading={busyAction === 'propose'}
                onClick={() => {
                  void handlePropose(manualTarget.trim())
                }}
                variant="outline"
              >
                向输入的名字求婚
              </Button>
            </div>
            <p className="text-muted-foreground text-xs">
              只能向当前在线的玩家求婚, 大小写不敏感; 中文名玩家请用上面的点选入口 (中文输入暂未开放)
            </p>
          </div>
        </Panel>
      ) : null}

      {/* 收到的求婚 */}
      {data.incomingProposals.length === 0 ? null : (
        <Panel title={`收到的求婚 (${String(data.incomingProposalTotal)} 份)`}>
          <div className="flex flex-col gap-3">
            {data.incomingProposalsTruncated ? (
              <p className="text-warning text-xs">
                共 {data.incomingProposalTotal} 份, 这里只列出最早登记的 {data.incomingProposals.length} 份
              </p>
            ) : null}
            {data.incomingProposals.map((proposal) => (
              <div className="flex flex-wrap items-center justify-between gap-3" key={proposal.proposalId}>
                <span className="flex flex-wrap items-center gap-2 text-foreground text-sm">
                  {playerLabel(proposal.proposerName, proposal.proposerUuid)}
                  <Tag size="sm" tone={proposal.proposerOnline ? 'success' : 'neutral'}>
                    {proposal.proposerOnline ? '在线' : '离线'}
                  </Tag>
                  {proposal.accepted ? <Tag size="sm" tone="info">已接受, 待办典礼</Tag> : null}
                </span>
                {proposal.accepted ? null : data.status === 'single' ? (
                  <div className="flex gap-2">
                    <Button
                      disabled={busyAction !== null}
                      loading={busyAction === `respond:${proposal.proposalId}`}
                      onClick={() => {
                        void handleRespond(proposal, true)
                      }}
                      size="sm"
                      variant="brand"
                    >
                      <CheckIcon />
                      接受
                    </Button>
                    <Button
                      disabled={busyAction !== null}
                      loading={busyAction === `respond:${proposal.proposalId}`}
                      onClick={() => {
                        void handleRespond(proposal, false)
                      }}
                      size="sm"
                      variant="destructive"
                    >
                      <XIcon />
                      拒绝
                    </Button>
                  </div>
                ) : (
                  <span className="text-muted-foreground text-sm">当前状态无法处理</span>
                )}
              </div>
            ))}
          </div>
        </Panel>
      )}

      {/* 典礼 */}
      {data.status === 'engaged' ? (
        <Panel title="婚礼典礼">
          <div className="flex flex-col gap-3">
            <p className="text-muted-foreground text-sm">
              典礼要求双方都在线, 费用 {formatAmount(data.weddingCostCredit)} 信用点 (双方各付一半),
              发起方需要持有婚戒。
            </p>
            {wedNeedsPartner ? (
              <div className="flex flex-wrap items-end gap-3">
                <div className="flex flex-col gap-1">
                  <span className="text-muted-foreground text-xs">选择伴侣 (有多份已接受的婚约)</span>
                  <Dropdown
                    className="w-56"
                    disabled={busyAction !== null}
                    onChange={setWedPartner}
                    options={wedOptions}
                    placeholder="选择一位已接受婚约的伴侣"
                    value={wedPartner}
                  />
                </div>
                <Button
                  disabled={busyAction !== null || wedPartner === ''}
                  loading={busyAction === 'wed'}
                  onClick={() => {
                    void handleWed(wedPartner)
                  }}
                  variant="brand"
                >
                  <HeartIcon />
                  与选中的人举行典礼
                </Button>
              </div>
            ) : (
              <div>
                <Button
                  disabled={busyAction !== null}
                  loading={busyAction === 'wed'}
                  onClick={() => {
                    void handleWed('')
                  }}
                  variant="brand"
                >
                  <HeartIcon />
                  举行典礼
                </Button>
              </div>
            )}
            {/*
              候选行取自 incomingProposals, 而它有 32 条硬上限 —— 超过 32 份已接受婚约时这张表取不全,
              故手填入口 (上面的求婚输入框同一条通路) 必须保留, 这里如实说明。
            */}
            {wedNeedsPartner && wedOptions.length === 0 ? (
              <p className="text-warning text-xs">
                候选列表是空的: 已接受婚约的伴侣可能都离线, 或求婚列表被 32 条上限截断了
              </p>
            ) : null}
          </div>
        </Panel>
      ) : null}

      {/* 离婚 + 传送 + 共享背包 (仅已婚可用) */}
      {data.status === 'married' ? (
        <>
          <Panel
            actions={
              data.pendingDivorce === null ? (
                <Button
                  onClick={() => {
                    setDivorceConfirmOpen(true)
                  }}
                  size="sm"
                  variant="destructive"
                >
                  申请离婚
                </Button>
              ) : null
            }
            title="离婚"
          >
            {data.pendingDivorce === null ? (
              <p className="text-muted-foreground text-sm">
                离婚需要 {formatAmount(data.divorceCostCredit)} 信用点, 提交后进入一段公示期 (时长以提交回执为准),
                公示期内共享背包会被冻结、按双方各自实际存入的物品清算 (不是全退发起方), 且发起方可在游戏内聊天栏
                用 <code>/marriage divorce cancel</code> 全额撤回; 公示期到期后自动解除, 之后进入再婚冷却
                (随离婚次数递增)。
              </p>
            ) : (
              // 撤回/确认是命令层专属能力, 面板目前没有对应 action —— 如实告知去处而不是假装能在这里操作。
              <div className="flex flex-col gap-2">
                <p className="text-warning text-sm">
                  {isPendingDivorceInitiatedBySelf ? '你已提交离婚申请' : '对方已提交离婚申请'}, 公示期还剩{' '}
                  {formatDuration(pendingDivorceReadyAt - nowClock)}, 到期后自动解除。
                </p>
                <p className="text-muted-foreground text-xs">
                  期间共享背包已被冻结。
                  {isPendingDivorceInitiatedBySelf
                    ? '如需撤回, 请在游戏内聊天栏输入 /marriage divorce cancel。'
                    : '如同意立即生效, 请在游戏内聊天栏输入 /marriage divorce confirm; 什么都不做则等公示期自然到期。'}
                </p>
              </div>
            )}
          </Panel>

          <Panel
            description="传送功能尚未开放, 下面的蓄力与冷却只是效果演示, 不会真的改变你的位置。"
            title="传送至配偶"
          >
            <div className="flex flex-col gap-3">
              {teleportPhase === 'idle' ? (
                <div className="flex items-center gap-2">
                  <Button disabled={!canTeleport} onClick={handleStartTeleport} variant="brand">
                    传送至配偶
                  </Button>
                  {canTeleport ? null : <span className="text-muted-foreground text-sm">配偶当前不在线</span>}
                </div>
              ) : teleportPhase === 'channeling' ? (
                <div className="flex flex-col gap-2">
                  <Meter label="蓄力中" max={TELEPORT_CHANNEL_MS} tone="brand" value={teleportChannelElapsed} />
                  <div>
                    <Button
                      onClick={() => {
                        setTeleportPhase('idle')
                      }}
                      size="sm"
                      variant="destructive"
                    >
                      取消
                    </Button>
                  </div>
                </div>
              ) : (
                <Meter
                  label={`冷却中, 还需 ${String(Math.ceil(teleportCooldownRemaining / 1000))} 秒`}
                  max={TELEPORT_COOLDOWN_MS}
                  tone="warning"
                  value={teleportCooldownElapsed}
                />
              )}
            </div>
          </Panel>

          <Panel
            description="这里只能查看, 存取物品请在游戏里打开共享背包。"
            title={`共享背包 (等级 ${String(data.sharedInvLevel)}, ${String(data.sharedInvSlots)} 格)`}
          >
            {sharedQuery.status === 'loading' ? (
              <LoadingBlock label="正在加载共享背包" />
            ) : sharedQuery.status === 'error' ? (
              <ErrorBlock message={callErrorText(sharedQuery.error)} />
            ) : !sharedQuery.data.married ? (
              <p className="text-muted-foreground text-sm">未婚状态没有共享背包。</p>
            ) : sharedQuery.data.slots === 0 ? (
              <p className="text-muted-foreground text-sm">当前等级还没有开放任何格子。</p>
            ) : (
              <div className="flex flex-col gap-3">
                <ItemSlotGrid
                  // 按箱子的 9 列排, 而不是把全部格子摊成一行 —— 升级后格数变多时那一行会横着溢出面板。
                  columns={Math.min(9, sharedQuery.data.slots)}
                  label="共享背包"
                  onSelect={setSelectedSharedSlot}
                  selectedSlot={selectedSharedSlot}
                  slots={sharedSlots}
                />
                <span className="text-muted-foreground text-xs">
                  容器共 {sharedQuery.data.capacity} 格, 当前等级开放前 {sharedQuery.data.slots} 格;
                  超出可见面的格子即使有货也不会下发
                </span>
                {selectedSharedItem === undefined ? (
                  <p className="text-muted-foreground text-sm">点击一个格子查看物品详情</p>
                ) : (
                  <div className="flex items-center gap-3">
                    <ItemIcon
                      customModelData={selectedSharedItem.customModelData}
                      itemId={selectedSharedItem.itemId}
                      scale={2}
                    />
                    <div className="flex flex-col">
                      <span className="text-foreground text-sm">
                        {resolveSharedItemName(selectedSharedItem, sharedNameOf)}
                      </span>
                      <span className="text-muted-foreground text-xs">数量: {selectedSharedItem.count}</span>
                    </div>
                  </div>
                )}
              </div>
            )}
          </Panel>
        </>
      ) : null}

      <ConfirmDangerDialog
        confirmLabel="提交离婚申请"
        loading={busyAction === 'divorce'}
        message={
          data.spouseUuid === null
            ? `提交离婚申请需要 ${formatAmount(data.divorceCostCredit)} 信用点, 之后进入一段公示期; 公示期内共享背包会被冻结、按双方各自实际存入的物品清算 (不是全退给你), 提交后可在游戏内聊天栏用 /marriage divorce cancel 全额撤回。`
            : `向 ${playerLabel(data.spouseName, data.spouseUuid)} 提交离婚申请需要 ${formatAmount(
                data.divorceCostCredit,
              )} 信用点, 之后进入一段公示期 (不是立即解除); 公示期内共享背包会被冻结、按双方各自实际存入的物品清算 (不是全退给你), 提交后可在游戏内聊天栏用 /marriage divorce cancel 全额撤回。`
        }
        onConfirm={() => {
          void handleDivorceConfirm()
        }}
        onOpenChange={setDivorceConfirmOpen}
        open={divorceConfirmOpen}
        title="确认提交离婚申请"
      />
    </div>
  )
}
