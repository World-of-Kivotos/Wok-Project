import { RefreshCwIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { useState } from 'react'
import {
  Button,
  Currency,
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
import { callErrorText } from '../lib/errorText'
import type {
  QuestChainRow,
  QuestClaimResult,
  QuestRefreshPayload,
  QuestRefreshResult,
  QuestRow,
  QuestTurnInResult,
} from '../lib/types'
import { callMock } from '../mock/handlers'
import { useMockAction } from '../mock/useMockWorld'

type ActionFeedback = { tone: 'success' | 'danger' | 'warning'; message: string }

type RefreshControl = {
  source: QuestRefreshPayload['source']
  slot: number
  cost: number
  creditBalance: number
  onRefresh: () => void
}

interface QuestCardProps {
  row: QuestRow
  pendingKey: string | null
  claimKey: string
  turnInKey: string
  onClaim: () => void
  onTurnIn: () => void
  refresh?: RefreshControl | undefined
}

interface QuestSectionProps {
  title: string
  emptyText: string
  rows: QuestRow[]
  pendingKey: string | null
  refreshSource?: QuestRefreshPayload['source'] | undefined
  refreshCost?: number | undefined
  creditBalance: number
  onClaim: (questId: string) => void
  onTurnIn: (questId: string) => void
  onRefresh: (source: QuestRefreshPayload['source'], slot: number) => void
}

function toError(value: unknown): Error {
  return value instanceof Error ? value : new Error(String(value))
}

function requireTitle(result: { questId: string; title: string | null }): string {
  if (result.title === null) {
    throw new Error(`任务回执 ${result.questId} 缺少标题`)
  }
  return result.title
}

function claimFeedback(result: QuestClaimResult): ActionFeedback {
  switch (result.outcome) {
    case 'CLAIMED': {
      const itemCount = result.items.reduce((sum, item) => sum + item.count, 0)
      const itemText = itemCount > 0 ? `，另有 ${String(itemCount)} 件物品` : ''
      return {
        tone: 'success',
        message: `已领取「${requireTitle(result)}」：${String(result.credit)} 信用点${itemText}`,
      }
    }
    case 'NOT_FOUND':
      return { tone: 'danger', message: `任务 ${result.questId} 不存在` }
    case 'NOT_COMPLETE':
      return { tone: 'warning', message: `「${requireTitle(result)}」尚未完成` }
    case 'ALREADY_CLAIMED':
      return { tone: 'warning', message: `「${requireTitle(result)}」已经领取过奖励` }
    default: {
      const unhandled: never = result.outcome
      throw new Error(`未处理的任务领取结果：${String(unhandled)}`)
    }
  }
}

function turnInFeedback(result: QuestTurnInResult): ActionFeedback {
  switch (result.outcome) {
    case 'TURNED_IN':
      return {
        tone: 'success',
        message: `已向「${requireTitle(result)}」上交 ${String(result.count)} 件物品`,
      }
    case 'NOT_FOUND':
      return { tone: 'danger', message: `任务 ${result.questId} 不存在` }
    case 'NOT_A_TURN_IN':
      return { tone: 'warning', message: `「${requireTitle(result)}」不是上交类任务` }
    case 'ALREADY_COMPLETE':
      return { tone: 'warning', message: `「${requireTitle(result)}」已经完成，可以领取奖励` }
    case 'NOTHING_TO_TURN_IN':
      return { tone: 'warning', message: `没有可向「${requireTitle(result)}」上交的物品` }
    default: {
      const unhandled: never = result.outcome
      throw new Error(`未处理的任务上交结果：${String(unhandled)}`)
    }
  }
}

function refreshFeedback(result: QuestRefreshResult): ActionFeedback {
  switch (result.outcome) {
    case 'REFRESHED':
      if (result.replacement === null) {
        throw new Error('任务重摇成功回执缺少 replacement')
      }
      return {
        tone: 'success',
        message: `已花费 ${String(result.cost)} 信用点，重摇为「${result.replacement.title}」`,
      }
    case 'NOT_ENOUGH_CREDIT':
      return {
        tone: 'warning',
        message: `信用点不足，本次重摇需要 ${String(result.cost)} 信用点`,
      }
    default: {
      const unhandled: never = result.outcome
      throw new Error(`未处理的任务重摇结果：${String(unhandled)}`)
    }
  }
}

function difficultyTone(difficulty: number): 'neutral' | 'warning' | 'danger' {
  if (difficulty <= 1) {
    return 'neutral'
  }
  return difficulty === 2 ? 'warning' : 'danger'
}

function QuestCard({
  row,
  pendingKey,
  claimKey,
  turnInKey,
  onClaim,
  onTurnIn,
  refresh,
}: QuestCardProps): ReactElement {
  const anyPending = pendingKey !== null
  const claimPending = pendingKey === claimKey
  const turnInPending = pendingKey === turnInKey
  const refreshKey =
    refresh === undefined ? null : `refresh:${refresh.source}:${String(refresh.slot)}`
  const refreshPending = refreshKey !== null && pendingKey === refreshKey

  return (
    <Surface className="flex h-full flex-col gap-3">
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-1">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="font-medium text-foreground text-sm">{row.title}</h3>
            <Tag tone={difficultyTone(row.difficulty)}>难度 {String(row.difficulty)}</Tag>
            {row.turnIn ? <Tag tone="info">上交</Tag> : null}
          </div>
          <p className="text-muted-foreground text-xs">{row.objective}</p>
        </div>
        {refresh === undefined ? null : (
          <Button
            aria-label={`重摇任务「${row.title}」`}
            disabled={anyPending || refresh.creditBalance < refresh.cost}
            loading={refreshPending}
            onClick={refresh.onRefresh}
            size="icon-sm"
            title={
              refresh.creditBalance < refresh.cost
                ? `信用点不足，需要 ${String(refresh.cost)}`
                : `花费 ${String(refresh.cost)} 信用点重摇`
            }
            variant="outline"
          >
            <RefreshCwIcon aria-hidden="true" />
          </Button>
        )}
      </div>

      <Meter
        label="任务进度"
        max={row.requiredCount}
        tone={row.complete ? 'success' : 'brand'}
        value={row.count}
        valueText={`${String(row.count)} / ${String(row.requiredCount)}`}
      />

      <div className="mt-auto flex items-end justify-between gap-3">
        <Stat
          label="信用点奖励"
          value={<Currency amount={row.creditReward} currency="credit" size="sm" />}
        />
        {row.claimed ? (
          <Button disabled size="sm" variant="secondary">
            已领取
          </Button>
        ) : row.complete ? (
          <Button
            disabled={anyPending && !claimPending}
            loading={claimPending}
            onClick={onClaim}
            size="sm"
            variant="brand"
          >
            领取
          </Button>
        ) : row.turnIn ? (
          <Button
            disabled={anyPending && !turnInPending}
            loading={turnInPending}
            onClick={onTurnIn}
            size="sm"
            variant="outline"
          >
            上交
          </Button>
        ) : (
          <Button disabled size="sm" variant="secondary">
            进行中
          </Button>
        )}
      </div>
    </Surface>
  )
}

function QuestSection({
  title,
  emptyText,
  rows,
  pendingKey,
  refreshSource,
  refreshCost,
  creditBalance,
  onClaim,
  onTurnIn,
  onRefresh,
}: QuestSectionProps): ReactElement {
  if ((refreshSource === undefined) !== (refreshCost === undefined)) {
    throw new Error(`任务区「${title}」的重摇配置不完整`)
  }

  return (
    <Panel title={title}>
      {rows.length === 0 ? (
        <EmptyBlock title={emptyText} />
      ) : (
        <div className="grid gap-3 lg:grid-cols-2">
          {rows.map((row, slot) => {
            const claimKey = `claim:${row.questId}`
            const turnInKey = `turnIn:${row.questId}`
            const refresh =
              refreshSource === undefined || refreshCost === undefined
                ? undefined
                : {
                    source: refreshSource,
                    slot,
                    cost: refreshCost,
                    creditBalance,
                    onRefresh: () => {
                      onRefresh(refreshSource, slot)
                    },
                  }
            return (
              <QuestCard
                claimKey={claimKey}
                key={row.questId}
                onClaim={() => {
                  onClaim(row.questId)
                }}
                onTurnIn={() => {
                  onTurnIn(row.questId)
                }}
                pendingKey={pendingKey}
                refresh={refresh}
                row={row}
                turnInKey={turnInKey}
              />
            )
          })}
        </div>
      )}
    </Panel>
  )
}

function QuestChainCard({
  chain,
  pendingKey,
  onClaim,
  onTurnIn,
}: {
  chain: QuestChainRow
  pendingKey: string | null
  onClaim: (questId: string) => void
  onTurnIn: (questId: string) => void
}): ReactElement {
  if (chain.finished) {
    return (
      <Surface className="flex items-center justify-between gap-3" tone="success">
        <div className="flex flex-col gap-1">
          <h3 className="font-medium text-foreground text-sm">{chain.title}</h3>
          <p className="text-muted-foreground text-xs">
            全部 {String(chain.stageCount)} 个阶段已经完成
          </p>
        </div>
        <Tag tone="success">已完成</Tag>
      </Surface>
    )
  }
  if (chain.current === null) {
    throw new Error(`未完成的任务线 ${chain.chainId} 缺少当前任务`)
  }
  const current = chain.current
  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center justify-between gap-3">
        <h3 className="font-medium text-foreground text-sm">{chain.title}</h3>
        <Tag tone="brand">
          阶段 {String(chain.stageIndex + 1)} / {String(chain.stageCount)}
        </Tag>
      </div>
      <QuestCard
        claimKey={`claim:${current.questId}`}
        onClaim={() => {
          onClaim(current.questId)
        }}
        onTurnIn={() => {
          onTurnIn(current.questId)
        }}
        pendingKey={pendingKey}
        row={current}
        turnInKey={`turnIn:${current.questId}`}
      />
    </div>
  )
}

export function QuestsPage(): ReactElement {
  const board = useMockAction('quest.board', {})
  const [pendingKey, setPendingKey] = useState<string | null>(null)
  const [feedback, setFeedback] = useState<ActionFeedback | null>(null)

  async function handleClaim(questId: string): Promise<void> {
    const key = `claim:${questId}`
    setPendingKey(key)
    setFeedback(null)
    try {
      const result = await callMock('quest.claim', { questId })
      board.reload()
      setFeedback(claimFeedback(result))
    } catch (error: unknown) {
      setFeedback({ tone: 'danger', message: callErrorText(toError(error)) })
    } finally {
      setPendingKey(null)
    }
  }

  async function handleTurnIn(questId: string): Promise<void> {
    const key = `turnIn:${questId}`
    setPendingKey(key)
    setFeedback(null)
    try {
      const result = await callMock('quest.turnIn', { questId })
      board.reload()
      setFeedback(turnInFeedback(result))
    } catch (error: unknown) {
      setFeedback({ tone: 'danger', message: callErrorText(toError(error)) })
    } finally {
      setPendingKey(null)
    }
  }

  async function handleRefresh(
    source: QuestRefreshPayload['source'],
    slot: number,
  ): Promise<void> {
    const key = `refresh:${source}:${String(slot)}`
    setPendingKey(key)
    setFeedback(null)
    try {
      const result = await callMock('quest.refresh', { source, slot })
      board.reload()
      setFeedback(refreshFeedback(result))
    } catch (error: unknown) {
      setFeedback({ tone: 'danger', message: callErrorText(toError(error)) })
    } finally {
      setPendingKey(null)
    }
  }

  if (board.status === 'loading') {
    return <LoadingBlock label="正在读取任务板" />
  }
  if (board.status === 'error') {
    return <ErrorBlock message={callErrorText(board.error)} onRetry={board.reload} />
  }

  const data = board.data
  return (
    <section className="flex flex-col gap-4">
      {feedback === null ? null : (
        <FeedbackAlert
          message={feedback.message}
          onDismiss={() => {
            setFeedback(null)
          }}
          tone={feedback.tone}
        />
      )}

      <Panel description="重摇会立即扣除信用点，并替换对应槽位的任务。" title="任务板">
        <div className="grid gap-4 sm:grid-cols-3">
          <Stat
            label="信用点余额"
            value={<Currency amount={data.creditBalance} currency="credit" />}
          />
          <Stat
            label="每日重摇单价"
            value={<Currency amount={data.dailyRefreshCost} currency="credit" />}
          />
          <Stat
            label="每周重摇单价"
            value={<Currency amount={data.weeklyRefreshCost} currency="credit" />}
          />
        </div>
      </Panel>

      <QuestSection
        creditBalance={data.creditBalance}
        emptyText="当前没有每日任务"
        onClaim={(questId) => {
          void handleClaim(questId)
        }}
        onRefresh={(source, slot) => {
          void handleRefresh(source, slot)
        }}
        onTurnIn={(questId) => {
          void handleTurnIn(questId)
        }}
        pendingKey={pendingKey}
        refreshCost={data.dailyRefreshCost}
        refreshSource="daily"
        rows={data.daily}
        title="每日任务"
      />

      <QuestSection
        creditBalance={data.creditBalance}
        emptyText="当前没有每周任务"
        onClaim={(questId) => {
          void handleClaim(questId)
        }}
        onRefresh={(source, slot) => {
          void handleRefresh(source, slot)
        }}
        onTurnIn={(questId) => {
          void handleTurnIn(questId)
        }}
        pendingKey={pendingKey}
        refreshCost={data.weeklyRefreshCost}
        refreshSource="weekly"
        rows={data.weekly}
        title="每周任务"
      />

      <QuestSection
        creditBalance={data.creditBalance}
        emptyText="当前没有特殊任务"
        onClaim={(questId) => {
          void handleClaim(questId)
        }}
        onRefresh={(source, slot) => {
          void handleRefresh(source, slot)
        }}
        onTurnIn={(questId) => {
          void handleTurnIn(questId)
        }}
        pendingKey={pendingKey}
        rows={data.special}
        title="特殊任务"
      />

      <Panel title="任务线">
        {data.chains.length === 0 ? (
          <EmptyBlock title="当前没有任务线" />
        ) : (
          <div className="flex flex-col gap-4">
            {data.chains.map((chain) => (
              <QuestChainCard
                chain={chain}
                key={chain.chainId}
                onClaim={(questId) => {
                  void handleClaim(questId)
                }}
                onTurnIn={(questId) => {
                  void handleTurnIn(questId)
                }}
                pendingKey={pendingKey}
              />
            ))}
          </div>
        )}
      </Panel>
    </section>
  )
}
