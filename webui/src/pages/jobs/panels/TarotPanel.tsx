import type { ReactElement } from 'react'
import { useEffect, useMemo, useState } from 'react'
import { tickDeadline } from '@/hooks/use-live-updates'
import type { CurrencyKind, DropdownOption, Tone } from '@/components/kit'
import {
  Button,
  ConfirmDangerDialog,
  Currency,
  DataTable,
  Dropdown,
  EmptyBlock,
  ErrorBlock,
  FeedbackAlert,
  LoadingBlock,
  NumberInput,
  Panel,
  Stat,
  Surface,
  TabBar,
  Tag,
} from '@/components/kit'
import { call } from '../../../lib/bridge'
import { callErrorText } from '../../../lib/errorText'
import { useItemNames } from '../../../lib/i18n'
import type {
  TarotBuyPackResult,
  TarotCardEffectsResult,
  TarotDeckEntry,
  TarotExchangeResult,
  TarotPackKind,
  TarotQualityId,
  TarotQualityRow,
  TarotStateResult,
  WebUiCurrency,
} from '../../../lib/types'
import { callMock, nowMs, useMockAction } from '../../../mock'
import { JobExpProgress } from '../JobProgressSummary'
import { formatCountdown, toError, useLiveNow } from './shared'

/**
 * 塔罗师面板 (Java 落点 com.miningdim.job.tarot.TarotWebUiActions; 回执形状见 lib/types.ts 的 Tarot*)。
 *
 * 三条与直觉相反、页面必须照做的契约事实:
 *   1. 牌与品质是**多对多**: 同一张大阿卡纳可同时持有 R/SR/SSR/UR/闪耀 五种实体牌, 故一行发
 *      ownedByQuality[5] 而没有单数 quality 字段。"这张牌是什么品质"这个问题在服务端不成立。
 *   2. 没有"编入卡组"这回事: 塔罗牌是背包里的实体物品, 右键即打出。能不能打是**按等级**判的
 *      (qualities[].usable 逐档), 不是逐张牌的 equipped。
 *   3. 牌效说明是服务端发来的 Component JSON 串 (job.tarot.cardEffects), 服务端解不出中文, 必须经
 *      client.formatText 在客户端本地排版后再显示, 严禁把 JSON 串原样画上页面。
 *
 * owned / inInventory / collectedByQuality 是三件事, 必须分开讲: owned 只数 ownerUUID == 本人的牌 (决定
 * "能不能打"); inInventory 数背包里同 cardId 的全部可读牌 (老实回答"背包里有几张"); collectedByQuality 才是
 * 服务端判"再开出来会不会变碎片"的真实口径, 按品质逐档 —— 某档已收集只挡同牌同档, 不挡别的档。
 *
 * 剩余冷却 (gcdRemainingTicks / deck[].cooldownRemainingTicks / shinyCooldownRemainingTicks) 在回执到达那一刻
 * 折成本地截止时刻 (tickDeadline), 之后本地倒计时, 不轮询服务端。
 *
 * 开包入口刻意不做: 普通/高级包右键就地开并走 TarotPackRevealS2C 客户端演出, 闪耀包要开原生 GUI 自选,
 * 两者在 MCEF 页面里都点不动 —— buyPack 买到的是**卡包物品**。
 *
 * 贴图直接引用 mod 资源 (textures/item/tarot/): 塔罗贴图落在 item/tarot/ 子目录且卡面与品质边框是两张图叠加,
 * 不满足 ItemIcon "item/<单段id>.png" 的假设。卡面序号直接用 cardId (TarotArcana 类注释: cardId 即贴图索引)。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}

const TAROT_TEXTURE_ROOT = `${import.meta.env.BASE_URL}mc/item/tarot/`

/**
 * 品质徽标色, 对齐游戏内品质边框 (白银 / 蓝 / 紫 / 粉红 / 金)。设计系统只开放语义色档与一个可调强调色,
 * 不另造色相: R 中性、SR 信息蓝、SSR 强调色 (默认蓝紫)、UR 危险红、闪耀警示金。
 */
const QUALITY_TONE: Record<TarotQualityId, Tone> = {
  r: 'neutral',
  sr: 'info',
  ssr: 'brand',
  ur: 'danger',
  shiny: 'warning',
}

/** 非闪耀牌的冷却分类, 服务端只发 id (utility/buff/combat), 中文归前端。 */
const COOLDOWN_CATEGORY_LABEL: Record<'utility' | 'buff' | 'combat', string> = {
  utility: '功能',
  buff: '增益',
  combat: '战斗',
}

/** 卡包内容说明 (数值在服务端配置里, 这里只讲"开出来是什么"的规则, 不写具体概率以免与配置漂移)。 */
const PACK_CONTENTS: Record<TarotPackKind, string> = {
  common: '1 张低级 (R) 牌, 随机牌面与正逆位',
  advanced: '数张中级/高级 (SR/SSR) 牌; 连续未出 SSR 有保底, 可能额外附带派生高级包',
  shiny: '开出后在游戏里从 22 张中自选一张高级 (SSR) 牌',
}

type CardArtScale = 1 | 2 | 3

const CARD_SCALE_CLASS: Record<CardArtScale, string> = {
  1: 'h-16 w-16',
  2: 'h-28 w-28',
  3: 'h-44 w-44',
}

/** 单次购买上限 (契约: count 域 [1,64])。 */
const PACK_COUNT_MAX = 64

/** client.formatText 结果缓存: 同一行 JSON 在本页生命周期内只排版一次 (切换牌/品质来回看不重复往返)。 */
const formattedCache = new Map<string, string>()

function currencyKindOf(currency: WebUiCurrency): CurrencyKind {
  return currency === 'AZURE' ? 'azure' : 'credit'
}

/** tick -> 秒的纯展示折算。服务端只有 game tick 这一种时间量纲, 20 tick = 1 秒。 */
function ticksToSecondsText(ticks: number): string {
  const seconds = ticks / 20
  return seconds >= 60 ? `${(seconds / 60).toFixed(seconds % 60 === 0 ? 0 : 1)} 分钟` : `${seconds.toFixed(1)} 秒`
}

/**
 * 把服务端发来的 Component JSON 行交给客户端排版成当前语言文字。lines 为 null 表示还没有要排的行。
 * 失败 (宿主不支持 client.formatText 等) 时给出错误, 由调用方显示, 不静默回退成 JSON 原文。
 */
function useFormattedLines(lines: readonly string[] | null): {
  texts: readonly string[] | null
  error: Error | null
} {
  const key = lines === null ? null : lines.join('\u0000')
  const [state, setState] = useState<{ key: string | null; texts: readonly string[] | null; error: Error | null }>(
    { key: null, texts: null, error: null },
  )
  useEffect(() => {
    if (lines === null || key === null) {
      return
    }
    const missing = lines.filter((line) => !formattedCache.has(line))
    if (missing.length === 0) {
      setState({ key, texts: lines.map((line) => formattedCache.get(line) ?? ''), error: null })
      return
    }
    let cancelled = false
    call('client.formatText', { texts: missing })
      .then((result) => {
        missing.forEach((line, index) => {
          formattedCache.set(line, result.texts[index] ?? '')
        })
        if (!cancelled) {
          setState({ key, texts: lines.map((line) => formattedCache.get(line) ?? ''), error: null })
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setState({ key, texts: null, error: toError(error) })
        }
      })
    return () => {
      cancelled = true
    }
    // key 已编码 lines 的全部内容, 依赖 key 即可避免数组引用每次渲染都变。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])
  return state.key === key ? { texts: state.texts, error: state.error } : { texts: null, error: null }
}

/**
 * 卡面渲染: 未持有一律显示牌背 (不提前泄露品质), 已持有则叠加"正面 + 品质边框"两张同尺寸贴图。
 * quality 为 null 即未持有 —— 一张牌可能同时持有多档, 调用方负责挑出要展示的那一档。
 */
function TarotCardArt({
  cardId,
  quality,
  scale,
  label,
  dimmed = false,
}: {
  cardId: number
  quality: TarotQualityId | null
  scale: CardArtScale
  label: string
  dimmed?: boolean
}): ReactElement {
  const sizeClass = CARD_SCALE_CLASS[scale]
  const dimClass = dimmed ? 'opacity-45' : ''

  if (quality === null) {
    return (
      <span className={`relative block ${sizeClass} ${dimClass}`} role="img" aria-label={`${label} (未持有)`}>
        <img
          src={`${TAROT_TEXTURE_ROOT}card_back.png`}
          alt=""
          aria-hidden="true"
          className={`block ${sizeClass}`}
          style={{ imageRendering: 'pixelated' }}
        />
      </span>
    )
  }

  return (
    <span className={`relative block ${sizeClass} ${dimClass}`} role="img" aria-label={label}>
      <img
        src={`${TAROT_TEXTURE_ROOT}${String(cardId).padStart(2, '0')}.png`}
        alt=""
        aria-hidden="true"
        className={`absolute inset-0 block ${sizeClass}`}
        style={{ imageRendering: 'pixelated' }}
      />
      <img
        src={`${TAROT_TEXTURE_ROOT}border_${quality}.png`}
        alt=""
        aria-hidden="true"
        className={`absolute inset-0 block ${sizeClass}`}
        style={{ imageRendering: 'pixelated' }}
      />
    </span>
  )
}

/** 一行牌里持有的最高品质 (下标越大品质越高); 一张都没有返回 null。 */
function topOwnedQuality(
  qualityOrder: readonly TarotQualityRow[],
  ownedByQuality: readonly number[],
): TarotQualityId | null {
  for (let index = qualityOrder.length - 1; index >= 0; index -= 1) {
    const row = qualityOrder[index]
    if (row !== undefined && (ownedByQuality[index] ?? 0) > 0) {
      return row.qualityId
    }
  }
  return null
}

/** 牌组里一张牌的本地冷却截止时刻 (回执到达时折算一次)。 */
interface CardDeadlines {
  card: number
  shiny: number
}

export function TarotPanel(): ReactElement {
  const query = useMockAction('job.tarot.state', EMPTY_PAYLOAD)
  const progress = useMockAction('job.progress', EMPTY_PAYLOAD)
  const wallet = useMockAction('player.wallet', EMPTY_PAYLOAD)
  const now = useLiveNow()

  const [filter, setFilter] = useState<'all' | TarotQualityId>('all')
  const [selectedCardId, setSelectedCardId] = useState<number | null>(null)
  const [packKind, setPackKind] = useState<TarotPackKind>('common')
  const [packCount, setPackCount] = useState(1)
  const [purchasing, setPurchasing] = useState(false)
  const [confirmingPurchase, setConfirmingPurchase] = useState(false)
  const [purchaseError, setPurchaseError] = useState<Error | null>(null)
  const [lastPurchase, setLastPurchase] = useState<TarotBuyPackResult | null>(null)

  const data: TarotStateResult | null = query.status === 'ready' ? query.data : null

  /*
   * 剩余 tick 只在收到回执那一刻有意义, 故在 data 这个引用刚换新时折一次本地时刻 (与矿工面板同一做法)。
   */
  const deadlines = useMemo(() => {
    if (data === null) {
      return { gcd: 0, cards: new Map<number, CardDeadlines>() }
    }
    const receivedAt = nowMs()
    const cards = new Map<number, CardDeadlines>()
    for (const card of data.deck) {
      cards.set(card.cardId, {
        card: tickDeadline(card.cooldownRemainingTicks, receivedAt),
        shiny: tickDeadline(card.shinyCooldownRemainingTicks, receivedAt),
      })
    }
    return { gcd: tickDeadline(data.gcdRemainingTicks, receivedAt), cards }
  }, [data])

  // 三类展示名 (牌名/品质名/卡包名) 一次批量解: client.i18n 本身按批合并, 分三次只会多两轮往返。
  const names = useItemNames(
    data === null
      ? []
      : [
          ...data.deck.map((card) => card.nameKey),
          ...data.qualities.map((quality) => quality.nameKey),
          ...data.packs.map((pack) => pack.nameKey),
        ],
  )
  const nameOf = (nameKey: string): string => names[nameKey] ?? nameKey

  if (query.status === 'loading') {
    return <LoadingBlock label="正在读取塔罗牌组" />
  }
  if (query.status === 'error') {
    return <ErrorBlock message={callErrorText(query.error)} onRetry={query.reload} />
  }
  if (data === null) {
    return <ErrorBlock message="job.tarot.state 回执为空" onRetry={query.reload} />
  }

  /** 品质声明序 (qualities 恒 5 行且顺序 = TarotQuality 声明序), 同时就是 ownedByQuality 的下标序。 */
  const qualityOrder: readonly TarotQualityRow[] = data.qualities
  const tarotProgress =
    progress.status === 'ready' ? progress.data.jobs.find((entry) => entry.jobId === 'tarot') : undefined

  const qualityFilterOptions: readonly DropdownOption<'all' | TarotQualityId>[] = [
    { value: 'all', label: '全部品质' },
    ...qualityOrder.map((quality) => ({
      value: quality.qualityId,
      label: nameOf(quality.nameKey),
    })),
  ]

  const packOptions: readonly DropdownOption<TarotPackKind>[] = data.packs.map((pack) => ({
    value: pack.packKind,
    label: nameOf(pack.nameKey),
  }))
  const selectedPack = data.packs.find((pack) => pack.packKind === packKind)

  /*
   * 测试模式下买包免费且不计日限 (TarotConfig.TEST_MODE), 故不拿 packsRemainingToday 卡步进器上限 ——
   * 那会在测试服上把按钮锁死在一个与实际行为无关的数上。
   */
  const dailyExhausted = !data.testMode && data.packsRemainingToday <= 0
  const stepperMax = data.testMode
    ? PACK_COUNT_MAX
    : Math.max(1, Math.min(PACK_COUNT_MAX, data.packsRemainingToday))
  const effectiveCount = Math.min(packCount, stepperMax)
  // 只是预估: 实扣额以回执 totalPrice 为准 (测试模式恒 0), 严禁拿这个数当"已花费"显示。
  const estimatedCost = selectedPack === undefined ? 0 : selectedPack.unitPrice * effectiveCount
  const balance =
    wallet.status !== 'ready' || selectedPack === undefined
      ? null
      : selectedPack.currency === 'AZURE'
        ? wallet.data.azure
        : wallet.data.credit
  const cannotAfford = !data.testMode && balance !== null && balance < estimatedCost

  async function handleBuyPack(): Promise<void> {
    setPurchasing(true)
    setPurchaseError(null)
    try {
      const result = await callMock('job.tarot.buyPack', { kind: packKind, count: effectiveCount })
      setLastPurchase(result)
      setPackCount(1)
      query.reload()
      wallet.reload()
    } catch (error) {
      setPurchaseError(toError(error))
    } finally {
      setPurchasing(false)
      setConfirmingPurchase(false)
    }
  }

  /** 青辉石是稀缺货币, 买闪耀包先确认; 信用点的普通/高级包直接买。 */
  function requestBuyPack(): void {
    if (selectedPack?.currency === 'AZURE' && !data?.testMode) {
      setConfirmingPurchase(true)
      return
    }
    void handleBuyPack()
  }

  // 筛选按"持有该品质的实体牌至少一张"判, 不是按"这张牌是该品质" —— 牌与品质是多对多。
  const filterQualityIndex =
    filter === 'all' ? -1 : qualityOrder.findIndex((quality) => quality.qualityId === filter)
  const filteredDeck =
    filterQualityIndex < 0
      ? data.deck
      : data.deck.filter((card) => (card.ownedByQuality[filterQualityIndex] ?? 0) > 0)
  const selectedCard = data.deck.find((card) => card.cardId === selectedCardId) ?? null

  const gcdReady = deadlines.gcd <= now
  const coolingCards = data.deck.filter((card) => {
    const deadline = deadlines.cards.get(card.cardId)
    return deadline !== undefined && (deadline.card > now || deadline.shiny > now)
  })
  const shardsShort = Math.max(0, data.shardExchangeCost - data.shards)

  return (
    <div className="flex flex-col gap-4">
      <Panel title="塔罗师">
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-3 gap-4">
            <Stat label="职业等级" value={`Lv.${String(data.level)}`} />
            <Stat
              label="塔罗碎片"
              value={String(data.shards)}
              hint={
                shardsShort > 0
                  ? `再攒 ${String(shardsShort)} 枚可兑换一张指定 SSR`
                  : `已够兑换 (每张 ${String(data.shardExchangeCost)} 枚), 在牌面详情里兑换`
              }
            />
            <Stat
              label="今日已获取卡包"
              value={`${String(data.packsBoughtToday)} / ${String(data.packDailyLimit)}`}
              hint="高级包派生出的免费包同样占额度"
            />
          </div>
          {tarotProgress === undefined ? null : <JobExpProgress entry={tarotProgress} />}
          <p className="text-muted-foreground text-xs">
            碎片只统计主背包与副手 (末影箱不计); 开包重复牌返 {data.duplicateShardRefund} 枚碎片。合成要在游戏里的塔罗合成台做
          </p>
          {data.testMode ? (
            <Surface tone="warning">
              <p className="text-foreground text-sm">
                测试模式已开启: 买包免费且不计每日限购, 任何等级都能打出任何品质 —— 下方的价格与
                "可用"两栏只代表正式环境下的规则
              </p>
            </Surface>
          ) : null}
        </div>
      </Panel>

      <Panel title="卡包">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-4">
            <div className="flex flex-col gap-1">
              <span className="text-muted-foreground text-xs">卡包种类</span>
              <Dropdown
                className="w-40"
                disabled={purchasing}
                onChange={setPackKind}
                options={packOptions}
                value={packKind}
              />
            </div>
            <div className="flex flex-col gap-1">
              <span className="text-muted-foreground text-xs">
                购买数量{data.testMode ? '' : ` (今日剩余 ${String(data.packsRemainingToday)} 包)`}
              </span>
              <NumberInput
                disabled={purchasing || dailyExhausted}
                max={stepperMax}
                min={1}
                onChange={setPackCount}
                value={effectiveCount}
              />
            </div>
            <Stat
              label="预计花费"
              value={
                selectedPack === undefined ? (
                  '—'
                ) : (
                  <Currency amount={estimatedCost} currency={currencyKindOf(selectedPack.currency)} />
                )
              }
            />
            <Stat
              label="当前余额"
              value={
                balance === null || selectedPack === undefined ? (
                  '—'
                ) : (
                  <Currency amount={balance} currency={currencyKindOf(selectedPack.currency)} />
                )
              }
            />
            <Button
              disabled={dailyExhausted || selectedPack === undefined || cannotAfford}
              loading={purchasing}
              onClick={requestBuyPack}
              variant="brand"
            >
              购买卡包
            </Button>
          </div>
          <p className="text-muted-foreground text-xs">
            {PACK_CONTENTS[packKind]}。
            {cannotAfford ? ' 余额不足。' : ''}
            {dailyExhausted ? ' 今日额度已用完。' : ''}
            买到的是卡包物品, 开包请在游戏里右键 (背包满时卡包会掉在脚下)
          </p>
          {purchaseError === null ? null : (
            <FeedbackAlert message={callErrorText(purchaseError)} tone="danger" />
          )}

          {lastPurchase === null ? null : (
            <Surface>
              <p className="flex flex-wrap items-center gap-1 text-muted-foreground text-xs">
                已购得 {lastPurchase.count} 个 {nameOf(lastPurchase.nameKey)} · 实扣{' '}
                <Currency
                  amount={lastPurchase.totalPrice}
                  currency={currencyKindOf(lastPurchase.currency)}
                  showIcon={false}
                  size="sm"
                />
                {lastPurchase.testMode ? ' (测试模式免费)' : ''}
              </p>
            </Surface>
          )}
        </div>
      </Panel>

      <ConfirmDangerDialog
        confirmLabel="确认购买"
        loading={purchasing}
        message={`将花费 ${String(estimatedCost)} 青辉石购买 ${String(effectiveCount)} 个${
          selectedPack === undefined ? '' : nameOf(selectedPack.nameKey)
        }。青辉石不可退回, 卡包到手后在游戏里右键打开并自选一张 SSR 牌。`}
        onConfirm={() => {
          void handleBuyPack()
        }}
        onOpenChange={setConfirmingPurchase}
        open={confirmingPurchase}
        title="确认用青辉石购买闪耀卡包"
      />

      <Panel title="品质与冷却">
        <div className="flex flex-col gap-3">
          <DataTable
            columns={[
              {
                header: '品质',
                key: 'quality',
                render: (row) => <Tag tone={QUALITY_TONE[row.qualityId]}>{nameOf(row.nameKey)}</Tag>,
              },
              {
                header: '解锁等级',
                key: 'requiredLevel',
                numeric: true,
                render: (row) => `Lv.${String(row.requiredLevel)}`,
                sortValue: (row) => row.requiredLevel,
              },
              {
                header: '当前可用',
                key: 'usable',
                render: (row) => (row.usable ? '可打出' : '未解锁'),
                sortValue: (row) => (row.usable ? 1 : 0),
              },
              {
                header: '单张经验',
                key: 'rawXp',
                numeric: true,
                render: (row) => String(row.rawXp),
                sortValue: (row) => row.rawXp,
              },
            ]}
            rowKey={(row) => row.qualityId}
            rows={qualityOrder}
          />
          <div className="grid grid-cols-4 gap-4">
            <Stat
              label="公共 CD"
              value={gcdReady ? '就绪' : formatCountdown(deadlines.gcd, now)}
              hint={`每次打牌后 ${ticksToSecondsText(data.cooldownTicks.gcd)}`}
            />
            <Stat label="功能牌 CD" value={ticksToSecondsText(data.cooldownTicks.utility)} />
            <Stat label="增益牌 CD" value={ticksToSecondsText(data.cooldownTicks.buff)} />
            <Stat label="战斗牌 CD" value={ticksToSecondsText(data.cooldownTicks.combat)} />
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-muted-foreground text-xs">冷却中的牌</span>
            {coolingCards.length === 0 ? (
              <Tag tone="success">全部就绪</Tag>
            ) : (
              coolingCards.map((card) => {
                const deadline = deadlines.cards.get(card.cardId)
                const until = Math.max(deadline?.card ?? 0, deadline?.shiny ?? 0)
                return (
                  <Tag key={card.cardId} tone="warning">
                    {nameOf(card.nameKey)} {formatCountdown(until, now)}
                  </Tag>
                )
              })
            )}
          </div>
          {data.cardDataLoaded ? null : (
            <Surface tone="warning">
              <p className="text-foreground text-sm">
                牌效数据尚未加载完 (datapack 重载中或失败), 牌组的冷却分类与牌效说明暂时读不到
              </p>
            </Surface>
          )}
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-muted-foreground text-xs">高级包保底进度</span>
            <Tag tone={data.advancedPityStreak >= data.advancedPityThreshold ? 'success' : 'neutral'}>
              {data.advancedPityStreak} / {data.advancedPityThreshold}
            </Tag>
            <span className="text-muted-foreground text-xs">攒满后下一个高级包首张保底 SSR</span>
          </div>
        </div>
      </Panel>

      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_24rem]">
        <Panel
          actions={
            <Dropdown
              className="w-32"
              onChange={setFilter}
              options={qualityFilterOptions}
              size="sm"
              value={filter}
            />
          }
          title="牌组 (22 张大阿卡纳)"
        >
          {filteredDeck.length === 0 ? (
            <EmptyBlock hint="换一个品质档位试试" title="没有符合筛选条件的牌" />
          ) : (
            <div className="flex flex-wrap gap-2">
              {filteredDeck.map((card) => {
                const cardName = nameOf(card.nameKey)
                const top = topOwnedQuality(qualityOrder, card.ownedByQuality)
                const selected = card.cardId === selectedCardId
                const deadline = deadlines.cards.get(card.cardId)
                const cooling = deadline !== undefined && deadline.card > now
                return (
                  <button
                    aria-pressed={selected}
                    className={`flex w-32 flex-col items-center gap-1 rounded-lg border p-2 transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                      selected ? 'border-brand bg-brand/12' : 'border-transparent hover:bg-accent'
                    }`}
                    key={card.cardId}
                    onClick={() => {
                      setSelectedCardId(selected ? null : card.cardId)
                    }}
                    type="button"
                  >
                    <TarotCardArt
                      cardId={card.cardId}
                      dimmed={cooling}
                      label={cardName}
                      quality={top}
                      scale={2}
                    />
                    <span className="max-w-full truncate text-foreground text-xs">{cardName}</span>
                    <div className="flex flex-wrap items-center justify-center gap-1">
                      {card.owned > 0 ? <Tag size="sm">x{card.owned}</Tag> : null}
                      {cooling && deadline !== undefined ? (
                        <Tag size="sm" tone="warning">
                          {formatCountdown(deadline.card, now)}
                        </Tag>
                      ) : card.cooldownCategory === null ? null : (
                        <Tag size="sm" tone="neutral">
                          {COOLDOWN_CATEGORY_LABEL[card.cooldownCategory]}
                        </Tag>
                      )}
                    </div>
                  </button>
                )
              })}
            </div>
          )}
        </Panel>

        {/* 窄屏下详情排在牌组前面: 原先详情在 22 张牌下方, 点上排的牌要滚到页底才看得到。 */}
        <div className="order-first lg:sticky lg:top-4 lg:order-none">
          {selectedCard === null ? (
            <Panel title="牌面详情">
              <EmptyBlock hint="点选牌组里的一张牌, 查看各品质牌效、冷却与碎片兑换" title="未选择牌" />
            </Panel>
          ) : (
            <TarotCardDetail
              card={selectedCard}
              cardDataLoaded={data.cardDataLoaded}
              deadline={deadlines.cards.get(selectedCard.cardId)}
              key={selectedCard.cardId}
              nameOf={nameOf}
              now={now}
              onExchanged={() => {
                query.reload()
              }}
              qualityOrder={qualityOrder}
              shardExchangeCost={data.shardExchangeCost}
              shards={data.shards}
            />
          )}
        </div>
      </div>
    </div>
  )
}

/** 牌面详情: 卡面、各品质持有/收集、剩余冷却、按品质分页的牌效说明、碎片兑换。 */
function TarotCardDetail({
  card,
  cardDataLoaded,
  deadline,
  nameOf,
  now,
  onExchanged,
  qualityOrder,
  shardExchangeCost,
  shards,
}: {
  card: TarotDeckEntry
  cardDataLoaded: boolean
  deadline: CardDeadlines | undefined
  nameOf: (nameKey: string) => string
  now: number
  onExchanged: () => void
  qualityOrder: readonly TarotQualityRow[]
  shardExchangeCost: number
  shards: number
}): ReactElement {
  const cardName = nameOf(card.nameKey)
  const top = topOwnedQuality(qualityOrder, card.ownedByQuality)
  const [qualityTab, setQualityTab] = useState<TarotQualityId>(top ?? 'r')

  const effects = useMockAction('job.tarot.cardEffects', { cardId: card.cardId })
  const effectData: TarotCardEffectsResult | null = effects.status === 'ready' ? effects.data : null

  // 当前页签要排版的行: 闪耀一栏; 其余品质正位 + 逆位两栏, 拼成一批一次排完。
  const tabIndex = qualityOrder.findIndex((quality) => quality.qualityId === qualityTab)
  const tierIndex = qualityOrder[tabIndex]?.tierIndex ?? -1
  const uprightLines = effectData === null || tierIndex < 0 ? [] : (effectData.upright[tierIndex] ?? [])
  const reversedLines = effectData === null || tierIndex < 0 ? [] : (effectData.reversed[tierIndex] ?? [])
  const shinyLines = effectData === null ? [] : effectData.shiny
  const rawLines =
    effectData === null || !effectData.loaded
      ? null
      : qualityTab === 'shiny'
        ? shinyLines
        : [...uprightLines, ...reversedLines]
  const formatted = useFormattedLines(rawLines)

  const [exchangeUpright, setExchangeUpright] = useState(true)
  const [confirmingExchange, setConfirmingExchange] = useState(false)
  const [exchanging, setExchanging] = useState(false)
  const [exchangeError, setExchangeError] = useState<Error | null>(null)
  const [exchangeResult, setExchangeResult] = useState<TarotExchangeResult | null>(null)

  async function handleExchange(): Promise<void> {
    setExchanging(true)
    setExchangeError(null)
    try {
      const result = await callMock('job.tarot.exchange', { cardId: card.cardId, upright: exchangeUpright })
      setExchangeResult(result)
      onExchanged()
    } catch (error) {
      setExchangeError(toError(error))
    } finally {
      setExchanging(false)
      setConfirmingExchange(false)
    }
  }

  const collectedNames = qualityOrder
    .filter((_quality, index) => card.collectedByQuality[index] === true)
    .map((quality) => nameOf(quality.nameKey))
  const cardCooling = deadline !== undefined && deadline.card > now
  const shinyCooling = deadline !== undefined && deadline.shiny > now
  const selectedQualityRow = qualityOrder[tabIndex]

  function renderEffectList(title: string, lines: readonly string[], offset: number): ReactElement {
    return (
      <div className="flex flex-col gap-1">
        <h3 className="font-medium text-foreground text-sm">{title}</h3>
        {lines.length === 0 ? (
          <span className="text-muted-foreground text-xs">此档无效果</span>
        ) : (
          <ul className="flex list-disc flex-col gap-0.5 pl-4 text-foreground text-sm">
            {lines.map((_line, index) => (
              // 同一牌效可能出现两行字面相同的说明, 故按位置作 key。
              <li key={index}>{formatted.texts?.[offset + index] || '(说明不可读)'}</li>
            ))}
          </ul>
        )}
      </div>
    )
  }

  let effectBody: ReactElement
  if (!cardDataLoaded || (effectData !== null && !effectData.loaded)) {
    effectBody = <span className="text-muted-foreground text-sm">牌效数据尚未加载完, 稍后再看</span>
  } else if (effects.status === 'error') {
    effectBody = <ErrorBlock message={callErrorText(effects.error)} onRetry={effects.reload} />
  } else if (formatted.error !== null) {
    effectBody = <FeedbackAlert message={`牌效说明排版失败: ${formatted.error.message}`} tone="danger" />
  } else if (effectData === null || formatted.texts === null) {
    effectBody = <LoadingBlock label="正在读取牌效" />
  } else if (qualityTab === 'shiny') {
    effectBody = renderEffectList('闪耀签名技 (不分正逆位)', shinyLines, 0)
  } else {
    effectBody = (
      <div className="flex flex-col gap-3">
        {renderEffectList('正位', uprightLines, 0)}
        {renderEffectList('逆位', reversedLines, uprightLines.length)}
      </div>
    )
  }

  return (
    <Panel title={`牌面详情 · ${cardName}`}>
      <div className="flex flex-col gap-3">
        <div className="flex items-start gap-3">
          <TarotCardArt cardId={card.cardId} label={cardName} quality={top} scale={3} />
          <div className="flex min-w-0 flex-col gap-2">
            <div className="flex flex-wrap items-center gap-1">
              {qualityOrder.map((quality, index) => {
                const held = card.ownedByQuality[index] ?? 0
                return (
                  <Tag key={quality.qualityId} size="sm" tone={held > 0 ? QUALITY_TONE[quality.qualityId] : 'neutral'}>
                    {nameOf(quality.nameKey)} x{held}
                  </Tag>
                )
              })}
            </div>
            <span className="text-muted-foreground text-xs">
              属于我的牌 {card.owned} 张 (只有这些打得出来) · 背包里同名牌共 {card.inInventory} 张 (含别人绑定的)
            </span>
            <span className="text-muted-foreground text-xs">
              {collectedNames.length === 0
                ? '开包判重: 尚未收集任何品质, 开到这张牌都会给真牌'
                : `开包判重: ${collectedNames.join('、')} 已收集 (含放进箱子的牌), 开到同牌同品质会转成碎片; 其它品质仍可能开出真牌`}
            </span>
            <span className="text-muted-foreground text-xs">
              {card.cooldownCategory === null
                ? '冷却分类读取失败 (牌效数据未加载)'
                : `${COOLDOWN_CATEGORY_LABEL[card.cooldownCategory]}牌 · ${
                    cardCooling && deadline !== undefined ? `冷却剩余 ${formatCountdown(deadline.card, now)}` : '冷却就绪'
                  }`}
            </span>
            <span className="text-muted-foreground text-xs">
              {card.shinyCooldownTicks === null
                ? '闪耀牌 CD 读取失败 (牌效数据未加载)'
                : `闪耀级 CD ${ticksToSecondsText(card.shinyCooldownTicks)} · ${
                    shinyCooling && deadline !== undefined ? `剩余 ${formatCountdown(deadline.shiny, now)}` : '就绪'
                  }`}
            </span>
          </div>
        </div>

        <TabBar
          activeId={qualityTab}
          onChange={(id) => {
            setQualityTab(id as TarotQualityId)
          }}
          tabs={qualityOrder.map((quality) => ({ id: quality.qualityId, label: nameOf(quality.nameKey) }))}
          variant="underline"
        />
        {selectedQualityRow !== undefined && !selectedQualityRow.usable ? (
          <span className="text-muted-foreground text-xs">
            这一档需要塔罗师 Lv.{selectedQualityRow.requiredLevel} 才能打出 (可以先持有)
          </span>
        ) : null}
        {effectBody}

        <div className="flex flex-col gap-2 border-border border-t pt-3">
          <h3 className="font-medium text-foreground text-sm">碎片兑换</h3>
          <span className="text-muted-foreground text-xs">
            用 {shardExchangeCost} 枚碎片兑换一张「{cardName}」高级 (SSR) 牌, 绑定在你名下。现有 {shards} 枚
            {shards < shardExchangeCost ? `, 还差 ${String(shardExchangeCost - shards)} 枚` : ''}。
          </span>
          <div className="flex flex-wrap items-center gap-2">
            <TabBar
              activeId={exchangeUpright ? 'upright' : 'reversed'}
              onChange={(id) => {
                setExchangeUpright(id === 'upright')
              }}
              tabs={[
                { id: 'upright', label: '正位' },
                { id: 'reversed', label: '逆位' },
              ]}
            />
            <Button
              disabled={shards < shardExchangeCost}
              loading={exchanging}
              onClick={() => {
                setConfirmingExchange(true)
              }}
              variant="brand"
            >
              兑换
            </Button>
          </div>
          {exchangeError === null ? null : <FeedbackAlert message={callErrorText(exchangeError)} tone="danger" />}
          {exchangeResult === null ? null : (
            <FeedbackAlert
              message={`已兑换: 花费 ${String(exchangeResult.shardsSpent)} 枚碎片, 剩余 ${String(
                exchangeResult.shardsLeft,
              )} 枚。牌已放进背包 (背包满时掉在脚下)`}
              tone="success"
            />
          )}
        </div>
      </div>

      <ConfirmDangerDialog
        confirmLabel="确认兑换"
        loading={exchanging}
        message={`将消耗 ${String(shardExchangeCost)} 枚塔罗碎片, 兑换一张${exchangeUpright ? '正位' : '逆位'}「${cardName}」高级 (SSR) 牌。碎片不可退回。`}
        onConfirm={() => {
          void handleExchange()
        }}
        onOpenChange={setConfirmingExchange}
        open={confirmingExchange}
        title="确认碎片兑换"
      />
    </Panel>
  )
}
