import { StarIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { EmptyBlock, ErrorBlock, LoadingBlock } from '@/components/kit'
import { jobNameKey, useItemNames } from '../../lib/i18n'
import type { PlayerJobProgressEntry } from '../../lib/types'
import { useMockAction } from '../../mock'
import { buildJobDetailPath, useNavigate } from '../../router'
import { JobExpProgress, JobLevelBadge } from './JobProgressSummary'

/**
 * 职业总览 (`job.progress`, Java 落点 com.miningdim.job.JobWebUiActions)。回执形状见 lib/types.ts 的
 * JobProgressResult / PlayerJobProgressEntry —— 与 player.profile 的 jobs 同形同实现, 独立成一条只为
 * 省掉钱包与 faucet 那 3 次 SQLite。
 *
 * 服务端不发职业中文名 (专用服务端解不出 lang), 故本页按 `job.miningdim.<jobId>` 走 client.i18n 自解,
 * 8 个键一次批量请求 —— 别退化成每张卡片各发一次。
 *
 * 全部职业被动恒生效, 不做单选器 —— 8 张卡片按 `JobId.values()` 声明序平铺, 点击进对应详情
 * (路由 `/jobs/:id`, 由 JobDetailPage 按 id 分发到各职业面板)。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}

function JobOverviewCard({
  entry,
  displayName,
  onOpen,
}: {
  entry: PlayerJobProgressEntry
  displayName: string
  onOpen: () => void
}): ReactElement {
  return (
    <button
      className="flex w-full flex-col gap-3 rounded-xl border bg-card p-4 text-left transition-colors outline-none hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring"
      onClick={onOpen}
      type="button"
    >
      <div className="flex items-center justify-between gap-2">
        <span className="font-medium text-foreground text-sm">{displayName}</span>
        <JobLevelBadge entry={entry} />
      </div>
      <JobExpProgress entry={entry} />
      <div className="flex items-center justify-between gap-2 text-xs">
        <span className="text-muted-foreground">今日已获经验 {entry.dailyXp}</span>
        <span className={entry.dailyRemaining === 0 ? 'text-destructive' : 'text-muted-foreground'}>
          今日剩余衰减额度 {entry.dailyRemaining}
        </span>
      </div>
    </button>
  )
}

export function JobsOverviewPage(): ReactElement {
  const navigate = useNavigate()
  const query = useMockAction('job.progress', EMPTY_PAYLOAD)
  const jobs: readonly PlayerJobProgressEntry[] = query.status === 'ready' ? query.data.jobs : []
  const names = useItemNames(jobs.map((entry) => jobNameKey(entry.jobId)))

  if (query.status === 'loading') {
    return <LoadingBlock label="正在加载职业总览" size="lg" />
  }

  if (query.status === 'error') {
    return <ErrorBlock message={query.error.message} onRetry={query.reload} />
  }

  if (jobs.length === 0) {
    return (
      <EmptyBlock
        hint="职业数据读取失败, 请稍后重试"
        icon={<StarIcon aria-hidden="true" />}
        title="暂无职业进度"
      />
    )
  }

  return (
    <div className="grid grid-cols-2 gap-4">
      {jobs.map((entry) => (
        <JobOverviewCard
          // 解不出来时退回翻译键本身 (与 lib/i18n 同纪律: 让"没解出来"可见, 不伪装成正常名字)。
          displayName={names[jobNameKey(entry.jobId)] ?? jobNameKey(entry.jobId)}
          entry={entry}
          key={entry.jobId}
          onOpen={() => {
            navigate(buildJobDetailPath(entry.jobId))
          }}
        />
      ))}
    </div>
  )
}
