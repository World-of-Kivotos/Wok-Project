import { CheckIcon, LockIcon, TriangleAlertIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { useEffect, useState } from 'react'
import {
  Button,
  EmptyBlock,
  ErrorBlock,
  FeedbackAlert,
  LoadingBlock,
  Meter,
  Panel,
  Stat,
  Tag,
} from '@/components/kit'
import type { Tone } from '@/components/kit'
import { callMock } from '../mock/handlers'
import type { PlannedDifficulty, PlannedMiningInstance } from '../mock/planned'
import { useMockAction } from '../mock/useMockWorld'

/**
 * 矿洞总览 (接线清单 F 组)。
 *
 * 依赖的假定契约 (均未接线, 走 mock/planned.ts, 见文件头"接线核销流程"三步走):
 *   - mining.overview  F1  三难度实例列表 + 我当前所在难度
 *   - mining.myStatus  F2 + F8  我的实时矿洞状态 (在场 / 区域坐标 / 危险度 / 新手保护倒计时)
 *   - mining.enter     F3  进入某一难度。**服务端实现必须复用 EntryGateway.requestEnter 权威路径**
 *                      (清单 F3 记录: /mining enter 命令与 SelectZoneC2S 包都跳过 gateCheck 且从不
 *                      实际传送玩家, 前端不得假定这两条路径已经能用)
 *   - mining.leave     F4  离开当前矿洞, 委派 EntrySystem.leaveToFallback
 *   - job.progress     C1  只用来读矿工当前等级: mining.myStatus.minerLevel 只是上次进入/离开那一刻
 *                      的快照, 升级后不会自动刷新, 等级门判定改从这条更新鲜的数据源取
 *
 * 认知前提 (F1 状态列原文, 这里重复一遍防止被"顺手"改成"我的副本"文案):
 * R1 模型下全服**只有 3 个常驻共享固定实例**, 每难度一个, 不是每个玩家各开一份。
 * 卡片上的"当前在线 N 人"指的是与我共享同一个物理空间的全服玩家数。
 *
 * 等级门数值来源: 清单 F5 记录 `GateResult` 头注释里的 MEDIUM=10/HARD=25 是过期文档口径,
 * 代码权威是 L4 开 Medium、L8 开 Hard —— 本页直接读 mining.overview 各实例的 requiredMinerLevel
 * 字段 (mock 种子已按 L4/L8 填), 不在前端另外硬编码一份数字。
 *
 * 重置倒计时 (F7): 真服倒计时只活在 AutoResetScheduler 私有内存字段, 仅经聊天广播, 无 S2C 通道;
 * mock 按 "上次重置时刻 + 固定周期" 推算 nextResetAt, 与清单建议的退而求其次方案同构。
 *
 * 中文输入: 本页全部交互都是按钮点击与只读展示, 不含任何自由文本输入控件, 不涉及
 * TextInput 的 onRequestEdit 接口位。
 */

const DIFFICULTY_TAG: Record<PlannedDifficulty, string> = {
  easy: '简单',
  medium: '普通',
  hard: '困难',
}

type ActionFeedback = { tone: 'success' | 'danger'; message: string }

function dangerTone(danger: number): Tone {
  if (danger < 0.3) {
    return 'success'
  }
  if (danger < 0.6) {
    return 'warning'
  }
  return 'danger'
}

function formatCountdown(targetMs: number, nowValue: number): string {
  const remaining = targetMs - nowValue
  if (remaining <= 0) {
    return '即将重置'
  }
  const totalSeconds = Math.floor(remaining / 1000)
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`
}

interface MiningInstanceCardProps {
  instance: PlannedMiningInstance
  minerLevel: number | null
  insideHere: boolean
  entering: boolean
  leaving: boolean
  nowValue: number
  onEnter: () => void
  onLeave: () => void
}

function MiningInstanceCard({
  instance,
  minerLevel,
  insideHere,
  entering,
  leaving,
  nowValue,
  onEnter,
  onLeave,
}: MiningInstanceCardProps): ReactElement {
  const locked = minerLevel === null || minerLevel < instance.requiredMinerLevel

  return (
    <Panel
      actions={<Tag tone={locked ? 'neutral' : 'brand'}>{DIFFICULTY_TAG[instance.difficulty]}</Tag>}
      title={instance.displayName}
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
              ? `需要矿工 ${String(instance.requiredMinerLevel)} 级 (${
                  minerLevel === null ? '等级读取中' : `当前 ${String(minerLevel)} 级`
                })`
              : `已解锁 (需矿工 ${String(instance.requiredMinerLevel)} 级)`}
          </span>
        </div>

        <Meter
          label="危险度"
          max={100}
          tone={dangerTone(instance.danger)}
          value={instance.danger * 100}
          valueText={`${String(Math.round(instance.danger * 100))}%`}
        />

        <div className="flex flex-col gap-1">
          <Stat label="当前在线" layout="inline" value={`${String(instance.playersInside)} 人`} />
          <Stat label="下次重置" layout="inline" value={formatCountdown(instance.nextResetAt, nowValue)} />
          <p className="text-muted-foreground text-xs">全服共享实例, 非私有副本</p>
        </div>

        {insideHere ? (
          <Button loading={leaving} onClick={onLeave} variant="destructive">
            离开矿洞
          </Button>
        ) : (
          <Button disabled={locked} loading={entering} onClick={onEnter} variant="brand">
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
  const jobs = useMockAction('job.progress', {})

  const [nowValue, setNowValue] = useState(() => Date.now())
  const [pendingDifficulty, setPendingDifficulty] = useState<PlannedDifficulty | null>(null)
  const [leavePending, setLeavePending] = useState(false)
  const [feedback, setFeedback] = useState<ActionFeedback | null>(null)

  useEffect(() => {
    const timer = window.setInterval(() => {
      setNowValue(Date.now())
    }, 1000)
    return () => {
      window.clearInterval(timer)
    }
  }, [])

  async function handleEnter(difficulty: PlannedDifficulty): Promise<void> {
    setPendingDifficulty(difficulty)
    setFeedback(null)
    try {
      const result = await callMock('mining.enter', { difficulty })
      setFeedback({ tone: result.entered ? 'success' : 'danger', message: result.message })
      overview.reload()
      status.reload()
    } catch (error) {
      setFeedback({ tone: 'danger', message: error instanceof Error ? error.message : String(error) })
    } finally {
      setPendingDifficulty(null)
    }
  }

  async function handleLeave(): Promise<void> {
    setLeavePending(true)
    setFeedback(null)
    try {
      const result = await callMock('mining.leave', {})
      setFeedback({ tone: result.left ? 'success' : 'danger', message: result.message })
      overview.reload()
      status.reload()
    } catch (error) {
      setFeedback({ tone: 'danger', message: error instanceof Error ? error.message : String(error) })
    } finally {
      setLeavePending(false)
    }
  }

  const minerLevel = jobs.status === 'ready' ? jobs.data.jobs.find((entry) => entry.jobId === 'miner')?.level ?? null : null
  const insideDifficulty = status.status === 'ready' && status.data.inside ? status.data.difficulty : null

  return (
    <section className="flex flex-col gap-4">
      {/* 页名由 TabletShell 的 h1 统一渲染, 页面内不再重复 —— 重复两遍且里层更大, 打开必现, 读起来像渲染 bug。 */}
      <header className="flex flex-col gap-2">
        <p className="text-muted-foreground text-sm">
          全服共 3 个常驻共享矿洞实例, 每难度各一个 —— 不是你的私有副本; 卡片上的在线人数是与你
          共享同一空间的全服玩家数, 不是"我的副本进度"。
        </p>
        {jobs.status === 'error' ? (
          <FeedbackAlert
            message={jobs.error.message}
            title="矿工等级读取失败, 等级门暂按锁定处理"
            tone="warning"
          />
        ) : null}
      </header>

      {feedback === null ? null : <FeedbackAlert message={feedback.message} tone={feedback.tone} />}

      {overview.status === 'loading' ? (
        <Panel>
          <LoadingBlock label="正在读取矿洞总览" size="lg" />
        </Panel>
      ) : overview.status === 'error' ? (
        <ErrorBlock message={overview.error.message} onRetry={overview.reload} />
      ) : overview.data.instances.length === 0 ? (
        <EmptyBlock
          hint="矿洞维度当前不可用"
          icon={<TriangleAlertIcon aria-hidden="true" />}
          title="暂无矿洞实例"
        />
      ) : (
        <div className="grid grid-cols-3 gap-4">
          {overview.data.instances.map((instance) => (
            <MiningInstanceCard
              key={instance.difficulty}
              instance={instance}
              minerLevel={minerLevel}
              insideHere={insideDifficulty === instance.difficulty}
              entering={pendingDifficulty === instance.difficulty}
              leaving={leavePending && insideDifficulty === instance.difficulty}
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
          <ErrorBlock message={status.error.message} onRetry={status.reload} />
        ) : !status.data.inside ? (
          <p className="text-muted-foreground text-sm">当前不在任何矿洞实例中。</p>
        ) : status.data.difficulty === null ? (
          <p className="text-destructive text-sm">数据异常: inside 为真但 difficulty 缺失, 请刷新重试。</p>
        ) : (
          <div className="flex flex-col gap-3">
            <p className="text-foreground text-sm">
              当前位于 {DIFFICULTY_TAG[status.data.difficulty]} 难度, 区域坐标 ({status.data.regionX},{' '}
              {status.data.regionZ})
            </p>
            <Meter
              label="实时危险度"
              max={100}
              tone={dangerTone(status.data.danger)}
              value={status.data.danger * 100}
              valueText={`${String(Math.round(status.data.danger * 100))}%`}
            />
            {status.data.spawnFreezeUntil > nowValue ? (
              <div>
                <Tag tone="info">
                  新手保护中, 剩余 {formatCountdown(status.data.spawnFreezeUntil, nowValue)}
                </Tag>
              </div>
            ) : null}
          </div>
        )}
      </Panel>
    </section>
  )
}
