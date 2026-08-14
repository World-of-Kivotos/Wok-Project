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
import { isMockActive } from '../lib/bridge'
import { callErrorText } from '../lib/errorText'
import { useItemNames } from '../lib/i18n'
import type {
  ChampionAffixPool,
  ChampionAffixQuality,
  ChampionAffixRow,
  ChampionInspectResult,
  ChampionStarRow,
  MiningDifficulty,
} from '../lib/types'
import { callMock } from '../mock/handlers'
import { useMockAction } from '../mock/useMockWorld'

/**
 * 精英怪图鉴 (`champion.codex` / `champion.inspect`, Java 落点
 * com.miningdim.champion.ChampionWebUiActions)。回执形状见 lib/types.ts。
 *
 * 四条决定本页形状的契约事实:
 *   1. **一条词条五个价**: 同一条词条在五个品质档下成本各不相同 (costs[5] = ceil(baseCost x 系数)),
 *      单一 cost 字段不存在。ceil 是防小数成本破整数点池预算的业务规则, 前端不得自己乘。
 *   2. **availableTiers 必须用**: primaryValues 里的 0 全是"该档不存在"的占位 (重型护甲/刚毅前两档、
 *      小男孩/命定前三档、自我修复中级档)。照直画五格会多出一排"减伤 0%"的假档位。
 *   3. **没有星级权重表**: ChampionSpawnPolicy.rollStar 就是"区间 [minStar,maxStar] 内均匀取整",
 *      顶层 starRollMode 声明了这件事。旧版那张权重条是凭空画的, 已改成均匀分布。
 *   4. **无互斥发字符串 'NONE' 而不是 null**: MutexFlag.NONE 是枚举里实打实的一档, 判空要写 === 'NONE'。
 *
 * 血量口径: 6 星起 (或低星被巨大化撑破 1024 的怪) 的战斗权威是自定义血池, vanilla 那一对被
 * generic.max_health 的 1024 硬上限钳住, 只是渲染镜像。**画血条一律只用 health/maxHealth/healthFraction**。
 *
 * 样本查询只在假数据模式下开放: champion.inspect 按**网络实体 id** 查, 而页面自己拿不到这个 id ——
 * 需要 MCEF 客户端侧提供"把准星/选中实体的 Entity.getId() 传进页面"的桥, 那条桥还不存在。
 * 在真服放几个写死的 id 只会稳定地拿到 ENTITY_NOT_FOUND。
 *
 * 中文输入: 本页无任何自由文本输入控件, 不涉及 TextInput 的 onRequestEdit 接口位。
 */

type TopTabId = 'affixes' | 'stars' | 'distribution' | 'inspect'
type PoolTabId = 'all' | ChampionAffixPool

const TOP_TABS: readonly TabItem[] = [
  { id: 'affixes', label: '词条' },
  { id: 'stars', label: '星级' },
  { id: 'distribution', label: '难度分布' },
  { id: 'inspect', label: '样本查询' },
]

const TOP_TAB_IDS: readonly TopTabId[] = ['affixes', 'stars', 'distribution', 'inspect']
const POOL_TAB_IDS: readonly PoolTabId[] = ['all', 'SURVIVAL', 'COMBAT', 'MOBILITY', 'SKILL']
const DIFFICULTY_TAB_IDS: readonly MiningDifficulty[] = ['easy', 'medium', 'hard']

const POOL_LABEL: Record<ChampionAffixPool, string> = {
  SURVIVAL: '生存',
  COMBAT: '战斗',
  MOBILITY: '机动',
  SKILL: '技能',
}

const POOL_TONE: Record<ChampionAffixPool, Tone> = {
  SURVIVAL: 'success',
  COMBAT: 'danger',
  MOBILITY: 'info',
  SKILL: 'brand',
}

const DIFFICULTY_LABEL: Record<MiningDifficulty, string> = {
  easy: '简单',
  medium: '普通',
  hard: '困难',
}

/**
 * 词条品质档的中文名。champion.codex 的 ChampionQualityRow **没有 nameKey** (服务端只发成本系数与
 * 展示色), 故这一档的文案归前端 —— 与旧版那张五档表逐字相同, 不是新发明的叫法。
 */
const AFFIX_QUALITY_LABEL: Record<ChampionAffixQuality, string> = {
  COMMON: '普通',
  UNCOMMON: '中级',
  RARE: '高级',
  EPIC: '超凡',
  LEGENDARY: '闪耀',
}

/** 已知样本实体 id (mock 世界的 champion.samples), 只在假数据模式下可用 —— 见文件头。 */
const SAMPLE_ENTITY_IDS: readonly { entityId: number; label: string }[] = [
  { entityId: 4201, label: '样本 A · 复合装甲 僵尸' },
  { entityId: 4202, label: '样本 B · 命定 凋灵骷髅' },
]
/** 不在样本表内的 id, 专用于演示 champion.inspect 的失败态。 */
const UNKNOWN_ENTITY_ID = 9999

function includes<T extends string>(list: readonly T[], value: string): value is T {
  return (list as readonly string[]).includes(value)
}

/**
 * 按量纲格式化词条数值。
 *
 * 量纲词表来自契约, **严禁跨词条比大小**: flat_hp_damage_cap (刚毅护盾) 与 seconds_cooldown 都是
 * 数值越小越强, 而 fraction_move_speed_bonus / fraction_max_health_bonus / fraction_damage_bonus
 * 可以大于 1 (超速最高 2.50 = +250%), 按 0..1 钳制会把超速压成 100%。
 */
function formatAffixValue(unit: string, value: number): string {
  if (unit === 'flag') {
    return '有'
  }
  if (unit.startsWith('fraction_')) {
    return `${(value * 100).toFixed(1)}%`
  }
  if (unit === 'flat_hp_per_second') {
    return `${String(value)} HP/秒`
  }
  if (unit === 'flat_hp_damage_cap') {
    return `${String(value)} HP`
  }
  if (unit === 'durability_points_per_hit') {
    return `${String(value)} 点/击`
  }
  if (unit === 'seconds_cooldown' || unit === 'seconds_duration') {
    return `${String(value)} 秒`
  }
  if (unit === 'multiplier') {
    return `x${value.toFixed(2)}`
  }
  return String(value)
}

/**
 * 副数值那一排。收成组件而不是内联三元: secondaryUnit / secondaryValues 是**同进同出的一对缺席键**,
 * 在 JSX 里判空之后再进 map 回调, TS 的窄化会在回调边界丢掉, 只能补 `?? ''` 这类会掩盖问题的兜底。
 */
function AffixSecondaryRow({ affix }: { affix: ChampionAffixRow }): ReactElement | null {
  const unit = affix.secondaryUnit
  const values = affix.secondaryValues
  if (unit === undefined || values === undefined) {
    return null
  }
  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-muted-foreground text-xs">副数值 (量纲 {unit})</span>
      <div className="grid grid-cols-5 gap-2">
        {values.map((value, index) => (
          <span className="text-foreground text-sm tabular-nums" key={`secondary-${String(index)}`}>
            {affix.availableTiers[index] === true ? formatAffixValue(unit, value) : '—'}
          </span>
        ))}
      </div>
    </div>
  )
}

function healthTone(fraction: number): Tone {
  if (fraction > 0.6) {
    return 'success'
  }
  if (fraction > 0.3) {
    return 'warning'
  }
  return 'danger'
}

const STAR_COLUMNS: readonly DataTableColumn<ChampionStarRow>[] = [
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
    key: 'maxAffixes',
    header: '词条上限',
    numeric: true,
    render: (row) => String(row.maxAffixes),
    sortValue: (row) => row.maxAffixes,
  },
  {
    key: 'maxSkills',
    header: '技能上限',
    numeric: true,
    render: (row) => String(row.maxSkills),
    sortValue: (row) => row.maxSkills,
  },
  {
    key: 'quality',
    header: '最高品质',
    render: (row) => AFFIX_QUALITY_LABEL[row.maxQuality],
    sortValue: (row) => row.maxQuality,
  },
  {
    key: 'hp',
    header: '基础有效血量',
    numeric: true,
    render: (row) => String(row.baseEffectiveHp),
    sortValue: (row) => row.baseEffectiveHp,
  },
  {
    key: 'hit',
    header: '单击基线 (占最大血量)',
    numeric: true,
    render: (row) => `${(row.baseSingleHitPct * 100).toFixed(1)}%`,
    sortValue: (row) => row.baseSingleHitPct,
  },
  {
    key: 'hitCap',
    header: '单击硬上限',
    numeric: true,
    render: (row) => `${(row.normalHitCapPct * 100).toFixed(1)}%`,
    sortValue: (row) => row.normalHitCapPct,
  },
]

type InspectState =
  | { status: 'idle' }
  | { status: 'loading'; entityId: number }
  | { status: 'ready'; entityId: number; data: ChampionInspectResult }
  | { status: 'error'; entityId: number; message: string }

export function CodexPage(): ReactElement {
  const codex = useMockAction('champion.codex', {})

  const [activeTab, setActiveTab] = useState<TopTabId>('affixes')
  const [activePool, setActivePool] = useState<PoolTabId>('all')
  const [activeDifficulty, setActiveDifficulty] = useState<MiningDifficulty>('easy')
  const [selectedAffixId, setSelectedAffixId] = useState<string | null>(null)
  const [selectedStar, setSelectedStar] = useState<number | null>(null)
  const [inspect, setInspect] = useState<InspectState>({ status: 'idle' })

  const codexData = codex.status === 'ready' ? codex.data : null
  const inspectData = inspect.status === 'ready' ? inspect.data : null

  // 词条名与实体名一次批量解: 服务端只发翻译键 (专用服务端不加载 lang), 中文由 client.i18n 出。
  const names = useItemNames([
    ...(codexData === null ? [] : codexData.affixes.map((affix) => affix.nameKey)),
    ...(inspectData === null
      ? []
      : [inspectData.entityDescriptionId, ...inspectData.affixes.map((affix) => affix.nameKey)]),
  ])
  const nameOf = (nameKey: string): string => names[nameKey] ?? nameKey

  async function handleInspect(entityId: number): Promise<void> {
    setInspect({ status: 'loading', entityId })
    try {
      const data = await callMock('champion.inspect', { entityId })
      setInspect({ status: 'ready', entityId, data })
    } catch (error) {
      setInspect({
        status: 'error',
        entityId,
        message: callErrorText(error instanceof Error ? error : new Error(String(error))),
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
    return <ErrorBlock message={callErrorText(codex.error)} onRetry={codex.reload} />
  }
  if (codexData === null) {
    return <ErrorBlock message="champion.codex 回执为空" onRetry={codex.reload} />
  }

  const { affixes, stars, distribution, qualities } = codexData

  /** 品质档表, 数组下标即 primaryValues / costs / availableTiers 的 tier。 */
  const tierColumns = qualities.map((quality) => ({
    qualityId: quality.qualityId,
    label: AFFIX_QUALITY_LABEL[quality.qualityId],
  }))

  const affixColumns: readonly DataTableColumn<ChampionAffixRow>[] = [
    {
      key: 'name',
      header: '名称',
      render: (row) => nameOf(row.nameKey),
      sortValue: (row) => nameOf(row.nameKey),
    },
    {
      key: 'pool',
      header: '词条池',
      render: (row) => <Tag tone={POOL_TONE[row.pool]}>{POOL_LABEL[row.pool]}</Tag>,
      sortValue: (row) => row.pool,
    },
    {
      key: 'baseCost',
      header: '基础成本',
      numeric: true,
      render: (row) => String(row.baseCost),
      sortValue: (row) => row.baseCost,
    },
    {
      key: 'minStar',
      header: '最低星',
      numeric: true,
      render: (row) => `${String(row.minStar)} 星`,
      sortValue: (row) => row.minStar,
    },
    {
      key: 'minQuality',
      header: '最低品质',
      render: (row) => AFFIX_QUALITY_LABEL[row.minQuality],
      sortValue: (row) => row.minQuality,
    },
    {
      key: 'isSkill',
      header: '占技能位',
      render: (row) => (row.isSkill ? '是' : '否'),
      sortValue: (row) => (row.isSkill ? 1 : 0),
    },
    {
      key: 'mutex',
      // MutexFlag.NONE 是枚举里实打实的一档, 不是"没填"; 判空必须写 === 'NONE'。
      header: '互斥族',
      render: (row) => (row.mutexFlag === 'NONE' ? '—' : row.mutexFlag),
      sortValue: (row) => row.mutexFlag,
    },
  ]

  const poolCounts: Record<ChampionAffixPool, number> = {
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
  const selectedAffix =
    selectedAffixId === null ? null : (affixes.find((entry) => entry.affixId === selectedAffixId) ?? null)
  const selectedStarEntry =
    selectedStar === null ? null : (stars.find((entry) => entry.star === selectedStar) ?? null)
  const activeDistribution = distribution.find((entry) => entry.configName === activeDifficulty)
  /** 均匀分布: 服务端没有权重表, 每个可掷星级的概率就是 1 / 区间长度。 */
  const uniformStars =
    activeDistribution === undefined
      ? []
      : Array.from(
          { length: activeDistribution.maxStar - activeDistribution.minStar + 1 },
          (_unused, index) => activeDistribution.minStar + index,
        )
  const uniformPercent = uniformStars.length === 0 ? 0 : 100 / uniformStars.length

  return (
    <section className="flex flex-col gap-4">
      {/* 页名由 TabletShell 的 h1 统一渲染, 页面内不再重复 —— 重复两遍且里层更大, 打开必现, 读起来像渲染 bug。 */}
      <p className="text-muted-foreground text-xs">
        {affixes.length} 条词条按生存 / 战斗 / 机动 / 技能四组呈现, 数值与服务器内的实际配置一致;
        {codexData.customBloodPoolMinStar} 星及以上走自定义血池, 血量会超出原版上限。
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
                columns={affixColumns}
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
                {selectedStarEntry.star} 星: 词条上限 {selectedStarEntry.maxAffixes} 条 (含{' '}
                {selectedStarEntry.maxSkills} 条技能位), 最高品质{' '}
                {AFFIX_QUALITY_LABEL[selectedStarEntry.maxQuality]}
                {selectedStarEntry.usesCustomBloodPool ? ', 走自定义血池 (血量超出原版上限)' : ''}
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
              title="暂无数据"
              hint="该难度没有星级分布"
              icon={<TriangleAlertIcon aria-hidden="true" />}
            />
          ) : (
            <Panel>
              <div className="flex flex-col gap-4">
                <div className="flex flex-wrap items-center gap-3">
                  <Tag tone="brand">
                    星级区间 {activeDistribution.minStar} - {activeDistribution.maxStar} 星
                  </Tag>
                  <Tag tone="info">
                    升格率 {(activeDistribution.promoteChance * 100).toFixed(1)}%
                  </Tag>
                </div>
                <div className="flex flex-col gap-3">
                  {uniformStars.map((star) => (
                    <Meter
                      key={star}
                      value={uniformPercent}
                      max={100}
                      tone="brand"
                      label={`${String(star)} 星`}
                      valueText={`${uniformPercent.toFixed(1)}%`}
                    />
                  ))}
                </div>
                <p className="text-muted-foreground text-xs">
                  掷星方式 {codexData.starRollMode}: 区间内均匀取整, 服务端没有任何权重表 ——
                  这几条等高的条就是真实分布, 不是占位图
                </p>
              </div>
            </Panel>
          )}
        </div>
      ) : null}

      {activeTab === 'inspect' ? (
        <div className="flex flex-col gap-3">
          {isMockActive() ? (
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
                演示查询失败
              </Button>
            </div>
          ) : (
            <EmptyBlock
              title="样本查询暂不可用"
              hint="查一只精英怪要先知道它的实体编号, 而平板现在拿不到你准星指着的是哪一只 —— 这条通路还没接"
              icon={<SearchIcon aria-hidden="true" />}
            />
          )}

          {inspect.status === 'idle' ? (
            isMockActive() ? (
              <EmptyBlock
                title="尚未查询"
                hint="点击上方按钮查看精英怪样本"
                icon={<SearchIcon aria-hidden="true" />}
              />
            ) : null
          ) : inspect.status === 'loading' ? (
            <Panel>
              <LoadingBlock label="正在查询" size="lg" />
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
              title={nameOf(inspect.data.entityDescriptionId)}
              {...(isMockActive()
                ? // 实体类型与 id 只有排查时用得上; 生产构建里 isMockActive 恒为 false, 这行不存在。
                  { description: `${inspect.data.entityTypeId} · 实体 id ${String(inspect.data.entityId)}` }
                : {})}
              actions={<Tag tone="warning">{inspect.data.star} 星</Tag>}
            >
              <div className="flex flex-col gap-3">
                {/*
                  分母只用服务端算好的 healthFraction: 6 星起战斗权威是自定义血池, vanilla 那一对被
                  1024 硬上限钳住, 拿它算比例必错 (实测 7 星 maxHealth=5312.8 而 vanillaMaxHealth=1024)。
                */}
                <Meter
                  value={inspect.data.healthFraction * 100}
                  max={100}
                  tone={healthTone(inspect.data.healthFraction)}
                  label="血量"
                  valueText={`${inspect.data.health.toFixed(0)} / ${inspect.data.maxHealth.toFixed(0)}`}
                />
                <div className="flex flex-wrap gap-2">
                  <Tag tone={inspect.data.customBloodPool ? 'info' : 'neutral'}>
                    {inspect.data.customBloodPool ? '自定义血池' : '原版血量'}
                  </Tag>
                  <Tag tone="neutral">设计有效血 {inspect.data.effectiveHp.toFixed(0)}</Tag>
                  <Tag tone="neutral">该星最高品质 {AFFIX_QUALITY_LABEL[inspect.data.maxQuality]}</Tag>
                  {inspect.data.summonedByAffix ? (
                    <Tag tone="warning">支援召唤物 (不计货币/经验/掉落)</Tag>
                  ) : null}
                </div>
                <div className="flex flex-col gap-2">
                  <h3 className="font-medium text-foreground text-sm">已掷出的词条</h3>
                  {inspect.data.affixes.length === 0 ? (
                    <p className="text-muted-foreground text-sm">这只精英一条词条都没有。</p>
                  ) : (
                    <div className="flex flex-wrap gap-2">
                      {inspect.data.affixes.map((affix) => (
                        <Tag key={affix.affixId} tone={POOL_TONE[affix.pool]}>
                          {nameOf(affix.nameKey)} · {AFFIX_QUALITY_LABEL[affix.quality]} ·{' '}
                          {formatAffixValue(affix.primaryUnit, affix.primaryValue)}
                          {affix.secondaryUnit === undefined || affix.secondaryValue === undefined
                            ? ''
                            : ` / ${formatAffixValue(affix.secondaryUnit, affix.secondaryValue)}`}
                        </Tag>
                      ))}
                    </div>
                  )}
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
                <DialogTitle>{nameOf(selectedAffix.nameKey)}</DialogTitle>
                <DialogDescription>
                  {/* 词条 id 只在假数据模式下露出, 生产构建里给玩家看它所属的词条池。 */}
                  {isMockActive() ? selectedAffix.affixId : `${POOL_LABEL[selectedAffix.pool]}词条`}
                </DialogDescription>
              </DialogHeader>
              <div className="flex flex-col gap-3 px-6 pb-6">
                <div className="flex flex-wrap gap-2">
                  <Tag tone={POOL_TONE[selectedAffix.pool]}>{POOL_LABEL[selectedAffix.pool]}</Tag>
                  <Tag>基础成本 {selectedAffix.baseCost}</Tag>
                  <Tag>最低 {selectedAffix.minStar} 星</Tag>
                  <Tag>最低品质 {AFFIX_QUALITY_LABEL[selectedAffix.minQuality]}</Tag>
                  {selectedAffix.isSkill ? <Tag tone="brand">占技能位</Tag> : null}
                  {selectedAffix.mutexFlag === 'NONE' ? null : (
                    <Tag tone="warning">互斥族: {selectedAffix.mutexFlag}</Tag>
                  )}
                </div>
                <div className="flex flex-col gap-1.5">
                  <span className="text-muted-foreground text-xs">
                    各品质档数值与成本 (量纲 {selectedAffix.primaryUnit}, 不同词条之间不可比大小)
                  </span>
                  <div className="grid grid-cols-5 gap-2">
                    {tierColumns.map((column) => (
                      <span className="text-muted-foreground text-xs" key={column.qualityId}>
                        {column.label}
                      </span>
                    ))}
                    {selectedAffix.primaryValues.map((value, index) => (
                      // 五档与 tierColumns 一一对应, key 用档位下标本身足够稳定 (数组长度固定为 5)。
                      <span className="text-foreground text-sm tabular-nums" key={`primary-${String(index)}`}>
                        {selectedAffix.availableTiers[index] === true
                          ? formatAffixValue(selectedAffix.primaryUnit, value)
                          : '—'}
                      </span>
                    ))}
                    {selectedAffix.costs.map((cost, index) => (
                      <span
                        className="text-muted-foreground text-xs tabular-nums"
                        key={`cost-${String(index)}`}
                      >
                        {selectedAffix.availableTiers[index] === true ? `${String(cost)} 点` : '—'}
                      </span>
                    ))}
                  </div>
                </div>
                <AffixSecondaryRow affix={selectedAffix} />
              </div>
            </>
          )}
        </DialogPopup>
      </Dialog>
    </section>
  )
}
