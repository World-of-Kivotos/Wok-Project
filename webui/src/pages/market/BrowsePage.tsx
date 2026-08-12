import type { ReactElement, ReactNode } from 'react'
import { useEffect, useRef, useState } from 'react'
import type { PixelFrameTone, PixelSelectOption, PixelTableColumn } from '../../components/pixel'
import {
  ItemIcon,
  PixelBadge,
  PixelButton,
  PixelCurrency,
  PixelEmpty,
  PixelError,
  PixelFrame,
  PixelIcon,
  PixelInput,
  PixelLoading,
  PixelModal,
  PixelScrollArea,
  PixelSelect,
  PixelStepper,
  PixelTable,
  PixelToast,
  PixelTooltip,
} from '../../components/pixel'
import { useItemNames } from '../../lib/i18n'
import type {
  CategoryNode,
  MarketBaseValueResult,
  MarketListPayload,
  MarketListing,
  MarketSort,
} from '../../lib/types'
import type { PlannedTradableResult } from '../../mock'
import { callMock, getWorld, refreshWalletAndInventory, useMockAction, useMockWorld } from '../../mock'

/**
 * 跳蚤市场 · 浏览与购买 (接线清单 B 组)。
 *
 * === 本页依赖的契约, 按接线状态分两类 ===
 *
 * 真契约 (已在 lib/types.ts, 接线即换后端, 页面不动):
 *   B8  market.categories  左栏分类树; 叶子 label 是**翻译键**, 须过 client.i18n (A2/A12)
 *   B1  market.list        订单簿主体; 已知缺陷: 无 total, 见下方"分页"一段
 *   B3  market.buy         购买 (count 支持部分买入)
 *   B7  market.baseValue   购买确认里的"相对基准价"参照, 分层 source: override / preset / none
 *   A7  player.inventory   经 mock 的真域镜像读"我背包里已有几件", 用于买入后果可见
 *
 * 假定契约 (后端还没有, 走 mock/planned.ts; 接线时按此表逐条核销):
 *   B10 market.p2pCap      工具栏的每日 P2P 额度。**只读展示** —— 买入是否计入额度由服务端记账,
 *                          mock 不写回 usedToday, 别照着这个数字推断服务端行为
 *   B12 market.tradable    购买确认前的标的可交易性判定; 判定为不可交易时禁用确认按钮
 *   A5  player.profile     余额基线。刻意不自己拼 mirror.wallet + walletOverlay ——
 *                          profile 的 wallet 已把 planned 域收支叠加算进去 (同 TabletShell 的理由),
 *                          自己再拼一遍等于把账目规则复制成两份, 必然与顶栏的数字漂移
 *
 * 受阻项 (不是本页能解的, 只能在 UI 上如实标出):
 *   A14 中文输入 BLOCKED   搜索框走 PixelInput 的 onRequestEdit 接口位, 当前点击只喊话不接收输入
 *   A10 错误码中文化未做   购买失败展示的是服务端异常原文, 本页不做任何猜测式翻译
 *   A16 玩家名解析未做     sellerName 是挂单瞬间的快照, 卖家改名后会过期, 本页不去二次解析
 *
 * === 三处刻意的设计取舍 ===
 *
 * 1. 分页做成"还有下一页"而不是页码。market.list 的回执只有 listings/page/pageSize, **没有 total**
 *    (B1; 全库唯一带 total 的是 admin.listItems)。没有总条数就算不出总页数, 硬画页码只能靠猜,
 *    而猜错的表现是"点第 7 页是空的"。故只给上一页/下一页, 并按"本页拿满 pageSize 即认为可能还有下一页"
 *    这一启发式点亮下一页 —— 它会在总数恰好是 pageSize 整数倍时多给一次翻页, 翻过去是空页;
 *    这一态被如实做成空态 + 返回上一页, 不掩盖。
 *
 * 2. 分类树的分支节点只负责展开/收起, 不作过滤条件。market.list 的过滤参数只有 query, 而 DAO 那侧
 *    是 `item_id LIKE ?` —— 服务端没有"按分类查"的能力。若在前端按分类做本地过滤, 过滤的只是当前这一页,
 *    翻页后结果自相矛盾。故只有**叶子 (具体物品)** 可选中, 选中即把 itemId 作为 query 交给服务端。
 *
 * 3. 搜索框只做本页本地过滤, 不进 market.list 的 query。两条理由: 服务端 query 只匹配 itemId (不碰中文名),
 *    而玩家想搜的是中文名; 且 A14 未接线前搜索词根本无法输入, 把它接到服务端 query 上会做出一条
 *    "看着能用、其实永远搜不到东西"的路径。本地过滤覆盖 itemId / 中文名 / 卖家名三项, 范围限于当前页,
 *    UI 上写明这一点。
 */

/** planned/空入参 action 共用。提到模块级只是让"本页一共发几种请求"一眼可数。 */
const EMPTY_PAYLOAD: Record<string, never> = {}

/**
 * 每页条数。
 *
 * 取 5 有两层考虑: 平板内容区一屏能完整看到的行数在这个量级 (text-1x 行高 32 CSS px + 单元格内边距);
 * 另外它恰好等于 bridge.mock 种子里的挂单总数, 于是"满页 -> 点下一页 -> 空页"这条由 B1 缺陷决定的
 * 路径在 dev 下必然被走到, 而不是等上线才被玩家发现。
 */
const PAGE_SIZE = 5

/**
 * MarketDaoSqlite 的排序白名单全集。白名单外的任何字符串 (含服务端自己的缺省值 "created_at")
 * 都会被静默映射回 newest —— 静默是关键: 传错了不会报错, 只是排序没生效。
 */
const MARKET_SORTS: readonly MarketSort[] = ['newest', 'price_asc', 'price_desc']

const SORT_OPTIONS: readonly PixelSelectOption[] = [
  { value: 'newest', label: '最新上架' },
  { value: 'price_asc', label: '单价升序' },
  { value: 'price_desc', label: '单价降序' },
]

/** PixelSelect 的 onChange 只给字符串; 在这里收窄回白名单, 越界即抛而不是悄悄落回 newest。 */
function toMarketSort(value: string): MarketSort {
  const matched = MARKET_SORTS.find((candidate) => candidate === value)
  if (matched === undefined) {
    throw new Error(`不在 MarketDaoSqlite 排序白名单内的排序键: ${value}`)
  }
  return matched
}

/**
 * useItemNames 对每个入参键都会给值 (未解析出中文名时退回键本身), 故这里的 undefined 只可能是
 * "这个 descriptionId 压根没进过入参数组"。退回键本身而不是显示空白, 让缺口在界面上看得见。
 */
function displayName(names: Record<string, string>, descriptionId: string): string {
  const resolved = names[descriptionId]
  return resolved === undefined ? descriptionId : resolved
}

function formatAge(elapsedMs: number): string {
  const minutes = Math.floor(elapsedMs / 60_000)
  if (minutes < 1) {
    return '刚刚'
  }
  if (minutes < 60) {
    return `${String(minutes)} 分钟前`
  }
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    return `${String(hours)} 小时前`
  }
  return `${String(Math.floor(hours / 24))} 天前`
}

/** 树的层级缩进。穷举成完整字面量而不是拼 `pl-${n}` —— 拼出来的类 Tailwind 不生成, 且不报错。 */
function indentClass(depth: number): string {
  if (depth <= 0) {
    return 'pl-2'
  }
  if (depth === 1) {
    return 'pl-6'
  }
  return 'pl-10'
}

/** 收集全部叶子的 label (翻译键), 交给 useItemNames 一次性批量解析。 */
function collectLeafLabels(nodes: readonly CategoryNode[]): string[] {
  const keys: string[] = []
  for (const node of nodes) {
    if ('children' in node) {
      keys.push(...collectLeafLabels(node.children))
      continue
    }
    keys.push(node.label)
  }
  return keys
}

interface ToastEntry {
  readonly id: number
  readonly tone: PixelFrameTone
  readonly message: string
}

// ============================================================
// 左栏分类树
// ============================================================

interface CategoryRowProps {
  readonly node: CategoryNode
  readonly depth: number
  readonly expandedIds: ReadonlySet<string>
  readonly names: Record<string, string>
  readonly selectedItemId: string | null
  readonly onToggle: (id: string) => void
  readonly onSelect: (itemId: string | null) => void
}

/**
 * 一行分类。分支与叶子共用一个组件而不是拆两个: 两者的差别只有"点击做什么"和"有没有子层",
 * 拆开会让递归的传参在两处各写一遍。
 *
 * 刻意不上 role="tree"/"treeitem": 那套角色要求实现 roving tabindex 与上下左右四向键盘导航,
 * 而当前形态用原生 <button> 就已经满足 conventions 九-1/2 的键盘下限 (Tab 可达、Enter/Space 触发、
 * focus-visible 换底色)。用了 tree 角色却不给方向键, 反而是对读屏用户的误报。
 */
function CategoryRow(props: CategoryRowProps): ReactElement {
  const { node, depth, expandedIds, names, selectedItemId, onToggle, onSelect } = props

  if ('children' in node) {
    const open = expandedIds.has(node.id)
    return (
      <li>
        <button
          type="button"
          aria-expanded={open}
          onClick={() => {
            onToggle(node.id)
          }}
          className={`flex w-full items-center gap-2 text-1x text-fg outline-none hover:bg-raised focus-visible:bg-raised ${indentClass(depth)}`}
        >
          <PixelIcon name={open ? 'arrow-down' : 'arrow-right'} scale={1} />
          <span className="min-w-0 truncate">{node.label}</span>
        </button>
        {open ? (
          <ul className="flex flex-col">
            {node.children.map((child) => (
              <CategoryRow
                key={child.id}
                node={child}
                depth={depth + 1}
                expandedIds={expandedIds}
                names={names}
                selectedItemId={selectedItemId}
                onToggle={onToggle}
                onSelect={onSelect}
              />
            ))}
          </ul>
        ) : null}
      </li>
    )
  }

  const active = node.itemId === selectedItemId
  const name = displayName(names, node.label)
  return (
    <li>
      <button
        type="button"
        aria-pressed={active}
        onClick={() => {
          // 再点一次即取消过滤: 树里没有别的地方能承载"回到全部"这个动作, 加一个"全部"伪节点会与
          // 服务端回来的树结构混在一起, 分不清哪些是真数据。
          onSelect(active ? null : node.itemId)
        }}
        className={`flex w-full items-center gap-2 text-1x outline-none hover:bg-raised focus-visible:bg-raised ${indentClass(depth)} ${
          active ? 'text-accent' : 'text-fg'
        }`}
      >
        <ItemIcon itemId={node.itemId} label={name} scale={1} />
        <span className="min-w-0 truncate">{name}</span>
      </button>
    </li>
  )
}

interface CategoryTreeProps {
  readonly nodes: readonly CategoryNode[]
  readonly selectedItemId: string | null
  readonly onSelect: (itemId: string | null) => void
}

function CategoryTree({ nodes, selectedItemId, onSelect }: CategoryTreeProps): ReactElement {
  const names = useItemNames(collectLeafLabels(nodes))
  /*
   * null = 玩家还没碰过折叠状态, 此时默认展开顶层。写成"覆盖值"而不是在 effect 里往 state 里塞默认值:
   * 树是异步到达的, 用 effect 播种会有一帧空树, 且玩家收起顶层后数据一刷新又会被播种回来。
   */
  const [expandedOverride, setExpandedOverride] = useState<ReadonlySet<string> | null>(null)
  const expandedIds =
    expandedOverride === null ? new Set(nodes.map((node) => node.id)) : expandedOverride

  const handleToggle = (id: string): void => {
    const next = new Set(expandedIds)
    if (next.has(id)) {
      next.delete(id)
    } else {
      next.add(id)
    }
    setExpandedOverride(next)
  }

  return (
    <ul className="flex flex-col p-2">
      {nodes.map((node) => (
        <CategoryRow
          key={node.id}
          node={node}
          depth={0}
          expandedIds={expandedIds}
          names={names}
          selectedItemId={selectedItemId}
          onToggle={handleToggle}
          onSelect={onSelect}
        />
      ))}
    </ul>
  )
}

// ============================================================
// 购买确认对话框
// ============================================================

interface DialogRowProps {
  readonly label: string
  readonly value: ReactNode
}

function DialogRow({ label, value }: DialogRowProps): ReactElement {
  return (
    <div className="flex items-center justify-between gap-2">
      <span className="text-1x text-muted">{label}</span>
      <span className="min-w-0 truncate text-1x text-fg">{value}</span>
    </div>
  )
}

interface BaseValueLineProps {
  readonly result: MarketBaseValueResult
  readonly unitPrice: number
}

/**
 * 挂价相对基准价的偏离度。只做展示不做拦截 —— 偏离度在服务端是手续费的输入 (MarketFee),
 * 买家侧不为此付费, 拿它挡下单等于替产品发明了一条不存在的规则。
 */
function BaseValueLine({ result, unitPrice }: BaseValueLineProps): ReactElement {
  if (result.v0 === null) {
    return (
      <p className="text-1x text-muted">该物品无基准价 (source={result.source}), 无从判断挂价高低。</p>
    )
  }
  if (result.v0 <= 0) {
    // 基准价为 0 或负数是服务端不该产生的状态; 如实显示原值并跳过百分比, 不用 0 去做除数。
    return (
      <p className="text-1x text-warning">
        基准价异常: {String(result.v0)} (source={result.source})
      </p>
    )
  }
  const premium = Math.round(((unitPrice - result.v0) / result.v0) * 100)
  const tone: PixelFrameTone = premium > 0 ? 'warning' : premium < 0 ? 'success' : 'neutral'
  return (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <span className="text-1x text-muted">
        基准价 {String(result.v0)} ({result.source})
      </span>
      <PixelBadge tone={tone} size="sm">
        {premium >= 0 ? `高 ${String(premium)}%` : `低 ${String(-premium)}%`}
      </PixelBadge>
    </div>
  )
}

/** reason 是给玩家看的中文, reasonCode 是机器码; 两者都缺时如实说"服务端没给", 不编一句理由。 */
function tradableReason(result: PlannedTradableResult): string {
  if (result.reason !== null) {
    return result.reason
  }
  if (result.reasonCode !== null) {
    return result.reasonCode
  }
  return '服务端未给出原因 (B12 的 reasonCode 目前仍是自由字符串位)'
}

interface BuyDialogProps {
  readonly listing: MarketListing
  readonly itemName: string
  /** null = 真域背包镜像还没拉到, 与"背包里一件都没有"是两回事, 不合成 0。 */
  readonly ownedCount: number | null
  readonly onClose: () => void
  readonly onBought: (message: string) => void
}

function BuyDialog({ listing, itemName, ownedCount, onClose, onBought }: BuyDialogProps): ReactElement {
  const [count, setCount] = useState(1)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)

  const baseValue = useMockAction('market.baseValue', { itemId: listing.itemId })
  const tradable = useMockAction('market.tradable', { itemId: listing.itemId })
  const profile = useMockAction('player.profile', EMPTY_PAYLOAD)

  /*
   * 总价前端自己乘, 因为 MarketListing.total 是**整单**总价, 部分购买时用不上。
   * 服务端回执里的 total 才是权威值, 这里算的只用于下单前展示。
   */
  const total = listing.unitPrice * count
  const totalIsSafe = Number.isSafeInteger(total)
  const balance = profile.status === 'ready' ? profile.data.wallet.credit : null
  const shortfall = balance === null || !totalIsSafe ? null : total - balance
  const blockedByTradable = tradable.status === 'ready' && !tradable.data.tradable

  const handleConfirm = async (): Promise<void> => {
    setSubmitting(true)
    setSubmitError(null)
    try {
      const result = await callMock('market.buy', { listingId: listing.id, count })
      onBought(
        `已买入 ${itemName} x${String(result.count)}, 实付 ${String(result.total)} 信用点 (手续费 ${String(result.fee)})`,
      )
    } catch (error: unknown) {
      /*
       * 提交按钮就是这条链的最外层 (lib/bridge 的收口约定), 在这里收住并展示, 不再往上抛。
       * 展示的是服务端原文 —— 错误码中文化 (A10) 还没做, 任何"翻译"都是猜的。
       */
      setSubmitError(error instanceof Error ? error.message : String(error))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <PixelModal open title="购买确认" size="lg" onClose={onClose}>
      <div className="flex flex-col gap-3">
        <div className="flex items-center gap-2">
          <ItemIcon itemId={listing.itemId} label={itemName} scale={1} />
          <span className="min-w-0 truncate text-1x text-fg">{itemName}</span>
        </div>

        <DialogRow label="卖家" value={listing.sellerName} />
        <DialogRow label="剩余" value={String(listing.count)} />
        <DialogRow label="上架" value={formatAge(Date.now() - listing.createdAt)} />
        <DialogRow
          label="单价"
          value={<PixelCurrency amount={listing.unitPrice} currency="credit" size="sm" className="break-all" />}
        />
        <DialogRow
          label="背包持有"
          value={ownedCount === null ? '未知 (镜像未就绪)' : String(ownedCount)}
        />

        {/*
          scale={1}: 对话框 lg 档宽 128 个像素格, 而 9-slice 边框宽走 slice x --pixel-scale 这条独立派生链,
          继承 :root 的 2 会让步进器 (两个按钮 + 数值井, 每个都带一圈边框) 整体挤出内容盒。
          按 conventions 二-2.2 "行内控件一律传 scale={1}", 在这一格里把倍率降到 1, 同一行的控件因此同倍率。
        */}
        <PixelFrame variant="inset" scale={1} className="flex flex-col gap-2 p-3">
          <span className="text-1x text-muted">购买数量 (支持部分购买)</span>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <PixelStepper value={count} onChange={setCount} min={1} max={listing.count} size="sm" />
            <PixelButton
              size="sm"
              disabled={count >= listing.count}
              onClick={() => {
                setCount(listing.count)
              }}
            >
              全部
            </PixelButton>
          </div>
        </PixelFrame>

        <div className="flex items-center justify-between gap-2">
          <span className="text-1x text-muted">总价</span>
          {totalIsSafe ? (
            <PixelCurrency amount={total} currency="credit" size="sm" className="break-all" />
          ) : (
            <PixelBadge tone="danger" size="sm">
              超出 2^53
            </PixelBadge>
          )}
        </div>
        {totalIsSafe ? null : (
          <p className="text-1x text-danger">
            单价 x 数量 已越过 JSON number 的安全整数上限 (契约层标注的 Java long 精度风险),
            这里显示的总价不可信, 以服务端回执为准。
          </p>
        )}

        {baseValue.status === 'loading' ? <PixelLoading size="sm" label="读取基准价" /> : null}
        {baseValue.status === 'error' ? (
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-1x text-danger">基准价读取失败: {baseValue.error.message}</span>
            <PixelButton size="sm" onClick={baseValue.reload}>
              重试
            </PixelButton>
          </div>
        ) : null}
        {baseValue.status === 'ready' ? (
          <BaseValueLine result={baseValue.data} unitPrice={listing.unitPrice} />
        ) : null}

        {tradable.status === 'loading' ? <PixelLoading size="sm" label="校验可交易性" /> : null}
        {tradable.status === 'error' ? (
          // market.tradable 本身还没接线 (B12), 它查不到不该反过来挡住真契约已经支持的买入。
          <p className="text-1x text-warning">可交易性未知 (B12 未接线): {tradable.error.message}</p>
        ) : null}
        {blockedByTradable && tradable.status === 'ready' ? (
          <PixelFrame variant="panel" tone="danger" scale={1} className="p-3">
            <p className="text-1x text-fg">不可交易: {tradableReason(tradable.data)}</p>
          </PixelFrame>
        ) : null}

        {profile.status === 'loading' ? <PixelLoading size="sm" label="读取余额" /> : null}
        {profile.status === 'error' ? (
          <p className="text-1x text-danger">余额读取失败: {profile.error.message}</p>
        ) : null}
        {shortfall !== null && shortfall > 0 ? (
          // 不禁用确认: 余额裁决在服务端, 前端预检只是提前告知; 拦下来反而会掩盖服务端真实的拒绝路径。
          <p className="text-1x text-warning">
            预估还差 {String(shortfall)} 信用点, 提交后大概率被服务端拒绝。
          </p>
        ) : null}

        {submitError === null ? null : (
          <PixelFrame variant="panel" tone="danger" scale={1} className="p-3">
            <p role="alert" className="text-1x text-fg">
              {submitError}
            </p>
          </PixelFrame>
        )}

        {/*
          按钮竖排而不是并排: PixelModal 最宽的 lg 档是 w-128 (128 个像素格), 扣掉 window 框的
          slice x --pixel-scale 边框与 p-4 内边距后, 内容盒只剩约 104 格 —— 两个 md 档按钮并排就超了。
          这是组件库缺一档更宽 modal 的问题, 不在本页绕 (className 覆盖不了 WIDTH_CLASS, 也违反 conventions 一-1.1)。
        */}
        <div className="flex flex-col gap-2">
          <PixelButton
            tone="accent"
            size="sm"
            loading={submitting}
            disabled={blockedByTradable}
            onClick={() => {
              void handleConfirm()
            }}
          >
            确认购买
          </PixelButton>
          <PixelButton size="sm" disabled={submitting} onClick={onClose}>
            取消
          </PixelButton>
        </div>
      </div>
    </PixelModal>
  )
}

// ============================================================
// 页面
// ============================================================

export function BrowsePage(): ReactElement {
  const world = useMockWorld()
  const [sort, setSort] = useState<MarketSort>('newest')
  const [page, setPage] = useState(0)
  const [categoryItemId, setCategoryItemId] = useState<string | null>(null)
  const [localFilter, setLocalFilter] = useState('')
  const [selected, setSelected] = useState<MarketListing | null>(null)
  const [toasts, setToasts] = useState<readonly ToastEntry[]>([])
  const toastIdRef = useRef(0)

  const categories = useMockAction('market.categories', EMPTY_PAYLOAD)
  const p2pCap = useMockAction('market.p2pCap', EMPTY_PAYLOAD)

  /*
   * sort 为 newest 时**不传 sort 字段**: 服务端缺省值 "created_at" 不在 DAO 白名单, 会被静默落回 newest,
   * 结果与显式传 newest 一致。省略字段让"前端没有意见"这件事在报文里如实体现 (lib/types.ts 的 MarketSort 注释)。
   */
  const listPayload: MarketListPayload = {
    ...(categoryItemId === null ? {} : { query: categoryItemId }),
    ...(sort === 'newest' ? {} : { sort }),
    page,
    pageSize: PAGE_SIZE,
  }
  const listQuery = useMockAction('market.list', listPayload)

  useEffect(() => {
    /*
     * 直接深链进本页 (未经 hub) 时真域镜像还是空的, 背包持有量会显示"未知"。这里补一次预热,
     * 已经热过就不重复发请求 —— 读的是 getWorld() 而不是订阅值, 免得镜像一变就重跑这个 effect。
     *
     * 判据看 inventory 本身而不是 mirror.refreshedAt: refreshedAt 是四块镜像共用的一个时间戳,
     * 开箱页的 refreshCaseTotals 只写钱包与皮肤总数却同样会推进它 (mock/handlers.ts)。
     * 按 refreshedAt 判定的话, 先逛过开箱页 (或首屏预热时背包那一路失败而开箱那一路成功) 再进本页,
     * 这次补拉会被直接跳过, 背包持有量就永久停在"未知"且没有任何重试。
     */
    if (getWorld().mirror.inventory !== null) {
      return
    }
    refreshWalletAndInventory().catch((error: unknown) => {
      console.error('[market-browse] 真域镜像预热失败, 背包持有量将显示为未知:', error)
    })
  }, [])

  const pushToast = (tone: PixelFrameTone, message: string): void => {
    toastIdRef.current += 1
    const entry: ToastEntry = { id: toastIdRef.current, tone, message }
    setToasts((current) => [...current, entry])
  }

  const dismissToast = (id: number): void => {
    setToasts((current) => current.filter((entry) => entry.id !== id))
  }

  const pageListings: readonly MarketListing[] =
    listQuery.status === 'ready' ? listQuery.data.listings : []
  const names = useItemNames(pageListings.map((listing) => listing.descriptionId))

  const keyword = localFilter.trim().toLowerCase()
  const rows =
    keyword === ''
      ? pageListings
      : pageListings.filter(
          (listing) =>
            listing.itemId.toLowerCase().includes(keyword) ||
            displayName(names, listing.descriptionId).toLowerCase().includes(keyword) ||
            listing.sellerName.toLowerCase().includes(keyword),
        )

  /** 满页即认为可能还有下一页 —— 无 total 的必然代价, 见文件头"分页"一段。用未过滤的条数判定。 */
  const hasNextPage = listQuery.status === 'ready' && listQuery.data.listings.length === PAGE_SIZE

  const inventory = world.mirror.inventory
  const ownedCountOf = (itemId: string): number | null => {
    if (inventory === null) {
      return null
    }
    return inventory
      .filter((item) => item.itemId === itemId)
      .reduce((sum, item) => sum + item.count, 0)
  }

  const now = Date.now()

  /*
   * 列一律不给 sortValue (PixelTable 的表头排序因此关闭)。排序权在服务端: 表头排序只能重排当前这一页,
   * 与 sort 参数并存时, 玩家看到的顺序取决于两者谁最后生效 —— 那是个查不出来的现象。
   */
  const columns: readonly PixelTableColumn<MarketListing>[] = [
    {
      key: 'item',
      header: '物品',
      render: (row) => (
        <span className="flex items-center gap-2">
          <ItemIcon itemId={row.itemId} label={displayName(names, row.descriptionId)} scale={1} />
          <span className="text-1x text-fg">{displayName(names, row.descriptionId)}</span>
        </span>
      ),
    },
    {
      key: 'count',
      header: '数量',
      render: (row) => <span className="text-1x text-fg">{String(row.count)}</span>,
    },
    {
      key: 'unitPrice',
      header: '单价',
      // break-all: 种子里那条 unitPrice = 2^53-1 的挂单是 21 个字符的连续数字, 不许它把整张表撑出容器。
      render: (row) => (
        <PixelCurrency amount={row.unitPrice} currency="credit" size="sm" className="break-all" />
      ),
    },
    {
      key: 'seller',
      header: '卖家',
      render: (row) => <span className="text-1x text-muted">{row.sellerName}</span>,
    },
    {
      key: 'age',
      header: '上架',
      render: (row) => <span className="text-1x text-muted">{formatAge(now - row.createdAt)}</span>,
    },
    {
      key: 'action',
      header: '操作',
      render: (row) => (
        <PixelButton
          tone="accent"
          size="sm"
          onClick={() => {
            setSelected(row)
          }}
        >
          购买
        </PixelButton>
      ),
    },
  ]

  const handleRefresh = (): void => {
    listQuery.reload()
    categories.reload()
    p2pCap.reload()
    refreshWalletAndInventory().catch((error: unknown) => {
      // 控制台那条留着 (带完整堆栈, 排障要用); 但只写控制台等于玩家点了刷新之后看着旧的持有量当新的用,
      // 故同时在页面上给一条 danger 回执 —— 本页已经有 toast 通道, 不必另造一套。
      console.error('[market-browse] 手动刷新真域镜像失败:', error)
      pushToast(
        'danger',
        `背包/钱包刷新失败, 当前持有量仍是旧数据: ${error instanceof Error ? error.message : String(error)}`,
      )
    })
  }

  const handleSelectCategory = (itemId: string | null): void => {
    setCategoryItemId(itemId)
    // 换过滤条件必须回到第 0 页: 保留页码会让人停在一个新条件下根本不存在的页上, 表现为"点了分类就空了"。
    setPage(0)
  }

  return (
    <section className="flex flex-col gap-4">
      <PixelFrame variant="panel" className="flex flex-wrap items-center gap-4 p-3">
        <PixelInput
          value={localFilter}
          onChange={setLocalFilter}
          onRequestEdit={(current) => {
            /*
             * A14 (MC EditBox 叠加) 未实现: 接口位在这里接好了, 宿主日后把玩家输入经 onChange 回填即可,
             * 本页一行不用改。现在只能如实喊一声, 不做浏览器端假输入 —— MCEF 里键盘根本到不了 CEF。
             */
            pushToast(
              'warning',
              current === ''
                ? '宿主中文输入叠加尚未接线 (接线清单 A14), 搜索词暂时无法输入'
                : `宿主中文输入叠加尚未接线 (A14), 无法编辑当前搜索词: ${current}`,
            )
          }}
          placeholder="搜索本页 (物品名/卖家)"
          size="sm"
          className="w-128"
        />
        <PixelTooltip
          content={
            <span>
              文本输入需要 MC 原生 EditBox 浮层接管键盘 (接线清单 A14, BLOCKED)。在它接线之前,
              这个框只能由宿主回填, 且只做当前页的本地过滤 —— 服务端 market.list 的 query 只匹配 itemId, 不匹配中文名。
            </span>
          }
        >
          <PixelBadge tone="warning" size="sm">
            当前不可输入中文
          </PixelBadge>
        </PixelTooltip>
        {keyword === '' ? null : (
          <PixelButton
            size="sm"
            onClick={() => {
              setLocalFilter('')
            }}
          >
            清除搜索
          </PixelButton>
        )}

        <PixelSelect
          value={sort}
          options={SORT_OPTIONS}
          size="sm"
          className="w-128"
          onChange={(next) => {
            setSort(toMarketSort(next))
            setPage(0)
          }}
        />

        {categoryItemId === null ? null : (
          <>
            <PixelBadge tone="accent" size="sm">
              分类过滤 {categoryItemId}
            </PixelBadge>
            <PixelButton
              size="sm"
              onClick={() => {
                handleSelectCategory(null)
              }}
            >
              清除分类
            </PixelButton>
          </>
        )}

        {p2pCap.status === 'ready' ? (
          <PixelTooltip
            content={
              <span>
                每日 P2P 成交额度 (接线清单 B10, market.p2pCap 尚未接线, 数值来自 mock 世界)。
                买入是否计入额度由服务端记账, mock 不写回, 别照这个数字推断服务端行为。
              </span>
            }
          >
            <PixelBadge tone={p2pCap.data.remaining > 0 ? 'info' : 'danger'} size="sm">
              P2P 额度 {String(p2pCap.data.usedToday)}/{String(p2pCap.data.capPerDay)}
            </PixelBadge>
          </PixelTooltip>
        ) : null}
        {p2pCap.status === 'error' ? (
          <PixelBadge tone="danger" size="sm">
            P2P 额度不可用
          </PixelBadge>
        ) : null}

        <PixelButton icon="refresh" size="sm" onClick={handleRefresh}>
          刷新
        </PixelButton>
      </PixelFrame>

      <div className="flex gap-4">
        <aside className="flex w-128 shrink-0 flex-col gap-2">
          <h2 className="text-1x text-muted">分类 (只有具体物品可作过滤条件)</h2>
          {categories.status === 'loading' ? <PixelLoading size="sm" label="读取分类树" /> : null}
          {categories.status === 'error' ? (
            <PixelError
              message={`分类树读取失败: ${categories.error.message}`}
              onRetry={categories.reload}
            />
          ) : null}
          {categories.status === 'ready' ? (
            categories.data.length === 0 ? (
              <PixelEmpty title="没有分类" hint="market.categories 回了空数组" icon="filter" />
            ) : (
              <PixelScrollArea className="h-128" label="市场分类树">
                <CategoryTree
                  nodes={categories.data}
                  selectedItemId={categoryItemId}
                  onSelect={handleSelectCategory}
                />
              </PixelScrollArea>
            )
          ) : null}
        </aside>

        <div className="flex min-w-0 flex-1 flex-col gap-2">
          {listQuery.status === 'loading' ? (
            <PixelFrame variant="panel" className="flex items-center justify-center p-8">
              <PixelLoading label="正在读取挂单" />
            </PixelFrame>
          ) : null}
          {listQuery.status === 'error' ? (
            <PixelError
              message={`挂单列表读取失败: ${listQuery.error.message}`}
              onRetry={listQuery.reload}
            />
          ) : null}
          {listQuery.status === 'ready' && rows.length === 0 ? (
            <PixelEmpty
              title={page === 0 ? '这里还没有挂单' : '本页是空的'}
              hint={
                page === 0
                  ? keyword === ''
                    ? '换个分类, 或等别人上架'
                    : `当前页没有匹配 "${localFilter.trim()}" 的挂单 (搜索只过滤当前页)`
                  : 'market.list 不返回 total, 上一页拿满即被判定为"可能还有下一页"; 翻过来是空的说明恰好翻到头了'
              }
              icon="search"
            />
          ) : null}
          {listQuery.status === 'ready' && rows.length > 0 ? (
            <PixelTable
              columns={columns}
              rows={rows}
              rowKey={(row) => String(row.id)}
              {...(selected === null ? {} : { selectedRowKey: String(selected.id) })}
            />
          ) : null}

          <div className="flex flex-wrap items-center gap-4">
            <PixelButton
              icon="arrow-left"
              size="sm"
              disabled={page === 0}
              onClick={() => {
                setPage((current) => Math.max(0, current - 1))
              }}
            >
              上一页
            </PixelButton>
            <span className="text-1x text-muted">
              第 {String(page + 1)} 页 · 本页 {String(pageListings.length)} 条
              {keyword === '' ? '' : ` (过滤后 ${String(rows.length)} 条)`}
            </span>
            <PixelButton
              icon="arrow-right"
              size="sm"
              disabled={!hasNextPage}
              onClick={() => {
                setPage((current) => current + 1)
              }}
            >
              下一页
            </PixelButton>
            <PixelTooltip
              content={
                <span>
                  market.list 的回执只有 listings/page/pageSize, 没有 total (接线清单 B1), 前端算不出总页数,
                  故没有页码。下一页按"本页拿满 {String(PAGE_SIZE)} 条"点亮, 恰好翻到头时会多给一次, 翻过去是空页。
                </span>
              }
            >
              <PixelBadge tone="info" size="sm">
                为什么没有页码
              </PixelBadge>
            </PixelTooltip>
          </div>
        </div>
      </div>

      {/* Toast 的挂载点由消费页面决定 (PixelToast 本身不抢视口); z-40 压在 PixelModal 的 z-50 之下,
          购买失败的回执因此留在对话框内部, 不会被浮到对话框上面去。 */}
      <div className="fixed bottom-4 right-4 z-40 flex flex-col gap-2">
        {toasts.map((entry) => (
          <PixelToast
            key={entry.id}
            tone={entry.tone}
            message={entry.message}
            onDismiss={() => {
              dismissToast(entry.id)
            }}
          />
        ))}
      </div>

      {selected === null ? null : (
        <BuyDialog
          listing={selected}
          itemName={displayName(names, selected.descriptionId)}
          ownedCount={ownedCountOf(selected.itemId)}
          onClose={() => {
            setSelected(null)
          }}
          onBought={(message) => {
            setSelected(null)
            pushToast('success', message)
            // 买入后这一页的数量/条目都变了; 钱包与背包由 callMock 在 market.buy 成功后自动刷进真域镜像,
            // 顶栏余额与本页"背包持有"因此跟着动, 不需要本页再各刷一遍。
            listQuery.reload()
          }}
        />
      )}
    </section>
  )
}
