import { StarIcon, TriangleAlertIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { EmptyBlock } from '@/components/kit'
import type { PlannedJobId } from '../../mock'
import { useMockWorld } from '../../mock'
import { useRouteParams } from '../../router'
import { AgentPanel } from './panels/AgentPanel'
import { BrewerPanel } from './panels/BrewerPanel'
import { ChefPanel } from './panels/ChefPanel'
import { EngineerPanel } from './panels/EngineerPanel'
import { FarmerPanel } from './panels/FarmerPanel'
import { MinerPanel } from './panels/MinerPanel'
import { MunitionsPanel } from './panels/MunitionsPanel'
import { TarotPanel } from './panels/TarotPanel'

/**
 * 单职业详情 (接线清单 C 组: 矿工/农夫/厨师/酿酒师/塔罗/特勤/军火商/工程师各一套面板)。
 *
 * 这是全表唯一带动态段的路由 (#/jobs/:id)。参数经 useRouteParams 取, 不从 location 自己解析 ——
 * 运行期整个前端只有 router.ts 读 location, 理由见那个文件头 (写 hash 会让宿主的 URL 精确匹配失效)。
 *
 * jobId 缺席时不兜一个默认职业: 那会让"链接拼错"表现成"打开了矿工页", 排障时无从发现。
 *
 * 本文件只做 jobId -> 面板组件的分发, 具体实现落在 panels/ 子目录 (一职业一文件)。这样拆分是刻意的:
 * 8 个职业面板由不同批次交付, 若都直接写死在本文件的一段大分支里, 任意两个批次同时收工都会在这一个
 * 文件上打架; 拆成"各自认领 panels/ 下自己的文件 + 在 IMPLEMENTED_PANELS 里追加一行"之后, 冲突面
 * 缩小到追加的那一行, 与 components/kit/index.ts 的 barrel 追加纪律同一思路。
 *
 * 战斗/特殊职业四家 (塔罗/特勤/军火商/工程师) 与生产职业四家 (矿工/农夫/厨师/酿酒师) 均已落地
 * (各自的假定契约与接线清单行号见对应 panels/ 文件头), 8 个 case 全部认领完毕。
 */

const IMPLEMENTED_PANELS: Partial<Record<PlannedJobId, () => ReactElement>> = {
  tarot: TarotPanel,
  agent: AgentPanel,
  munitions: MunitionsPanel,
  engineer: EngineerPanel,
  miner: MinerPanel,
  farmer: FarmerPanel,
  chef: ChefPanel,
  brewer: BrewerPanel,
}

const KNOWN_JOB_IDS: readonly PlannedJobId[] = [
  'miner',
  'farmer',
  'engineer',
  'tarot',
  'chef',
  'agent',
  'munitions',
  'brewer',
]

function isPlannedJobId(value: string): value is PlannedJobId {
  return (KNOWN_JOB_IDS as readonly string[]).includes(value)
}

export function JobDetailPage(): ReactElement {
  const params = useRouteParams()
  const jobId = params.id
  const world = useMockWorld()

  if (jobId === undefined) {
    return (
      <EmptyBlock hint="路径里缺少职业 id" icon={<TriangleAlertIcon aria-hidden="true" />} title="单职业详情" />
    )
  }
  if (!isPlannedJobId(jobId)) {
    return (
      <EmptyBlock
        hint={`未知职业 id: ${jobId}`}
        icon={<TriangleAlertIcon aria-hidden="true" />}
        title="单职业详情"
      />
    )
  }

  // 叫 JobPanel 而不是 Panel: kit 导出了一个同名的容器组件 (@/components/kit 的 Panel),
  // 本文件恰好没导入它所以不冲突, 但同一个标识符在别处是另一个东西, 是纯粹的阅读陷阱。
  const JobPanel = IMPLEMENTED_PANELS[jobId]
  if (JobPanel !== undefined) {
    return <JobPanel />
  }

  const progress = world.jobs.progress.find((entry) => entry.jobId === jobId)
  const displayName = progress === undefined ? jobId : progress.displayName
  return (
    <EmptyBlock
      hint="该职业面板由生产职业批次交付, 当前仅占位"
      icon={<StarIcon aria-hidden="true" />}
      title={`单职业详情 · ${displayName}`}
    />
  )
}
