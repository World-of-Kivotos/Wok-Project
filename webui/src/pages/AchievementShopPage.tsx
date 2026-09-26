import { CopyIcon, PackageOpenIcon, PlusIcon, XIcon } from 'lucide-react'
import type { CSSProperties, ReactElement } from 'react'
import { useEffect, useState } from 'react'
import {
  Button,
  EmptyBlock,
  ErrorBlock,
  FeedbackAlert,
  ItemIcon,
  LoadingBlock,
  Panel,
  Stat,
  Surface,
  TabBar,
  Tag,
  TextInput,
  Toggle,
  formatAmount,
} from '@/components/kit'
import { callErrorText, customTitleViolationText } from '@/lib/errorText'
import { useItemDisplayNames, useItemNames } from '@/lib/i18n'
import { invalidateAll } from '@/lib/refresh'
import {
  INGAME_DEFAULT_COLOR,
  customTitleSegments,
  ingameShadowColor,
  parseHexColor,
  segmentKeys,
  segmentString,
  segmentsText,
} from '@/lib/title'
import type {
  AchievementClaimRewardsResult,
  AchievementGoods,
  AchievementPendingReward,
  AchievementPointShopBuyResult,
  AchievementPointShopResult,
  TextSegment,
  TierId,
  TitleCustomDraftPayload,
  TitleCustomPreviewResult,
  TitleCustomState,
  TitleListResult,
  TitlePaletteTier,
  TitleRow,
} from '@/lib/types'
import { callMock, useMockAction } from '@/mock'

/**
 * 成就点商店页 (Achievement_System_DesignSpec 第八章, Title_System_DesignSpec 第七章、13.8)。
 *
 * 顶部是成就点余额、累计获得与"待领取"栏; 下面两个页签: "成就点商店" (商品网格, 目前一件都没有上架, 画空态)
 * 与 "我的称号" (每张卡片按游戏里的样子预览"称号 + 玩家名", 赞助玩家另有专属称号编辑卡片)。
 *
 * 两种文字颜色刻意分开用:
 *   - "游戏内效果"预览条一律深底, 字的颜色取服务端下发的原值 (称号在服务端已按游戏里的样子逐字上色) ——
 *     那是聊天框、Tab 列表里真实的样子, 亮色档下也不换;
 *   - 页面本身的装饰 (档位名、卡片描边) 走 CSS 变量 --tier-*, 亮色档用加深版, 保证白底上看得清。
 */

type ActionFeedback = { tone: 'success' | 'danger' | 'warning' | 'info'; message: string }

type TabId = 'shop' | 'titles'

const EMPTY_PAYLOAD: Record<string, never> = {}

const TABS: readonly { id: TabId; label: string }[] = [
  { id: 'shop', label: '成就点商店' },
  { id: 'titles', label: '我的称号' },
]

const TIER_LABEL: Readonly<Record<TierId, string>> = {
  bronze: '铜',
  silver: '银',
  gold: '金',
  platinum: '白金',
  diamond: '钻石',
  master: '大师',
  legend: '传说',
}

/** 定义了 -from / -via / -to 三个色标变量的档位 (styles/index.css); 其余档只有单值变量。 */
const GRADIENT_TIERS: ReadonlySet<TierId> = new Set<TierId>(['platinum', 'diamond', 'master', 'legend'])

/** 专属称号草稿的服务端校验防抖。敲字时不必每个字都打一次服务端, 停手后很快就能看到权威结论。 */
const PREVIEW_DEBOUNCE_MS = 400

/** 没有专属称号记录时编辑器的起始草稿。 */
const DEFAULT_DRAFT: TitleCustomDraftPayload = { text: '', colors: ['#FFD23F'], bold: false }

function toError(value: unknown): Error {
  return value instanceof Error ? value : new Error(String(value))
}

function formatTime(epochMs: number): string {
  return new Date(epochMs).toLocaleString('zh-CN', { hour12: false })
}

/** 档位名的文字样式: 渐变档用三色标渐变字, 单色档用单值变量 (两者在亮色档都自动换成加深版)。 */
function tierTextStyle(tier: TierId): CSSProperties {
  if (!GRADIENT_TIERS.has(tier)) {
    return { color: `var(--tier-${tier})` }
  }
  return {
    backgroundImage: `linear-gradient(90deg, var(--tier-${tier}-from), var(--tier-${tier}-via), var(--tier-${tier}-to))`,
    WebkitBackgroundClip: 'text',
    backgroundClip: 'text',
    color: 'transparent',
  }
}

/** 称号卡片顶部那条档位色带的底色 (渐变档画三色标渐变)。 */
function tierBarStyle(tier: TierId): CSSProperties {
  return GRADIENT_TIERS.has(tier)
    ? {
        backgroundImage: `linear-gradient(90deg, var(--tier-${tier}-from), var(--tier-${tier}-via), var(--tier-${tier}-to))`,
      }
    : { backgroundColor: `var(--tier-${tier})` }
}

function TierLabel({ tier }: { tier: TierId }): ReactElement {
  return (
    <span className="font-semibold text-xs" style={tierTextStyle(tier)}>
      {TIER_LABEL[tier]}
    </span>
  )
}

// ============================================================
// 游戏内文字
// ============================================================

/** 按服务端下发的片段逐段画字: 颜色、粗体与原版文字阴影 (前景色每通道除以 4) 都照游戏里来。 */
function GameText({
  segments,
  names,
}: {
  segments: readonly TextSegment[]
  names: Readonly<Record<string, string>>
}): ReactElement {
  return (
    <>
      {segments.map((segment, index) => (
        <span
          // 片段没有身份, 顺序即身份 (同一串文字里两个"星"字的片段完全相同)。
          key={index}
          style={{
            color: segment.color ?? INGAME_DEFAULT_COLOR,
            fontWeight: segment.bold ? 700 : 400,
            textShadow: `0.08em 0.08em 0 ${ingameShadowColor(segment.color)}`,
          }}
        >
          {segmentString(segment, names)}
        </span>
      ))}
    </>
  )
}

/**
 * "称号 + 玩家名"的游戏内效果, 与聊天、Tab 列表里的前缀完全一致: 徽记后跟一个空格再接名字 (TitleRenderer 的 prefix)。
 * 不佩戴时只有名字。未拥有的称号整条压灰。
 */
function IngamePreview({
  badge,
  playerName,
  names,
  muted = false,
}: {
  badge: readonly TextSegment[] | null
  playerName: string
  names: Readonly<Record<string, string>>
  muted?: boolean | undefined
}): ReactElement {
  const label = `${badge === null ? '' : `${segmentsText(badge, names)} `}${playerName}`
  return (
    <div
      aria-label={`游戏内效果: ${label}`}
      className={`flex min-h-10 items-center overflow-x-auto rounded-md bg-(--ingame-surface) px-3 py-2 text-sm ${
        muted ? 'opacity-55 grayscale' : ''
      }`}
    >
      {/*
        whitespace-pre: 徽记与名字之间那个空格、以及专属称号文字里玩家自己写的空格, 游戏里都原样占位;
        按 HTML 默认的空白折叠画, 前缀就会和名字粘在一起。
      */}
      <span className="whitespace-pre">
        {badge === null ? null : <GameText names={names} segments={[...badge, { t: ' ', bold: false }]} />}
        <GameText names={names} segments={[{ t: playerName, bold: false }]} />
      </span>
    </div>
  )
}

/** 页面正文里的一小段游戏内文字 (称号徽记): 深底小块, 与预览条同一套取色。 */
function IngameChip({
  segments,
  names,
}: {
  segments: readonly TextSegment[]
  names: Readonly<Record<string, string>>
}): ReactElement {
  return (
    <span className="inline-flex items-center whitespace-pre rounded-sm bg-(--ingame-surface) px-1.5 py-0.5 text-xs">
      <GameText names={names} segments={segments} />
    </span>
  )
}

// ============================================================
// 回执文案
// ============================================================

function claimFeedback(result: AchievementClaimRewardsResult): ActionFeedback {
  const titles = result.titles.length === 0 ? '' : `, 另得称号 ${String(result.titles.length)} 个 (在"我的称号"里佩戴)`
  return {
    tone: 'success',
    message: `已领取 ${String(result.claimed.length)} 条奖励, 获得 ${formatAmount(result.points)} 成就点${titles}; 当前余额 ${formatAmount(result.balance)}`,
  }
}

function buyFeedback(result: AchievementPointShopBuyResult): ActionFeedback {
  const delivered = result.type === 'title' ? '称号已发放' : '物品已放进背包 (绑定, 不能上架市场)'
  return {
    tone: 'success',
    message: `兑换成功: 花费 ${formatAmount(result.price)} 成就点, ${delivered}; 剩余 ${formatAmount(result.balance)} 成就点`,
  }
}

// ============================================================
// 顶部: 成就点与待领取
// ============================================================

function PendingRow({
  reward,
  names,
  pendingKey,
  onClaim,
}: {
  reward: AchievementPendingReward
  names: Readonly<Record<string, string>>
  pendingKey: string | null
  onClaim: () => void
}): ReactElement {
  const key = `claim:${reward.advancementId}`
  const title = reward.name === null ? reward.advancementId : segmentsText(reward.name, names)
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-t py-2 first:border-t-0">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <TierLabel tier={reward.tier} />
        <span className="truncate font-medium text-foreground text-sm">{title}</span>
        <span className="text-muted-foreground text-xs">{formatTime(reward.earnedAt)}</span>
      </div>
      <div className="flex items-center gap-2">
        {reward.points > 0 ? <Tag tone="brand">+{formatAmount(reward.points)} 成就点</Tag> : null}
        {reward.titleBadge === null ? null : (
          <span className="flex items-center gap-1 text-muted-foreground text-xs">
            称号 <IngameChip names={names} segments={reward.titleBadge} />
          </span>
        )}
        <Button
          disabled={pendingKey !== null && pendingKey !== key}
          loading={pendingKey === key}
          onClick={onClaim}
          size="sm"
          variant="outline"
        >
          领取
        </Button>
      </div>
    </div>
  )
}

function PointsPanel({
  data,
  names,
  pendingKey,
  onClaim,
}: {
  data: AchievementPointShopResult
  names: Readonly<Record<string, string>>
  pendingKey: string | null
  onClaim: (advancementIds: string[] | 'all') => void
}): ReactElement {
  return (
    <Panel
      description="解锁成就获得成就点, 只能在成就点商店里兑换; 不能交易, 也不能换成信用点。"
      title="成就点"
    >
      <div className="grid gap-4 sm:grid-cols-3">
        <Stat label="成就点余额" value={formatAmount(data.balance)} />
        <Stat label="累计获得" value={formatAmount(data.lifetime)} />
        <Stat label="待领取奖励" value={`${String(data.pending.length)} 条`} />
      </div>
      {data.pending.length === 0 ? null : (
        <Surface className="mt-4 flex flex-col" tone="brand">
          <div className="flex items-center justify-between gap-3 pb-2">
            <h3 className="font-medium text-foreground text-sm">待领取</h3>
            <Button
              disabled={pendingKey !== null && pendingKey !== 'claim:all'}
              loading={pendingKey === 'claim:all'}
              onClick={() => {
                onClaim('all')
              }}
              size="sm"
              variant="brand"
            >
              全部领取
            </Button>
          </div>
          {data.pending.map((reward) => (
            <PendingRow
              key={reward.advancementId}
              names={names}
              onClaim={() => {
                onClaim([reward.advancementId])
              }}
              pendingKey={pendingKey}
              reward={reward}
            />
          ))}
        </Surface>
      )}
    </Panel>
  )
}

// ============================================================
// 页签一: 成就点商店
// ============================================================

/** 兑换按钮为什么是灰的; 能兑换时返回 null。 */
function buyBlocker(goods: AchievementGoods): string | null {
  if (goods.title?.owned === true) {
    return '已经拥有这个称号'
  }
  if (goods.remaining === 0) {
    return '已达每人限购'
  }
  if (goods.requirement !== null && !goods.requirement.met) {
    return '还没有获得所需的成就'
  }
  if (!goods.affordable) {
    return '成就点不足'
  }
  return null
}

function requirementText(goods: AchievementGoods, names: Readonly<Record<string, string>>): string | null {
  const requirement = goods.requirement
  if (requirement === null) {
    return null
  }
  const name = requirement.name === null ? null : segmentsText(requirement.name, names)
  if (requirement.met) {
    return `已获得成就「${name ?? requirement.advancementId}」`
  }
  // 隐藏成就在获得之前服务端就不发名字, 这里也只说"一项隐藏成就", 不在成就点商店里剧透。
  if (requirement.hidden) {
    return '需要先获得一项隐藏成就'
  }
  return `需要先获得成就「${name ?? requirement.advancementId}」`
}

function GoodsCard({
  goods,
  itemName,
  names,
  pendingKey,
  onBuy,
}: {
  goods: AchievementGoods
  itemName: string | null
  names: Readonly<Record<string, string>>
  pendingKey: string | null
  onBuy: () => void
}): ReactElement {
  const key = `buy:${goods.goodsId}`
  const blocker = buyBlocker(goods)
  const requirement = requirementText(goods, names)
  return (
    <Surface className="flex h-full flex-col gap-3">
      <div className="flex items-start gap-3">
        <div className="flex size-14 shrink-0 items-center justify-center rounded-md border bg-muted/40">
          {goods.item === null ? (
            <ItemIcon itemId="minecraft:name_tag" label="称号" scale={2} />
          ) : (
            <ItemIcon
              customModelData={goods.item.customModelData}
              itemId={goods.item.itemId}
              label={itemName ?? goods.item.itemId}
              scale={2}
            />
          )}
        </div>
        <div className="flex min-w-0 flex-col gap-1">
          {goods.title === null || goods.title.badge === null ? (
            <h3 className="truncate font-medium text-foreground text-sm">
              {itemName ?? goods.goodsId}
              {goods.item !== null && goods.item.count > 1 ? ` x${String(goods.item.count)}` : ''}
            </h3>
          ) : (
            <div className="flex items-center gap-2">
              <span className="text-muted-foreground text-xs">称号</span>
              <IngameChip names={names} segments={goods.title.badge} />
            </div>
          )}
          <p className="text-muted-foreground text-xs">
            {goods.limit === null
              ? '不限购'
              : `每人限购 ${String(goods.limit)}, 已兑换 ${String(goods.purchased)}`}
          </p>
          {requirement === null ? null : (
            <p className={`text-xs ${goods.requirement?.met === true ? 'text-success' : 'text-warning'}`}>
              {requirement}
            </p>
          )}
        </div>
      </div>
      <div className="mt-auto flex items-center justify-between gap-3">
        <div className="flex flex-col">
          <span className="font-medium text-foreground text-sm tabular-nums">
            {formatAmount(goods.price)} 成就点
          </span>
          {blocker === null ? null : <span className="text-muted-foreground text-xs">{blocker}</span>}
        </div>
        <Button
          disabled={blocker !== null || (pendingKey !== null && pendingKey !== key)}
          loading={pendingKey === key}
          onClick={onBuy}
          size="sm"
          variant="brand"
        >
          兑换
        </Button>
      </div>
    </Surface>
  )
}

function ShopTab({
  data,
  names,
  pendingKey,
  onBuy,
}: {
  data: AchievementPointShopResult
  names: Readonly<Record<string, string>>
  pendingKey: string | null
  onBuy: (goodsId: string) => void
}): ReactElement {
  const items = data.goods.flatMap((goods) => (goods.item === null ? [] : [goods.item]))
  const displayName = useItemDisplayNames(items)
  if (data.goods.length === 0) {
    return (
      <Panel title="成就点商店">
        <EmptyBlock
          hint="装饰物、特殊小道具和称号正在准备中。先把成就点攒着, 上架后就能在这里兑换。"
          icon={<PackageOpenIcon aria-hidden="true" />}
          title="商品即将上架"
        />
      </Panel>
    )
  }
  return (
    <Panel
      description="兑换出的物品绑定到你本人, 不能在市场上架。"
      title="成就点商店"
    >
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        {data.goods.map((goods) => (
          <GoodsCard
            goods={goods}
            itemName={goods.item === null ? null : displayName(goods.item)}
            key={goods.goodsId}
            names={names}
            onBuy={() => {
              onBuy(goods.goodsId)
            }}
            pendingKey={pendingKey}
          />
        ))}
      </div>
    </Panel>
  )
}

// ============================================================
// 页签二: 我的称号
// ============================================================

function TitleCard({
  row,
  playerName,
  names,
  shopPrice,
  pendingKey,
  onEquip,
}: {
  row: TitleRow
  playerName: string
  names: Readonly<Record<string, string>>
  /** 这个称号若在成就点商店上架, 它的价格; 没上架为 null。 */
  shopPrice: number | null
  pendingKey: string | null
  onEquip: () => void
}): ReactElement {
  const key = `equip:${row.titleId}`
  const hint = row.description === null ? null : segmentsText(row.description, names)
  return (
    <Surface className={`flex h-full flex-col gap-2 ${row.equipped ? 'ring-2 ring-brand' : ''}`}>
      <span aria-hidden="true" className="h-1 rounded-full" style={tierBarStyle(row.rarity)} />
      <div className="flex items-center justify-between gap-2">
        <TierLabel tier={row.rarity} />
        {row.equipped ? (
          <Tag size="sm" tone="brand">
            佩戴中
          </Tag>
        ) : row.owned ? (
          <Tag size="sm" tone="success">
            已拥有
          </Tag>
        ) : (
          <Tag size="sm">未拥有</Tag>
        )}
      </div>
      <IngamePreview badge={row.badge} muted={!row.owned} names={names} playerName={playerName} />
      {row.owned ? null : (
        <p className="text-muted-foreground text-xs">
          {hint ?? '获取途径未公开'}
          {shopPrice === null ? '' : `; 或在成就点商店以 ${formatAmount(shopPrice)} 成就点兑换`}
        </p>
      )}
      <div className="mt-auto flex justify-end">
        <Button
          disabled={!row.owned || row.equipped || (pendingKey !== null && pendingKey !== key)}
          loading={pendingKey === key}
          onClick={onEquip}
          size="sm"
          variant={row.equipped ? 'secondary' : 'outline'}
        >
          {row.equipped ? '佩戴中' : '佩戴'}
        </Button>
      </div>
    </Surface>
  )
}

type PreviewState =
  | { status: 'idle' }
  | { status: 'checking'; key: string }
  | { status: 'done'; key: string; result: TitleCustomPreviewResult }
  | { status: 'error'; key: string; message: string }

/**
 * 把命令参数复制到剪贴板。MCEF 里的异步剪贴板接口在非焦点态下可能被拒, 故失败时退回选区复制;
 * 两条路都走不通就返回 false, 由调用方提示玩家手动选中 (参数本身始终显示在页面上)。
 */
async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    const area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', '')
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    const copied = document.execCommand('copy')
    document.body.removeChild(area)
    return copied
  }
}

/**
 * 赞助玩家的专属称号卡片 (Title_System_DesignSpec 13.8): 当前的专属称号、赞助到期时间, 以及一个边打字边预览的
 * 编辑器。预览先在本地按服务端同一份渐变插值画出来, 停手后再经 title.customPreview 拿权威校验结果。
 *
 * 提交跟随服务端开关 selfServiceEnabled: 打开时直接提交; 关闭 (当前默认, 由管理员代设置) 时只给"复制参数发给管理员",
 * 复制的就是服务端回的那条 /mtitle custom admin set 命令。
 */
function CustomTitleCard({
  custom,
  palette,
  playerName,
  names,
  pendingKey,
  onEquip,
  onSubmit,
  onFeedback,
}: {
  custom: TitleCustomState
  palette: readonly TitlePaletteTier[]
  playerName: string
  names: Readonly<Record<string, string>>
  pendingKey: string | null
  onEquip: () => void
  onSubmit: (draft: TitleCustomDraftPayload) => void
  onFeedback: (feedback: ActionFeedback) => void
}): ReactElement {
  const record = custom.record
  const sponsor = custom.sponsor
  const sponsorActive = sponsor?.active === true
  const [draft, setDraft] = useState<TitleCustomDraftPayload>(() =>
    record === null ? DEFAULT_DRAFT : { text: record.text, colors: [...record.colors], bold: record.bold },
  )
  const [preview, setPreview] = useState<PreviewState>({ status: 'idle' })
  const draftKey = JSON.stringify(draft)

  useEffect(() => {
    if (!sponsorActive) {
      return
    }
    let cancelled = false
    setPreview({ status: 'checking', key: draftKey })
    const timer = window.setTimeout(() => {
      callMock('title.customPreview', JSON.parse(draftKey) as TitleCustomDraftPayload)
        .then((result) => {
          if (!cancelled) {
            setPreview({ status: 'done', key: draftKey, result })
          }
        })
        .catch((error: unknown) => {
          if (!cancelled) {
            setPreview({ status: 'error', key: draftKey, message: callErrorText(toError(error)) })
          }
        })
    }, PREVIEW_DEBOUNCE_MS)
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [draftKey, sponsorActive])

  const localSegments = customTitleSegments(draft.text, draft.colors, draft.bold)
  const verdict = preview.status === 'done' && preview.key === draftKey ? preview.result : null
  const valid = verdict?.status === 'VALID'
  const locked = record?.locked === true
  const equipKey = `equip:${custom.titleId}`
  const submitKey = 'custom:set'

  function updateColor(index: number, value: string): void {
    setDraft({ ...draft, colors: draft.colors.map((color, at) => (at === index ? value : color)) })
  }

  async function handleCopy(command: string): Promise<void> {
    const copied = await copyText(command)
    onFeedback(
      copied
        ? { tone: 'success', message: '已复制, 把这条命令发给管理员即可' }
        : { tone: 'warning', message: '复制失败, 请手动选中下面的命令复制' },
    )
  }

  return (
    <Panel
      actions={
        sponsor === null ? null : sponsor.permanent ? (
          <Tag tone="brand">永久赞助</Tag>
        ) : sponsorActive ? (
          <Tag tone="brand">赞助到期 {formatTime(sponsor.expiresAt ?? 0)}</Tag>
        ) : (
          <Tag tone="danger">赞助已过期 {formatTime(sponsor.expiresAt ?? 0)}</Tag>
        )
      }
      description="赞助玩家专属: 文字、外框与颜色都按你的意思定, 原样显示, 不加方括号。"
      title="专属称号"
    >
      <div className="flex flex-col gap-4">
        {record === null ? (
          <p className="text-muted-foreground text-sm">还没有设置专属称号。</p>
        ) : (
          <div className="flex flex-col gap-2">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h3 className="font-medium text-foreground text-sm">当前的专属称号</h3>
              <div className="flex items-center gap-2">
                {locked ? <Tag tone="warning">已被管理员锁定</Tag> : null}
                <Button
                  disabled={!custom.owned || custom.equipped || (pendingKey !== null && pendingKey !== equipKey)}
                  loading={pendingKey === equipKey}
                  onClick={onEquip}
                  size="sm"
                  variant={custom.equipped ? 'secondary' : 'outline'}
                >
                  {custom.equipped ? '佩戴中' : '佩戴'}
                </Button>
              </div>
            </div>
            <IngamePreview badge={record.badge} muted={!custom.owned} names={names} playerName={playerName} />
          </div>
        )}

        {!sponsorActive ? (
          <p className="text-muted-foreground text-sm">
            赞助资格失效期间专属称号不能佩戴, 也不能修改; 记录会保留, 续期后自动恢复。
          </p>
        ) : (
          <div className="flex flex-col gap-3">
            <h3 className="font-medium text-foreground text-sm">编辑与预览</h3>
            <TextInput
              maxLength={32}
              onChange={(text) => {
                setDraft({ ...draft, text })
              }}
              placeholder="称号文字, 外框符号自己写, 例如【星海旅人】"
              value={draft.text}
            />
            <div className="flex flex-col gap-2">
              {draft.colors.map((color, index) => (
                <div className="flex items-center gap-2" key={index}>
                  <span
                    aria-hidden="true"
                    className="size-7 shrink-0 rounded-md border"
                    style={{ backgroundColor: parseHexColor(color) === null ? 'transparent' : color }}
                  />
                  <TextInput
                    className="max-w-40 font-mono"
                    invalid={parseHexColor(color) === null}
                    maxLength={7}
                    onChange={(value) => {
                      updateColor(index, value)
                    }}
                    placeholder="#RRGGBB"
                    value={color}
                  />
                  {draft.colors.length > 1 ? (
                    <Button
                      aria-label={`删除第 ${String(index + 1)} 个色标`}
                      onClick={() => {
                        setDraft({ ...draft, colors: draft.colors.filter((_, at) => at !== index) })
                      }}
                      size="icon-sm"
                      variant="ghost"
                    >
                      <XIcon />
                    </Button>
                  ) : null}
                </div>
              ))}
              <div className="flex flex-wrap items-center gap-3">
                {draft.colors.length < 3 ? (
                  <Button
                    onClick={() => {
                      setDraft({ ...draft, colors: [...draft.colors, draft.colors[draft.colors.length - 1] ?? '#FFFFFF'] })
                    }}
                    size="sm"
                    variant="outline"
                  >
                    <PlusIcon aria-hidden="true" />
                    添加色标 (渐变)
                  </Button>
                ) : null}
                <Toggle
                  checked={draft.bold}
                  label="粗体"
                  onChange={(bold) => {
                    setDraft({ ...draft, bold })
                  }}
                />
              </div>
              <div className="flex flex-wrap items-center gap-1.5">
                <span className="text-muted-foreground text-xs">套用档位配色</span>
                {palette.map((tier) => (
                  <Button
                    key={tier.rarity}
                    onClick={() => {
                      setDraft({ ...draft, colors: [...tier.stops], bold: tier.bold })
                    }}
                    size="xs"
                    variant="outline"
                  >
                    <TierLabel tier={tier.rarity} />
                  </Button>
                ))}
              </div>
            </div>
            {localSegments === null ? (
              <p className="text-warning text-xs">颜色写法不对 (应为 #RRGGBB, 1~3 个), 暂时画不出预览。</p>
            ) : (
              <IngamePreview badge={localSegments} names={names} playerName={playerName} />
            )}
            <div aria-live="polite" className="flex flex-col gap-1 text-xs">
              {preview.status === 'idle' || preview.status === 'checking' || preview.key !== draftKey ? (
                <span className="text-muted-foreground">服务端校验中...</span>
              ) : preview.status === 'error' ? (
                <span className="text-destructive">{preview.message}</span>
              ) : preview.result.status === 'VALID' ? (
                <span className="text-success">服务端校验通过, 与上面的预览一致。</span>
              ) : (
                <ul className="flex list-disc flex-col gap-0.5 pl-4 text-destructive">
                  {preview.result.violations.map((violation, index) => (
                    <li key={index}>{customTitleViolationText(violation)}</li>
                  ))}
                </ul>
              )}
            </div>
            {custom.selfServiceEnabled ? (
              <div className="flex flex-wrap items-center justify-between gap-3">
                <span className="text-muted-foreground text-xs">
                  {custom.nextEditAt === 0 ? '现在即可修改' : `下次可修改时间: ${formatTime(custom.nextEditAt)}`}
                </span>
                <Button
                  disabled={!valid || locked || custom.nextEditAt !== 0 || (pendingKey !== null && pendingKey !== submitKey)}
                  loading={pendingKey === submitKey}
                  onClick={() => {
                    onSubmit(draft)
                  }}
                  size="sm"
                  variant="brand"
                >
                  提交
                </Button>
              </div>
            ) : (
              <div className="flex flex-col gap-2">
                <p className="text-muted-foreground text-xs">
                  专属称号目前由管理员代为设置: 预览满意后, 复制下面这条命令发给管理员。
                </p>
                <div className="flex flex-wrap items-center gap-2">
                  <code className="min-w-0 flex-1 select-all break-all rounded-md border bg-muted/40 px-2 py-1.5 font-mono text-xs">
                    {valid && verdict.adminCommand !== null ? verdict.adminCommand : '预览通过校验后显示命令'}
                  </code>
                  <Button
                    disabled={!valid || verdict.adminCommand === null}
                    onClick={() => {
                      if (verdict !== null && verdict.adminCommand !== null) {
                        void handleCopy(verdict.adminCommand)
                      }
                    }}
                    size="sm"
                    variant="brand"
                  >
                    <CopyIcon aria-hidden="true" />
                    复制参数发给管理员
                  </Button>
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </Panel>
  )
}

function TitlesTab({
  data,
  shop,
  playerName,
  names,
  pendingKey,
  onEquip,
  onSubmit,
  onFeedback,
}: {
  data: TitleListResult
  shop: AchievementPointShopResult | null
  playerName: string
  names: Readonly<Record<string, string>>
  pendingKey: string | null
  onEquip: (titleId: string | null) => void
  onSubmit: (draft: TitleCustomDraftPayload) => void
  onFeedback: (feedback: ActionFeedback) => void
}): ReactElement {
  const shopPrices = new Map<string, number>()
  for (const goods of shop?.goods ?? []) {
    if (goods.title !== null) {
      shopPrices.set(goods.title.titleId, goods.price)
    }
  }
  const bareKey = 'equip:none'
  return (
    <div className="flex flex-col gap-4">
      {data.custom.sponsor === null ? null : (
        <CustomTitleCard
          custom={data.custom}
          names={names}
          onEquip={() => {
            onEquip(data.custom.titleId)
          }}
          onFeedback={onFeedback}
          onSubmit={onSubmit}
          palette={data.palette}
          pendingKey={pendingKey}
          playerName={playerName}
        />
      )}
      <Panel description="同一时刻只佩戴一个, 显示在聊天、Tab 列表与头顶名牌上。" title="我的称号">
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          <Surface className={`flex h-full flex-col gap-2 ${data.equipped === null ? 'ring-2 ring-brand' : ''}`}>
            <div className="flex items-center justify-between gap-2">
              <span className="font-semibold text-muted-foreground text-xs">不佩戴</span>
              {data.equipped === null ? (
                <Tag size="sm" tone="brand">
                  当前
                </Tag>
              ) : null}
            </div>
            <IngamePreview badge={null} names={names} playerName={playerName} />
            <div className="mt-auto flex justify-end">
              <Button
                disabled={data.equipped === null || (pendingKey !== null && pendingKey !== bareKey)}
                loading={pendingKey === bareKey}
                onClick={() => {
                  onEquip(null)
                }}
                size="sm"
                variant="outline"
              >
                不佩戴
              </Button>
            </div>
          </Surface>
          {data.titles.map((row) => (
            <TitleCard
              key={row.titleId}
              names={names}
              onEquip={() => {
                onEquip(row.titleId)
              }}
              pendingKey={pendingKey}
              playerName={playerName}
              row={row}
              shopPrice={shopPrices.get(row.titleId) ?? null}
            />
          ))}
        </div>
      </Panel>
    </div>
  )
}

// ============================================================
// 页面
// ============================================================

export function AchievementShopPage(): ReactElement {
  const shop = useMockAction('achievement.pointShop', EMPTY_PAYLOAD)
  const titles = useMockAction('title.list', EMPTY_PAYLOAD)
  const profile = useMockAction('player.profile', EMPTY_PAYLOAD)
  const [tab, setTab] = useState<TabId>('shop')
  const [pendingKey, setPendingKey] = useState<string | null>(null)
  const [feedback, setFeedback] = useState<ActionFeedback | null>(null)

  const shopData = shop.status === 'ready' ? shop.data : null
  const titleData = titles.status === 'ready' ? titles.data : null
  const names = useItemNames(
    segmentKeys([
      ...(shopData?.pending ?? []).flatMap((reward) => [reward.name, reward.titleBadge]),
      ...(shopData?.goods ?? []).flatMap((goods) => [goods.title?.badge ?? null, goods.requirement?.name ?? null]),
      ...(titleData?.titles ?? []).flatMap((row) => [row.badge, row.description]),
    ]),
  )
  // 预览里的名字就是游戏里别人看到的那个名字; 还没取到时先占一个明确的占位, 不编一个名字出来。
  const playerName = profile.status === 'ready' ? profile.data.playerName : '...'

  /** 写操作的统一收口: 成功后全量作废 (余额、待领取、限购、持有称号都可能变), 失败显示稳定码对应的中文。 */
  async function runAction(key: string, action: () => Promise<ActionFeedback>): Promise<void> {
    setPendingKey(key)
    setFeedback(null)
    try {
      const result = await action()
      invalidateAll()
      setFeedback(result)
    } catch (error: unknown) {
      setFeedback({ tone: 'danger', message: callErrorText(toError(error)) })
    } finally {
      setPendingKey(null)
    }
  }

  function handleClaim(advancementIds: string[] | 'all'): void {
    const key = advancementIds === 'all' ? 'claim:all' : `claim:${advancementIds.join(',')}`
    void runAction(key, async () => claimFeedback(await callMock('achievement.claimRewards', { advancementIds })))
  }

  function handleBuy(goodsId: string): void {
    void runAction(`buy:${goodsId}`, async () => buyFeedback(await callMock('achievement.pointShopBuy', { goodsId })))
  }

  function handleEquip(titleId: string | null): void {
    void runAction(titleId === null ? 'equip:none' : `equip:${titleId}`, async () => {
      const result = await callMock('title.equip', { titleId })
      return {
        tone: 'success',
        message: result.equipped === null ? '已卸下称号' : '已佩戴, 聊天、Tab 列表与头顶名牌随即更新',
      }
    })
  }

  function handleSubmit(draft: TitleCustomDraftPayload): void {
    void runAction('custom:set', async () => {
      const result = await callMock('title.customSet', draft)
      return {
        tone: 'success',
        message: `专属称号已更新; 下次可修改时间: ${formatTime(result.nextEditAt)}`,
      }
    })
  }

  return (
    <section className="flex flex-col gap-4">
      {feedback === null ? null : (
        <FeedbackAlert
          message={feedback.message}
          onDismiss={() => {
            setFeedback(null)
          }}
          tone={feedback.tone}
        />
      )}

      {shop.status === 'loading' && shopData === null ? <LoadingBlock label="正在读取成就点" /> : null}
      {shop.status === 'error' ? <ErrorBlock message={callErrorText(shop.error)} onRetry={shop.reload} /> : null}
      {shopData === null ? null : (
        <PointsPanel
          data={shopData}
          names={names}
          onClaim={handleClaim}
          pendingKey={pendingKey}
        />
      )}

      <TabBar
        activeId={tab}
        onChange={(id) => {
          setTab(id === 'titles' ? 'titles' : 'shop')
        }}
        tabs={TABS}
      />

      {tab === 'shop' ? (
        shopData === null ? null : (
          <ShopTab data={shopData} names={names} onBuy={handleBuy} pendingKey={pendingKey} />
        )
      ) : titles.status === 'error' ? (
        <ErrorBlock message={callErrorText(titles.error)} onRetry={titles.reload} />
      ) : titleData === null ? (
        <LoadingBlock label="正在读取称号" />
      ) : (
        <TitlesTab
          data={titleData}
          names={names}
          onEquip={handleEquip}
          onFeedback={setFeedback}
          onSubmit={handleSubmit}
          pendingKey={pendingKey}
          playerName={playerName}
          shop={shopData}
        />
      )}
    </section>
  )
}
