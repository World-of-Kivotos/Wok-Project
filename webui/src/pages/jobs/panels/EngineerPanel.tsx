import type { ReactElement } from 'react'
import { useMemo, useState } from 'react'
import type { TabItem } from '@/components/kit'
import {
  DataTable,
  ErrorBlock,
  LoadingBlock,
  Meter,
  Panel,
  Stat,
  Surface,
  TabBar,
  Tag,
} from '@/components/kit'
import { MS_PER_TICK, tickDeadline } from '@/hooks/use-live-updates'
import { callErrorText } from '../../../lib/errorText'
import { useItemNames } from '../../../lib/i18n'
import type { EngineerStatLine, NanoTierRow } from '../../../lib/types'
import { nowMs, useMockAction } from '../../../mock'
import { formatCountdown, useLiveNow } from './shared'

/**
 * 铸甲师面板 (`job.engineer.state`, Java 落点 com.miningdim.job.engineer.EngineerWebUiActions)。
 * 回执形状见 lib/types.ts 的 EngineerStateResult。
 *
 * 两条必须照做的契约事实:
 *   1. **repairValue 一定要连 repairUnit 一起读**: 低/中/高档是绝对耐久点 (100/250/600), 极品/超凡/闪耀
 *      是最大耐久的**千分比** (300/650/1000)。同一个数字在两种量纲下差几个数量级, 只画 value 就是骗人。
 *   2. **四个护甲特效没有各自的等级门**: NanoRepair.rollEffect 是四选一等概率, 它们在"最低的那个能掷
 *      特效的档"同时解锁, 故四个 unlocked 恒同步翻转 —— 面板只说一句"Lv.N 起可掷出", 不画四条解锁线。
 *
 * 反应堆 CD 已由服务端下发 (剩余 tick + 全长 tick), 旧版那句"反应堆冷却时间暂不可见"作废。
 * 纳米校准 QTE 一个字段都不下发 (决策 J5: 那是必须在游戏内做的操作, 面板里给出来等于开挂),
 * 但校准的**结果面** (阈值/额外产板概率) 属数值预览, 照常展示。
 *
 * 特效名/描述与数值标签走的翻译键 (effect.miningdim.nano.* / stat.miningdim.engineer.*) 在 lang 文件里
 * 尚未落地, 解出来就是键本身 —— 那是服务端已报备的缺口, 前端如实显示而不是自己编一份中文字典。
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

/**
 * 护甲特效数值行的量纲格式化。
 * 刻意不复用 shared.ts 的 formatStatValue: 那个吃的是 JobStatUnit, 而本组多一档 'count' 且没有
 * multiplier/blocks/credit —— 硬塞进去会让两个互不相交的量纲词表在一个 switch 里混住。
 */
function formatEngineerStat(line: EngineerStatLine): string {
  switch (line.unit) {
    case 'percent':
      return `${(line.value * 100).toFixed(1)}%`
    // 实现把 IntValue 全部拓宽成 double, 故 ticks/count 在 JSON 里也是 36000.0 这种形态, 展示前必须取整。
    case 'ticks':
      return `${(line.value / 20).toFixed(1)}s`
    case 'count':
      return String(Math.round(line.value))
    case 'flat':
      return String(line.value)
    default:
      return String(line.value)
  }
}

/** 修复量。两种量纲的数量级差几个数, 单画数字必然误导。 */
function formatRepair(tier: NanoTierRow): string {
  return tier.repairUnit === 'permille'
    ? `最大耐久 ${(tier.repairValue / 10).toFixed(1)}%`
    : `${String(tier.repairValue)} 点耐久`
}

/** 该档的特殊规则 (只有闪耀档带成功率与失败返还)。 */
function tierNote(tier: NanoTierRow): string {
  const notes: string[] = []
  if (tier.guaranteedEffect) {
    notes.push('必定重掷特效')
  } else if (tier.canRollEffect) {
    notes.push('可掷出特效')
  }
  if (tier.successChance !== undefined) {
    notes.push(`成功率 ${(tier.successChance * 100).toFixed(0)}%`)
  }
  if (tier.failRefundScrap !== undefined) {
    notes.push(`失败返还 ${String(tier.failRefundScrap)} 碎片`)
  }
  return notes.length === 0 ? '—' : notes.join(' · ')
}

export function EngineerPanel(): ReactElement {
  const query = useMockAction('job.engineer.state', EMPTY_PAYLOAD)
  const now = useLiveNow()
  const [activeTab, setActiveTab] = useState<EngineerTabId>('tiers')
  const [expandedEffectId, setExpandedEffectId] = useState<string | null>(null)

  const data = query.status === 'ready' ? query.data : null

  // 剩余 tick 只在收到回执那一刻有意义, 故在 data 换引用时折一次本地到期时刻 (与矿工面板同纪律)。
  const reactorReadyAt = useMemo(
    () => (data === null ? 0 : tickDeadline(data.reactorCooldownRemainingTicks, nowMs())),
    [data],
  )

  const names = useItemNames(
    data === null
      ? []
      : [
          data.jobNameKey,
          ...data.tiers.map((tier) => tier.labelKey),
          ...data.armorEffects.map((effect) => effect.labelKey),
          ...data.armorEffects.map((effect) => effect.descriptionKey),
          ...data.armorEffects.flatMap((effect) => effect.stats.map((line) => line.labelKey)),
        ],
  )
  const nameOf = (nameKey: string): string => names[nameKey] ?? nameKey

  if (query.status === 'loading') {
    return <LoadingBlock label="正在读取铸甲师档案" />
  }
  if (query.status === 'error') {
    return <ErrorBlock message={callErrorText(query.error)} onRetry={query.reload} />
  }
  if (data === null) {
    return <ErrorBlock message="job.engineer.state 回执为空" onRetry={query.reload} />
  }

  const reactorRemainingMs = Math.max(0, reactorReadyAt - now)
  const reactorTotalMs = data.reactorSharedCdTicks * MS_PER_TICK
  const unlockedTier = data.tiers.find((tier) => tier.tierId === data.unlockedTierId)

  return (
    <div className="flex flex-col gap-4">
      <Panel title={nameOf(data.jobNameKey)}>
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-3 gap-4">
            <Stat label="职业等级" value={`Lv.${String(data.level)}`} />
            <Stat
              label="已解锁最高档"
              value={unlockedTier === undefined ? data.unlockedTierId : nameOf(unlockedTier.labelKey)}
            />
            <Stat label="护甲特效解锁等级" value={`Lv.${String(data.effectUnlockLevel)}`} />
          </div>

          {reactorTotalMs <= 0 ? (
            <p className="text-muted-foreground text-xs">反应堆共享冷却全长为 0, 无冷却可画</p>
          ) : reactorRemainingMs <= 0 ? (
            <div className="flex flex-wrap items-center gap-2">
              <Tag tone="success">纳米反应堆已就绪</Tag>
              <span className="text-muted-foreground text-xs">
                全长 {(data.reactorSharedCdTicks / 1200).toFixed(0)} 分钟, 与游戏内触发读同一个字段
              </span>
            </div>
          ) : (
            <Meter
              label={`纳米反应堆冷却中, 剩余 ${formatCountdown(reactorReadyAt, now)}`}
              max={reactorTotalMs}
              tone="warning"
              value={reactorTotalMs - reactorRemainingMs}
            />
          )}

          <div className="grid grid-cols-3 gap-4">
            <Stat label="校准命中阈值" value={`${String(data.qualityBonusThreshold)} 次`} />
            <Stat
              label="达阈值后额外产板概率"
              value={`${(data.qualityBonusPlateChance * 100).toFixed(0)}%`}
            />
            <Stat
              label="自产板修甲经验加成"
              value={`+${(data.ownPlateRepairXpBonus * 100).toFixed(0)}%`}
            />
          </div>
          <p className="text-muted-foreground text-xs">
            纳米校准的操作面 (游标/绿区/相位) 一个字段都不下发, 校准必须在游戏里做; 这里只给结果面数值
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
              {
                header: '档位',
                key: 'label',
                render: (row) => (
                  <span className="flex items-center gap-2">
                    <span>{nameOf(row.labelKey)}</span>
                    <Tag size="sm" tone={row.unlocked ? 'success' : 'neutral'}>
                      {row.unlocked ? '已解锁' : `Lv.${String(row.unlockLevel)}`}
                    </Tag>
                  </span>
                ),
                sortValue: (row) => row.index,
              },
              {
                header: '矿耗',
                key: 'oreCost',
                numeric: true,
                render: (row) => String(row.oreCost),
                sortValue: (row) => row.oreCost,
              },
              {
                header: '单次产出',
                key: 'outputCount',
                numeric: true,
                render: (row) => `${String(row.outputCount)} 块`,
                sortValue: (row) => row.outputCount,
              },
              {
                header: '耗时',
                key: 'produceTicks',
                numeric: true,
                render: (row) => `${(row.produceTicks / 20).toFixed(1)}s`,
                sortValue: (row) => row.produceTicks,
              },
              {
                header: '经验',
                key: 'rawXp',
                numeric: true,
                render: (row) => String(row.rawXp),
                sortValue: (row) => row.rawXp,
              },
              {
                header: '修复量',
                key: 'repair',
                numeric: true,
                render: (row) => formatRepair(row),
                // 两种量纲不可比大小, 排序只按档序 —— 按 repairValue 排会把 600 点耐久排在 1000 千分比前面。
                sortValue: (row) => row.index,
              },
              { header: '备注', key: 'note', render: (row) => tierNote(row) },
            ]}
            emptyHint="尚无纳米板档位数据"
            rowKey={(row) => row.tierId}
            rows={data.tiers}
          />
          <p className="text-muted-foreground text-xs">
            修复量有两种量纲: 低/中/高档是绝对耐久点数, 极品/超凡/闪耀是最大耐久的百分比, 不可跨档比大小
          </p>
        </Panel>
      ) : (
        <Panel title="护甲特效">
          <div className="flex flex-col gap-2">
            <p className="text-muted-foreground text-xs">
              四个特效等概率四选一, 在 Lv.{data.effectUnlockLevel} 起同时解锁, 没有各自的等级门
            </p>
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
                    <h3 className="font-medium text-foreground text-sm">{nameOf(effect.labelKey)}</h3>
                    <Tag tone={effect.unlocked ? 'success' : 'neutral'}>
                      {effect.unlocked ? '已解锁' : `Lv.${String(effect.unlockLevel)} 解锁`}
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
                      <p className="pt-2 text-muted-foreground text-sm">
                        {nameOf(effect.descriptionKey)}
                      </p>
                      {effect.stats.length === 0 ? null : (
                        <div className="flex flex-col gap-1 pt-2">
                          {effect.stats.map((line) => (
                            <Stat
                              key={line.key}
                              label={nameOf(line.labelKey)}
                              layout="inline"
                              value={formatEngineerStat(line)}
                            />
                          ))}
                        </div>
                      )}
                    </div>
                  </div>
                </button>
              )
            })}
          </div>
          <Surface tone="neutral">
            <p className="text-muted-foreground text-xs">
              特效名与数值标签的 lang 条目尚未落地, 上面显示成翻译键属已知缺口, 不是数据错误
            </p>
          </Surface>
        </Panel>
      )}
    </div>
  )
}
