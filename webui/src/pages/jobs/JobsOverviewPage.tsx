import type { ReactElement } from 'react'
import { PixelEmpty, PixelError, PixelFrame, PixelLoading } from '../../components/pixel'
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
      type="button"
      onClick={onOpen}
      className="block w-full border-2 border-transparent text-left shadow-hard outline-none focus-visible:border-border-strong active:translate-y-1 active:shadow-none"
    >
      <PixelFrame variant="panel" className="flex w-full flex-col gap-3 p-4">
        <div className="flex items-center justify-between gap-2">
          <span className="text-2x text-fg">{entry.displayName}</span>
          <JobLevelBadge entry={entry} />
        </div>
        <JobExpProgress entry={entry} />
        <div className="flex items-center justify-between text-1x text-muted">
          <span>今日已获经验 {entry.dailyXp}</span>
          <span className={entry.dailyRemaining === 0 ? 'text-danger' : 'text-muted'}>
            今日剩余衰减额度 {entry.dailyRemaining}
          </span>
        </div>
      </PixelFrame>
    </button>
  )
}

export function JobsOverviewPage(): ReactElement {
  const navigate = useNavigate()
  const query = useMockAction('job.progress', EMPTY_PAYLOAD)

  if (query.status === 'loading') {
    return (
      <PixelFrame variant="panel" className="flex items-center justify-center p-12">
        <PixelLoading label="正在加载职业总览" size="lg" />
      </PixelFrame>
    )
  }

  if (query.status === 'error') {
    return <PixelError message={query.error.message} onRetry={query.reload} />
  }

  const jobs = query.data.jobs

  if (jobs.length === 0) {
    return <PixelEmpty title="暂无职业进度" hint="种子数据缺失, 请检查 mock/seed.ts" icon="star" />
  }

  return (
    <div className="grid grid-cols-2 gap-4">
      {jobs.map((entry) => (
        <JobOverviewCard
          key={entry.jobId}
          entry={entry}
          onOpen={() => {
            navigate(buildJobDetailPath(entry.jobId))
          }}
        />
      ))}
    </div>
  )
}
