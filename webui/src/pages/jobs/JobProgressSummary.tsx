import type { ReactElement } from 'react'
import { PixelBadge, PixelProgress } from '../../components/pixel'
import type { PlannedJobProgressEntry } from '../../mock'

/**
 * 职业进度的等级徽标与经验条 —— 职业总览的卡片与单职业详情的页头共用同一份渲染逻辑, 避免"满级怎么判"
 * 这条规则 (nextLevelXp === 0, 不是 level === 10, 见 mock/planned.ts PlannedJobProgressEntry 的字段注释)
 * 在两处各抄一份、日后改一处漏一处。
 */

export function isGraduated(entry: PlannedJobProgressEntry): boolean {
  return entry.nextLevelXp === 0
}

export function JobLevelBadge({ entry }: { entry: PlannedJobProgressEntry }): ReactElement {
  return (
    <PixelBadge tone={isGraduated(entry) ? 'success' : 'accent'}>{`Lv.${String(entry.level)}`}</PixelBadge>
  )
}

export function JobExpProgress({ entry }: { entry: PlannedJobProgressEntry }): ReactElement {
  const graduated = isGraduated(entry)
  return (
    <PixelProgress
      value={graduated ? 1 : entry.levelXp}
      max={graduated ? 1 : entry.nextLevelXp}
      tone={graduated ? 'success' : 'accent'}
      label={graduated ? '已毕业 (满级)' : `经验 ${String(entry.levelXp)}/${String(entry.nextLevelXp)}`}
    />
  )
}
