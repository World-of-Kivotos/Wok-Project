import type { ReactElement } from 'react'
import { useState } from 'react'
import type { CurrencyKind, DropdownOption, Tone } from '@/components/kit'
import {
  Button,
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
  Tag,
} from '@/components/kit'
import { callErrorText } from '../../../lib/errorText'
import { useItemNames } from '../../../lib/i18n'
import type {
  TarotBuyPackResult,
  TarotPackKind,
  TarotQualityId,
  TarotQualityRow,
  WebUiCurrency,
} from '../../../lib/types'
import { callMock, useMockAction } from '../../../mock'
import { toError } from './shared'

/**
 * 塔罗师面板 (`job.tarot.state` / `job.tarot.buyPack`, Java 落点 com.miningdim.job.tarot.TarotWebUiActions)。
 * 回执形状见 lib/types.ts 的 TarotStateResult / TarotBuyPackResult。
 *
 * 三条与直觉相反、页面必须照做的契约事实:
 *   1. 牌与品质是**多对多**: 同一张大阿卡纳可同时持有 R/SR/SSR/UR/闪耀 五种实体牌, 故一行发
 *      ownedByQuality[5] 而没有单数 quality 字段。"这张牌是什么品质"这个问题在服务端不成立。
 *   2. 没有"编入卡组"这回事: 塔罗牌是背包里的实体物品, 右键即打出。能不能打是**按等级**判的
 *      (qualities[].usable 逐档), 不是逐张牌的 equipped —— 旧版的卡组编辑界面零服务端支撑, 已删。
 *   3. 服务端只发四个"满 CD 时长", 发不出"这张牌还剩多久": TarotCooldownManager 的三张截止表全私有,
 *      唯一读取入口 tryUse 是"校验并占用"的写方法, 面板一调就把玩家的 GCD 吃掉。故本页画的是
 *      "这一类牌的 CD 有多长", 不是倒计时。
 *
 * owned / inInventory / collected 是三件事, 必须分开讲 (复核 finding 3/5): owned 只数 ownerUUID == 本人
 * 的牌 (决定"能不能打"); inInventory 数背包里同 cardId 的全部可读牌 (老实回答"背包里有几张", 不是判重口径);
 * collected 才是服务端账本判"再开出来会不会变碎片"的真实口径 —— 五档品质任一档净持有 (含放进箱子里的牌)
 * 即真, 打出/合成消耗掉后账本会释放, 不是永久标记。
 *
 * 开包入口刻意不做: 普通/高级包右键就地开并走 TarotPackRevealS2C 客户端演出, 闪耀包要开原生 GUI 自选,
 * 两者在 MCEF 页面里都点不动 —— buyPack 买到的是**卡包物品**, 回执里没有也不会有开出的牌。
 *
 * 贴图直接引用 mod 资源 (textures/item/tarot/), 不经 ItemIcon: 塔罗贴图落在 item/tarot/ 子目录且
 * 卡面与品质边框是两张图叠加, 均不满足 ItemIcon "item/<单段id>.png" 的假设。卡面序号直接用 cardId ——
 * TarotArcana 的类注释写死了 "cardId 即 ordinal (0-21), 是贴图索引", 与游戏内 TarotCraftScreen 同一口径,
 * 不再按牌名去猜大阿卡纳顺序。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}

const TAROT_TEXTURE_ROOT = `${import.meta.env.BASE_URL}mc/item/tarot/`

/**
 * 品质徽标色。只留颜色: 品质名走 nameKey 经 client.i18n 解 (服务端不发中文), 边框文件名可由 qualityId
 * 直接拼出 (资产目录里就是 border_r/sr/ssr/ur/shiny 五个), 都不需要再抄一份字典。
 */
const QUALITY_TONE: Record<TarotQualityId, Tone> = {
  r: 'neutral',
  sr: 'info',
  ssr: 'success',
  ur: 'warning',
  shiny: 'danger',
}

/** 非闪耀牌的冷却分类, 服务端只发 id (utility/buff/combat), 中文归前端。 */
const COOLDOWN_CATEGORY_LABEL: Record<'utility' | 'buff' | 'combat', string> = {
  utility: '功能',
  buff: '增益',
  combat: '战斗',
}

type CardArtScale = 1 | 2 | 3

const CARD_SCALE_CLASS: Record<CardArtScale, string> = {
  1: 'h-16 w-16',
  2: 'h-32 w-32',
  3: 'h-48 w-48',
}

/** 单次购买上限 (契约: count 域 [1,64])。 */
const PACK_COUNT_MAX = 64

function currencyKindOf(currency: WebUiCurrency): CurrencyKind {
  return currency === 'AZURE' ? 'azure' : 'credit'
}

/** tick -> 秒的纯展示折算。服务端只有 game tick 这一种时间量纲, 20 tick = 1 秒。 */
function ticksToSecondsText(ticks: number): string {
  return `${(ticks / 20).toFixed(1)}s`
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
}: {
  cardId: number
  quality: TarotQualityId | null
  scale: CardArtScale
  label: string
}): ReactElement {
  const sizeClass = CARD_SCALE_CLASS[scale]

  if (quality === null) {
    return (
      <span className={`relative block ${sizeClass}`} role="img" aria-label={`${label} (未持有)`}>
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
    <span className={`relative block ${sizeClass}`} role="img" aria-label={label}>
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

export function TarotPanel(): ReactElement {
  const query = useMockAction('job.tarot.state', EMPTY_PAYLOAD)

  const [filter, setFilter] = useState<'all' | TarotQualityId>('all')
  const [selectedCardId, setSelectedCardId] = useState<number | null>(null)
  const [packKind, setPackKind] = useState<TarotPackKind>('common')
  const [packCount, setPackCount] = useState(1)
  const [purchasing, setPurchasing] = useState(false)
  const [purchaseError, setPurchaseError] = useState<Error | null>(null)
  const [lastPurchase, setLastPurchase] = useState<TarotBuyPackResult | null>(null)

  const data = query.status === 'ready' ? query.data : null

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

  /** 一行牌里持有的最高品质 (下标越大品质越高); 一张都没有返回 null。 */
  function topOwnedQuality(ownedByQuality: readonly number[]): TarotQualityId | null {
    for (let index = qualityOrder.length - 1; index >= 0; index -= 1) {
      const row = qualityOrder[index]
      if (row !== undefined && (ownedByQuality[index] ?? 0) > 0) {
        return row.qualityId
      }
    }
    return null
  }

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

  async function handleBuyPack(): Promise<void> {
    setPurchasing(true)
    setPurchaseError(null)
    try {
      const result = await callMock('job.tarot.buyPack', { kind: packKind, count: effectiveCount })
      setLastPurchase(result)
      setPackCount(1)
      query.reload()
    } catch (error) {
      setPurchaseError(toError(error))
    } finally {
      setPurchasing(false)
    }
  }

  // 筛选按"持有该品质的实体牌至少一张"判, 不是按"这张牌是该品质" —— 牌与品质是多对多。
  const filterQualityIndex =
    filter === 'all' ? -1 : qualityOrder.findIndex((quality) => quality.qualityId === filter)
  const filteredDeck =
    filterQualityIndex < 0
      ? data.deck
      : data.deck.filter((card) => (card.ownedByQuality[filterQualityIndex] ?? 0) > 0)
  const foundSelectedCard = data.deck.find((card) => card.cardId === selectedCardId)
  const selectedCard = foundSelectedCard === undefined ? null : foundSelectedCard

  return (
    <div className="flex flex-col gap-4">
      <Panel title="塔罗师">
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-3 gap-4">
            <Stat label="职业等级" value={`Lv.${String(data.level)}`} />
            <Stat
              label="塔罗碎片"
              value={String(data.shards)}
              hint={`${String(data.shardExchangeCost)} 张可换一张指定 SSR`}
            />
            <Stat
              label="今日已获取卡包"
              value={`${String(data.packsBoughtToday)} / ${String(data.packDailyLimit)}`}
              hint="高级包派生出的免费包同样占额度"
            />
          </div>
          <p className="text-muted-foreground text-xs">
            碎片只统计主背包与副手 (末影箱不计); 兑换与合成要在游戏里做, 面板不提供入口。开包重复牌返{' '}
            {data.duplicateShardRefund} 张碎片
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
            <Button
              disabled={dailyExhausted || selectedPack === undefined}
              loading={purchasing}
              onClick={() => {
                void handleBuyPack()
              }}
              variant="brand"
            >
              购买卡包
            </Button>
          </div>
          <p className="text-muted-foreground text-xs">
            闪耀包收青辉石, 另两种收信用点; 买到的是卡包物品, 开包请在游戏里右键 (背包满时卡包会掉在脚下)
          </p>
          {purchaseError === null ? null : (
            <FeedbackAlert message={callErrorText(purchaseError)} tone="danger" />
          )}

          {lastPurchase === null ? (
            <EmptyBlock hint="购买回执会显示在这里" title="本次尚未购买卡包" />
          ) : (
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

      <Panel title="品质与冷却">
        <div className="flex flex-col gap-3">
          <DataTable
            columns={[
              {
                header: '品质',
                key: 'quality',
                render: (row) => (
                  <Tag tone={QUALITY_TONE[row.qualityId]}>{nameOf(row.nameKey)}</Tag>
                ),
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
            <Stat label="公共 CD" value={ticksToSecondsText(data.cooldownTicks.gcd)} />
            <Stat label="功能牌 CD" value={ticksToSecondsText(data.cooldownTicks.utility)} />
            <Stat label="增益牌 CD" value={ticksToSecondsText(data.cooldownTicks.buff)} />
            <Stat label="战斗牌 CD" value={ticksToSecondsText(data.cooldownTicks.combat)} />
          </div>
          <p className="text-muted-foreground text-xs">
            这四个是满 CD 时长, 不是剩余时间 —— 服务端没有"还剩多久"的只读入口, 剩余冷却只能在游戏里看
          </p>
          {data.cardDataLoaded ? null : (
            <Surface tone="warning">
              <p className="text-foreground text-sm">
                牌效数据尚未加载完 (datapack 重载中或失败), 下方牌组的冷却分类暂时读不到
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
          <div className="flex flex-wrap gap-3">
            {filteredDeck.map((card) => {
              const cardName = nameOf(card.nameKey)
              const top = topOwnedQuality(card.ownedByQuality)
              const selected = card.cardId === selectedCardId
              return (
                <button
                  className={`flex flex-col items-center gap-1 rounded-lg border p-2 transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                    selected ? 'border-brand bg-brand/12' : 'border-transparent hover:bg-accent'
                  }`}
                  key={card.cardId}
                  onClick={() => {
                    setSelectedCardId(selected ? null : card.cardId)
                  }}
                  type="button"
                >
                  <TarotCardArt cardId={card.cardId} label={cardName} quality={top} scale={2} />
                  <div className="flex items-center gap-1">
                    {card.owned > 0 ? <Tag size="sm">x{card.owned}</Tag> : null}
                    {card.cooldownCategory === null ? null : (
                      <Tag size="sm" tone="neutral">
                        {COOLDOWN_CATEGORY_LABEL[card.cooldownCategory]}
                      </Tag>
                    )}
                  </div>
                  <span className="text-muted-foreground text-xs">{cardName}</span>
                </button>
              )
            })}
          </div>
        )}
      </Panel>

      {selectedCard === null ? null : (
        <Panel title="牌面详情">
          <div className="flex items-start gap-4">
            <TarotCardArt
              cardId={selectedCard.cardId}
              label={nameOf(selectedCard.nameKey)}
              quality={topOwnedQuality(selectedCard.ownedByQuality)}
              scale={3}
            />
            <div className="flex flex-col gap-2">
              <h3 className="font-medium text-base text-foreground">{nameOf(selectedCard.nameKey)}</h3>
              <div className="flex flex-wrap items-center gap-2">
                {qualityOrder.map((quality, index) => {
                  const held = selectedCard.ownedByQuality[index] ?? 0
                  return (
                    <Tag
                      key={quality.qualityId}
                      tone={held > 0 ? QUALITY_TONE[quality.qualityId] : 'neutral'}
                    >
                      {nameOf(quality.nameKey)} x{held}
                    </Tag>
                  )
                })}
              </div>
              <span className="text-muted-foreground text-sm">
                属于我的牌 {selectedCard.owned} 张 (只有这些打得出来)
              </span>
              <span className="text-muted-foreground text-sm">
                背包里同名牌共 {selectedCard.inInventory} 张 (含别人绑定的)
              </span>
              <span className="text-muted-foreground text-sm">
                {selectedCard.collected
                  ? '至少一个品质已收集过 (含放进箱子未拿出的牌): 该品质再开包会转成碎片, 未收集的品质仍可能开出真牌'
                  : '尚未收集过任何品质: 开包仍可能开出真牌'}
              </span>
              <span className="text-muted-foreground text-sm">
                {selectedCard.cooldownCategory === null
                  ? '冷却分类读取失败 (牌效数据未加载)'
                  : `冷却分类: ${COOLDOWN_CATEGORY_LABEL[selectedCard.cooldownCategory]}牌`}
              </span>
              <span className="text-muted-foreground text-sm">
                {selectedCard.shinyCooldownTicks === null
                  ? '闪耀牌 CD 读取失败 (牌效数据未加载)'
                  : `闪耀牌单独 CD: ${ticksToSecondsText(selectedCard.shinyCooldownTicks)}`}
              </span>
            </div>
          </div>
        </Panel>
      )}
    </div>
  )
}
