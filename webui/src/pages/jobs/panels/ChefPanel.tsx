import type { ReactElement } from 'react'
import { PixelCurrency, PixelError, PixelFrame, PixelLoading, PixelTable } from '../../../components/pixel'
import { useMockAction } from '../../../mock'
import { formatStatValue } from './shared'

/**
 * 厨师面板 (接线清单 C9 job.chef.state, PLANNED, 备注"品质上限纯函数 + ChefConfig 效果表. **数值走
 * ForgeConfigSpec 运营可调, 前端必须实时读而非抄静态副本**")。
 *
 * 依赖的假定契约:
 *   - job.chef.state -> PlannedChefStateResult (品质上限/各品质效果数值/调味台花费)
 *
 * 做菜火候小游戏判定 C10 NONE(webui): QTE 类交互走原生 Container GUI, 按接线清单 J5 的决策不进 MCEF
 * (游标是每 tick 变化的服务端时序权威值, 网络延迟直接影响判定手感)。本面板因此只做数值预览, 没有
 * 任何计时器/判定 UI, 符合决策而非遗漏 —— 与 EngineerPanel 对纳米校准 QTE 的处理同一条纪律。
 * 因此本面板没有对应写操作, 全部只读; 三态 (加载/空/错误) 仍全部可触发。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}

export function ChefPanel(): ReactElement {
  const query = useMockAction('job.chef.state', EMPTY_PAYLOAD)

  if (query.status === 'loading') {
    return <PixelLoading label="正在读取厨师档案" />
  }
  if (query.status === 'error') {
    return <PixelError message={query.error.message} onRetry={query.reload} />
  }

  const data = query.data

  return (
    <div className="flex flex-col gap-6">
      <PixelFrame variant="panel" className="flex flex-wrap items-center justify-between gap-4 p-4">
        <span className="text-2x text-fg">厨师 Lv.{data.level}</span>
        <span className="text-1x text-muted">品质上限 {data.qualityCap} 品</span>
      </PixelFrame>

      <section className="flex flex-col gap-3">
        <h2 className="text-2x text-fg">效果数值表</h2>
        <PixelTable
          columns={[
            { key: 'label', header: '品质档位', render: (row) => row.label },
            {
              key: 'value',
              header: '效果数值',
              render: (row) => formatStatValue(row.value, row.unit),
              sortValue: (row) => row.value,
            },
          ]}
          rows={data.effects}
          rowKey={(row) => row.key}
          emptyHint="暂无效果数据"
        />
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-2x text-fg">调味台花费</h2>
        <PixelFrame variant="panel" className="p-4">
          <PixelCurrency amount={data.seasoningCostCredit} currency="credit" />
        </PixelFrame>
      </section>
    </div>
  )
}
