import type { ReactElement } from 'react'
import { useState } from 'react'
import type { DropdownOption, Tone } from '@/components/kit'
import {
  Button,
  Currency,
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
import { callMock, useMockAction } from '../../../mock'
import type { PlannedBuyPackResult, PlannedTarotCard, PlannedTarotQuality } from '../../../mock'
import { formatCountdown, toError, useLiveNow } from './shared'

/**
 * 塔罗师面板 (接线清单 C14 job.tarot.state / C15 job.tarot.buyPack, 均为 PLANNED)。
 *
 * 依赖的假定契约 (webui/src/mock/planned.ts):
 *   - job.tarot.state   -> PlannedTarotStateResult (等级/碎片/22 张牌组/卡包价与限购)
 *   - job.tarot.buyPack -> PlannedBuyPackResult (开包结果, 会写回 mock 世界的牌组持有与钱包叠加层)
 *
 * 契约缺口 (报告给核销清单, 不在此处自造接口凑齐):
 *   - 无 equip/unequip 类 action: PlannedTarotCard.equipped 因此只能只读展示, 不能在本面板切换;
 *   - 无碎片兑换/合成类 action: 任务描述提到的"合成/碎片兑换"在 C14 checklist 备注为已知空缺
 *     ("战斗窗口聚合快照与 CD 只读 peek 都没有"), 碎片余额只做展示, 兑换入口留白不假装能点。
 *
 * 贴图直接引用 mod 资源 (src/main/resources/assets/miningdim/textures/item/tarot/), 不经 ItemIcon:
 * ItemIcon 的取图链只处理 "item/<单段id>.png" 这种单层路径, 而塔罗贴图落在 item/tarot/ 子目录下且
 * 卡面/品质边框是两张图叠加渲染, 均不满足 ItemIcon 的假设, 故本文件按 ItemIcon 同款 vite 挂载点
 * (vite.config.ts 的 /mc/ 前缀) 自行拼 URL。22 张牌到 00-21 序号的映射按国际通用大阿卡纳顺序
 * (愚者..世界) 靠牌名匹配, 不依赖 mock 的 cardId 具体格式 —— 后端真实契约的 cardId 大概率不是
 * "tarot_00" 这种写法, 牌名顺序则是文化常量, 更适合作为映射锚点。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}

const TAROT_TEXTURE_ROOT = `${import.meta.env.BASE_URL}mc/item/tarot/`

/** 22 张大阿卡纳的国际通用顺序, 与贴图文件 00.png-21.png 逐一对应, 不是本项目发明的排序。 */
const MAJOR_ARCANA_ORDER: readonly string[] = [
  '愚者',
  '魔术师',
  '女祭司',
  '女皇',
  '皇帝',
  '教皇',
  '恋人',
  '战车',
  '力量',
  '隐者',
  '命运之轮',
  '正义',
  '倒吊人',
  '死神',
  '节制',
  '恶魔',
  '高塔',
  '星星',
  '月亮',
  '太阳',
  '审判',
  '世界',
]

interface QualityMeta {
  readonly label: string
  readonly borderFile: string
  readonly tone: Tone
}

/** 品质 -> (R/SR/SSR/UR/闪耀 短标签, 边框资产文件名, 徽标语义色)。边框文件名逐字取自实际资产目录。 */
const QUALITY_META: Record<PlannedTarotQuality, QualityMeta> = {
  common: { label: 'R', borderFile: 'border_r.png', tone: 'neutral' },
  uncommon: { label: 'SR', borderFile: 'border_sr.png', tone: 'info' },
  rare: { label: 'SSR', borderFile: 'border_ssr.png', tone: 'success' },
  epic: { label: 'UR', borderFile: 'border_ur.png', tone: 'warning' },
  legendary: { label: '闪耀', borderFile: 'border_shiny.png', tone: 'danger' },
}

const QUALITY_FILTER_OPTIONS: readonly DropdownOption<'all' | PlannedTarotQuality>[] = [
  { value: 'all', label: '全部品质' },
  { value: 'common', label: 'R 普通' },
  { value: 'uncommon', label: 'SR 罕见' },
  { value: 'rare', label: 'SSR 稀有' },
  { value: 'epic', label: 'UR 史诗' },
  { value: 'legendary', label: '闪耀' },
]

type CardArtScale = 1 | 2 | 3

const CARD_SCALE_CLASS: Record<CardArtScale, string> = {
  1: 'h-16 w-16',
  2: 'h-32 w-32',
  3: 'h-48 w-48',
}

function cardFaceIndex(displayName: string): number | null {
  const index = MAJOR_ARCANA_ORDER.indexOf(displayName)
  return index < 0 ? null : index
}

/**
 * 卡面渲染: 未持有一律显示牌背 (不提前泄露品质), 已持有则叠加"正面 + 品质边框"两张同尺寸贴图。
 * 牌名对不上标准大阿卡纳表时退化为牌背并报错到控制台 —— 这是契约漂移信号, 不能悄悄吞掉。
 */
function TarotCardArt({
  card,
  scale,
  label,
}: {
  card: PlannedTarotCard
  scale: CardArtScale
  label: string
}): ReactElement {
  const sizeClass = CARD_SCALE_CLASS[scale]
  const owned = card.owned > 0
  const index = owned ? cardFaceIndex(card.displayName) : null

  if (!owned || index === null) {
    if (owned && index === null) {
      console.error('[tarot-panel] 牌名不在标准大阿卡纳表内, 退化为牌背:', card.displayName)
    }
    return (
      <span className={`relative block ${sizeClass}`} role="img" aria-label={owned ? label : `${label} (未持有)`}>
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

  const meta = QUALITY_META[card.quality]
  return (
    <span className={`relative block ${sizeClass}`} role="img" aria-label={label}>
      <img
        src={`${TAROT_TEXTURE_ROOT}${String(index).padStart(2, '0')}.png`}
        alt=""
        aria-hidden="true"
        className={`absolute inset-0 block ${sizeClass}`}
        style={{ imageRendering: 'pixelated' }}
      />
      <img
        src={`${TAROT_TEXTURE_ROOT}${meta.borderFile}`}
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
  const now = useLiveNow()

  const [filter, setFilter] = useState<'all' | PlannedTarotQuality>('all')
  const [selectedCardId, setSelectedCardId] = useState<string | null>(null)
  const [packCount, setPackCount] = useState(1)
  const [purchasing, setPurchasing] = useState(false)
  const [purchaseError, setPurchaseError] = useState<Error | null>(null)
  const [lastDraw, setLastDraw] = useState<PlannedBuyPackResult | null>(null)

  if (query.status === 'loading') {
    return <LoadingBlock label="正在读取塔罗牌组" />
  }
  if (query.status === 'error') {
    return <ErrorBlock message={query.error.message} onRetry={query.reload} />
  }

  const data = query.data
  const remainingToday = Math.max(0, data.packDailyLimit - data.packsBoughtToday)
  const stepperMax = Math.max(1, remainingToday)
  const previewCost = data.packPriceCredit * Math.min(packCount, stepperMax)

  async function handleBuyPack(): Promise<void> {
    setPurchasing(true)
    setPurchaseError(null)
    try {
      const result = await callMock('job.tarot.buyPack', { count: packCount })
      setLastDraw(result)
      setPackCount(1)
      query.reload()
    } catch (error) {
      setPurchaseError(toError(error))
    } finally {
      setPurchasing(false)
    }
  }

  const filteredDeck = data.deck.filter((card) => filter === 'all' || card.quality === filter)
  const foundSelectedCard = data.deck.find((card) => card.cardId === selectedCardId)
  const selectedCard = foundSelectedCard === undefined ? null : foundSelectedCard

  return (
    <div className="flex flex-col gap-4">
      <Panel title="塔罗师">
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-3 gap-4">
            <Stat label="职业等级" value={`Lv.${String(data.level)}`} />
            <Stat label="碎片" value={String(data.fragments)} />
          </div>
          <p className="text-muted-foreground text-xs">
            碎片兑换与卡组编入暂无对应服务端接口 (接线清单 C14), 本页仅作只读展示
          </p>
        </div>
      </Panel>

      <Panel title="卡包">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-end gap-4">
            <div className="flex flex-col gap-1">
              <span className="text-muted-foreground text-xs">购买数量 (今日剩余 {remainingToday} 包)</span>
              <NumberInput
                disabled={purchasing || remainingToday <= 0}
                max={stepperMax}
                min={1}
                onChange={setPackCount}
                value={Math.min(packCount, stepperMax)}
              />
            </div>
            <Stat label="预计花费" value={<Currency amount={previewCost} currency="credit" />} />
            <Button
              disabled={remainingToday <= 0}
              loading={purchasing}
              onClick={() => {
                void handleBuyPack()
              }}
              variant="brand"
            >
              购买卡包
            </Button>
          </div>
          {purchaseError === null ? null : <FeedbackAlert message={purchaseError.message} tone="danger" />}

          {lastDraw === null ? (
            <EmptyBlock hint="购买后本轮开出的牌会显示在这里" title="尚未开出卡包" />
          ) : (
            <Surface>
              <div className="flex flex-col gap-2">
                <p className="flex flex-wrap items-center gap-1 text-muted-foreground text-xs">
                  本次花费 <Currency amount={lastDraw.spentCredit} currency="credit" showIcon={false} size="sm" />{' '}
                  · 重复转碎片 {lastDraw.fragmentsGained}
                </p>
                <div className="flex flex-wrap gap-2">
                  {lastDraw.drawn.map((draw, index) => (
                    <Tag
                      key={`${draw.cardId}-${String(index)}`}
                      tone={draw.duplicate ? 'neutral' : QUALITY_META[draw.quality].tone}
                    >
                      {draw.displayName} · {QUALITY_META[draw.quality].label}
                      {draw.duplicate ? ' (重复)' : ' (新增)'}
                    </Tag>
                  ))}
                </div>
              </div>
            </Surface>
          )}
        </div>
      </Panel>

      <Panel
        actions={
          <Dropdown
            className="w-32"
            onChange={setFilter}
            options={QUALITY_FILTER_OPTIONS}
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
              const cooldownActive = card.cooldownUntil > now
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
                  <TarotCardArt card={card} label={card.displayName} scale={2} />
                  <div className="flex items-center gap-1">
                    {card.owned > 1 ? <Tag size="sm">x{card.owned}</Tag> : null}
                    {card.equipped ? (
                      <Tag size="sm" tone="brand">
                        已装备
                      </Tag>
                    ) : null}
                    {cooldownActive ? (
                      <Tag size="sm" tone="warning">
                        {formatCountdown(card.cooldownUntil, now)}
                      </Tag>
                    ) : null}
                  </div>
                  <span className="text-muted-foreground text-xs">{card.displayName}</span>
                </button>
              )
            })}
          </div>
        )}
      </Panel>

      {selectedCard === null ? null : (
        <Panel title="牌面详情">
          <div className="flex items-center gap-4">
            <TarotCardArt card={selectedCard} label={selectedCard.displayName} scale={3} />
            <div className="flex flex-col gap-2">
              <div className="flex items-center gap-2">
                <h3 className="font-medium text-base text-foreground">{selectedCard.displayName}</h3>
                <Tag tone={QUALITY_META[selectedCard.quality].tone}>
                  {QUALITY_META[selectedCard.quality].label}
                </Tag>
              </div>
              <span className="text-muted-foreground text-sm">
                持有 {selectedCard.owned} 张 · {selectedCard.equipped ? '已编入卡组 (只读)' : '未编入卡组'}
              </span>
              <span className="text-muted-foreground text-sm">
                {selectedCard.cooldownUntil > now
                  ? `冷却中, 剩余 ${formatCountdown(selectedCard.cooldownUntil, now)}`
                  : '当前无冷却'}
              </span>
            </div>
          </div>
        </Panel>
      )}
    </div>
  )
}
