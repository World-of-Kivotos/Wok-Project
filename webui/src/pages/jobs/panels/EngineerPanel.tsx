import type { ReactElement } from 'react'
import { useState } from 'react'
import type { TabItem } from '@/components/kit'
import { DataTable, ErrorBlock, LoadingBlock, Panel, Stat, TabBar, Tag } from '@/components/kit'
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

const ENGINEER_TABS: readonly TabItem[] = [
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
    return <LoadingBlock label="正在读取铸甲师档案" />
  }
  if (query.status === 'error') {
    return <ErrorBlock message={query.error.message} onRetry={query.reload} />
  }

  const data = query.data

  return (
    <div className="flex flex-col gap-4">
      <Panel title="铸甲师">
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-3 gap-4">
            <Stat label="职业等级" value={`Lv.${String(data.level)}`} />
          </div>
          <p className="text-muted-foreground text-xs">
            反应堆冷却时间暂不可见
          </p>
        </div>
      </Panel>

      <TabBar
        activeId={activeTab}
        onChange={(id) => {
          if (isEngineerTabId(id)) {
            setActiveTab(id)
          }
        }}
        tabs={ENGINEER_TABS}
        variant="underline"
      />

      {activeTab === 'tiers' ? (
        <Panel title="纳米板档位">
          <DataTable
            columns={[
              { header: '档位', key: 'label', render: (row) => row.label },
              {
                header: '数值',
                key: 'value',
                numeric: true,
                render: (row) => formatStatValue(row.value, row.unit),
                sortValue: (row) => row.value,
              },
            ]}
            emptyHint="尚无纳米板档位数据"
            rowKey={(row) => row.key}
            rows={data.tiers}
          />
        </Panel>
      ) : (
        <Panel title="护甲特效">
          <div className="flex flex-col gap-2">
            {data.armorEffects.map((effect) => {
              const expanded = effect.effectId === expandedEffectId
              return (
                <button
                  className="flex w-full flex-col rounded-lg border border-border bg-muted/40 p-3 text-left transition-colors outline-none hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring"
                  key={effect.effectId}
                  onClick={() => {
                    setExpandedEffectId(expanded ? null : effect.effectId)
                  }}
                  type="button"
                >
                  <div className="flex items-center justify-between gap-4">
                    <h3 className="font-medium text-foreground text-sm">{effect.displayName}</h3>
                    <Tag tone={effect.unlocked ? 'success' : 'neutral'}>
                      {effect.unlocked ? '已解锁' : '未解锁'}
                    </Tag>
                  </div>
                  {/*
                   * 折叠区走 grid-template-rows: 0fr -> 1fr, 不用 height/max-height。
                   *
                   * height 需要先量出内容高度才能写死数值, 描述文案长度不定且会随窗口宽度换行, 量不准就是
                   * 展开到一半被截断; max-height 猜一个上限则让短文案的过渡前半段在空跑, 手感是"先卡后弹"。
                   * fr 插值由浏览器按内容实际高度算, 两个毛病都没有 —— 代价是它确实会逐帧重排, 这是本次
                   * 动效里唯一被豁免的非 transform/opacity 属性, 因为折叠区只有一段文字, 重排范围小。
                   * fr 插值 Chrome 107 起支持, 在 MCEF 的 Chromium 116 基线内。
                   *
                   * 内层的 overflow-hidden 不只是裁切: 它同时把 grid 项的 automatic minimum size 归零,
                   * 少了它行高压不到 0, 收起后仍留一条内容高度的残影。
                   *
                   * 按钮上原来的 gap-2 挪进来变成 pt-2: 折叠区现在恒常挂在 DOM 上, 留着 gap 会让收起态
                   * 也吃到 8px 间距, 卡片收不干净。间距放进被裁切的盒子里才跟着一起收。
                   *
                   * aria-hidden 跟着 expanded 走, 是为了保住改动前的读屏行为: 折叠区在 <button> 内部,
                   * 不隐藏的话这段描述会永远被算进按钮的可访问名, 收起态也照读一遍。
                   */}
                  <div
                    aria-hidden={!expanded}
                    className={`grid transition-[grid-template-rows,opacity] duration-(--duration-expand) ease-out-soft ${
                      expanded ? 'grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0'
                    }`}
                  >
                    <div className="overflow-hidden">
                      <p className="pt-2 text-muted-foreground text-sm">{effect.description}</p>
                    </div>
                  </div>
                </button>
              )
            })}
          </div>
        </Panel>
      )}
    </div>
  )
}
