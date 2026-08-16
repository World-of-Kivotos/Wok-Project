import type { ReactElement } from 'react'
import { useEffect, useId, useState } from 'react'
import type { ItemSlotGridEntry, Tone } from '@/components/kit'
import {
  Button,
  Currency,
  EmptyBlock,
  ErrorBlock,
  ItemIcon,
  ItemSlotGrid,
  LoadingBlock,
  NumberInput,
  Panel,
  Surface,
  Tag,
} from '@/components/kit'
import { isMockActive } from '../../lib/bridge'
import { callErrorText, errorCodeText } from '../../lib/errorText'
import { useItemDisplayNames } from '../../lib/i18n'
import type { PlayerInventoryItem } from '../../lib/types'
import { callMock, refreshInventoryMirror, useMockAction, useMockWorld } from '../../mock'

/**
 * 跳蚤市场 · 挂单。
 *
 * 全部依赖都是真契约 (W2 接线后本页不再有假定契约):
 *   player.inventory     经 mirror 读背包 (TabletShell 首屏预热, 本页兜底自补一次)
 *   market.tradable      按**槽位**判定这一格能不能挂 —— 入参是 slot 而非 itemId, 因为塔罗牌 220 张牌面
 *                        x 5 档品质共用一个 itemId, 品质只活在 NBT 里, 只给 itemId 服务端判不出来
 *   market.baseValue     基准价锚 V0, 用来给单价一个"贴着 V0 挂"的默认起点
 *   market.feePreview    手续费预览。与 market.place 实收同源 (同一 MarketFee 纯函数)
 *   market.place         提交挂单
 *
 * 手续费**不再由前端算**: 这里曾有一份逐字核对 MarketFee.java 的客户端镜像 (FEE_RATE/DEVIATION_K/
 * MIN_ANCHOR_VALUE 三个常量 + estimateListingFee), 它自己的注释就写着"须与服务端同步" —— 两份公式各自
 * 漂移的风险是真的。现在服务端有了同源接口, 镜像整段删除。**最终手续费仍以 market.place 回执的 listFee
 * 为准**: V0 可能在预览与提交之间被管理员改动。
 */

const INVENTORY_SLOT_COUNT = 36
const INVENTORY_COLUMNS = 9
/** 挂单单价上限, 纯前端可用性钳制 (远低于 Number.MAX_SAFE_INTEGER, 服务端本身不设上限)。 */
const MAX_UNIT_PRICE = 999_999_999

/**
 * 手续费预览的防抖窗口。步进器按住不放会连点很多下, 不防抖就是每下一次 IPC 往返。
 * 250ms 是"手停下来就出结果"的量级, 再长会让玩家觉得数字卡住了。
 */
const FEE_PREVIEW_DEBOUNCE_MS = 250

/**
 * 费率染色阈值。**纯展示口径**, 不是服务端常量的镜像 —— 早先这里写的是 FEE_RATE + 0.02 这类表达式,
 * 那要求前端持有一份 MarketConstants 的副本, 正是本页要消灭的东西。
 * 回执的 ratio 分母是玩家挂的总价, 可以 > 1 (极端贱卖时费超过标价), 故阈值只做单向比较, 不钳到 0..1。
 */
const FEE_RATIO_CALM = 0.22
const FEE_RATIO_WARN = 0.35

function feeRatioTone(ratio: number): Tone {
  if (ratio <= FEE_RATIO_CALM) {
    return 'success'
  }
  if (ratio <= FEE_RATIO_WARN) {
    return 'warning'
  }
  return 'danger'
}

function toError(value: unknown): Error {
  return value instanceof Error ? value : new Error(String(value))
}

/**
 * 不可挂单的显示文案。
 *
 * reason 是服务端写给玩家看的原话 (它区分"品质不够低"与"牌数据不完整"两种情形), 有就原样用 ——
 * 前端改写它等于制造第二套业务口径。reason 缺席时才退到本地错误码文案表, 那张表的 ITEM_NOT_TRADABLE
 * 与 market.place 被拒时用的是同一条, 于是"按钮为什么灰"和"提交为什么被拒"永远是同一句话。
 *
 * reasonCode 是机器码, 玩家读它等于读天书, 故只在假数据模式下附带: 排障的人需要它, 玩家不需要。
 */
function describeNonTradable(reason: string | null, reasonCode: string | null): string {
  const debugSuffix = isMockActive() && reasonCode !== null ? ` (${reasonCode})` : ''
  if (reason !== null) {
    return `${reason}${debugSuffix}`
  }
  const fromCode = reasonCode === null ? null : errorCodeText(reasonCode)
  return `${fromCode ?? '这件物品不能上架出售'}${debugSuffix}`
}

/**
 * 稠密 36 槽表的一格。
 *
 * ItemSlotGrid 只认数组下标 (它的 onSelect 给的就是下标), 而挂单要提交的是背包槽位号,
 * 故格子自带 slot 字段: 稠密排布下两者数值相等, 但把这层对应关系写出来, 日后网格若改成
 * 非稠密 (如按分类分组) 也不会静默把下标当槽位号提交上去。
 */
interface DenseSlot extends ItemSlotGridEntry {
  slot: number
}

/** 稠密 36 槽表: 空槽也要有记录, ItemSlotGrid 靠数组下标 (而非 slot 号) 排布几何位置。 */
function buildDenseSlots(
  items: readonly PlayerInventoryItem[] | null,
  nameOf: (item: PlayerInventoryItem) => string,
): DenseSlot[] {
  const bySlot = new Map<number, PlayerInventoryItem>()
  if (items !== null) {
    for (const item of items) {
      bySlot.set(item.slot, item)
    }
  }
  return Array.from({ length: INVENTORY_SLOT_COUNT }, (_unused, slot) => {
    const item = bySlot.get(slot)
    if (item === undefined) {
      return { slot }
    }
    return {
      slot,
      itemId: item.itemId,
      // 不带这个键的话, 195 种枪匠零件在背包网格里是同一张图。
      customModelData: item.customModelData,
      count: item.count,
      label: item.displayName ?? nameOf(item),
    }
  })
}

type SubmitState = { status: 'idle' } | { status: 'submitting' } | { status: 'error'; error: Error }

interface PlacedReceipt {
  listingId: number
  listFee: number
}

interface InventoryFetchState {
  status: 'idle' | 'loading' | 'error'
  error: Error | null
}

interface ListingFormProps {
  readonly stack: PlayerInventoryItem
  readonly itemLabel: string
  readonly onPlaced: (receipt: PlacedReceipt) => void
}

/**
 * 选中槽位之后的整个表单。
 *
 * 为什么必须是独立组件而不是内联在 SellPage 里: useMockAction 无条件发起请求 (没有 skip 能力),
 * 而 market.tradable / market.feePreview 都要拿真实的 slot 与价格。留在父组件里就只能在未选中时
 * 传 slot: -1 这类哨兵值去换一次必然失败的调用 —— 那会在玩家还没选东西的时候先弹一个业务错误。
 * 挂在这里, 组件本身只在"已经有 stack"时存在, 三个查询天然都有合法入参。
 */
function ListingForm({ stack, itemLabel, onPlaced }: ListingFormProps): ReactElement {
  const [count, setCount] = useState(1)
  const [unitPrice, setUnitPrice] = useState(1)
  const [priceTouched, setPriceTouched] = useState(false)
  const [submitState, setSubmitState] = useState<SubmitState>({ status: 'idle' })
  /** 防抖后的手续费预览入参。初值 (1, 1) 本身就是合法入参, 故挂载即可发一次预览。 */
  const [feeInput, setFeeInput] = useState({ unitPrice: 1, count: 1 })
  const countLabelId = useId()
  const priceLabelId = useId()

  const tradableQuery = useMockAction('market.tradable', { slot: stack.slot })
  /*
   * 基准价单独查一次, 不复用 feePreview 回执里的 v0: 它的用途是给单价一个默认起点, 而 feePreview 的入参
   * 里就有单价 —— 拿一个吃单价的接口去决定单价, 是让默认值依赖它自己。展示用的 V0 则统一读 feePreview,
   * 那才是这笔手续费真正算在了哪个基准价上。
   */
  const baseValueQuery = useMockAction('market.baseValue', { itemId: stack.itemId })

  const inputsValid = unitPrice >= 1 && count >= 1
  const feeQuery = useMockAction('market.feePreview', {
    itemId: stack.itemId,
    unitPrice: feeInput.unitPrice,
    count: feeInput.count,
  })

  useEffect(() => {
    if (!inputsValid) {
      // 非法草稿态不发起调用 (服务端对 <=0 抛 INVALID_REQUEST), 也不拿上一次的结果冒充这一次。
      return
    }
    const timer = setTimeout(() => {
      setFeeInput({ unitPrice, count })
    }, FEE_PREVIEW_DEBOUNCE_MS)
    return () => {
      clearTimeout(timer)
    }
  }, [unitPrice, count, inputsValid])

  useEffect(() => {
    if (priceTouched || baseValueQuery.status !== 'ready' || baseValueQuery.data.v0 === null) {
      return
    }
    // 只在玩家还没手动动过价格时代填基准价, 给一个"贴着 V0 挂单"的默认起点 (费率地板)。
    setUnitPrice(baseValueQuery.data.v0)
  }, [baseValueQuery.status, baseValueQuery.data, priceTouched])

  function handlePriceChange(next: number): void {
    setPriceTouched(true)
    setUnitPrice(next)
  }

  function handleSubmit(): void {
    setSubmitState({ status: 'submitting' })
    callMock('market.place', { slot: stack.slot, count, unitPrice })
      .then((result) => {
        onPlaced({ listingId: result.listingId, listFee: result.listFee })
      })
      .catch((error: unknown) => {
        setSubmitState({ status: 'error', error: toError(error) })
      })
  }

  const notTradable = tradableQuery.status === 'ready' && !tradableQuery.data.tradable
  /*
   * 提交口只认"查过且明确可交易"。
   * 早先的判据是 notTradable 取反, 于是 market.tradable 还在路上或读取失败时 notTradable 恒为 false,
   * 提交按钮照常可点 —— 一个本该被拦住的标的在校验没回来的空窗里就能挂出去。缺数据必须锁死,
   * 不能默认放行。
   */
  const tradableConfirmed = tradableQuery.status === 'ready' && tradableQuery.data.tradable
  const inputsDisabled = notTradable || submitState.status === 'submitting'
  const submitDisabled = !tradableConfirmed || !inputsValid || submitState.status === 'submitting'
  /** 回执带 itemId 回显, 用它挡住"换了标的但旧回执后到"的串行。 */
  const feeMatchesStack = feeQuery.status === 'ready' && feeQuery.data.itemId === stack.itemId
  /** 手已经动了但防抖还没到点: 屏幕上那个数算的是上一对入参, 必须说出来而不是让它冒充新值。 */
  const feeIsStale = feeInput.unitPrice !== unitPrice || feeInput.count !== count

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-3">
        <ItemIcon
          customModelData={stack.customModelData}
          itemId={stack.itemId}
          label={itemLabel}
          scale={2}
        />
        <div className="flex flex-col">
          <span className="text-foreground text-sm">{itemLabel}</span>
          <span className="text-muted-foreground text-xs">持有 {stack.count} 件</span>
        </div>
      </div>

      {/*
        可交易性三态都要看得见: 早先只画 notTradable 一态, loading 与 error 全无痕迹,
        而提交按钮那时是可点的 —— 玩家既不知道还在查, 也不知道查失败了。
      */}
      {tradableQuery.status === 'loading' ? (
        <LoadingBlock label="正在检查这件物品能否上架" size="sm" />
      ) : null}
      {tradableQuery.status === 'error' ? (
        <ErrorBlock
          message={`没能确认这件物品是否可以上架, 暂时不能提交: ${callErrorText(tradableQuery.error)}`}
          onRetry={tradableQuery.reload}
        />
      ) : null}
      {notTradable && tradableQuery.status === 'ready' ? (
        <ErrorBlock
          message={`这件物品不能上架出售: ${describeNonTradable(tradableQuery.data.reason, tradableQuery.data.reasonCode)}`}
        />
      ) : null}

      {/*
        两组步进器的按钮无障碍名都是"减少"/"增加", 读屏用户离开可见排版后无从分辨改的是哪个字段。
        包一层 role="group" 并把已有的可见标题接成组名, 于是焦点进组时先播报"挂单数量"再播报按钮名。
      */}
      <div aria-labelledby={countLabelId} className="flex flex-col gap-2" role="group">
        <span className="text-muted-foreground text-sm" id={countLabelId}>
          数量 (最多 {stack.count})
        </span>
        <NumberInput
          disabled={inputsDisabled}
          max={stack.count}
          min={1}
          onChange={setCount}
          value={count}
        />
      </div>

      <div aria-labelledby={priceLabelId} className="flex flex-col gap-2" role="group">
        <span className="text-muted-foreground text-sm" id={priceLabelId}>
          单价 (信用点 / 件)
        </span>
        <NumberInput
          disabled={inputsDisabled}
          max={MAX_UNIT_PRICE}
          min={1}
          onChange={handlePriceChange}
          value={unitPrice}
        />
      </div>

      <Surface>
        <div className="flex flex-col gap-2">
          {inputsValid ? null : (
            <span className="text-sm text-warning">数量与单价都至少要填 1, 现在算不出手续费。</span>
          )}
          {feeQuery.status === 'loading' ? <LoadingBlock label="正在算手续费" size="sm" /> : null}
          {feeQuery.status === 'error' ? (
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-destructive text-sm">
                手续费预览失败: {callErrorText(feeQuery.error)}
              </span>
              <Button onClick={feeQuery.reload} size="sm" variant="outline">
                重试
              </Button>
            </div>
          ) : null}

          {inputsValid && feeQuery.status === 'ready' && feeMatchesStack ? (
            <>
              {feeIsStale ? (
                <span className="text-muted-foreground text-xs">
                  下面这组数字算的是上一次的数量/单价, 正在重算
                </span>
              ) : null}
              <div className="flex items-center justify-between gap-3">
                <span className="text-muted-foreground text-sm">基准价</span>
                {feeQuery.data.v0 === null ? (
                  <span className="text-muted-foreground text-sm">暂无基准价 (按平率收费)</span>
                ) : (
                  <Currency amount={feeQuery.data.v0} currency="credit" size="sm" />
                )}
              </div>
              <div className="flex items-center justify-between gap-3">
                <span className="text-muted-foreground text-sm">
                  预计手续费 (上单即收, 撤单不退)
                </span>
                <Currency amount={feeQuery.data.listFee} currency="credit" size="sm" />
              </div>
              <div className="flex items-center justify-between gap-3">
                <span className="text-muted-foreground text-sm">占挂单总价</span>
                <Tag tone={feeRatioTone(feeQuery.data.ratio)}>
                  {(feeQuery.data.ratio * 100).toFixed(1)}%
                </Tag>
              </div>
              <span className="text-muted-foreground text-xs">
                预览与实收同源, 但最终以提交后的回执为准 —— 基准价可能在这中间被管理员改动。
              </span>
            </>
          ) : null}
        </div>
      </Surface>

      <div>
        <Button
          disabled={submitDisabled}
          loading={submitState.status === 'submitting'}
          onClick={handleSubmit}
          variant="brand"
        >
          提交挂单
        </Button>
      </div>

      {submitState.status === 'error' ? (
        <ErrorBlock message={callErrorText(submitState.error)} onRetry={handleSubmit} />
      ) : null}
    </div>
  )
}

export function SellPage(): ReactElement {
  const world = useMockWorld()
  const inventoryItems = world.mirror.inventory

  const [inventoryFetch, setInventoryFetch] = useState<InventoryFetchState>({
    status: 'idle',
    error: null,
  })
  const [selectedSlot, setSelectedSlot] = useState<number | null>(null)
  const [receipt, setReceipt] = useState<PlacedReceipt | null>(null)

  // 兜底: TabletShell 已在挂载时预热镜像, 但直接深链到本页 (或首次预热失败) 时这里独立补一次,
  // 不假设自己是唯一读者、也不重复"打成功了却没人处理错误"的老毛病。
  useEffect(() => {
    if (inventoryItems !== null) {
      return
    }
    setInventoryFetch({ status: 'loading', error: null })
    refreshInventoryMirror()
      .then(() => {
        setInventoryFetch({ status: 'idle', error: null })
      })
      .catch((error: unknown) => {
        setInventoryFetch({ status: 'error', error: toError(error) })
      })
  }, [inventoryItems])

  const nameOf = useItemDisplayNames(inventoryItems === null ? [] : inventoryItems)
  const denseSlots = buildDenseSlots(inventoryItems, nameOf)
  const selectedStack = inventoryItems?.find((item) => item.slot === selectedSlot) ?? null

  function handleSelectSlot(slot: number): void {
    const entry = denseSlots.find((candidate) => candidate.slot === slot)
    if (entry === undefined || entry.itemId === undefined) {
      return
    }
    setSelectedSlot(slot)
    // 上一单的回执随着换物品作废; 表单自身的状态由 ListingForm 的 key 换掉时整体重来。
    setReceipt(null)
  }

  function handlePlaced(placed: PlacedReceipt): void {
    setReceipt(placed)
    // 那件物品已经进了挂单, 选中格随之作废 (库存镜像也会被 callMock 刷新)。
    setSelectedSlot(null)
  }

  const selectedItemLabel =
    selectedStack === null ? '' : (selectedStack.displayName ?? nameOf(selectedStack))

  return (
    <div className="grid grid-cols-2 gap-4">
      <Panel title="选择要出售的物品">
        {inventoryItems === null ? (
          inventoryFetch.status === 'error' && inventoryFetch.error !== null ? (
            <ErrorBlock
              message={`背包读取失败: ${inventoryFetch.error.message}`}
              onRetry={() => {
                setInventoryFetch({ status: 'loading', error: null })
                refreshInventoryMirror()
                  .then(() => {
                    setInventoryFetch({ status: 'idle', error: null })
                  })
                  .catch((error: unknown) => {
                    setInventoryFetch({ status: 'error', error: toError(error) })
                  })
              }}
            />
          ) : (
            <LoadingBlock label="正在读取背包" />
          )
        ) : inventoryItems.length === 0 ? (
          <EmptyBlock hint="背包里没有可以出售的物品" title="背包为空" />
        ) : (
          <ItemSlotGrid
            columns={INVENTORY_COLUMNS}
            label="背包"
            onSelect={handleSelectSlot}
            slots={denseSlots}
            {...(selectedSlot === null ? {} : { selectedSlot })}
          />
        )}
      </Panel>

      <Panel title="数量 · 单价 · 手续费预览">
        {selectedStack === null ? (
          <EmptyBlock hint="点击左侧背包格开始挂单" title="尚未选择物品" />
        ) : (
          // key 换成新槽位即整表重来: 数量/单价/上一次的提交错误都不该跨物品带过去。
          <ListingForm
            itemLabel={selectedItemLabel}
            key={selectedStack.slot}
            onPlaced={handlePlaced}
            stack={selectedStack}
          />
        )}

        {/*
          回执排在选中格分支之外。提交成功后本页会清空选中格 (那件物品已经进了挂单),
          回执若留在依赖 selectedStack 的分支里就随之一起消失 —— listingId 与真实 listFee 永远看不到。
        */}
        {receipt === null ? null : (
          <Surface className="mt-3" tone="success">
            <p className="flex flex-wrap items-center gap-1 text-foreground text-sm">
              挂单成功 (#{receipt.listingId}), 已扣手续费
              <Currency amount={receipt.listFee} currency="credit" size="sm" />
            </p>
          </Surface>
        )}
      </Panel>
    </div>
  )
}
