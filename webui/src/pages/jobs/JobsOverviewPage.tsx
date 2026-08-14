import { StarIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { EmptyBlock, ErrorBlock, LoadingBlock } from '@/components/kit'
import { useMockAction } from '../../mock'
import type { PlannedJobProgressEntry } from '../../mock'
import { buildJobDetailPath, useNavigate } from '../../router'
import { JobExpProgress, JobLevelBadge } from './JobProgressSummary'

/**
 * 职业总览 (接线清单 C1 `job.progress`: 8 条职业进度一次拿回, `IJobService.progress` 已给全字段,
 * 当前只经 `/job list` 聊天文本暴露)。假定契约见 mock/planned.ts 的 PlannedJobProgressResult /
 * PlannedJobProgressEntry, 对应 mock/handlers.ts 的 `job.progress` 分支。
 *
 * C3 已定: 全部职业被动恒生效, 不做单选器 —— 8 张卡片按 `JobId.values()` 声明序平铺, 点击进对应详情
 * (路由 `/jobs/:id`, 由 JobDetailPage 按 id 分发到各职业面板)。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}

function JobOverviewCard({
  entry,
  onOpen,
}: {
  entry: PlannedJobProgressEntry
  onOpen: () => void
}): ReactElement {
  return (
    <button
      className="flex w-full flex-col gap-3 rounded-xl border bg-card p-4 text-left transition-colors outline-none hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring"
      onClick={onOpen}
      type="button"
    >
      <div className="flex items-center justify-between gap-2">
        <span className="font-medium text-foreground text-sm">{entry.displayName}</span>
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

  if (query.status === 'loading') {
    return <LoadingBlock label="正在加载职业总览" size="lg" />
  }

  if (query.status === 'error') {
    return <ErrorBlock message={query.error.message} onRetry={query.reload} />
  }

  const jobs = query.data.jobs

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
