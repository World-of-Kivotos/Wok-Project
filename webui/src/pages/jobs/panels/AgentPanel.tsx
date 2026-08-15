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
import { MS_PER_TICK, tickDeadline } from '@/hooks/use-live-updates'
import { WebUiCallError } from '../../../lib/bridge'
import { callErrorText } from '../../../lib/errorText'
import { useItemNames } from '../../../lib/i18n'
import type {
  AgentScanResult,
  AgentScanTarget,
  AgentSealOutcomeCode,
  AgentSealResult,
} from '../../../lib/types'
import { callMock, nowMs, useMockAction } from '../../../mock'
import { formatCountdown, toError, useLiveNow } from './shared'

/**
 * 特勤干员面板 (`job.agent.state` / `job.agent.scan` / `job.agent.seal`, Java 落点
 * com.miningdim.job.agent.AgentWebUiActions)。回执形状见 lib/types.ts。
 *
 * 四条决定本页形状的契约事实:
 *   1. **分级解密**: 目标身上的词条是逐条裁决的, 未解密行的 affixId / displayKey / category 三格
 *      同时是 JSON null —— 这是服务端在回执层刻意做的脱敏 (真值送进浏览器等于在开发者工具里明码
 *      给出词条身份)。故未解密行只能渲染成不可点的占位, 列表 key 只能用行下标。
 *   2. **坐标绑在 L8**: pos 为 null 时只有距离可显示; 且判据取的是**发出那次脉冲时**的干员等级,
 *      L7 升到 L8 之后旧快照里的 pos 仍是 null, 要坐标必须重扫。
 *   3. **时间一律是剩余 tick**: 服务端不发绝对时刻, 前端在收到回执那一刻折成本地基准再倒计时
 *      (与矿工面板同纪律)。快照倒计时归零即 targetNetworkId 作废, 封印按钮必须跟着变灰。
 *   4. **没有悬赏实例**: 全工程没有"玩家已接的悬赏"这个存储 (BountyDefinition/BountyProgress 是零构造点
 *      的逻辑骨架), 服务端发的是一张 bounty 权限表。旧版的悬赏板本轮无数据可渲染, 已改成权限一览 ——
 *      画一块假的进度条比空着更糟。
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

interface SealFeedback {
  readonly ok: boolean
  readonly message: string
}

/** 一次脉冲的本地快照: 回执本身 + 收到它的时刻 (冷却与快照有效期都从这一刻起算)。 */
interface ScanSnapshot {
  result: AgentScanResult
  receivedAt: number
}

/** 当前该渲染哪一份候选表 —— 刚扫的那次, 或 state 带回来的上一次脉冲投影, 两者同形。 */
interface ActiveSnapshot {
  targets: readonly AgentScanTarget[]
  expiresAt: number
  truncated: boolean
  scanOnline: boolean
}

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

export function AgentPanel(): ReactElement {
  const stateQuery = useMockAction('job.agent.state', EMPTY_PAYLOAD)
  const now = useLiveNow()

  const [scan, setScan] = useState<ScanSnapshot | null>(null)
  const [scanning, setScanning] = useState(false)
  const [scanError, setScanError] = useState<Error | null>(null)
  const [sealingKey, setSealingKey] = useState<string | null>(null)
  const [sealFeedback, setSealFeedback] = useState<Record<string, SealFeedback>>({})

  const data = stateQuery.status === 'ready' ? stateQuery.data : null

  /*
   * 回执里的剩余 tick 只在"收到它那一刻"有意义, 故在 data 换引用时折一次本地时刻。
   * data 只在一次新回执到达时才换引用, 这份折算因此恰好每条回执做一次。
   */
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
   * 冷却取"state 折出来的"与"刚扫那次折出来的"较大值: 冷却只会因扫描而变长, 这样就不必为刷新一个字段
   * 重查整个 job.agent.state (重查会闪一次骨架屏, 把刚展示出来的候选表盖掉)。
   */
  const scanReadyAt =
    scan === null
      ? stateScanReadyAt
      : Math.max(stateScanReadyAt, tickDeadline(scan.result.scanCooldownRemainingTicks, scan.receivedAt))

  let activeSnapshot: ActiveSnapshot | null = null
  if (scan !== null && scanExpiresAt > now) {
    activeSnapshot = {
      targets: scan.result.targets,
      expiresAt: scanExpiresAt,
      truncated: scan.result.truncated,
      scanOnline: scan.result.scanOnline,
    }
  } else if (data !== null && stateSnapshotExpiresAt > now) {
    activeSnapshot = {
      targets: data.targets,
      expiresAt: stateSnapshotExpiresAt,
      truncated: data.truncated,
      scanOnline: data.scanOnline,
    }
  }

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
  if (data === null) {
    return <ErrorBlock message="job.agent.state 回执为空" onRetry={stateQuery.reload} />
  }

  const scanReady = now >= scanReadyAt

  async function handleScan(): Promise<void> {
    setScanning(true)
    setScanError(null)
    try {
      const result = await callMock('job.agent.scan', {})
      // 收到的那一刻就是快照与冷却的起点, 之后一律本地算, 不再问服务端。
      setScan({ result, receivedAt: nowMs() })
      setSealFeedback({})
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
              value={data.activeAgent ? '已入职' : '尚未入职'}
              hint={
                data.activeAgent
                  ? `奖励 x${data.enhancedRewardMultiplier.toFixed(2)} · 对精英伤害 +${String(data.damageBonusPercent)}%`
                  : '做过一次特勤活计后才吃加强奖励与伤害加成'
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
                          精确坐标需要 8 级干员
                        </span>
                      ) : (
                        <span className="text-muted-foreground text-xs">
                          脉冲当刻坐标 ({target.pos.x}, {target.pos.y}, {target.pos.z})
                        </span>
                      )}
                    </div>
                    {target.entries.length === 0 ? (
                      <span className="text-muted-foreground text-xs">这只精英身上没有可封的词条</span>
                    ) : (
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
                                {entry.sealed ? `${label} (封印中)` : `封印 ${label}`}
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
            两类各有一本冷却账本, 封被动不会锁住机制; 与游戏内按键封印共用同一本
          </p>
        </div>
      </Panel>

      <Panel title="悬赏权限一览">
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-3 gap-4">
            <Stat label="每日悬赏位" value={`${String(data.bounty.dailySlots)} 个`} />
            <Stat
              label="每周悬赏位"
              value={
                data.bounty.weeklyUnlocked
                  ? `${String(data.bounty.weeklySlots)} 个`
                  : `需要 Lv.${String(data.bounty.weeklyUnlockLevel)}`
              }
            />
            <Stat
              label="可接最高星级"
              value={data.bounty.maxBountyStar === 0 ? '未解锁' : `${String(data.bounty.maxBountyStar)} 星`}
            />
          </div>
          <Meter
            label="本周青辉石配额"
            max={data.bounty.weeklyAzureCap}
            tone={data.bounty.weeklyAzureGranted >= data.bounty.weeklyAzureCap ? 'danger' : 'info'}
            value={data.bounty.weeklyAzureGranted}
            valueText={`${String(data.bounty.weeklyAzureGranted)} / ${String(data.bounty.weeklyAzureCap)}`}
          />
          <div className="flex flex-wrap items-center gap-2">
            <Tag tone={data.bounty.worldBossUnlocked ? 'success' : 'neutral'}>
              世界 BOSS {data.bounty.worldBossUnlocked ? '已解锁' : `需要 Lv.${String(data.bounty.worldBossUnlockLevel)}`}
            </Tag>
          </div>
          <p className="text-muted-foreground text-xs">
            这里是权限表而不是悬赏列表: 服务端目前没有"玩家已接的悬赏"这个存储, 接单与进度只能在游戏里进行
          </p>
        </div>
      </Panel>
    </div>
  )
}
