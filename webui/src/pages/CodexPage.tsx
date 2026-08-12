import type { ReactElement } from 'react'
import { useState } from 'react'
import {
  PixelBadge,
  PixelButton,
  PixelEmpty,
  PixelError,
  PixelFrame,
  PixelLoading,
  PixelModal,
  PixelProgress,
  PixelTable,
  PixelTabs,
} from '../components/pixel'
import type { PixelFrameTone, PixelTab, PixelTableColumn } from '../components/pixel'
import { callMock } from '../mock/handlers'
import type {
  PlannedAffixPool,
  PlannedChampionAffix,
  PlannedChampionInspectResult,
  PlannedChampionStar,
  PlannedDifficulty,
} from '../mock/planned'
import { useMockAction } from '../mock/useMockWorld'

/**
 * 精英怪图鉴 (接线清单 G 组)。
 *
 * 依赖的假定契约 (均未接线, 走 mock/planned.ts):
 *   - champion.codex    G1  35 词条 (池/成本/最低星/互斥族/5 档数值) + 10 星级主数据表 + 难度分布
 *                       升格权重。清单标注 WRAP、纯静态枚举 dump —— mock/seed.ts 里这块数据是全库
 *                       唯一"逐字抄自 Java 真值"的部分 (AffixDef / StarRank), 不是编出来的演示数字。
 *   - champion.inspect  G2  按实体 id 查星级/词条/血量, 6 星及以上走自定义血池。清单同标 WRAP,
 *                       但 mock 只能提供两只固定样本, 不代表真实在线实体。
 *
 * 明确不做的部分: 清单 G 组还列了 G3 (参团贡献实时进度)/G4 (击杀奖励结算)/G5 (DoT 层数汇总)/
 * G6 (减伤汇总快照) 四项, 状态全是 BACKEND (服务端连数据都还没有), 不在本页范围内, 不得臆造。
 *
 * 中文输入: 本页无任何自由文本输入控件 (词条池/难度切换走 PixelTabs, 样本查询走固定按钮),
 * 不涉及 PixelInput 的 onRequestEdit 接口位。
 */

type TopTabId = 'affixes' | 'stars' | 'distribution' | 'inspect'
type PoolTabId = 'all' | PlannedAffixPool

const TOP_TABS: readonly PixelTab[] = [
  { id: 'affixes', label: '词条', icon: 'star' },
  { id: 'stars', label: '星级', icon: 'info' },
  { id: 'distribution', label: '难度分布', icon: 'sort' },
  { id: 'inspect', label: '样本查询', icon: 'search' },
]

const TOP_TAB_IDS: readonly TopTabId[] = ['affixes', 'stars', 'distribution', 'inspect']
const POOL_TAB_IDS: readonly PoolTabId[] = ['all', 'SURVIVAL', 'COMBAT', 'MOBILITY', 'SKILL']
const DIFFICULTY_TAB_IDS: readonly PlannedDifficulty[] = ['easy', 'medium', 'hard']

const POOL_LABEL: Record<PlannedAffixPool, string> = {
  SURVIVAL: '生存',
  COMBAT: '战斗',
  MOBILITY: '机动',
  SKILL: '技能',
}

const POOL_TONE: Record<PlannedAffixPool, PixelFrameTone> = {
  SURVIVAL: 'success',
  COMBAT: 'danger',
  MOBILITY: 'info',
  SKILL: 'accent',
}

const DIFFICULTY_LABEL: Record<PlannedDifficulty, string> = {
  easy: '简单',
  medium: '普通',
  hard: '困难',
}

/** 5 档品质数值的固定列序, 对应 seed.ts AFFIX_ROWS 的 tiers 元组顺序。 */
const TIER_LABELS: readonly string[] = ['普通', '中级', '高级', '超凡', '闪耀']

/** 已知样本实体 id (mock/seed.ts champion.samples), 供样本查询按钮使用。 */
const SAMPLE_ENTITY_IDS: readonly { entityId: number; label: string }[] = [
  { entityId: 4201, label: '样本 A · 复合装甲 僵尸' },
  { entityId: 4202, label: '样本 B · 命定 凋灵骷髅' },
]
/** 不在样本表内的 id, 专用于演示 champion.inspect 的失败态。 */
const UNKNOWN_ENTITY_ID = 9999

function includes<T extends string>(list: readonly T[], value: string): value is T {
  return (list as readonly string[]).includes(value)
}

function tierText(value: number): string {
  return value === 0 ? '—' : String(value)
}

function starWeightTone(star: number): PixelFrameTone {
  if (star <= 3) {
    return 'success'
  }
  if (star <= 6) {
    return 'warning'
  }
  return 'danger'
}

function healthTone(ratio: number): PixelFrameTone {
  if (ratio > 0.6) {
    return 'success'
  }
  if (ratio > 0.3) {
    return 'warning'
  }
  return 'danger'
}

const AFFIX_COLUMNS: readonly PixelTableColumn<PlannedChampionAffix>[] = [
  { key: 'name', header: '名称', render: (row) => row.displayName, sortValue: (row) => row.displayName },
  {
    key: 'pool',
    header: '词条池',
    render: (row) => <PixelBadge tone={POOL_TONE[row.pool]}>{POOL_LABEL[row.pool]}</PixelBadge>,
    sortValue: (row) => row.pool,
  },
  { key: 'cost', header: '基础成本', render: (row) => String(row.cost), sortValue: (row) => row.cost },
  {
    key: 'minStar',
    header: '最低星',
    render: (row) => `${String(row.minStar)} 星`,
    sortValue: (row) => row.minStar,
  },
  {
    key: 'isSkill',
    header: '占技能位',
    render: (row) => (row.isSkill ? '是' : '否'),
    sortValue: (row) => (row.isSkill ? 1 : 0),
  },
  {
    key: 'mutex',
    header: '互斥族',
    render: (row) => (row.mutexFamily === null ? '—' : row.mutexFamily),
    sortValue: (row) => row.mutexFamily ?? '',
  },
]

const STAR_COLUMNS: readonly PixelTableColumn<PlannedChampionStar>[] = [
  { key: 'star', header: '星级', render: (row) => `${String(row.star)} 星`, sortValue: (row) => row.star },
  {
    key: 'survival',
    header: '生存预算',
    render: (row) => String(row.survivalBudget),
    sortValue: (row) => row.survivalBudget,
  },
  {
    key: 'combat',
    header: '战斗预算',
    render: (row) => String(row.combatBudget),
    sortValue: (row) => row.combatBudget,
  },
  {
    key: 'mobility',
    header: '机动预算',
    render: (row) => String(row.mobilityBudget),
    sortValue: (row) => row.mobilityBudget,
  },
  { key: 'skill', header: '技能预算', render: (row) => String(row.skillBudget), sortValue: (row) => row.skillBudget },
  {
    key: 'affixCap',
    header: '词条上限',
    render: (row) => String(row.affixCap),
    sortValue: (row) => row.affixCap,
  },
  {
    key: 'skillCap',
    header: '技能上限',
    render: (row) => String(row.skillCap),
    sortValue: (row) => row.skillCap,
  },
  { key: 'quality', header: '最高品质', render: (row) => row.maxQuality, sortValue: (row) => row.maxQuality },
  {
    key: 'hp',
    header: '基础有效 HP',
    render: (row) => String(row.baseEffectiveHp),
    sortValue: (row) => row.baseEffectiveHp,
  },
  {
    key: 'hit',
    header: '基础单击 %maxHP',
    render: (row) => `${(row.baseHitPct * 100).toFixed(1)}%`,
    sortValue: (row) => row.baseHitPct,
  },
]

type InspectState =
  | { status: 'idle' }
  | { status: 'loading'; entityId: number }
  | { status: 'ready'; entityId: number; data: PlannedChampionInspectResult }
  | { status: 'error'; entityId: number; message: string }

export function CodexPage(): ReactElement {
  const codex = useMockAction('champion.codex', {})

  const [activeTab, setActiveTab] = useState<TopTabId>('affixes')
  const [activePool, setActivePool] = useState<PoolTabId>('all')
  const [activeDifficulty, setActiveDifficulty] = useState<PlannedDifficulty>('easy')
  const [selectedAffixId, setSelectedAffixId] = useState<string | null>(null)
  const [selectedStar, setSelectedStar] = useState<number | null>(null)
  const [inspect, setInspect] = useState<InspectState>({ status: 'idle' })

  async function handleInspect(entityId: number): Promise<void> {
    setInspect({ status: 'loading', entityId })
    try {
      const data = await callMock('champion.inspect', { entityId })
      setInspect({ status: 'ready', entityId, data })
    } catch (error) {
      setInspect({
        status: 'error',
        entityId,
        message: error instanceof Error ? error.message : String(error),
      })
    }
  }

  if (codex.status === 'loading') {
    return (
      <PixelFrame variant="panel" className="p-8">
        <PixelLoading label="正在读取精英怪图鉴" size="lg" />
      </PixelFrame>
    )
  }
  if (codex.status === 'error') {
    return <PixelError message={codex.error.message} onRetry={codex.reload} />
  }

  const { affixes, stars, distribution } = codex.data

  const poolCounts: Record<PlannedAffixPool, number> = {
    SURVIVAL: affixes.filter((entry) => entry.pool === 'SURVIVAL').length,
    COMBAT: affixes.filter((entry) => entry.pool === 'COMBAT').length,
    MOBILITY: affixes.filter((entry) => entry.pool === 'MOBILITY').length,
    SKILL: affixes.filter((entry) => entry.pool === 'SKILL').length,
  }
  const poolTabs: readonly PixelTab[] = [
    { id: 'all', label: `全部 (${String(affixes.length)})` },
    { id: 'SURVIVAL', label: `生存 (${String(poolCounts.SURVIVAL)})` },
    { id: 'COMBAT', label: `战斗 (${String(poolCounts.COMBAT)})` },
    { id: 'MOBILITY', label: `机动 (${String(poolCounts.MOBILITY)})` },
    { id: 'SKILL', label: `技能 (${String(poolCounts.SKILL)})` },
  ]
  const difficultyTabs: readonly PixelTab[] = DIFFICULTY_TAB_IDS.map((difficulty) => ({
    id: difficulty,
    label: DIFFICULTY_LABEL[difficulty],
  }))

  const visibleAffixes = activePool === 'all' ? affixes : affixes.filter((entry) => entry.pool === activePool)
  const selectedAffix = selectedAffixId === null ? null : affixes.find((entry) => entry.affixId === selectedAffixId) ?? null
  const selectedStarEntry = selectedStar === null ? null : stars.find((entry) => entry.star === selectedStar) ?? null
  const activeDistribution = distribution.find((entry) => entry.difficulty === activeDifficulty)
  const totalWeight =
    activeDistribution === undefined ? 0 : activeDistribution.starWeights.reduce((sum, entry) => sum + entry.weight, 0)
  const affixNameByid: Record<string, string> = Object.fromEntries(
    affixes.map((entry) => [entry.affixId, entry.displayName]),
  )

  return (
    <section className="flex flex-col gap-6">
      {/* 页名由 TabletShell 的 h1 统一渲染, 页面内不再重复 —— 重复两遍且里层更大, 打开必现, 读起来像渲染 bug。 */}
      <header className="flex flex-col gap-2">
        <p className="text-1x text-muted">
          35 条词条按生存 / 战斗 / 机动 / 技能四组呈现, 数值抄自服务端 AffixDef / StarRank 枚举真值
          (静态 dump, 与真服完全一致); 样本查询走 champion.inspect, 结果来自 mock 固定样本,
          不代表真实在线实体的实时状态。
        </p>
      </header>

      <PixelTabs
        tabs={TOP_TABS}
        activeId={activeTab}
        onChange={(id) => {
          if (includes(TOP_TAB_IDS, id)) {
            setActiveTab(id)
          }
        }}
      />

      {activeTab === 'affixes' ? (
        <div className="flex flex-col gap-3">
          <PixelTabs
            tabs={poolTabs}
            activeId={activePool}
            level="secondary"
            onChange={(id) => {
              if (includes(POOL_TAB_IDS, id)) {
                setActivePool(id)
              }
            }}
          />
          <PixelTable
            columns={AFFIX_COLUMNS}
            rows={visibleAffixes}
            rowKey={(row) => row.affixId}
            onRowClick={(row) => {
              setSelectedAffixId(row.affixId)
            }}
            emptyHint="该分组暂无词条"
            className="h-96"
            {...(selectedAffixId === null ? {} : { selectedRowKey: selectedAffixId })}
          />
        </div>
      ) : null}

      {activeTab === 'stars' ? (
        <div className="flex flex-col gap-3">
          <PixelTable
            columns={STAR_COLUMNS}
            rows={stars}
            rowKey={(row) => String(row.star)}
            onRowClick={(row) => {
              setSelectedStar(row.star)
            }}
            className="h-96"
            {...(selectedStar === null ? {} : { selectedRowKey: String(selectedStar) })}
          />
          {selectedStarEntry === null ? null : (
            <PixelFrame variant="panel" tone="info" className="p-3">
              <p className="text-1x text-fg">
                {selectedStarEntry.star} 星: 词条上限 {selectedStarEntry.affixCap} 条 (含{' '}
                {selectedStarEntry.skillCap} 条技能位), 最高品质 {selectedStarEntry.maxQuality}
                {selectedStarEntry.star >= 6 ? ', 基础有效 HP 突破原版 1024 上限, 走自定义血池' : ''}
              </p>
            </PixelFrame>
          )}
        </div>
      ) : null}

      {activeTab === 'distribution' ? (
        <div className="flex flex-col gap-3">
          <PixelTabs
            tabs={difficultyTabs}
            activeId={activeDifficulty}
            level="secondary"
            onChange={(id) => {
              if (includes(DIFFICULTY_TAB_IDS, id)) {
                setActiveDifficulty(id)
              }
            }}
          />
          {activeDistribution === undefined ? (
            <PixelEmpty title="无难度分布数据" hint="mock 种子未覆盖该难度" icon="warning" />
          ) : (
            <div className="flex flex-col gap-3">
              <div className="flex flex-col gap-2">
                {activeDistribution.starWeights.map((entry) => (
                  <PixelProgress
                    key={entry.star}
                    value={entry.weight}
                    max={100}
                    tone={starWeightTone(entry.star)}
                    label={`${String(entry.star)} 星 · 权重 ${String(entry.weight)}%`}
                  />
                ))}
              </div>
              <PixelBadge tone={totalWeight === 100 ? 'success' : 'warning'}>总权重 {totalWeight}%</PixelBadge>
            </div>
          )}
        </div>
      ) : null}

      {activeTab === 'inspect' ? (
        <div className="flex flex-col gap-4">
          <div className="flex flex-wrap gap-2">
            {SAMPLE_ENTITY_IDS.map((sample) => (
              <PixelButton
                key={sample.entityId}
                tone="neutral"
                loading={inspect.status === 'loading' && inspect.entityId === sample.entityId}
                onClick={() => {
                  void handleInspect(sample.entityId)
                }}
              >
                {sample.label}
              </PixelButton>
            ))}
            <PixelButton
              tone="danger"
              loading={inspect.status === 'loading' && inspect.entityId === UNKNOWN_ENTITY_ID}
              onClick={() => {
                void handleInspect(UNKNOWN_ENTITY_ID)
              }}
            >
              查询未知实体 (演示失败态)
            </PixelButton>
          </div>

          {inspect.status === 'idle' ? (
            <PixelEmpty title="尚未查询" hint="点击上方按钮按实体 id 查询精英怪状态" icon="search" />
          ) : inspect.status === 'loading' ? (
            <PixelFrame variant="panel" className="p-8">
              <PixelLoading label="正在查询实体" size="lg" />
            </PixelFrame>
          ) : inspect.status === 'error' ? (
            <PixelError
              message={inspect.message}
              onRetry={() => {
                void handleInspect(inspect.entityId)
              }}
            />
          ) : (
            <PixelFrame variant="panel" className="flex flex-col gap-3 p-4">
              <div className="flex items-center justify-between">
                <h3 className="text-2x text-fg">{inspect.data.displayName}</h3>
                <PixelBadge tone="warning">{inspect.data.star} 星</PixelBadge>
              </div>
              <p className="text-1x text-muted">
                {inspect.data.entityType} · 实体 id {inspect.data.entityId}
              </p>
              <PixelProgress
                value={inspect.data.health}
                max={inspect.data.maxHealth}
                tone={healthTone(inspect.data.health / inspect.data.maxHealth)}
                label={`血量 ${String(inspect.data.health)}/${String(inspect.data.maxHealth)}`}
              />
              {inspect.data.customBloodPool ? <PixelBadge tone="info">自定义血池 (突破原版上限)</PixelBadge> : null}
              <div className="flex flex-wrap gap-2">
                {inspect.data.affixIds.map((affixId) => (
                  <PixelBadge key={affixId} tone="accent">
                    {affixNameByid[affixId] ?? affixId}
                  </PixelBadge>
                ))}
              </div>
            </PixelFrame>
          )}
        </div>
      ) : null}

      <PixelModal
        open={selectedAffix !== null}
        title={selectedAffix === null ? '' : selectedAffix.displayName}
        onClose={() => {
          setSelectedAffixId(null)
        }}
      >
        {selectedAffix === null ? null : (
          <div className="flex flex-col gap-3">
            <div className="flex flex-wrap gap-2">
              <PixelBadge tone={POOL_TONE[selectedAffix.pool]}>{POOL_LABEL[selectedAffix.pool]}</PixelBadge>
              <PixelBadge tone="neutral">基础成本 {selectedAffix.cost}</PixelBadge>
              <PixelBadge tone="neutral">最低 {selectedAffix.minStar} 星</PixelBadge>
              {selectedAffix.isSkill ? <PixelBadge tone="accent">占技能位</PixelBadge> : null}
              {selectedAffix.mutexFamily === null ? null : (
                <PixelBadge tone="warning">互斥族: {selectedAffix.mutexFamily}</PixelBadge>
              )}
            </div>
            <table className="w-full border-collapse text-1x">
              <thead>
                <tr>
                  {TIER_LABELS.map((label) => (
                    <th key={label} scope="col" className="border-b border-border px-2 py-1 text-left">
                      {label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                <tr>
                  {selectedAffix.tiers.map((value, index) => (
                    // 5 档数值与 TIER_LABELS 一一对应, key 用档位下标本身足够稳定 (数组长度固定为 5)。
                    <td key={index} className="px-2 py-1">
                      {tierText(value)}
                    </td>
                  ))}
                </tr>
              </tbody>
            </table>
          </div>
        )}
      </PixelModal>
    </section>
  )
}
