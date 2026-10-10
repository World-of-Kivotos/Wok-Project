import type { ReactElement } from 'react'
import { useRef, useState } from 'react'
import type { DataTableColumn, DropdownOption, Tone } from '@/components/kit'
import {
  Button,
  ConfirmDangerDialog,
  Currency,
  DataTable,
  Dropdown,
  EmptyBlock,
  ErrorBlock,
  FeedbackAlert,
  formatAmount,
  ItemIcon,
  ItemSlot,
  LoadingBlock,
  Meter,
  Panel,
  Stat,
  Surface,
  Tag,
  TextInput,
} from '@/components/kit'
import { SERVER_FAILURE_CODE, WebUiCallError } from '../../../lib/bridge'
import { callErrorText } from '../../../lib/errorText'
import { useItemNames } from '../../../lib/i18n'
import type {
  Blueprint,
  MunitionsBuyResult,
  MunitionsShopEntry,
  MunitionsShopKind,
  MunitionsShopResult,
  MunitionsStation,
} from '../../../lib/types'
import { createClientUuid } from '../../../lib/uuid'
import { callMock, useMockAction } from '../../../mock'
import { toError } from './shared'

/**
 * 军火商面板 (`job.munitions.state` / `job.blueprints` / `job.munitions.shop` / `job.munitions.buy`, Java 落点
 * com.miningdim.job.munitions.MunitionsWebUiActions)。回执形状见 lib/types.ts。
 *
 * 系统采购 (Munitions_Job_DesignSpec 6.4) 是本页唯一的写操作: 六档军火台、冲压机、装配台与枪匠图纸按军火商等级
 * 解锁、用信用点向系统购买。页面只展示与转交意图, 等级门/台数上限/余额/背包空位全部由服务端在下单时重新判一遍;
 * 目录行的 reasonCode 与下单被拒的 errorCode 出自同一个服务端判定, 所以灰按钮上的那句话就是提交会被拒的理由。
 * 防连点分两层: 在途时本地 ref 闸住重入 (React 的 state 要等下一帧才生效, 快速双击挡不住), 服务端按 purchaseId
 * 幂等 —— 网络层失败 (超时/桥异常) 后重试沿用原 id, 第一次其实成交了也只会回放回执而不会二次扣费。
 *
 * 三条与旧版假定相反、必须照做的契约事实:
 *   1. **pos 是"附近扫到的最近一台", 不是"我的台"**: 全工程没有"玩家 -> 台位坐标"注册表, 冲压机与
 *      装配台连归属字段都没有。pos=null 只能写"附近未找到", 绝不能写"尚未建造" —— 想说造了几台请看
 *      benchesPlaced (那才是跨维度权威计数)。
 *   2. **装配台读不出已进行 tick**: 它没有 ContainerData, animationEndTick 是私有字段, 故 progressTicks
 *      恒 null。running=true 且 progressTicks=null 时只能说"加工中", 不能当 0% 画一条空条。
 *   3. **枪匠链默认关闭** (MunitionsConfig.gunsmithEnabled=false): 关着时装配台点开工只会被拒。面板必须
 *      先把这件事讲清楚, 否则玩家会当成 bug。
 *
 * 三台仍是纯只读遥测: job.munitions.state / job.blueprints 都没有配套的写入口 (开工/选口径/开始装配), 面板不放点了
 * 没有后果的按钮 —— 本页唯一的按钮是系统采购的"购买"。
 *
 * 图纸名是**两层拼的**: 套壳键 item.miningdim.gunsmith_blueprint.name 带一个 %s, 实参是枪名键
 * tacz.gun.<id>.name —— 后者属 TACZ 的 lang, 未装 TACZ 的客户端解不出, 那时退回显示 gunId。
 * 筛选仍只按 blueprintId/gunId 子串匹配 (英文/数字资源定位符), 不依赖当前 BLOCKED 的宿主中文输入通道。
 */

const EMPTY_PAYLOAD: Record<string, never> = {}

/** 三台的稳定 id (= 方块注册名), 用于按台种类取 detail 里的键。 */
const STATION_MUNITIONS_BENCH = 'munitions_bench'
const STATION_GUNSMITH_PRESS = 'gunsmith_press'
const STATION_GUNSMITH_ASSEMBLY = 'gunsmith_assembly_bench'

/**
 * 翻译键解不出来时 useItemNames 原样退回键本身 (见 lib/i18n 的降级纪律), 故"解出来了没有"只能
 * 拿结果与键比对。本页需要区分这两种情况: 枪名解不出要退回 gunId, 而不是把 tacz.gun.xxx.name 顶给玩家看。
 */
function resolvedOrNull(names: Record<string, string>, key: string): string | null {
  const value = names[key]
  return value === undefined || value === key ? null : value
}

/** 图纸标题: 套壳键 %s 位填枪名; 枪名解不出退回 gunId, 套壳键解不出就只显示枪名。 */
function blueprintTitle(blueprint: Blueprint, names: Record<string, string>): string {
  const gunName = resolvedOrNull(names, blueprint.gunNameKey) ?? blueprint.gunId
  const wrapper = resolvedOrNull(names, blueprint.nameKey)
  return wrapper === null ? gunName : wrapper.replace('%s', gunName)
}

/** tick -> 秒的纯展示折算 (20 tick = 1 秒)。 */
function ticksToSecondsText(ticks: number): string {
  return `${(ticks / 20).toFixed(1)}s`
}

/** 一台机器的特有状态。键随 stationId 变 (契约: detail 是按台种类条件写入的), 故按 id 分支取。 */
function StationDetail({ station }: { station: MunitionsStation }): ReactElement | null {
  const detail = station.detail

  if (station.stationId === STATION_MUNITIONS_BENCH) {
    return (
      <div className="flex flex-wrap items-center gap-4">
        <Stat
          label="口径"
          layout="inline"
          value={detail.caliberId === undefined || detail.caliberId === null ? '未选' : detail.caliberId}
        />
        <Stat
          label="缓冲发数"
          layout="inline"
          value={`${String(detail.bufferedRounds ?? 0)} / ${String(detail.bufferCap ?? 0)}`}
        />
        {detail.effectiveLevel === undefined ? null : (
          <Stat label="按几级算产能" layout="inline" value={String(detail.effectiveLevel)} />
        )}
        {detail.locked === true ? <Tag tone="warning">已锁定</Tag> : null}
        {detail.refineUnlocked === true ? <Tag tone="success">精炼已解锁</Tag> : null}
        {detail.continuousCrafting === true ? <Tag tone="info">连续生产</Tag> : null}
      </div>
    )
  }

  if (station.stationId === STATION_GUNSMITH_PRESS) {
    return (
      <div className="flex flex-wrap items-center gap-4">
        <Stat label="平台" layout="inline" value={detail.platformId ?? '未选'} />
        <Stat label="部位" layout="inline" value={detail.partId ?? '未选'} />
        <Stat label="品质" layout="inline" value={detail.qualityId ?? '未选'} />
        <Stat label="变体" layout="inline" value={detail.variantId ?? '未选'} />
      </div>
    )
  }

  if (station.stationId === STATION_GUNSMITH_ASSEMBLY) {
    return (
      <Stat
        label="图纸槽"
        layout="inline"
        value={detail.blueprintId === undefined || detail.blueprintId === null ? '未放图纸' : detail.blueprintId}
      />
    )
  }

  return null
}

/** 采购目录的筛选: 冲压机与装配台合成一类 (都是枪匠链的设备, 各只有一件)。 */
type ShopFilter = 'all' | 'bench' | 'gunsmith' | 'blueprint'

const SHOP_FILTER_OPTIONS: readonly DropdownOption<ShopFilter>[] = [
  { value: 'all', label: '全部条目' },
  { value: 'bench', label: '军火台' },
  { value: 'gunsmith', label: '枪匠设备' },
  { value: 'blueprint', label: '枪匠图纸' },
]

const SHOP_KIND_LABEL: Record<MunitionsShopKind, string> = {
  bench: '军火台',
  press: '枪匠设备',
  assembly: '枪匠设备',
  blueprint: '图纸',
}

function matchesShopFilter(entry: MunitionsShopEntry, filter: ShopFilter): boolean {
  switch (filter) {
    case 'all':
      return true
    case 'bench':
      return entry.kind === 'bench'
    case 'gunsmith':
      return entry.kind === 'press' || entry.kind === 'assembly'
    case 'blueprint':
      return entry.kind === 'blueprint'
  }
}

/**
 * 目录行"为什么买不了"的短句。目录行不带 params, 所需数字都在行上, 故就地拼句; 下单被拒时的那一句走
 * lib/errorText (带 params) —— 两边出自服务端同一个判定, 措辞同义。
 */
function shopStatus(entry: MunitionsShopEntry, shop: MunitionsShopResult): { text: string; tone: Tone } {
  switch (entry.reasonCode) {
    case null:
      return { text: '可购买', tone: 'success' }
    case 'SHOP_ITEM_UNAVAILABLE':
      return {
        text: entry.unavailableReason === 'gun_pack_missing' ? '缺少所需枪包' : '枪匠系统未开放',
        tone: 'neutral',
      }
    case 'PURCHASE_LEVEL_LOCKED':
      return { text: `需要 Lv.${String(entry.requiredLevel)}`, tone: 'neutral' }
    case 'PURCHASE_CAP_REACHED':
      return { text: `已达台数上限 ${String(shop.benchCap)}`, tone: 'warning' }
    case 'ALREADY_OWNED':
      return { text: '背包里已有', tone: 'info' }
    case 'ECONOMY_OFFLINE':
      return { text: '经济未就绪', tone: 'danger' }
    case 'INSUFFICIENT_FUNDS':
      return { text: '信用点不足', tone: 'warning' }
    case 'INVENTORY_FULL':
      return { text: '背包已满', tone: 'warning' }
  }
}

/**
 * 条目名: 台子直接解方块键; 图纸与物品栏同一拼法 (套壳键 %s 填枪名, 枪名解不出退回 gunId)。
 * 入参只取四个键, 目录行与成交回执 (MunitionsBuyResult) 共用。
 */
function shopEntryTitle(
  entry: Pick<MunitionsShopEntry, 'entryId' | 'nameKey' | 'gunNameKey' | 'gunId'>,
  names: Record<string, string>,
): string {
  if (entry.gunNameKey === null) {
    return names[entry.nameKey] ?? entry.nameKey
  }
  const gunName = resolvedOrNull(names, entry.gunNameKey) ?? entry.gunId ?? entry.entryId
  const wrapper = resolvedOrNull(names, entry.nameKey)
  return wrapper === null ? gunName : wrapper.replace('%s', gunName)
}

/** 口径名键 (与游戏内军火台界面同一批 lang 键); 解不出时退回 caliberId。 */
function caliberNameKey(caliberId: string): string {
  return `munitions.caliber.${caliberId}`
}

/** 条目的一行说明: 买之前就该知道的那件事 (台子到几级封顶 / 图纸吃什么弹)。 */
function shopEntrySubtitle(entry: MunitionsShopEntry, names: Record<string, string>): string {
  switch (entry.kind) {
    case 'bench':
      if (entry.maxEffectiveLevel === null) {
        return '军火台'
      }
      // 职业等级上限是 10 (MunitionsLevels.MAX_LEVEL): 上限 10 的台不存在"更高一档", 别让玩家以为还要再换。
      return entry.maxEffectiveLevel >= 10
        ? '产能随职业等级一直涨到满级'
        : `产能至多按 Lv.${String(entry.maxEffectiveLevel)} 计, 升过这一级要换更高档的台`
    case 'press':
      return '冲压枪匠零件'
    case 'assembly':
      return '装配与维修枪械'
    case 'blueprint': {
      const caliber =
        entry.caliberId === null
          ? null
          : (resolvedOrNull(names, caliberNameKey(entry.caliberId)) ?? entry.caliberId)
      return `${entry.gunId ?? ''}${caliber === null ? '' : ` · 弹药 ${caliber}`} · 装配不消耗图纸`
    }
  }
}

export function MunitionsPanel(): ReactElement {
  const stationQuery = useMockAction('job.munitions.state', EMPTY_PAYLOAD)
  const blueprintQuery = useMockAction('job.blueprints', EMPTY_PAYLOAD)
  const shopQuery = useMockAction('job.munitions.shop', EMPTY_PAYLOAD)

  const [filterText, setFilterText] = useState('')
  const [selectedBlueprintId, setSelectedBlueprintId] = useState<string | null>(null)

  const [shopFilter, setShopFilter] = useState<ShopFilter>('all')
  /** 待确认的那一件; 非 null 即确认框开着。 */
  const [confirmEntry, setConfirmEntry] = useState<MunitionsShopEntry | null>(null)
  const [purchasing, setPurchasing] = useState(false)
  const [purchaseError, setPurchaseError] = useState<Error | null>(null)
  const [lastPurchase, setLastPurchase] = useState<MunitionsBuyResult | null>(null)
  /**
   * 在途闸。不能只靠 purchasing 这个 state: setState 要等下一次渲染才生效, 同一帧里的第二次点击读到的仍是
   * false —— 快速双击就这样发出两单。ref 是同步写的, 第二次进来当场就被挡住。
   */
  const purchaseInFlight = useRef(false)
  /**
   * 还没了结的那一单的幂等键。网络层失败 (超时 / 桥异常) 时服务端到底成没成交是未知的, 此时必须留着原 id,
   * 同一条目再点一次时原样带上, 让服务端回放而不是再扣一次; 服务端明确回了结果 (成交或拒绝) 才清掉。
   */
  const pendingPurchase = useRef<{ entryId: string; purchaseId: string } | null>(null)

  const blueprintData = blueprintQuery.status === 'ready' ? blueprintQuery.data : null
  const blueprints = blueprintData === null ? [] : blueprintData.blueprints
  const stationData = stationQuery.status === 'ready' ? stationQuery.data : null
  const shopData = shopQuery.status === 'ready' ? shopQuery.data : null

  /*
   * 键一次批量解: 台名 / 图纸套壳名 / 枪名 / 部位标签 / 采购目录的条目名与口径名。零件的 itemId 与 descriptionId
   * 由服务端提到顶层只发一份 (195 种枪匠零件全注册在同一个 id 之下靠 NBT 区分), 逐行重复是纯浪费, 故这里也只解一次。
   */
  const names = useItemNames([
    ...(stationData === null ? [] : stationData.stations.map((station) => station.nameKey)),
    ...blueprints.map((blueprint) => blueprint.nameKey),
    ...blueprints.map((blueprint) => blueprint.gunNameKey),
    ...blueprints.flatMap((blueprint) => blueprint.requiredParts.map((part) => part.labelKey)),
    ...(blueprintData === null ? [] : [blueprintData.partDescriptionId]),
    ...(shopData === null
      ? []
      : shopData.entries.flatMap((entry) => [
          entry.nameKey,
          ...(entry.gunNameKey === null ? [] : [entry.gunNameKey]),
          ...(entry.caliberId === null ? [] : [caliberNameKey(entry.caliberId)]),
        ])),
  ])
  const nameOf = (nameKey: string): string => names[nameKey] ?? nameKey

  async function handlePurchase(entry: MunitionsShopEntry): Promise<void> {
    if (purchaseInFlight.current) {
      return
    }
    purchaseInFlight.current = true
    const pending = pendingPurchase.current
    const purchaseId =
      pending !== null && pending.entryId === entry.entryId ? pending.purchaseId : createClientUuid()
    pendingPurchase.current = { entryId: entry.entryId, purchaseId }
    setPurchasing(true)
    setPurchaseError(null)
    setLastPurchase(null)
    try {
      const result = await callMock('job.munitions.buy', { entryId: entry.entryId, purchaseId })
      pendingPurchase.current = null
      setLastPurchase(result)
      // 余额、台数、每行的可购态全在服务端, 成交后重查, 不在前端自己减。
      shopQuery.reload()
      stationQuery.reload()
    } catch (error) {
      // 服务端回了失败信封 (业务拒绝或通用失败) = 这一单已了结, 下一单换新 id; 其余失败保留原 id 供重试。
      if (error instanceof WebUiCallError && error.code === SERVER_FAILURE_CODE) {
        pendingPurchase.current = null
      }
      setPurchaseError(toError(error))
    } finally {
      purchaseInFlight.current = false
      setPurchasing(false)
      setConfirmEntry(null)
    }
  }

  const shopColumns = (shop: MunitionsShopResult): readonly DataTableColumn<MunitionsShopEntry>[] => [
    {
      key: 'name',
      header: '名称',
      render: (entry) => {
        const title = shopEntryTitle(entry, names)
        return (
          <div className="flex items-center gap-2">
            <ItemIcon itemId={entry.itemId} label={title} />
            <span className="flex flex-col">
              <span className="text-foreground text-sm">{title}</span>
              <span className="text-muted-foreground text-xs">{shopEntrySubtitle(entry, names)}</span>
            </span>
          </div>
        )
      },
      sortValue: (entry) => shopEntryTitle(entry, names),
    },
    {
      key: 'kind',
      header: '类别',
      render: (entry) => (
        <Tag size="sm" tone="neutral">
          {SHOP_KIND_LABEL[entry.kind]}
        </Tag>
      ),
    },
    {
      key: 'requiredLevel',
      header: '等级',
      numeric: true,
      render: (entry) => `Lv.${String(entry.requiredLevel)}`,
      sortValue: (entry) => entry.requiredLevel,
    },
    {
      key: 'price',
      header: '价格',
      numeric: true,
      render: (entry) => <Currency amount={entry.price} currency="credit" size="sm" />,
      sortValue: (entry) => entry.price,
    },
    {
      key: 'status',
      header: '状态',
      render: (entry) => {
        const status = shopStatus(entry, shop)
        return (
          <Tag size="sm" tone={status.tone}>
            {status.text}
          </Tag>
        )
      },
    },
    {
      key: 'action',
      header: '操作',
      render: (entry) => (
        <Button
          disabled={!entry.purchasable || purchasing}
          onClick={() => {
            setPurchaseError(null)
            setConfirmEntry(entry)
          }}
          size="sm"
          variant="brand"
        >
          购买
        </Button>
      ),
    },
  ]

  if (stationQuery.status === 'loading') {
    return <LoadingBlock label="正在读取军械台状态" />
  }
  if (stationQuery.status === 'error') {
    return <ErrorBlock message={callErrorText(stationQuery.error)} onRetry={stationQuery.reload} />
  }
  if (stationData === null) {
    return <ErrorBlock message="job.munitions.state 回执为空" onRetry={stationQuery.reload} />
  }

  const needle = filterText.trim().toLowerCase()
  const filteredBlueprints = blueprints.filter(
    (blueprint) =>
      needle === '' ||
      blueprint.blueprintId.toLowerCase().includes(needle) ||
      blueprint.gunId.toLowerCase().includes(needle),
  )
  const foundSelectedBlueprint = blueprints.find(
    (blueprint) => blueprint.blueprintId === selectedBlueprintId,
  )
  const selectedBlueprint = foundSelectedBlueprint === undefined ? null : foundSelectedBlueprint

  return (
    <div className="flex flex-col gap-4">
      <Panel title="军火商">
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-3 gap-4">
            <Stat label="职业等级" value={`Lv.${String(stationData.level)}`} />
            <Stat
              label="军火台"
              value={`${String(stationData.benchesPlaced)} / ${String(stationData.benchCap)} 台`}
              hint="跨维度权威计数, 与下方扫到几台无关"
            />
            <Stat
              label="就近搜索半径"
              value={`${String(stationData.searchRadiusBlocks)} 格`}
              hint="没扫到只代表这个半径内没有"
            />
          </div>
          {stationData.gunsmithEnabled ? null : (
            <Surface tone="warning">
              <p className="text-foreground text-sm">
                枪匠冲压与装配整条链当前是关闭的 (服务端配置), 装配台点开工只会被拒绝 —— 下面的冲压机与
                装配台仅供查看
              </p>
            </Surface>
          )}
        </div>
      </Panel>

      <Panel
        actions={
          <Dropdown
            className="w-32"
            onChange={setShopFilter}
            options={SHOP_FILTER_OPTIONS}
            size="sm"
            value={shopFilter}
          />
        }
        title="系统采购"
      >
        {shopQuery.status === 'loading' && shopData === null ? (
          <LoadingBlock label="正在读取采购目录" />
        ) : null}
        {shopQuery.status === 'error' ? (
          <ErrorBlock message={callErrorText(shopQuery.error)} onRetry={shopQuery.reload} />
        ) : null}
        {shopData === null ? null : (
          <div className="flex flex-col gap-3">
            <div className="grid grid-cols-3 gap-4">
              <Stat
                label="信用点余额"
                value={
                  shopData.balance === null ? (
                    '经济未就绪'
                  ) : (
                    <Currency amount={shopData.balance} currency="credit" />
                  )
                }
              />
              <Stat
                label="军火台拥有数"
                value={`${String(shopData.benchesPlaced + shopData.benchesHeld)} / ${String(shopData.benchCap)} 台`}
                hint={`已放置 ${String(shopData.benchesPlaced)} · 背包里 ${String(shopData.benchesHeld)}`}
              />
              <Stat
                label="现在可买"
                value={`${String(shopData.entries.filter((entry) => entry.purchasable).length)} / ${String(
                  shopData.entryCount,
                )} 项`}
              />
            </div>
            <p className="text-muted-foreground text-xs">
              按军火商等级解锁, 信用点由系统直接销毁、购买后不退款。军火台拥有数 = 已放置 + 背包里未放置的
              (放进箱子里的不计, 但放置时照样受台数上限约束); 背包满时不发货也不扣款。图纸装配时不消耗, 买一张可一直用
            </p>
            {shopData.gunsmithEnabled ? null : (
              <Surface tone="warning">
                <p className="text-foreground text-sm">
                  枪匠链当前关闭 (服务端配置), 冲压机、装配台与图纸暂不出售, 只能先买军火台
                </p>
              </Surface>
            )}
            {lastPurchase === null ? null : (
              <FeedbackAlert
                key={lastPurchase.purchaseId}
                message={
                  lastPurchase.replayed
                    ? `这一单此前已成交, 本次未重复扣费: ${shopEntryTitle(lastPurchase, names)}`
                    : `已购得 ${shopEntryTitle(lastPurchase, names)} · 实扣 ${formatAmount(
                        lastPurchase.price,
                      )} 信用点${
                        lastPurchase.balanceAfter === null
                          ? ''
                          : ` · 余额 ${formatAmount(lastPurchase.balanceAfter)}`
                      }`
                }
                onDismiss={() => {
                  setLastPurchase(null)
                }}
                tone={lastPurchase.replayed ? 'info' : 'success'}
              />
            )}
            {purchaseError === null ? null : (
              <FeedbackAlert
                message={callErrorText(purchaseError)}
                onDismiss={() => {
                  setPurchaseError(null)
                }}
                autoDismissMs={0}
                tone="danger"
              />
            )}
            <DataTable
              columns={shopColumns(shopData)}
              emptyHint="这个分类下没有条目"
              rowKey={(entry) => entry.entryId}
              rows={shopData.entries.filter((entry) => matchesShopFilter(entry, shopFilter))}
            />
          </div>
        )}
      </Panel>

      <ConfirmDangerDialog
        confirmLabel="确认购买"
        loading={purchasing}
        message={
          confirmEntry === null
            ? ''
            : `将花费 ${formatAmount(confirmEntry.price)} 信用点购买「${shopEntryTitle(
                confirmEntry,
                names,
              )}」。信用点由系统直接销毁, 购买后不退款; 物品会放进背包。`
        }
        onConfirm={() => {
          if (confirmEntry !== null) {
            void handlePurchase(confirmEntry)
          }
        }}
        onOpenChange={(open) => {
          if (!open) {
            setConfirmEntry(null)
          }
        }}
        open={confirmEntry !== null}
        title="确认购买"
      />

      <Panel title="生产状态">
        <div className="flex flex-col gap-3">
          {stationData.stations.map((station) => {
            /*
             * 三种取值都要分开处理 (契约): null = 没扫到或装配台读不出; 0 = 军火台未选口径 (此时
             * requiredTicks 同为 0, 直接相除是 NaN); 正数才是真进度。收成一个对象是为了让下面的
             * Meter 不必写 `?? 0` 这类会把"读不出"悄悄画成 0% 的兜底。
             */
            const progress =
              station.progressTicks !== null && station.requiredTicks !== null && station.requiredTicks > 0
                ? { value: station.progressTicks, max: station.requiredTicks }
                : null
            return (
              <Surface key={station.stationId}>
                <div className="flex flex-col gap-2">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <h3 className="font-medium text-foreground text-sm">{nameOf(station.nameKey)}</h3>
                    <div className="flex items-center gap-3">
                      {station.pos === null ? (
                        <Tag tone="neutral">
                          附近未找到 (半径 {stationData.searchRadiusBlocks} 格)
                        </Tag>
                      ) : (
                        <>
                          <span className="text-muted-foreground text-xs">
                            坐标 ({station.pos.x}, {station.pos.y}, {station.pos.z})
                          </span>
                          <Tag tone={station.running ? 'success' : 'neutral'}>
                            {station.running ? '运行中' : '空闲'}
                          </Tag>
                        </>
                      )}
                    </div>
                  </div>

                  {station.pos === null ? null : (
                    <>
                      <div className="flex items-center gap-4">
                        {progress !== null ? (
                          <Meter
                            className="flex-1"
                            label="加工进度"
                            max={progress.max}
                            tone={station.running ? 'brand' : 'neutral'}
                            value={progress.value}
                            valueText={`${ticksToSecondsText(progress.value)} / ${ticksToSecondsText(
                              progress.max,
                            )}`}
                          />
                        ) : (
                          <span className="flex-1 text-muted-foreground text-xs">
                            {station.running
                              ? '加工中 · 这台机器的已进行时间服务端读不出, 只能确认它在转'
                              : station.requiredTicks === 0
                                ? '未选口径, 尚未开始加工'
                                : '当前空闲'}
                          </span>
                        )}
                        {station.outputItemId === null ? (
                          <span className="text-muted-foreground text-xs">输出槽为空</span>
                        ) : (
                          <div className="flex items-center gap-2">
                            <ItemIcon itemId={station.outputItemId} label={station.outputItemId} />
                            <span className="text-muted-foreground text-xs">
                              {station.outputItemId} x{station.outputCount}
                            </span>
                          </div>
                        )}
                      </div>
                      <StationDetail station={station} />
                    </>
                  )}
                </div>
              </Surface>
            )
          })}
          <p className="text-muted-foreground text-xs">
            军火台的产出以缓冲发数为准: 未装 TACZ 时输出槽恒空而缓冲照常累积
          </p>
        </div>
      </Panel>

      <Panel title="图纸百科">
        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-1">
            <TextInput
              onChange={setFilterText}
              placeholder="按图纸/枪械 ID 筛选 (英文/数字)"
              size="sm"
              value={filterText}
            />
            <span className="text-muted-foreground text-xs">
              中文输入暂未开放, 检索请用英文或数字 ID
              {blueprintData === null ? '' : ` · 共 ${String(blueprintData.blueprintCount)} 款图纸`}
            </span>
          </div>

          {blueprintQuery.status === 'loading' ? <LoadingBlock label="正在读取图纸百科" /> : null}
          {blueprintQuery.status === 'error' ? (
            <ErrorBlock
              message={callErrorText(blueprintQuery.error)}
              onRetry={blueprintQuery.reload}
            />
          ) : null}
          {blueprintQuery.status === 'ready' && filteredBlueprints.length === 0 ? (
            <EmptyBlock hint="换一个关键词试试" title="没有匹配的图纸" />
          ) : null}

          {blueprintQuery.status === 'ready' && filteredBlueprints.length > 0 ? (
            <div className="flex flex-col gap-2">
              {filteredBlueprints.map((blueprint) => {
                const selected = blueprint.blueprintId === selectedBlueprintId
                return (
                  <button
                    className={`flex w-full items-center justify-between gap-4 rounded-lg border p-3 text-left transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                      selected ? 'border-brand bg-brand/12' : 'border-border bg-muted/40 hover:bg-accent'
                    }`}
                    key={blueprint.blueprintId}
                    onClick={() => {
                      setSelectedBlueprintId(selected ? null : blueprint.blueprintId)
                    }}
                    type="button"
                  >
                    <span className="flex flex-col">
                      <span className="font-medium text-foreground text-sm">
                        {blueprintTitle(blueprint, names)}
                      </span>
                      <span className="text-muted-foreground text-xs">
                        {blueprint.gunId} · {nameOf(blueprint.platformLabelKey)}
                      </span>
                    </span>
                    <span className="text-muted-foreground text-xs">
                      {blueprint.requiredParts.length} 种部件
                    </span>
                  </button>
                )
              })}
            </div>
          ) : null}
        </div>
      </Panel>

      {selectedBlueprint === null || blueprintData === null ? null : (
        <Panel title={`${blueprintTitle(selectedBlueprint, names)} 所需部件`}>
          <div className="flex flex-wrap gap-3">
            {selectedBlueprint.requiredParts.map((part) => (
              <ItemSlot
                count={part.count}
                itemId={blueprintData.partItemId}
                key={part.partId}
                label={nameOf(part.labelKey)}
                scale={2}
              />
            ))}
          </div>
          <p className="text-muted-foreground text-xs">
            195 种枪匠零件共用同一个物品 id, 平台/部位/品质由 NBT 区分, 故这里的图标全是同一张
          </p>
        </Panel>
      )}
    </div>
  )
}
