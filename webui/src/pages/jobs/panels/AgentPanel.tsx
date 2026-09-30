import type { ReactElement } from 'react'
import { useMemo, useState } from 'react'
import {
  Button,
  EmptyBlock,
  ErrorBlock,
  FeedbackAlert,
  LoadingBlock,
  Meter,
  Panel,
  Stat,
  Surface,
  Tag,
} from '@/components/kit'
import { MS_PER_TICK, POLL_INTERVAL_MS, tickDeadline, usePolling } from '@/hooks/use-live-updates'
import { WebUiCallError } from '../../../lib/bridge'
import { callErrorText } from '../../../lib/errorText'
import { useItemNames } from '../../../lib/i18n'
import type {
  AgentAffixPool,
  AgentBountyAcceptOutcomeCode,
  AgentBountyBoard,
  AgentBountyEntry,
  AgentBountyPeriod,
  AgentScanFieldName,
  AgentScanLive,
  AgentScanMechanic,
  AgentScanResult,
  AgentScanTarget,
  AgentSealOutcomeCode,
  AgentSealResult,
  AgentStateResult,
  ChampionAffixQuality,
} from '../../../lib/types'
import { callMock, nowMs, useMockAction } from '../../../mock'
import { formatCountdown, toError, useLiveNow } from './shared'

/**
 * 特勤干员面板 (`job.agent.state` / `job.agent.scan` / `job.agent.seal`, Java 落点
 * com.miningdim.job.agent.AgentWebUiActions)。回执形状见 lib/types.ts。
 *
 * 六条决定本页形状的契约事实:
 *   1. **分级解密**: 目标身上的词条是逐条裁决的, 未解密行的 affixId / displayKey / category 三格
 *      同时是 JSON null —— 这是服务端在回执层刻意做的脱敏 (真值送进浏览器等于在开发者工具里明码
 *      给出词条身份)。故未解密行只能渲染成不可点的占位, 列表 key 只能用行下标。
 *   2. **坐标绑在 L8**: pos 为 null 时只有距离可显示; 且判据取的是**发出那次脉冲时**的干员等级,
 *      L7 升到 L8 之后旧快照里的 pos 仍是 null, 要坐标必须重扫。
 *   3. **时间一律是剩余 tick**: 服务端不发绝对时刻, 前端在收到回执那一刻折成本地基准再倒计时
 *      (与矿工面板同纪律)。快照倒计时归零即 targetNetworkId 作废, 封印按钮必须跟着变灰。
 *   4. **悬赏板全在服务端**: 可接悬赏、槽位、进度、翻期都由服务端悬赏板裁决 (AgentBountyWebUi); 接取回执带
 *      整段最新 bounty, 前端直接替换, 不在本地推算"还剩几个槽"。完成即自动发奖, 没有领取按钮。
 *   5. **数值情报的 null 是"加密"不是 0**: 有效血 / 减伤 / 子弹抗性 / 攻击单击移速 / 技能时序 / 品质各格
 *      按脉冲等级逐格解密, 未解锁即 null, 画成"需要 Lv.N" (等级取 state 的 scanFieldUnlockLevels, 不在
 *      前端另抄)。减伤率、单击补足的真值本来就可能是 0, 把 null 画成 0 就是在对玩家撒谎。只给数, 不替玩家
 *      下"它怕什么"的结论。
 *   6. **实时透视靠轮询**: L9+ 脉冲的目标带 live, 服务端每次被读 job.agent.state 才重读活数值; 本页只在这种
 *      快照有效期内按 POLL_INTERVAL_MS.agentLiveIntel 轮询, 快照一过期即停。
 *
 * scanOnline=false (Champions 未加载) 必须显示"扫描离线"而不是渲染一张空的候选表: 前者是"这台服务器
 * 现在读不到精英词条", 后者是"周围没有精英", 对玩家是完全不同的两句话。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}

/** job.agent.seal 的九态结果码文案。服务端不下发中文 (专用服务端不加载 lang), 这张表就是唯一出处。 */
const SEAL_OUTCOME_TEXT: Record<AgentSealOutcomeCode, string> = {
  OK: '封印成功',
  NOT_BOUND: '精英怪系统未加载, 封印不可用',
  NO_TARGET: '目标已离场或不再是精英, 请重新扫描',
  AFFIX_NOT_SEALABLE: '这条词条封不了 (外来词条或纯防御词条)',
  CATEGORY_LOCKED: '该类别尚未解锁 (被动需 3 级, 机制需 8 级)',
  STAR_TOO_HIGH: '目标星级高于你当前可封的上限',
  ALL_SLOTS_OCCUPIED: '这只精英的封印位已经满了',
  AFFIX_ALREADY_SEALED: '这条词条已被其他干员封印中',
  ON_COOLDOWN: '该类别的封印还在冷却中',
}

/**
 * 词条品质色标 (ChampionStarAffix 9A.7: 普通灰白 / 中级绿 / 高级蓝 / 超凡紫 / 闪耀金; 色值即 Java
 * AffixQuality.displayColor)。类名必须是完整字面量: Tailwind 只为源码里整串出现过的类生成样式。
 */
const QUALITY_STYLE: Record<ChampionAffixQuality, { label: string; dotClass: string }> = {
  COMMON: { label: '普通', dotClass: 'bg-[#c8c8c8]' },
  UNCOMMON: { label: '中级', dotClass: 'bg-[#55c040]' },
  RARE: { label: '高级', dotClass: 'bg-[#3070e0]' },
  EPIC: { label: '超凡', dotClass: 'bg-[#9b30e0]' },
  LEGENDARY: { label: '闪耀', dotClass: 'bg-[#e0b020]' },
}

interface SealFeedback {
  readonly ok: boolean
  readonly message: string
}

/** job.agent.bounty.accept 的结果码文案 (服务端不下发中文)。 */
const BOUNTY_ACCEPT_TEXT: Record<AgentBountyAcceptOutcomeCode, string> = {
  OK: '接取成功',
  DISABLED: '悬赏已被服务器关闭',
  NOT_FOUND: '这张悬赏已过期 (跨日或跨周), 面板已刷新',
  ALREADY_ACCEPTED: '已经接过这张悬赏',
  LOCKED: '该周期的悬赏尚未解锁',
  NO_SLOT: '本期悬赏位已经接满',
  STAR_TOO_HIGH: '目标星级高于你当前可接的上限',
}

const AFFIX_POOL_TEXT: Record<AgentAffixPool, string> = {
  SURVIVAL: '生存',
  COMBAT: '战斗',
  MOBILITY: '机动',
  SKILL: '技能',
}

/** 接取回执覆盖在哪一份 state 之上: state 换了引用 (重查过) 就以新 state 为准, 覆盖自动作废。 */
interface BountyOverride {
  readonly base: AgentStateResult
  readonly bounty: AgentBountyBoard
  readonly activeAgent: boolean
}

function bountyTitle(entry: AgentBountyEntry): string {
  const count = String(entry.requiredCount)
  const star = String(entry.minStar)
  if (entry.targetType === 'KILL_WITH_AFFIX_CATEGORY' && entry.targetPool !== null) {
    return `讨伐 ${count} 只 ${star} 星及以上、带${AFFIX_POOL_TEXT[entry.targetPool]}类词条的精英`
  }
  return `讨伐 ${count} 只 ${star} 星及以上的精英`
}

function rewardText(credit: number, xp: number, azure: number): string {
  const parts = [`${credit.toLocaleString('zh-CN')} 信用点`, `${xp.toLocaleString('zh-CN')} 干员经验`]
  if (azure > 0) {
    parts.push(`${String(azure)} 青辉石`)
  }
  return parts.join(' · ')
}

/** 翻期倒计时以天/小时计, 分:秒 的格式在这里没法读。 */
function formatResetIn(deadlineMs: number, now: number): string {
  const totalMinutes = Math.max(0, Math.ceil((deadlineMs - now) / 60_000))
  const days = Math.floor(totalMinutes / 1440)
  const hours = Math.floor((totalMinutes % 1440) / 60)
  const minutes = totalMinutes % 60
  if (days > 0) {
    return `${String(days)} 天 ${String(hours)} 小时`
  }
  if (hours > 0) {
    return `${String(hours)} 小时 ${String(minutes)} 分`
  }
  return `${String(minutes)} 分`
}

/** 一次脉冲的本地快照: 回执本身 + 收到它的时刻 (冷却与快照有效期都从这一刻起算)。 */
interface ScanSnapshot {
  result: AgentScanResult
  receivedAt: number
}

/** 当前该渲染哪一份候选表 —— 刚扫的那次, 或 state 带回来的脉冲投影, 两者同形。 */
interface ActiveSnapshot {
  targets: readonly AgentScanTarget[]
  expiresAt: number
  truncated: boolean
  scanOnline: boolean
  glowingHighlight: boolean
}

type UnlockLevels = Record<AgentScanFieldName, number>

/**
 * 封印的两道**前置门**不走 outcomeCode 而是抛 INVALID_REQUEST, 且服务端刻意把"没有这条词条"与
 * "有但尚未解密"合并成同一句拒绝 (否则客户端能拿公开注册名逐个试探, 二十次请求就在 L1 反推出整张词条表)。
 * 前端只能按 params.field 分成两句话, 不能再细分。
 */
function sealRejectionText(error: Error): string {
  if (error instanceof WebUiCallError && error.business !== null) {
    const field = error.business.params?.field
    if (field === 'targetNetworkId') {
      return '这次扫描的快照已经失效, 请重新发起探测脉冲'
    }
    if (field === 'affixId') {
      return '这条词条现在封不了 (多半是还没解密), 重新扫描后再试'
    }
  }
  return callErrorText(error)
}

function sealResultText(result: AgentSealResult): string {
  const base = SEAL_OUTCOME_TEXT[result.outcomeCode]
  if (result.ok) {
    return `${base} · 持续 ${String(result.windowSeconds)} 秒, 该类别冷却 ${String(result.cooldownSeconds)} 秒`
  }
  if (result.outcomeCode === 'ON_COOLDOWN') {
    return `${base} (还需约 ${String(Math.ceil((result.categoryCooldownRemainingTicks * MS_PER_TICK) / 1000))} 秒)`
  }
  return base
}

function lockedText(level: number): string {
  return `需要 Lv.${String(level)}`
}

function percentText(fraction: number): string {
  return `${(fraction * 100).toFixed(1)}%`
}

/** 属性值原样显示: 整数不补小数, 其余保留到能看出差别的位数 (移速属性常见 0.23 这种量级)。 */
function attributeText(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(3)
}

/** 一格数值情报: null 即加密, 显示解锁等级; 否则格式化真值。 */
function intelText(value: number | null, unlockLevel: number, format: (value: number) => string): string {
  return value === null ? lockedText(unlockLevel) : format(value)
}

/** 数值情报网格 (L3-L6)。L10 快照带实时全属性时改用现值, 与快照同口径。 */
function IntelGrid({ target, unlock }: { target: AgentScanTarget; unlock: UnlockLevels }): ReactElement {
  const live = target.live?.attributes ?? null
  const effectiveHp = live?.effectiveHp ?? target.effectiveHp
  const armor = live?.armor ?? target.armor
  const damageReduction = live?.damageReductionPct ?? target.damageReductionPct
  const bulletResistance = live?.bulletResistancePct ?? target.bulletResistancePct
  const attackDamage = live?.attackDamage ?? target.attackDamage
  const singleHit = live?.singleHitPct ?? target.singleHitPct
  const movementSpeed = live?.movementSpeed ?? target.movementSpeed
  return (
    <div className="flex flex-col gap-1">
      {live === null ? null : <span className="text-muted-foreground text-xs">以下数值为实时读数 (Lv.10 全属性实时)</span>}
      <div className="grid grid-cols-3 gap-x-6 gap-y-1">
        <Stat
          label="有效血量"
          layout="inline"
          value={intelText(effectiveHp, unlock.EFFECTIVE_HP, (value) => String(Math.round(value)))}
        />
        <Stat label="护甲" layout="inline" value={intelText(armor, unlock.ARMOR_DR_PERCENT, attributeText)} />
        <Stat
          label="减伤 (满层)"
          layout="inline"
          value={intelText(damageReduction, unlock.ARMOR_DR_PERCENT, percentText)}
        />
        <Stat
          label="子弹抗性"
          layout="inline"
          value={intelText(bulletResistance, unlock.BULLET_RESISTANCE, percentText)}
        />
        <Stat label="攻击" layout="inline" value={intelText(attackDamage, unlock.ATTACK_AND_SPEED, attributeText)} />
        <Stat
          label="近战单击"
          layout="inline"
          value={intelText(singleHit, unlock.ATTACK_AND_SPEED, (value) =>
            // 0 是真值: 这只精英的近战不按玩家最大血量补足, 伤害就是攻击值。
            value === 0 ? '按攻击值结算' : `玩家最大血量 ${percentText(value)}`,
          )}
        />
        <Stat
          label="移速属性"
          layout="inline"
          value={intelText(movementSpeed, unlock.ATTACK_AND_SPEED, attributeText)}
        />
      </div>
    </div>
  )
}

/** L9 实时透视一块: 未解锁 / 信号丢失 / 实时读数三态。 */
function LiveBlock({ live, unlock }: { live: AgentScanLive | null; unlock: UnlockLevels }): ReactElement {
  if (live === null) {
    return <span className="text-muted-foreground text-xs">实时透视 (血量 / 吸收 / 层数) {lockedText(unlock.REALTIME_NUMBERS)}</span>
  }
  if (!live.tracked || live.currentHp === null || live.maxHp === null || live.maxHp <= 0) {
    return <span className="text-muted-foreground text-xs">实时信号丢失: 目标已死亡、离场或所在区块已卸载</span>
  }
  return (
    <div className="flex flex-col gap-1">
      <Meter
        label="实时血量"
        max={live.maxHp}
        size="sm"
        tone="danger"
        value={Math.min(live.currentHp, live.maxHp)}
        valueText={`${String(Math.round(live.currentHp))} / ${String(Math.round(live.maxHp))}`}
      />
      <span className="text-muted-foreground text-xs">
        伤害吸收 {String(Math.round(live.absorption ?? 0))} · 叠在你身上: 寒霜 {String(live.frostStacksOnYou ?? 0)} 层,
        燃烧 {String(live.burningStacksOnYou ?? 0)} 层
      </span>
    </div>
  )
}

/** L7 技能时序一行; 缺键 = 这条技能没有这个概念, 不画 0。 */
function mechanicText(mechanic: AgentScanMechanic): string {
  const parts: string[] = []
  if (mechanic.chargeSeconds !== undefined) {
    parts.push(`起手窗口 ${String(mechanic.chargeSeconds)} 秒`)
  }
  if (mechanic.interruptDamagePerPlayer !== undefined) {
    parts.push(`打断门槛 每人 ${String(mechanic.interruptDamagePerPlayer)} 伤害`)
  }
  if (mechanic.cooldownSeconds !== undefined) {
    parts.push(`冷却 ${String(mechanic.cooldownSeconds)} 秒`)
  }
  return parts.join(' · ')
}

export function AgentPanel(): ReactElement {
  const stateQuery = useMockAction('job.agent.state', EMPTY_PAYLOAD)
  const now = useLiveNow()

  const [scan, setScan] = useState<ScanSnapshot | null>(null)
  const [scanning, setScanning] = useState(false)
  const [scanError, setScanError] = useState<Error | null>(null)
  const [discoveryNotice, setDiscoveryNotice] = useState<string | null>(null)
  const [sealingKey, setSealingKey] = useState<string | null>(null)
  const [sealFeedback, setSealFeedback] = useState<Record<string, SealFeedback>>({})
  const [bountyOverride, setBountyOverride] = useState<BountyOverride | null>(null)
  const [acceptingId, setAcceptingId] = useState<string | null>(null)
  const [acceptFeedback, setAcceptFeedback] = useState<Record<string, SealFeedback>>({})

  const data = stateQuery.status === 'ready' ? stateQuery.data : null

  /*
   * 接取回执带回整段最新悬赏板, 叠在当前 state 上显示; state 一旦重查换了引用, 覆盖即作废 (新 state 已含接取结果)。
   * 这样接取不必重查整个 job.agent.state —— 重查会闪骨架屏, 把刚展示的扫描候选表盖掉。
   */
  const override = bountyOverride !== null && bountyOverride.base === data ? bountyOverride : null
  const bounty = override !== null ? override.bounty : data !== null ? data.bounty : null
  const activeAgent = override !== null ? override.activeAgent : data !== null && data.activeAgent
  /* 翻期剩余秒数只在收到它那一刻有意义 (与扫描 CD 同纪律), 故每份悬赏板折一次本地时刻。 */
  const bountyResetAt = useMemo(
    () =>
      bounty === null
        ? { daily: 0, weekly: 0 }
        : {
            daily: nowMs() + bounty.dailyResetRemainingSeconds * 1000,
            weekly: nowMs() + bounty.weeklyResetRemainingSeconds * 1000,
          },
    [bounty],
  )

  /*
   * 回执里的剩余 tick 只在"收到它那一刻"有意义, 故在 data 换引用时折一次本地时刻。
   * data 只在一次新回执到达时才换引用, 这份折算因此恰好每条回执做一次。
   */
  const stateReceivedAt = useMemo(() => (data === null ? 0 : nowMs()), [data])
  const stateScanReadyAt = useMemo(
    () => (data === null ? 0 : tickDeadline(data.scanCooldownRemainingTicks, nowMs())),
    [data],
  )
  const stateSnapshotExpiresAt = useMemo(
    () => (data === null ? 0 : tickDeadline(data.snapshotRemainingTicks, nowMs())),
    [data],
  )
  const passiveReadyAt = useMemo(
    () => (data === null ? 0 : tickDeadline(data.seal.passiveCooldownRemainingTicks, nowMs())),
    [data],
  )
  const mechanicReadyAt = useMemo(
    () => (data === null ? 0 : tickDeadline(data.seal.mechanicCooldownRemainingTicks, nowMs())),
    [data],
  )

  const scanExpiresAt =
    scan === null ? 0 : tickDeadline(scan.result.snapshotRemainingTicks, scan.receivedAt)
  /*
   * 冷却取"state 折出来的"与"刚扫那次折出来的"较大值: 冷却只会因扫描而变长, 这样不必为刷新一个字段
   * 专门重查整个 job.agent.state。
   */
  const scanReadyAt =
    scan === null
      ? stateScanReadyAt
      : Math.max(stateScanReadyAt, tickDeadline(scan.result.scanCooldownRemainingTicks, scan.receivedAt))

  /*
   * 两份同形投影取**较新**的一份: L9+ 的实时透视靠轮询 state 刷新活数值, 若刚扫那次的回执一直压在上面,
   * 轮询回来的新读数永远画不出来。扫描之后的第一次 state 回执已是新脉冲的投影 (扫描期间不可能再有旧快照:
   * 冷却与快照同长), 故按到达先后取即可。
   */
  const scanValid = scan !== null && scanExpiresAt > now
  const stateValid = data !== null && stateSnapshotExpiresAt > now
  let activeSnapshot: ActiveSnapshot | null = null
  if (data !== null && stateValid && (scan === null || !scanValid || stateReceivedAt >= scan.receivedAt)) {
    activeSnapshot = {
      targets: data.targets,
      expiresAt: stateSnapshotExpiresAt,
      truncated: data.truncated,
      scanOnline: data.scanOnline,
      glowingHighlight: data.glowingHighlight,
    }
  } else if (scan !== null && scanValid) {
    activeSnapshot = {
      targets: scan.result.targets,
      expiresAt: scanExpiresAt,
      truncated: scan.result.truncated,
      scanOnline: scan.result.scanOnline,
      glowingHighlight: scan.result.glowingHighlight,
    }
  }

  // 只有 L9+ 脉冲的目标带 live; 这种快照有效期内才轮询, 过期或低等级快照一个定时器都不挂。
  const liveTracking = activeSnapshot !== null && activeSnapshot.targets.some((target) => target.live !== null)
  usePolling(stateQuery.reload, POLL_INTERVAL_MS.agentLiveIntel, liveTracking)

  // 实体名与已解密词条名一次批量解 (未解密行的 displayKey 是 null, 本来就没有键可解)。
  const names = useItemNames(
    activeSnapshot === null
      ? []
      : [
          ...activeSnapshot.targets.map((target) => target.entityNameKey),
          ...activeSnapshot.targets.flatMap((target) =>
            target.entries
              .map((entry) => entry.displayKey)
              .filter((displayKey): displayKey is string => displayKey !== null),
          ),
        ],
  )
  const nameOf = (nameKey: string): string => names[nameKey] ?? nameKey

  if (stateQuery.status === 'loading') {
    return <LoadingBlock label="正在读取特勤档案" />
  }
  if (stateQuery.status === 'error') {
    return <ErrorBlock message={callErrorText(stateQuery.error)} onRetry={stateQuery.reload} />
  }
  if (data === null || bounty === null) {
    return <ErrorBlock message="job.agent.state 回执为空" onRetry={stateQuery.reload} />
  }

  const unlock = data.scanFieldUnlockLevels
  const scanReady = now >= scanReadyAt

  async function handleScan(): Promise<void> {
    setScanning(true)
    setScanError(null)
    try {
      const result = await callMock('job.agent.scan', {})
      // 收到的那一刻就是快照与冷却的起点, 之后一律本地算, 不再问服务端。
      setScan({ result, receivedAt: nowMs() })
      setSealFeedback({})
      setDiscoveryNotice(
        result.discoveryCount > 0
          ? `首次发现 ${String(result.discoveryCount)} 只精英, 特勤经验 +${String(result.discoveryXp)}`
          : null,
      )
    } catch (error) {
      setScanError(toError(error))
    } finally {
      setScanning(false)
    }
  }

  async function handleSeal(targetNetworkId: number, affixId: string): Promise<void> {
    const key = `${String(targetNetworkId)}:${affixId}`
    setSealingKey(key)
    try {
      const result = await callMock('job.agent.seal', { targetNetworkId, affixId })
      setSealFeedback((previous) => ({
        ...previous,
        [key]: { ok: result.ok, message: sealResultText(result) },
      }))
      if (result.ok) {
        /*
         * 成功后不在本地把那一行改成"已封印": 槽位占用、类别冷却、其它干员的封印互斥全在服务端账本上,
         * 本地补一份必然与真值分叉。丢掉本地快照改读 state 的投影, 一次重查把三件事一起对齐。
         */
        setScan(null)
        stateQuery.reload()
      }
    } catch (error) {
      setSealFeedback((previous) => ({
        ...previous,
        [key]: { ok: false, message: sealRejectionText(toError(error)) },
      }))
    } finally {
      setSealingKey(null)
    }
  }

  async function handleAccept(period: AgentBountyPeriod, bountyId: string, base: AgentStateResult): Promise<void> {
    setAcceptingId(bountyId)
    try {
      const result = await callMock('job.agent.bounty.accept', { period, bountyId })
      setBountyOverride({ base, bounty: result.bounty, activeAgent: result.activeAgent })
      const message = result.newlyActiveAgent
        ? `${BOUNTY_ACCEPT_TEXT[result.outcomeCode]} · 已入职: 加强奖励与对精英伤害加成从现在起生效`
        : BOUNTY_ACCEPT_TEXT[result.outcomeCode]
      setAcceptFeedback((previous) => ({ ...previous, [bountyId]: { ok: result.ok, message } }))
    } catch (error) {
      setAcceptFeedback((previous) => ({
        ...previous,
        [bountyId]: { ok: false, message: callErrorText(toError(error)) },
      }))
    } finally {
      setAcceptingId(null)
    }
  }

  function renderBountyEntries(
    period: AgentBountyPeriod,
    entries: readonly AgentBountyEntry[],
    slots: number,
    acceptedCount: number,
    base: AgentStateResult,
    board: AgentBountyBoard,
  ): ReactElement {
    const slotsFull = acceptedCount >= slots
    return (
      <div className="flex flex-col gap-2">
        {entries.map((entry) => {
          const feedback = acceptFeedback[entry.bountyId]
          return (
            <Surface key={entry.bountyId} tone={entry.completed ? 'success' : 'neutral'}>
              <div className="flex flex-col gap-2">
                <div className="flex flex-wrap items-center gap-2">
                  <h4 className="font-medium text-foreground text-sm">{bountyTitle(entry)}</h4>
                  {entry.completed ? (
                    <Tag tone="success">已完成, 奖励已发放</Tag>
                  ) : entry.accepted ? (
                    <Tag tone="info">进行中</Tag>
                  ) : null}
                </div>
                <span className="text-muted-foreground text-xs">
                  奖励 {rewardText(entry.creditReward, entry.xpReward, entry.azureReward)}
                </span>
                {entry.accepted ? (
                  <Meter
                    label="合格击杀"
                    max={entry.requiredCount}
                    tone={entry.completed ? 'success' : 'info'}
                    value={entry.killCount}
                    valueText={`${String(entry.killCount)} / ${String(entry.requiredCount)}`}
                  />
                ) : (
                  <div>
                    <Button
                      disabled={!board.available || slotsFull}
                      loading={acceptingId === entry.bountyId}
                      onClick={() => {
                        void handleAccept(period, entry.bountyId, base)
                      }}
                      size="sm"
                      variant="outline"
                    >
                      {slotsFull ? '本期悬赏位已满' : '接取'}
                    </Button>
                  </div>
                )}
                {feedback === undefined ? null : (
                  <span className={`text-xs ${feedback.ok ? 'text-success' : 'text-destructive'}`}>
                    {feedback.message}
                  </span>
                )}
              </div>
            </Surface>
          )
        })}
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4">
      <Panel title="特勤干员">
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-3 gap-4">
            <Stat label="职业等级" value={`Lv.${String(data.level)}`} />
            <Stat
              label="探测半径"
              value={`${String(data.scanRadiusBlocks)} 格`}
              hint={data.scanCrossChunk ? '已跨区块' : undefined}
            />
            <Stat
              label="入职状态"
              value={activeAgent ? '已入职' : '尚未入职'}
              hint={
                activeAgent
                  ? `奖励 x${data.enhancedRewardMultiplier.toFixed(2)} · 对精英伤害 +${String(data.damageBonusPercent)}%`
                  : '接取第一张悬赏 (或封印成功) 后入职, 才吃加强奖励与伤害加成'
              }
            />
          </div>
          {data.scanOnline ? null : (
            <Surface tone="warning">
              <p className="text-foreground text-sm">
                扫描离线: 精英怪系统未加载, 本次扫描读不到任何真实词条 (不烧冷却, 可随时重试)
              </p>
            </Surface>
          )}
        </div>
      </Panel>

      <Panel title="战术扫描">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-3">
            <Button
              disabled={!scanReady}
              loading={scanning}
              onClick={() => {
                void handleScan()
              }}
              variant="brand"
            >
              发起探测脉冲
            </Button>
            <span className="text-muted-foreground text-sm">
              {scanReady
                ? `就绪, 可立即扫描 (整轮冷却 ${String(Math.round(data.scanPulseCooldownTicks / 20))} 秒)`
                : `冷却中, 剩余 ${formatCountdown(scanReadyAt, now)}`}
            </span>
          </div>
          {scanError === null ? null : <FeedbackAlert message={callErrorText(scanError)} tone="danger" />}
          {discoveryNotice === null ? null : (
            <FeedbackAlert
              message={discoveryNotice}
              onDismiss={() => {
                setDiscoveryNotice(null)
              }}
              tone="success"
            />
          )}

          {activeSnapshot === null ? (
            <EmptyBlock
              hint="点击上方按钮发起一次探测脉冲, 结果会在此列出; 快照过期后需要重新扫描"
              title="当前没有有效的扫描快照"
            />
          ) : !activeSnapshot.scanOnline ? (
            <Surface tone="warning">
              <p className="text-foreground text-sm">扫描离线, 本次脉冲没有读到任何目标</p>
            </Surface>
          ) : activeSnapshot.targets.length === 0 ? (
            <EmptyBlock hint="探测球内没有本工程盖章的精英怪" title="本轮扫描没有发现目标" />
          ) : (
            <div className="flex flex-col gap-3">
              <span className="text-muted-foreground text-xs">
                本轮快照将于 {formatCountdown(activeSnapshot.expiresAt, now)} 后失效, 届时封印按钮全部作废
                {activeSnapshot.truncated ? ' · 仅显示最近 8 个' : ''}
                {activeSnapshot.glowingHighlight ? ' · 目标正在对你高亮 (穿墙可见, 仅你本人看得见)' : ''}
              </span>
              {activeSnapshot.targets.map((target) => (
                <Surface key={target.targetNetworkId}>
                  <div className="flex flex-col gap-2">
                    <div className="flex flex-wrap items-center gap-3">
                      <h3 className="font-medium text-foreground text-sm">
                        {nameOf(target.entityNameKey)}
                      </h3>
                      <Tag tone="warning">{target.star} 星</Tag>
                      <span className="text-muted-foreground text-xs">
                        距离 {target.distanceBlocks.toFixed(1)} 格
                      </span>
                      {target.pos === null ? (
                        <span className="text-muted-foreground text-xs">
                          精确坐标{lockedText(unlock.GLOWING_HIGHLIGHT)}
                        </span>
                      ) : (
                        <span className="text-muted-foreground text-xs">
                          脉冲当刻坐标 ({target.pos.x}, {target.pos.y}, {target.pos.z})
                        </span>
                      )}
                    </div>
                    <IntelGrid target={target} unlock={unlock} />
                    <LiveBlock live={target.live} unlock={unlock} />
                    {target.entries.length === 0 ? (
                      <span className="text-muted-foreground text-xs">这只精英身上没有可封的词条</span>
                    ) : (
                      <div className="flex flex-col gap-1">
                        <div className="flex flex-wrap gap-2">
                          {target.entries.map((entry, index) => {
                            /*
                             * key 只能用行下标: 未解密行的 affixId 是 null (服务端脱敏), 拿它当 key 会让
                             * 同一目标上的多条加密行撞成一个 key。
                             */
                            const rowKey = `${String(target.targetNetworkId)}#${String(index)}`
                            if (!entry.decrypted || entry.affixId === null) {
                              return (
                                <span
                                  className="rounded-md border border-border border-dashed px-2 py-1 text-muted-foreground text-xs"
                                  key={rowKey}
                                >
                                  未解密词条 (提升干员等级后可见)
                                </span>
                              )
                            }
                            const affixId = entry.affixId
                            const buttonKey = `${String(target.targetNetworkId)}:${affixId}`
                            const feedback = sealFeedback[buttonKey]
                            const label = entry.displayKey === null ? affixId : nameOf(entry.displayKey)
                            const quality = entry.quality === null ? null : QUALITY_STYLE[entry.quality]
                            return (
                              <div className="flex items-center gap-2" key={rowKey}>
                                <Button
                                  disabled={entry.sealed || !entry.sealable}
                                  loading={sealingKey === buttonKey}
                                  onClick={() => {
                                    void handleSeal(target.targetNetworkId, affixId)
                                  }}
                                  size="sm"
                                  variant="outline"
                                >
                                  {quality === null ? null : (
                                    <span
                                      aria-hidden="true"
                                      className={`inline-block size-2 rounded-full ${quality.dotClass}`}
                                    />
                                  )}
                                  {entry.sealed ? `${label} (封印中)` : `封印 ${label}`}
                                  {quality === null ? null : ` · ${quality.label}`}
                                </Button>
                                {feedback === undefined ? null : (
                                  <span
                                    className={`text-xs ${feedback.ok ? 'text-success' : 'text-destructive'}`}
                                  >
                                    {feedback.message}
                                  </span>
                                )}
                              </div>
                            )
                          })}
                        </div>
                        {target.entries.some((entry) => entry.decrypted && entry.quality === null) ? (
                          <span className="text-muted-foreground text-xs">
                            词条品质表{lockedText(unlock.QUALITY_TABLE)}
                          </span>
                        ) : null}
                      </div>
                    )}
                    {target.mechanics === null ? (
                      <span className="text-muted-foreground text-xs">
                        技能机制 (起手窗口 / 打断门槛 / 冷却){lockedText(unlock.SKILL_MECHANICS)}
                      </span>
                    ) : (
                      target.mechanics.map((mechanic) => {
                        const entry = target.entries.find((candidate) => candidate.affixId === mechanic.affixId)
                        const name =
                          entry === undefined || entry.displayKey === null
                            ? mechanic.affixId
                            : nameOf(entry.displayKey)
                        return (
                          <span className="text-muted-foreground text-xs" key={mechanic.affixId}>
                            {name}: {mechanicText(mechanic)}
                          </span>
                        )
                      })
                    )}
                  </div>
                </Surface>
              ))}
            </div>
          )}
        </div>
      </Panel>

      <Panel title="封印权限">
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-3 gap-4">
            <Stat
              label="可封最高星级"
              value={data.seal.maxSealableStar === 0 ? '未解锁' : `${String(data.seal.maxSealableStar)} 星`}
            />
            <Stat
              label="封印位"
              value={
                data.seal.slotsDefault === 0
                  ? '未解锁'
                  : `${String(data.seal.slotsDefault)} 个 (8 星+ 目标 ${String(data.seal.slotsVsStar8Plus)} 个)`
              }
              hint={`第二个位需要 Lv.${String(data.seal.secondSlotUnlockLevel)}`}
            />
            <Stat label="槽位归属" value="精英自身容量, 不随在场人数增加" />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <Surface tone={data.seal.passiveUnlocked ? 'neutral' : 'warning'}>
              <div className="flex flex-col gap-1">
                <h3 className="font-medium text-foreground text-sm">被动词条</h3>
                <span className="text-muted-foreground text-xs">
                  {data.seal.passiveUnlocked
                    ? `持续 ${String(data.seal.passiveWindowSeconds)} 秒 / 冷却 ${String(data.seal.passiveCooldownSeconds)} 秒`
                    : `需要干员 Lv.${String(data.seal.passiveUnlockLevel)}`}
                </span>
                {data.seal.passiveUnlocked ? (
                  <span className="text-muted-foreground text-xs">
                    当前冷却: {formatCountdown(passiveReadyAt, now)}
                  </span>
                ) : null}
              </div>
            </Surface>
            <Surface tone={data.seal.mechanicUnlocked ? 'neutral' : 'warning'}>
              <div className="flex flex-col gap-1">
                <h3 className="font-medium text-foreground text-sm">机制词条</h3>
                <span className="text-muted-foreground text-xs">
                  {data.seal.mechanicUnlocked
                    ? `持续 ${String(data.seal.mechanicWindowSeconds)} 秒 / 冷却 ${String(data.seal.mechanicCooldownSeconds)} 秒`
                    : `需要干员 Lv.${String(data.seal.mechanicUnlockLevel)}`}
                </span>
                {data.seal.mechanicUnlocked ? (
                  <span className="text-muted-foreground text-xs">
                    当前冷却: {formatCountdown(mechanicReadyAt, now)}
                  </span>
                ) : null}
              </div>
            </Surface>
          </div>
          <p className="text-muted-foreground text-xs">
            两类各有一本冷却账本, 封被动不会锁住机制; 封印只能在本面板点已解密的词条发起
          </p>
        </div>
      </Panel>

      <Panel title="特勤悬赏">
        <div className="flex flex-col gap-3">
          {bounty.available ? null : (
            <Surface tone="warning">
              <p className="text-foreground text-sm">悬赏已被服务器关闭: 暂时不能接取, 击杀精英也不推进悬赏</p>
            </Surface>
          )}
          <div className="grid grid-cols-3 gap-4">
            <Stat
              label="每日悬赏"
              value={`已接 ${String(bounty.dailyAccepted)} / ${String(bounty.dailySlots)}`}
              hint={`${formatResetIn(bountyResetAt.daily, now)} 后刷新`}
            />
            <Stat
              label="每周悬赏"
              value={
                bounty.weeklyUnlocked
                  ? `已接 ${String(bounty.weeklyAccepted)} / ${String(bounty.weeklySlots)}`
                  : `需要 Lv.${String(bounty.weeklyUnlockLevel)}`
              }
              hint={bounty.weeklyUnlocked ? `${formatResetIn(bountyResetAt.weekly, now)} 后刷新` : undefined}
            />
            <Stat
              label="可接最高星级"
              value={`${String(bounty.maxBountyStar)} 星`}
              hint="日常与周常最高 9 星, 10 星只出自世界 BOSS"
            />
          </div>
          <Meter
            label="本周悬赏青辉石"
            max={bounty.weeklyAzureCap}
            tone={bounty.weeklyAzureGranted >= bounty.weeklyAzureCap ? 'danger' : 'info'}
            value={Math.min(bounty.weeklyAzureGranted, bounty.weeklyAzureCap)}
            valueText={`${String(bounty.weeklyAzureGranted)} / ${String(bounty.weeklyAzureCap)}`}
          />
          {bounty.pendingAzure > 0 ? (
            <Surface tone="info">
              <p className="text-foreground text-sm">
                有 {bounty.pendingAzure} 颗悬赏青辉石因今日青辉石上限 (与精英掉落共用) 暂未到账,
                下次登录或完成悬赏时自动补发
              </p>
            </Surface>
          ) : null}

          <h3 className="font-medium text-foreground text-sm">日常悬赏</h3>
          {bounty.daily.length === 0 ? (
            <EmptyBlock hint="悬赏开放后每天 UTC 零点刷新" title="今天没有可接的日常悬赏" />
          ) : (
            renderBountyEntries('DAILY', bounty.daily, bounty.dailySlots, bounty.dailyAccepted, data, bounty)
          )}

          <h3 className="font-medium text-foreground text-sm">周常悬赏</h3>
          {!bounty.weeklyUnlocked ? (
            <Surface tone="warning">
              <p className="text-muted-foreground text-xs">
                干员 Lv.{bounty.weeklyUnlockLevel} 解锁周常悬赏, 周常额外奖励青辉石
              </p>
            </Surface>
          ) : bounty.weekly.length === 0 ? (
            <EmptyBlock hint="每周一 UTC 零点刷新" title="本周没有可接的周常悬赏" />
          ) : (
            renderBountyEntries('WEEKLY', bounty.weekly, bounty.weeklySlots, bounty.weeklyAccepted, data, bounty)
          )}

          <Surface tone={bounty.worldBossOrder.unlocked ? 'neutral' : 'warning'}>
            <div className="flex flex-col gap-1">
              <div className="flex flex-wrap items-center gap-2">
                <h3 className="font-medium text-foreground text-sm">世界 BOSS 讨伐令</h3>
                <Tag tone={bounty.worldBossOrder.unlocked ? 'success' : 'neutral'}>
                  {bounty.worldBossOrder.unlocked ? '常驻生效' : `需要 Lv.${String(bounty.worldBossUnlockLevel)}`}
                </Tag>
                {bounty.worldBossOrder.completedThisWeek > 0 ? (
                  <Tag tone="info">本周已结算 {bounty.worldBossOrder.completedThisWeek} 次</Tag>
                ) : null}
              </div>
              <span className="text-muted-foreground text-xs">
                世界 BOSS 出现时全服公告; 已入职干员参与击倒且输出达到入池门槛即自动结算, 不占悬赏位, 不用接取
              </span>
              <span className="text-foreground text-sm">
                每次{' '}
                {rewardText(
                  bounty.worldBossOrder.creditReward,
                  bounty.worldBossOrder.xpReward,
                  bounty.worldBossOrder.azureReward,
                )}
              </span>
            </div>
          </Surface>

          <p className="text-muted-foreground text-xs">
            接取后不能放弃或重摇, 完成即自动发奖; 只有输出达到入池门槛的击杀才算数。信用点与卖矿、击杀奖励共用每日收入衰减,
            青辉石受本周悬赏上限与每日上限约束。接取第一张悬赏即算入职
          </p>
        </div>
      </Panel>
    </div>
  )
}
