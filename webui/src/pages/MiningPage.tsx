import { CheckIcon, LockIcon, TriangleAlertIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { useMemo, useState } from 'react'
import {
  Button,
  EmptyBlock,
  ErrorBlock,
  FeedbackAlert,
  formatAmount,
  LoadingBlock,
  Panel,
  Stat,
  Tag,
} from '@/components/kit'
import { POLL_INTERVAL_MS, tickDeadline, useLiveClock, usePolling } from '@/hooks/use-live-updates'
import { isMockActive } from '../lib/bridge'
import { callErrorText } from '../lib/errorText'
import { useItemNames } from '../lib/i18n'
import type { MiningDifficulty, MiningEnterReasonCode, MiningInstanceRow } from '../lib/types'
import { nowMs } from '../mock'
import { callMock } from '../mock/handlers'
import { useMockAction } from '../mock/useMockWorld'

/**
 * 矿洞总览 (`mining.overview` / `mining.myStatus` / `mining.enter` / `mining.leave`,
 * Java 落点 com.miningdim.entry.MiningWebUiActions)。回执形状见 lib/types.ts。
 *
 * R1 模型 (这一条被反复改错过, 再写一遍): 全服**只有 3 块常驻共享区域**, 每难度一块, 不是每个玩家各开
 * 一份。卡片上的"当前在线 N 人"指的是与我共享同一个物理空间的全服玩家数。
 *
 * 三条必须照做的契约事实:
 *   1. **accepted 不等于已进去**: mining.enter 只表示"已交给权威入场链路", 真正的传送发生在之后若干
 *      tick, 且成败只经原生 TeleportResult S2C 下发 (webui 通道收不到)。要确认是否真进去了只能轮询
 *      mining.myStatus —— 本页因此挂了一条 3 秒的轮询 (间隔见 hooks/use-live-updates)。
 *   2. **时间全是矿山维度 game tick**, 不是 epoch millis。每个回执都附带当刻 gameTime 作换算基准,
 *      前端在收到那一刻折成本地时刻再倒计时。
 *   3. **danger 已从契约里删掉**: capability 上那个字段全库只被写过一次 0.0f, 活值没有对外只读门面,
 *      编一个 0 出来比不发更糟。旧版的危险度进度条随之删除。
 *
 * nextResetGameTime 是**预警起点不是换图时刻**: 真正的清场与重置发生在其后 autoResetWarnSeconds 秒。
 * 且它只反映"定时自动刷新" —— 手动 / 管理台重置不写这个基准, 文案不能写成"上次重置"。
 */

const DIFFICULTY_TAG: Record<MiningDifficulty, string> = {
  easy: '简单',
  medium: '普通',
  hard: '困难',
}

/**
 * mining.enter 三条同步拒绝的文案。服务端只发翻译键 (reasonKey), 专用服务端解不出中文;
 * 这张表是玩家看到的那句话的唯一出处, 与 reasonCode 一一对应。
 */
const ENTER_REASON_TEXT: Record<MiningEnterReasonCode, string> = {
  LEVEL_TOO_LOW: '矿工等级不够, 这个难度还进不去',
  INSUFFICIENT_FUNDS: '信用点余额不足, 无法支付本次入场费',
  ALREADY_INSIDE: '你已经在矿洞里了',
}

type ActionFeedback = { tone: 'success' | 'danger' | 'warning'; message: string }

function toError(value: unknown): Error {
  return value instanceof Error ? value : new Error(String(value))
}

/** 剩余毫秒 -> HH:MM:SS。已到点时不显示负数。 */
function formatCountdown(targetMs: number, nowValue: number): string {
  const remaining = targetMs - nowValue
  if (remaining <= 0) {
    return '00:00:00'
  }
  const totalSeconds = Math.floor(remaining / 1000)
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`
}

interface MiningInstanceCardProps {
  instance: MiningInstanceRow
  displayName: string
  minerLevel: number
  insideHere: boolean
  entering: boolean
  leaving: boolean
  /** 该难度下一次定时刷新的本地时刻; null = 关闭了定时刷新或没有基准, 此时不许画倒计时。 */
  nextResetAt: number | null
  nowValue: number
  onEnter: () => void
  onLeave: () => void
}

function MiningInstanceCard({
  instance,
  displayName,
  minerLevel,
  insideHere,
  entering,
  leaving,
  nextResetAt,
  nowValue,
  onEnter,
  onLeave,
}: MiningInstanceCardProps): ReactElement {
  const locked = !instance.unlocked || minerLevel < instance.requiredMinerLevel

  return (
    <Panel
      actions={<Tag tone={locked ? 'neutral' : 'brand'}>{DIFFICULTY_TAG[instance.difficulty]}</Tag>}
      title={displayName}
    >
      <div className="flex flex-col gap-3">
        <div className="flex items-center gap-2 text-sm">
          {locked ? (
            <LockIcon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
          ) : (
            <CheckIcon aria-hidden="true" className="size-4 shrink-0 text-success" />
          )}
          <span className={locked ? 'text-muted-foreground' : 'text-foreground'}>
            {locked
              ? `需要矿工 ${String(instance.requiredMinerLevel)} 级 (当前 ${String(minerLevel)} 级)`
              : `已解锁 (需矿工 ${String(instance.requiredMinerLevel)} 级)`}
          </span>
        </div>

        {instance.available ? null : (
          <p className="text-warning text-sm">该难度的常驻区域此刻不存在, 暂时进不去</p>
        )}

        {instance.dropsOnDeath ? (
          <FeedbackAlert
            message="本区死亡掉落全部物品, 请只携带能够承受损失的装备"
            title="死亡掉落"
            tone="danger"
          />
        ) : null}

        <div className="flex flex-col gap-1">
          <Stat
            label="入场价格"
            layout="inline"
            value={instance.entryFee <= 0 ? '免费' : `${formatAmount(instance.entryFee)} 信用点`}
          />
          <Stat
            label="当前在线"
            layout="inline"
            value={instance.playersInside === null ? '—' : `${String(instance.playersInside)} 人`}
          />
          <Stat
            label="下次定时刷新"
            layout="inline"
            value={nextResetAt === null ? '未开启定时刷新' : formatCountdown(nextResetAt, nowValue)}
          />
          {instance.genState === null ? null : (
            <Stat label="区域状态" layout="inline" value={instance.genState} />
          )}
          <p className="text-muted-foreground text-xs">全服玩家共用同一个矿洞</p>
        </div>

        {insideHere ? (
          <Button loading={leaving} onClick={onLeave} variant="destructive">
            离开矿洞
          </Button>
        ) : (
          <Button
            disabled={locked || !instance.enterable}
            loading={entering}
            onClick={onEnter}
            variant="brand"
          >
            进入矿洞
          </Button>
        )}
      </div>
    </Panel>
  )
}

export function MiningPage(): ReactElement {
  const overview = useMockAction('mining.overview', {})
  const status = useMockAction('mining.myStatus', {})

  // 本地时钟, 不是轮询: 回执里的 game tick 只在收到那一刻折算一次, 之后的倒计时全在本地推进。
  const nowValue = useLiveClock(1000)
  const [pendingDifficulty, setPendingDifficulty] = useState<MiningDifficulty | null>(null)
  const [leavePending, setLeavePending] = useState(false)
  const [feedback, setFeedback] = useState<ActionFeedback | null>(null)

  const overviewData = overview.status === 'ready' ? overview.data : null
  const statusData = status.status === 'ready' ? status.data : null

  /*
   * mining.enter 只回"已受理", 真正的传送在之后若干 tick 才发生且不走 webui 通道 —— 契约明文要求
   * 轮询本条才能知道自己到底进去了没有。间隔集中在 hooks/use-live-updates, 不在此写死。
   */
  usePolling(status.reload, POLL_INTERVAL_MS.miningStatus)

  /*
   * 依赖数组在这里当"新回执到达"的信号用, 不是工厂真读了它: 服务端一律发剩余 tick 而非绝对时刻,
   * 前端必须在收到回执那一刻折一个本地基准, 否则倒计时会从页面挂载时刻算起, 越挂越偏。
   * exhaustive-deps 建议删掉这个依赖 —— 删了基准就永不刷新, 故定向豁免。
   */
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const overviewReceivedAt = useMemo(() => nowMs(), [overviewData])
  /*
   * 依赖数组在这里当"新回执到达"的信号用, 不是工厂真读了它: 服务端一律发剩余 tick 而非绝对时刻,
   * 前端必须在收到回执那一刻折一个本地基准, 否则倒计时会从页面挂载时刻算起, 越挂越偏。
   * exhaustive-deps 建议删掉这个依赖 —— 删了基准就永不刷新, 故定向豁免。
   */
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const statusReceivedAt = useMemo(() => nowMs(), [statusData])

  const instanceNames = useItemNames(
    overviewData === null ? [] : overviewData.instances.map((instance) => instance.nameKey),
  )

  const spawnFreezeUntil =
    statusData === null ? 0 : tickDeadline(statusData.spawnFreezeRemainingTicks, statusReceivedAt)

  async function handleEnter(difficulty: MiningDifficulty): Promise<void> {
    setPendingDifficulty(difficulty)
    setFeedback(null)
    try {
      const result = await callMock('mining.enter', { difficulty })
      if (result.accepted) {
        setFeedback({
          tone: 'success',
          message: '入场请求已受理, 正在为你准备地形; 进去之后本页会自动刷新',
        })
      } else {
        setFeedback({
          tone: 'danger',
          message:
            result.reasonCode === null
              ? '进入被拒绝, 但服务端没有给出原因码'
              : ENTER_REASON_TEXT[result.reasonCode],
        })
      }
      overview.reload()
      status.reload()
    } catch (error) {
      setFeedback({ tone: 'danger', message: callErrorText(toError(error)) })
    } finally {
      setPendingDifficulty(null)
    }
  }

  async function handleLeave(): Promise<void> {
    setLeavePending(true)
    setFeedback(null)
    try {
      const result = await callMock('mining.leave', {})
      setFeedback(
        result.left
          ? { tone: 'success', message: '已离开矿洞, 传送回进入前的位置' }
          : { tone: 'warning', message: '你本来就不在矿洞里' },
      )
      overview.reload()
      status.reload()
    } catch (error) {
      setFeedback({ tone: 'danger', message: callErrorText(toError(error)) })
    } finally {
      setLeavePending(false)
    }
  }

  const minerLevel = overviewData === null ? 0 : overviewData.minerLevel
  const insideDifficulty = statusData !== null && statusData.inside ? statusData.difficulty : null

  /** 该难度下一次定时刷新的本地时刻; 未开启定时刷新或没有基准时为 null (契约: 此时不许画倒计时)。 */
  function nextResetAtOf(instance: MiningInstanceRow): number | null {
    if (overviewData === null || instance.nextResetGameTime === null) {
      return null
    }
    const remainingTicks = instance.nextResetGameTime - overviewData.gameTime
    return remainingTicks <= 0 ? overviewReceivedAt : tickDeadline(remainingTicks, overviewReceivedAt)
  }

  return (
    <section className="flex flex-col gap-4">
      {/* 页名由 TabletShell 的 h1 统一渲染, 页面内不再重复 —— 重复两遍且里层更大, 打开必现, 读起来像渲染 bug。 */}
      <header className="flex flex-col gap-2">
        <p className="text-muted-foreground text-sm">
          全服只有 3 个矿洞, 简单 / 普通 / 困难各一个, 所有人共用 —— 卡片上的在线人数就是此刻和你
          在同一个矿洞里的玩家数。
        </p>
        {overviewData === null || overviewData.autoResetWarnSeconds <= 0 ? null : (
          <p className="text-muted-foreground text-xs">
            倒计时归零是换图预警的起点, 真正的清场与重置在其后 {overviewData.autoResetWarnSeconds} 秒发生
          </p>
        )}
      </header>

      {feedback === null ? null : <FeedbackAlert message={feedback.message} tone={feedback.tone} />}

      {overview.status === 'loading' ? (
        <Panel>
          <LoadingBlock label="正在读取矿洞总览" size="lg" />
        </Panel>
      ) : overview.status === 'error' ? (
        <ErrorBlock message={callErrorText(overview.error)} onRetry={overview.reload} />
      ) : overview.data.instances.length === 0 ? (
        <EmptyBlock
          hint="三个常驻区域一个都没回来, 属服务端异常"
          icon={<TriangleAlertIcon aria-hidden="true" />}
          title="暂无可进入的矿洞"
        />
      ) : (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          {overview.data.instances.map((instance) => (
            <MiningInstanceCard
              key={instance.difficulty}
              displayName={instanceNames[instance.nameKey] ?? DIFFICULTY_TAG[instance.difficulty]}
              instance={instance}
              minerLevel={minerLevel}
              insideHere={insideDifficulty === instance.difficulty}
              entering={pendingDifficulty === instance.difficulty}
              leaving={leavePending && insideDifficulty === instance.difficulty}
              nextResetAt={nextResetAtOf(instance)}
              nowValue={nowValue}
              onEnter={() => {
                void handleEnter(instance.difficulty)
              }}
              onLeave={() => {
                void handleLeave()
              }}
            />
          ))}
        </div>
      )}

      <Panel title="我的矿洞状态">
        {status.status === 'loading' ? (
          <LoadingBlock label="正在读取我的状态" />
        ) : status.status === 'error' ? (
          <ErrorBlock message={callErrorText(status.error)} onRetry={status.reload} />
        ) : !status.data.inside ? (
          <div className="flex flex-col gap-2">
            <p className="text-muted-foreground text-sm">当前不在任何矿洞里。</p>
            {status.data.inMiningDimension ? (
              <p className="text-warning text-sm">
                你人在矿洞维度里, 但站的位置不属于任何一块常驻区域 —— 请用上面的按钮重新进入。
              </p>
            ) : null}
          </div>
        ) : status.data.difficulty === null ? (
          <p className="text-destructive text-sm">
            {/* 具体是哪个字段缺失只对开发有意义; isMockActive 在生产构建里恒为 false, 装进游戏后只剩后一句。 */}
            {isMockActive()
              ? '数据异常: inside 为真但 difficulty 缺失, 请刷新重试。'
              : '矿洞状态读取异常, 请刷新重试。'}
          </p>
        ) : (
          <div className="flex flex-col gap-3">
            <p className="text-foreground text-sm">
              当前位于 {DIFFICULTY_TAG[status.data.difficulty]} 难度
              {status.data.regionOriginX === null || status.data.regionOriginZ === null
                ? ''
                : `, 区域原点 (${String(status.data.regionOriginX)}, ${String(status.data.regionOriginZ)})`}
            </p>
            {status.data.instanceId === status.data.currentInstanceId ? null : (
              <p className="text-warning text-xs">
                实例指针 ({status.data.currentInstanceId}) 与几何反查结果 (
                {status.data.instanceId === null ? '无' : status.data.instanceId}) 不一致, 请报告给管理员
              </p>
            )}
            {spawnFreezeUntil > nowValue ? (
              <div>
                <Tag tone="info">新手保护中, 剩余 {formatCountdown(spawnFreezeUntil, nowValue)}</Tag>
              </div>
            ) : null}
          </div>
        )}
      </Panel>
    </section>
  )
}
