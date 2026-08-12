import type { ReactElement } from 'react'
import { useEffect, useId, useState } from 'react'
import type { PixelFrameTone, PixelSlotGridEntry } from '../../components/pixel'
import {
  ItemIcon,
  PixelBadge,
  PixelButton,
  PixelCurrency,
  PixelEmpty,
  PixelError,
  PixelFrame,
  PixelLoading,
  PixelScrollArea,
  PixelSlotGrid,
  PixelStepper,
} from '../../components/pixel'
import { useItemNames } from '../../lib/i18n'
import type { PlayerInventoryItem } from '../../lib/types'
import { callMock, refreshWalletAndInventory, useMockAction, useMockWorld } from '../../mock'

/**
 * 跳蚤市场 · 挂单。
 *
 * 依赖的假定契约 (planned.ts, 后端尚无对应 action):
 *   - market.tradable (接线清单 B12): 挂单前灰掉不可交易标的 (如青辉石/被禁塔罗牌), 避免玩家先托管
 *     再在提交时才收到拒绝。见 mock/planned.ts PlannedTradableResult 与 mock/seed.ts 的 nonTradable 表。
 *
 * 已真实接线的 action: player.inventory (经 mirror, 由 TabletShell 首屏预热, 本页兜底自补一次)、
 * market.baseValue (基准价锚 V0)、market.place (提交挂单)。
 *
 * 手续费公式不是本页发明的近似值, 而是逐字核对服务端源码后的客户端镜像 (真源):
 *   src/main/java/com/miningdim/market/MarketFee.java (deviationFee / flatFee)
 *   src/main/java/com/miningdim/market/MarketConstants.java (FEE_RATE=0.20 / DEVIATION_K=0.04 / MIN_ANCHOR_VALUE=1)
 * 这故意不走 mock 里 market.feePreview (B9) 的占位 4% 比例 —— 那条契约本就声明"不复刻真实费率",
 * 而这里已经有 Java 源码可核对的精确公式, 用占位值反而更不准。**最终手续费仍以 market.place 回执的
 * listFee 为准**, 本面板算出的只是提交前的预览, 二者理论相等但严禁互相替代 (V0 在极端并发下可能已被
 * 管理员改动)。
 */

const INVENTORY_SLOT_COUNT = 36
const INVENTORY_COLUMNS = 9
/** 挂单单价上限, 纯前端可用性钳制 (远低于 Number.MAX_SAFE_INTEGER, 服务端本身不设上限)。 */
const MAX_UNIT_PRICE = 999_999_999

/** MarketConstants.FEE_RATE 的客户端镜像, 见文件头 "真源"。 */
const FEE_RATE = 0.2
/** MarketConstants.DEVIATION_K 的客户端镜像。 */
const DEVIATION_K = 0.04
/** MarketConstants.MIN_ANCHOR_VALUE 的客户端镜像。 */
const MIN_ANCHOR_VALUE = 1

interface FeeEstimate {
  fee: number
  /** 0..N 的实际费率 (未转百分比)。 */
  rate: number
  /** 挂价相对基准价的倍数; 无锚时为 null。 */
  ratioToAnchor: number | null
}

/** MarketFee.listingFee 的纯函数镜像, 逐行对应 deviationFee/flatFee 两个分支。 */
function estimateListingFee(v0: number | null, unitPrice: number, count: number): FeeEstimate {
  if (v0 === null) {
    return { fee: Math.round(unitPrice * count * FEE_RATE), rate: FEE_RATE, ratioToAnchor: null }
  }
  const anchor = Math.max(MIN_ANCHOR_VALUE, v0)
  const vr = Math.max(MIN_ANCHOR_VALUE, unitPrice)
  const logRatio = Math.log(vr / anchor)
  const rate = FEE_RATE + DEVIATION_K * logRatio * logRatio
  const scaleRef = Math.max(anchor, vr) * count
  return { fee: Math.round(scaleRef * rate), rate, ratioToAnchor: vr / anchor }
}

/** 费率越高染色越重, 让"偏离基准价越远费越高"这件事不必读数字就能一眼看出。 */
function feeRateTone(rate: number): PixelFrameTone {
  if (rate <= FEE_RATE + 0.02) {
    return 'success'
  }
  if (rate <= FEE_RATE + 0.15) {
    return 'warning'
  }
  return 'danger'
}

function toError(value: unknown): Error {
  return value instanceof Error ? value : new Error(String(value))
}

/**
 * 拒绝理由的显示文案。
 *
 * PlannedTradableResult 的 reason (人话) 与 reasonCode (机器码) 各自可空, 早先只读 reason 并
 * `?? '未知原因'` —— 服务端明明给了 reasonCode 却被这句兜底盖掉, 玩家与排障的人都看不到它。
 * 两者都缺时如实说"服务端没给理由", 而不是含糊成"未知"。
 */
function describeNonTradable(reason: string | null, reasonCode: string | null): string {
  if (reason !== null) {
    return reasonCode === null ? reason : `${reason} (${reasonCode})`
  }
  return reasonCode === null ? '服务端未给出拒绝理由' : `理由码 ${reasonCode}`
}

/** 稠密 36 槽表: 空槽也要有记录, PixelSlotGrid 靠数组下标 (而非 slot 号) 排布几何位置。 */
function buildDenseSlots(
  items: readonly PlayerInventoryItem[] | null,
  names: Record<string, string>,
): PixelSlotGridEntry[] {
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
    const label = item.displayName ?? names[item.descriptionId]
    return {
      slot,
      itemId: item.itemId,
      count: item.count,
      ...(label === undefined ? {} : { label }),
    }
  })
}

type SubmitState =
  | { status: 'idle' }
  | { status: 'submitting' }
  | { status: 'success'; listingId: number; listFee: number }
  | { status: 'error'; error: Error }

interface InventoryFetchState {
  status: 'idle' | 'loading' | 'error'
  error: Error | null
}

export function SellPage(): ReactElement {
  const world = useMockWorld()
  const inventoryItems = world.mirror.inventory

  const [inventoryFetch, setInventoryFetch] = useState<InventoryFetchState>({
    status: 'idle',
    error: null,
  })
  const [selectedSlot, setSelectedSlot] = useState<number | null>(null)
  const [count, setCount] = useState(1)
  const [unitPrice, setUnitPrice] = useState(1)
  const [priceTouched, setPriceTouched] = useState(false)
  const [submitState, setSubmitState] = useState<SubmitState>({ status: 'idle' })
  const countLabelId = useId()
  const priceLabelId = useId()

  // 兜底: TabletShell 已在挂载时预热镜像, 但直接深链到本页 (或首次预热失败) 时这里独立补一次,
  // 不假设自己是唯一读者、也不重复"打成功了却没人处理错误"的老毛病。
  useEffect(() => {
    if (inventoryItems !== null) {
      return
    }
    setInventoryFetch({ status: 'loading', error: null })
    refreshWalletAndInventory()
      .then(() => {
        setInventoryFetch({ status: 'idle', error: null })
      })
      .catch((error: unknown) => {
        setInventoryFetch({ status: 'error', error: toError(error) })
      })
  }, [inventoryItems])

  const descriptionIds =
    inventoryItems === null ? [] : Array.from(new Set(inventoryItems.map((item) => item.descriptionId)))
  const names = useItemNames(descriptionIds)
  const denseSlots = buildDenseSlots(inventoryItems, names)
  const selectedStack = inventoryItems?.find((item) => item.slot === selectedSlot) ?? null

  const itemQueryPayload = { itemId: selectedStack === null ? '' : selectedStack.itemId }
  const baseValueQuery = useMockAction('market.baseValue', itemQueryPayload)
  const tradableQuery = useMockAction('market.tradable', itemQueryPayload)

  useEffect(() => {
    if (priceTouched || baseValueQuery.status !== 'ready' || baseValueQuery.data.v0 === null) {
      return
    }
    // 只在玩家还没手动动过价格时代填基准价, 给一个"贴着 V0 挂单"的默认起点 (费率地板)。
    setUnitPrice(baseValueQuery.data.v0)
  }, [baseValueQuery.status, baseValueQuery.data, priceTouched])

  function handleSelectSlot(slot: number): void {
    const entry = denseSlots.find((candidate) => candidate.slot === slot)
    if (entry === undefined || entry.itemId === undefined) {
      return
    }
    setSelectedSlot(slot)
    /*
     * 表单归位收在这里, 而不是挂一个 useEffect 到 [selectedSlot] 上。
     * 挂 effect 的写法会把"提交成功后清空选中格"也算成一次换物品, 顺手把刚写进去的成功回执重置成 idle,
     * 于是 listingId 与真实 listFee 永远没机会被画出来 (清空选中 + 重置回执 = 回执必然不可达)。
     * 只有玩家真的换了一格才该重来一遍, 那正是本函数。
     */
    setPriceTouched(false)
    setCount(1)
    setUnitPrice(1)
    setSubmitState({ status: 'idle' })
  }

  function handlePriceChange(next: number): void {
    setPriceTouched(true)
    setUnitPrice(next)
  }

  function handleSubmit(): void {
    if (selectedStack === null) {
      return
    }
    setSubmitState({ status: 'submitting' })
    callMock('market.place', { slot: selectedStack.slot, count, unitPrice })
      .then((result) => {
        setSubmitState({ status: 'success', listingId: result.listingId, listFee: result.listFee })
        setSelectedSlot(null)
      })
      .catch((error: unknown) => {
        setSubmitState({ status: 'error', error: toError(error) })
      })
  }

  const notTradable = tradableQuery.status === 'ready' && !tradableQuery.data.tradable
  /*
   * 提交口只认"查过且明确可交易"。
   * 早先的判据是 notTradable 取反, 于是 market.tradable 还在路上或读取失败时 notTradable 恒为 false,
   * 提交按钮照常可点 —— 一个本该被拦住的青辉石/禁卡在校验没回来的空窗里就能挂出去。缺数据必须锁死,
   * 不能默认放行。
   */
  const tradableConfirmed = tradableQuery.status === 'ready' && tradableQuery.data.tradable
  const inputsDisabled = selectedStack === null || notTradable || submitState.status === 'submitting'
  const submitDisabled = !tradableConfirmed || submitState.status === 'submitting'
  /*
   * 只有 ready 才算数: 非 ready 时传 null 给 estimateListingFee 会落进"无锚"分支, 把"还没查到基准价"
   * 画成一个货真价实的 20% 平率手续费 —— 缺失数据被伪装成正常金额, 而且和 market.place 的真实回执对不上。
   * 契约缺失就该在界面上看得见 (下方按 status 分别渲染), 不能兜一个数字盖过去。
   */
  const feeEstimate =
    selectedStack === null || baseValueQuery.status !== 'ready'
      ? null
      : estimateListingFee(baseValueQuery.data.v0, unitPrice, count)
  const selectedItemLabel =
    selectedStack === null
      ? ''
      : (selectedStack.displayName ?? names[selectedStack.descriptionId] ?? selectedStack.descriptionId)

  return (
    <div className="grid grid-cols-2 gap-8">
      <section className="flex flex-col gap-4">
        <h2 className="text-1x text-fg">选择要出售的物品</h2>

        {inventoryItems === null ? (
          inventoryFetch.status === 'error' && inventoryFetch.error !== null ? (
            <PixelError
              message={`背包读取失败: ${inventoryFetch.error.message}`}
              onRetry={() => {
                setInventoryFetch({ status: 'loading', error: null })
                refreshWalletAndInventory()
                  .then(() => {
                    setInventoryFetch({ status: 'idle', error: null })
                  })
                  .catch((error: unknown) => {
                    setInventoryFetch({ status: 'error', error: toError(error) })
                  })
              }}
            />
          ) : (
            <PixelLoading label="正在读取背包" />
          )
        ) : inventoryItems.length === 0 ? (
          <PixelEmpty title="背包为空" hint="仓库里没有可挂单的物品" icon="bag" />
        ) : (
          <PixelScrollArea className="h-80" label="背包">
            <PixelSlotGrid
              slots={denseSlots}
              columns={INVENTORY_COLUMNS}
              label="背包"
              onSelect={handleSelectSlot}
              {...(selectedSlot === null ? {} : { selectedSlot })}
            />
          </PixelScrollArea>
        )}
      </section>

      <section className="flex flex-col gap-4">
        <h2 className="text-1x text-fg">数量 · 单价 · 手续费预览</h2>

        {selectedStack === null ? (
          <PixelEmpty title="尚未选择物品" hint="点击左侧背包格开始挂单" icon="coin-credit" />
        ) : (
          <div className="flex flex-col gap-4">
            <div className="flex items-center gap-4">
              <ItemIcon itemId={selectedStack.itemId} label={selectedItemLabel} scale={2} />
              <div className="flex flex-col">
                <span className="text-1x text-fg">{selectedItemLabel}</span>
                <span className="text-1x text-muted">
                  持有 {selectedStack.count} 件 · 槽位 {selectedStack.slot}
                </span>
              </div>
            </div>

            {/*
              可交易性三态都要看得见: 早先只画 notTradable 一态, loading 与 error 全无痕迹,
              而提交按钮那时是可点的 —— 玩家既不知道还在查, 也不知道查失败了。
            */}
            {tradableQuery.status === 'loading' ? (
              <PixelLoading size="sm" label="正在校验该物品是否可挂单" />
            ) : null}
            {tradableQuery.status === 'error' ? (
              <PixelError
                message={`可交易性校验失败, 挂单已锁定: ${tradableQuery.error.message}`}
                onRetry={tradableQuery.reload}
              />
            ) : null}
            {notTradable && tradableQuery.status === 'ready' ? (
              <PixelError
                message={`该物品不可挂单出售: ${describeNonTradable(tradableQuery.data.reason, tradableQuery.data.reasonCode)}`}
              />
            ) : null}

            {/*
              两组步进器的按钮无障碍名都是"减少"/"增加", 读屏用户离开可见排版后无从分辨改的是哪个字段。
              包一层 role="group" 并把已有的可见标题接成组名, 于是焦点进组时先播报"挂单数量"再播报按钮名。
            */}
            <div className="flex flex-col gap-2" role="group" aria-labelledby={countLabelId}>
              <span id={countLabelId} className="text-1x text-muted">
                数量 (最多 {selectedStack.count})
              </span>
              <PixelStepper
                value={count}
                onChange={setCount}
                min={1}
                max={selectedStack.count}
                disabled={inputsDisabled}
              />
            </div>

            <div className="flex flex-col gap-2" role="group" aria-labelledby={priceLabelId}>
              <span id={priceLabelId} className="text-1x text-muted">
                单价 (信用点 / 件)
              </span>
              <PixelStepper
                value={unitPrice}
                onChange={handlePriceChange}
                min={1}
                max={MAX_UNIT_PRICE}
                disabled={inputsDisabled}
              />
            </div>

            <PixelFrame variant="inset" className="flex flex-col gap-2 p-4">
              <div className="flex items-center justify-between gap-4">
                <span className="text-1x text-muted">基准价 V0</span>
                {baseValueQuery.status === 'loading' ? <PixelLoading size="sm" /> : null}
                {baseValueQuery.status === 'error' ? (
                  <span className="text-1x text-danger">读取失败: {baseValueQuery.error.message}</span>
                ) : null}
                {baseValueQuery.status === 'ready' ? (
                  baseValueQuery.data.v0 === null ? (
                    <span className="text-1x text-muted">无锚 (按 {(FEE_RATE * 100).toFixed(0)}% 平率计费)</span>
                  ) : (
                    <PixelCurrency amount={baseValueQuery.data.v0} currency="credit" size="sm" />
                  )
                ) : null}
              </div>

              {/*
                手续费预览只在基准价 ready 时给。非 ready 时明说"算不出", 而不是退回平率算一个数出来 ——
                那个数看着完全正常, 却和 market.place 的真实 listFee 无关。
              */}
              {selectedStack !== null && baseValueQuery.status !== 'ready' ? (
                <span className="text-1x text-muted">
                  基准价未就绪, 手续费预览暂不可用; 最终以 market.place 回执的 listFee 为准
                </span>
              ) : null}

              {feeEstimate === null ? null : (
                <>
                  <div className="flex items-center justify-between gap-4">
                    <span className="text-1x text-muted">预计手续费 (上单即收, 撤单不退)</span>
                    <PixelCurrency amount={feeEstimate.fee} currency="credit" size="sm" />
                  </div>
                  <div className="flex items-center justify-between gap-4">
                    <span className="text-1x text-muted">费率</span>
                    <PixelBadge tone={feeRateTone(feeEstimate.rate)}>
                      {(feeEstimate.rate * 100).toFixed(1)}%
                    </PixelBadge>
                  </div>
                  {feeEstimate.ratioToAnchor === null ? null : (
                    <span className="text-1x text-muted">
                      挂价是基准价的 {feeEstimate.ratioToAnchor.toFixed(2)} 倍 —— 偏离基准价越远, 费率上涨越快
                    </span>
                  )}
                </>
              )}
            </PixelFrame>

            <PixelButton
              tone="accent"
              loading={submitState.status === 'submitting'}
              disabled={submitDisabled}
              onClick={handleSubmit}
            >
              提交挂单
            </PixelButton>
          </div>
        )}

        {/*
          回执排在选中格分支之外。提交成功后本页会清空选中格 (那件物品已经进了挂单),
          回执若留在依赖 selectedStack 的分支里就随之一起消失 —— listingId 与真实 listFee 永远看不到。
        */}
        {submitState.status === 'success' ? (
          <PixelBadge tone="success">
            挂单成功 (#{submitState.listingId}), 已扣手续费{' '}
            <PixelCurrency amount={submitState.listFee} currency="credit" size="sm" />
          </PixelBadge>
        ) : null}
        {submitState.status === 'error' ? (
          <PixelError message={submitState.error.message} onRetry={handleSubmit} />
        ) : null}
      </section>
    </div>
  )
}
