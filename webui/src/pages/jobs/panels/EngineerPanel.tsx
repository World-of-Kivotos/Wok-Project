import type { ReactElement } from 'react'
import { useState } from 'react'
import type { PixelTab } from '../../../components/pixel'
import { PixelBadge, PixelError, PixelFrame, PixelLoading, PixelTable, PixelTabs } from '../../../components/pixel'
import { useMockAction } from '../../../mock'
import { formatStatValue } from './shared'

/**
 * 铸甲师 (engineer, 玩家可见职业名"铸甲师"; engineer 只是旧存档兼容 id, 见 planned.ts 的
 * PlannedEngineerStateResult 注释) 面板 —— 接线清单 C21 job.engineer.state, PLANNED。
 *
 * 依赖的假定契约:
 *   - job.engineer.state -> PlannedEngineerStateResult (纳米板档位表 + 护甲特效解锁状态)
 *
 * 契约缺口 (报告给核销清单, 不在此处自造字段凑齐):
 *   - 任务描述要求的"反应堆 CD"在 PlannedEngineerStateResult 里没有对应字段。服务端确实存在该数据
 *     (docs/MillenniumEngineer_Mod_DesignSpec.md 记的 `nanoReactorCdEndTick`, 已并入
 *     IMiningPlayerData), 但 webui 前端假定契约 (mock/planned.ts) 遗漏了这个字段, 本面板因此无法
 *     展示反应堆冷却, 只能展示纳米板档位与护甲特效两块。
 *   - 纳米校准 QTE (C21 备注) 按清单 J5 的决策"不进 MCEF, 只做数值预览", 本面板不做任何计时器/判定 UI,
 *     符合决策而非遗漏。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}

type EngineerTabId = 'tiers' | 'armor'

const ENGINEER_TABS: readonly PixelTab[] = [
  { id: 'tiers', label: '纳米板档位' },
  { id: 'armor', label: '护甲特效' },
]

function isEngineerTabId(value: string): value is EngineerTabId {
  return value === 'tiers' || value === 'armor'
}

export function EngineerPanel(): ReactElement {
  const query = useMockAction('job.engineer.state', EMPTY_PAYLOAD)
  const [activeTab, setActiveTab] = useState<EngineerTabId>('tiers')
  const [expandedEffectId, setExpandedEffectId] = useState<string | null>(null)

  if (query.status === 'loading') {
    return <PixelLoading label="正在读取铸甲师档案" />
  }
  if (query.status === 'error') {
    return <PixelError message={query.error.message} onRetry={query.reload} />
  }

  const data = query.data

  return (
    <div className="flex flex-col gap-6">
      <PixelFrame variant="panel" className="flex flex-wrap items-center justify-between gap-4 p-4">
        <span className="text-2x text-fg">铸甲师 Lv.{data.level}</span>
        <span className="text-1x text-muted">反应堆冷却字段缺失: 契约 (planned.ts) 未包含 nanoReactorCdEndTick</span>
      </PixelFrame>

      <PixelTabs
        tabs={ENGINEER_TABS}
        activeId={activeTab}
        onChange={(id) => {
          if (isEngineerTabId(id)) {
            setActiveTab(id)
          }
        }}
      />

      {activeTab === 'tiers' ? (
        <PixelTable
          columns={[
            { key: 'label', header: '档位', render: (row) => row.label },
            {
              key: 'value',
              header: '数值',
              render: (row) => formatStatValue(row.value, row.unit),
              sortValue: (row) => row.value,
            },
          ]}
          rows={data.tiers}
          rowKey={(row) => row.key}
          emptyHint="尚无纳米板档位数据"
        />
      ) : (
        <div className="flex flex-col gap-2">
          {data.armorEffects.map((effect) => {
            const expanded = effect.effectId === expandedEffectId
            return (
              <button
                key={effect.effectId}
                type="button"
                onClick={() => {
                  setExpandedEffectId(expanded ? null : effect.effectId)
                }}
                className="block w-full border-2 border-transparent text-left outline-none focus-visible:border-border-strong"
              >
                <PixelFrame variant="panel" className="flex flex-col gap-2 p-4">
                  <div className="flex items-center justify-between gap-4">
                    <span className="text-1x text-fg">{effect.displayName}</span>
                    <PixelBadge tone={effect.unlocked ? 'success' : 'neutral'}>
                      {effect.unlocked ? '已解锁' : '未解锁'}
                    </PixelBadge>
                  </div>
                  {expanded ? <p className="text-1x text-muted">{effect.description}</p> : null}
                </PixelFrame>
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}
