import { SearchIcon, TriangleAlertIcon } from 'lucide-react'
import type { ReactElement } from 'react'
import { useState } from 'react'
import {
  Button,
  DataTable,
  type DataTableColumn,
  EmptyBlock,
  ErrorBlock,
  LoadingBlock,
  Meter,
  Panel,
  Surface,
  TabBar,
  type TabItem,
  Tag,
  type Tone,
} from '@/components/kit'
import {
  Dialog,
  DialogDescription,
  DialogHeader,
  DialogPopup,
  DialogTitle,
} from '@/components/ui/dialog'
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
 * 中文输入: 本页无任何自由文本输入控件 (词条池/难度切换走页签, 样本查询走固定按钮),
 * 不涉及 TextInput 的 onRequestEdit 接口位。
 */

type TopTabId = 'affixes' | 'stars' | 'distribution' | 'inspect'
type PoolTabId = 'all' | PlannedAffixPool

const TOP_TABS: readonly TabItem[] = [
  { id: 'affixes', label: '词条' },
  { id: 'stars', label: '星级' },
  { id: 'distribution', label: '难度分布' },
  { id: 'inspect', label: '样本查询' },
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

const POOL_TONE: Record<PlannedAffixPool, Tone> = {
  SURVIVAL: 'success',
  COMBAT: 'danger',
  MOBILITY: 'info',
  SKILL: 'brand',
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

function starWeightTone(star: number): Tone {
  if (star <= 3) {
    return 'success'
  }
  if (star <= 6) {
    return 'warning'
  }
  return 'danger'
}

function healthTone(ratio: number): Tone {
  if (ratio > 0.6) {
    return 'success'
  }
  if (ratio > 0.3) {
    return 'warning'
  }
  return 'danger'
}

const AFFIX_COLUMNS: readonly DataTableColumn<PlannedChampionAffix>[] = [
  { key: 'name', header: '名称', render: (row) => row.displayName, sortValue: (row) => row.displayName },
  {
    key: 'pool',
    header: '词条池',
    render: (row) => <Tag tone={POOL_TONE[row.pool]}>{POOL_LABEL[row.pool]}</Tag>,
    sortValue: (row) => row.pool,
  },
  {
    key: 'cost',
    header: '基础成本',
    numeric: true,
    render: (row) => String(row.cost),
    sortValue: (row) => row.cost,
  },
  {
    key: 'minStar',
    header: '最低星',
    numeric: true,
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

const STAR_COLUMNS: readonly DataTableColumn<PlannedChampionStar>[] = [
  {
    key: 'star',
    header: '星级',
    numeric: true,
    render: (row) => `${String(row.star)} 星`,
    sortValue: (row) => row.star,
  },
  {
    key: 'survival',
    header: '生存预算',
    numeric: true,
    render: (row) => String(row.survivalBudget),
    sortValue: (row) => row.survivalBudget,
  },
  {
    key: 'combat',
    header: '战斗预算',
    numeric: true,
    render: (row) => String(row.combatBudget),
    sortValue: (row) => row.combatBudget,
  },
  {
    key: 'mobility',
    header: '机动预算',
    numeric: true,
    render: (row) => String(row.mobilityBudget),
    sortValue: (row) => row.mobilityBudget,
  },
  {
    key: 'skill',
    header: '技能预算',
    numeric: true,
    render: (row) => String(row.skillBudget),
    sortValue: (row) => row.skillBudget,
  },
  {
    key: 'affixCap',
    header: '词条上限',
    numeric: true,
    render: (row) => String(row.affixCap),
    sortValue: (row) => row.affixCap,
  },
  {
    key: 'skillCap',
    header: '技能上限',
    numeric: true,
    render: (row) => String(row.skillCap),
    sortValue: (row) => row.skillCap,
  },
  { key: 'quality', header: '最高品质', render: (row) => row.maxQuality, sortValue: (row) => row.maxQuality },
  {
    key: 'hp',
    header: '基础有效 HP',
    numeric: true,
    render: (row) => String(row.baseEffectiveHp),
    sortValue: (row) => row.baseEffectiveHp,
  },
  {
    key: 'hit',
    header: '基础单击 %maxHP',
    numeric: true,
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
      <Panel>
        <LoadingBlock label="正在读取精英怪图鉴" size="lg" />
      </Panel>
    )
  }
  if (codex.status === 'error') {
    return <ErrorBlock message={codex.error.message} onRetry={codex.reload} />
  }

  const { affixes, stars, distribution } = codex.data

  const poolCounts: Record<PlannedAffixPool, number> = {
    SURVIVAL: affixes.filter((entry) => entry.pool === 'SURVIVAL').length,
    COMBAT: affixes.filter((entry) => entry.pool === 'COMBAT').length,
    MOBILITY: affixes.filter((entry) => entry.pool === 'MOBILITY').length,
    SKILL: affixes.filter((entry) => entry.pool === 'SKILL').length,
  }
  const poolTabs: readonly TabItem[] = [
    { id: 'all', label: `全部 (${String(affixes.length)})` },
    { id: 'SURVIVAL', label: `生存 (${String(poolCounts.SURVIVAL)})` },
    { id: 'COMBAT', label: `战斗 (${String(poolCounts.COMBAT)})` },
    { id: 'MOBILITY', label: `机动 (${String(poolCounts.MOBILITY)})` },
    { id: 'SKILL', label: `技能 (${String(poolCounts.SKILL)})` },
  ]
  const difficultyTabs: readonly TabItem[] = DIFFICULTY_TAB_IDS.map((difficulty) => ({
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
    <section className="flex flex-col gap-4">
      {/* 页名由 TabletShell 的 h1 统一渲染, 页面内不再重复 —— 重复两遍且里层更大, 打开必现, 读起来像渲染 bug。 */}
      <p className="text-muted-foreground text-xs">
        35 条词条按生存 / 战斗 / 机动 / 技能四组呈现, 数值抄自服务端 AffixDef / StarRank 枚举真值
        (静态 dump, 与真服完全一致); 样本查询走 champion.inspect, 结果来自 mock 固定样本,
        不代表真实在线实体的实时状态。
      </p>

      <TabBar
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
          <TabBar
            tabs={poolTabs}
            activeId={activePool}
            variant="underline"
            onChange={(id) => {
              if (includes(POOL_TAB_IDS, id)) {
                setActivePool(id)
              }
            }}
          />
          {/* padded=false 让表格铺满卡片, 故须由卡片自己裁掉溢出, 否则表头方角会盖住卡片的圆角。 */}
          <Panel className="overflow-hidden" padded={false}>
            <div className="max-h-96 overflow-y-auto">
              <DataTable
                columns={AFFIX_COLUMNS}
                rows={visibleAffixes}
                rowKey={(row) => row.affixId}
                onRowClick={(row) => {
                  setSelectedAffixId(row.affixId)
                }}
                emptyHint="该分组暂无词条"
                {...(selectedAffixId === null ? {} : { selectedRowKey: selectedAffixId })}
              />
            </div>
          </Panel>
        </div>
      ) : null}

      {activeTab === 'stars' ? (
        <div className="flex flex-col gap-3">
          <Panel className="overflow-hidden" padded={false}>
            <div className="max-h-96 overflow-y-auto">
              <DataTable
                columns={STAR_COLUMNS}
                rows={stars}
                rowKey={(row) => String(row.star)}
                onRowClick={(row) => {
                  setSelectedStar(row.star)
                }}
                {...(selectedStar === null ? {} : { selectedRowKey: String(selectedStar) })}
              />
            </div>
          </Panel>
          {selectedStarEntry === null ? null : (
            <Surface tone="info">
              <p className="text-foreground text-sm">
                {selectedStarEntry.star} 星: 词条上限 {selectedStarEntry.affixCap} 条 (含{' '}
                {selectedStarEntry.skillCap} 条技能位), 最高品质 {selectedStarEntry.maxQuality}
                {selectedStarEntry.star >= 6 ? ', 基础有效 HP 突破原版 1024 上限, 走自定义血池' : ''}
              </p>
            </Surface>
          )}
        </div>
      ) : null}

      {activeTab === 'distribution' ? (
        <div className="flex flex-col gap-3">
          <TabBar
            tabs={difficultyTabs}
            activeId={activeDifficulty}
            variant="underline"
            onChange={(id) => {
              if (includes(DIFFICULTY_TAB_IDS, id)) {
                setActiveDifficulty(id)
              }
            }}
          />
          {activeDistribution === undefined ? (
            <EmptyBlock
              title="无难度分布数据"
              hint="mock 种子未覆盖该难度"
              icon={<TriangleAlertIcon aria-hidden="true" />}
            />
          ) : (
            <Panel>
              <div className="flex flex-col gap-4">
                <div className="flex flex-col gap-3">
                  {activeDistribution.starWeights.map((entry) => (
                    <Meter
                      key={entry.star}
                      value={entry.weight}
                      max={100}
                      tone={starWeightTone(entry.star)}
                      label={`${String(entry.star)} 星 · 权重 ${String(entry.weight)}%`}
                    />
                  ))}
                </div>
                <div>
                  <Tag tone={totalWeight === 100 ? 'success' : 'warning'}>总权重 {totalWeight}%</Tag>
                </div>
              </div>
            </Panel>
          )}
        </div>
      ) : null}

      {activeTab === 'inspect' ? (
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap gap-2">
            {SAMPLE_ENTITY_IDS.map((sample) => (
              <Button
                key={sample.entityId}
                variant="outline"
                loading={inspect.status === 'loading' && inspect.entityId === sample.entityId}
                onClick={() => {
                  void handleInspect(sample.entityId)
                }}
              >
                {sample.label}
              </Button>
            ))}
            <Button
              variant="destructive-outline"
              loading={inspect.status === 'loading' && inspect.entityId === UNKNOWN_ENTITY_ID}
              onClick={() => {
                void handleInspect(UNKNOWN_ENTITY_ID)
              }}
            >
              查询未知实体 (演示失败态)
            </Button>
          </div>

          {inspect.status === 'idle' ? (
            <EmptyBlock
              title="尚未查询"
              hint="点击上方按钮按实体 id 查询精英怪状态"
              icon={<SearchIcon aria-hidden="true" />}
            />
          ) : inspect.status === 'loading' ? (
            <Panel>
              <LoadingBlock label="正在查询实体" size="lg" />
            </Panel>
          ) : inspect.status === 'error' ? (
            <ErrorBlock
              message={inspect.message}
              onRetry={() => {
                void handleInspect(inspect.entityId)
              }}
            />
          ) : (
            <Panel
              title={inspect.data.displayName}
              description={`${inspect.data.entityType} · 实体 id ${String(inspect.data.entityId)}`}
              actions={<Tag tone="warning">{inspect.data.star} 星</Tag>}
            >
              <div className="flex flex-col gap-3">
                <Meter
                  value={inspect.data.health}
                  max={inspect.data.maxHealth}
                  tone={healthTone(inspect.data.health / inspect.data.maxHealth)}
                  label="血量"
                  valueText={`${String(inspect.data.health)}/${String(inspect.data.maxHealth)}`}
                />
                {inspect.data.customBloodPool ? (
                  <div>
                    <Tag tone="info">自定义血池 (突破原版上限)</Tag>
                  </div>
                ) : null}
                <div className="flex flex-wrap gap-2">
                  {inspect.data.affixIds.map((affixId) => (
                    <Tag key={affixId} tone="brand">
                      {affixNameByid[affixId] ?? affixId}
                    </Tag>
                  ))}
                </div>
              </div>
            </Panel>
          )}
        </div>
      ) : null}

      <Dialog
        open={selectedAffix !== null}
        onOpenChange={(next) => {
          if (!next) {
            setSelectedAffixId(null)
          }
        }}
      >
        <DialogPopup>
          {selectedAffix === null ? null : (
            <>
              <DialogHeader>
                <DialogTitle>{selectedAffix.displayName}</DialogTitle>
                <DialogDescription>{selectedAffix.affixId}</DialogDescription>
              </DialogHeader>
              <div className="flex flex-col gap-3 px-6 pb-6">
                <div className="flex flex-wrap gap-2">
                  <Tag tone={POOL_TONE[selectedAffix.pool]}>{POOL_LABEL[selectedAffix.pool]}</Tag>
                  <Tag>基础成本 {selectedAffix.cost}</Tag>
                  <Tag>最低 {selectedAffix.minStar} 星</Tag>
                  {selectedAffix.isSkill ? <Tag tone="brand">占技能位</Tag> : null}
                  {selectedAffix.mutexFamily === null ? null : (
                    <Tag tone="warning">互斥族: {selectedAffix.mutexFamily}</Tag>
                  )}
                </div>
                <div className="grid grid-cols-5 gap-2">
                  {TIER_LABELS.map((label) => (
                    <span className="text-muted-foreground text-xs" key={label}>
                      {label}
                    </span>
                  ))}
                  {selectedAffix.tiers.map((value, index) => (
                    // 5 档数值与 TIER_LABELS 一一对应, key 用档位下标本身足够稳定 (数组长度固定为 5)。
                    <span className="text-foreground text-sm tabular-nums" key={index}>
                      {tierText(value)}
                    </span>
                  ))}
                </div>
              </div>
            </>
          )}
        </DialogPopup>
      </Dialog>
    </section>
  )
}
