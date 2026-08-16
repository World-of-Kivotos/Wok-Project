import {
  ROUTE_MARKET,
  ROUTE_MARKET_HISTORY,
  ROUTE_MARKET_INBOX,
  ROUTE_MARKET_MINE,
  ROUTE_MARKET_SELL,
  useNavigate,
  useRouteMatch,
} from '@/router'
import {
  ArrowLeftIcon,
  ArrowRightIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  RefreshCwIcon,
  SearchIcon,
} from 'lucide-react'
import type { ReactElement, ReactNode } from 'react'
import { useEffect, useRef, useState } from 'react'
import type { DataTableColumn, DropdownOption, FeedbackTone, Tone } from '@/components/kit'
import {
  Button,
  Currency,
  DataTable,
  Dropdown,
  EmptyBlock,
  ErrorBlock,
  FeedbackAlert,
  Hint,
  ItemIcon,
  LoadingBlock,
  NumberInput,
  Panel,
  Surface,
  Tag,
  TextInput,
} from '@/components/kit'
import {
  Dialog,
  DialogFooter,
  DialogHeader,
  DialogPopup,
  DialogTitle,
} from '@/components/ui/dialog'
import { isMockActive } from '../../lib/bridge'
import { useItemDisplayNames, useItemNames } from '../../lib/i18n'
import type {
  CategoryNode,
  MarketBaseValueResult,
  MarketListPayload,
  MarketListing,
  MarketP2pCapResult,
  MarketSort,
} from '../../lib/types'
import { callMock, getWorld, refreshInventoryMirror, useMockAction, useMockWorld } from '../../mock'

/**
 * 跳蚤市场 · 浏览与购买 (接线清单 B 组)。
 *
 * === 本页依赖的契约, 按接线状态分两类 ===
 *
 * 全部依赖都是真契约 (已在 lib/types.ts):
 *   B8  market.categories  左栏分类树; 叶子 label 是**翻译键**, 须过 client.i18n (A2/A12)
 *   B1  market.list        订单簿主体; 已知缺陷: 无 total, 见下方"分页"一段
 *   B3  market.buy         购买 (count 支持部分买入)
 *   B7  market.baseValue   购买确认里的"相对基准价"参照, 分层 source: override / preset / none
 *   B10 market.p2pCap      工具栏的挂单额度。**只覆盖铜/铁 6 个标的**, 不是全品类额度 (回执自带
 *                          scopeItemIds, 文案必须点明范围); 且它约束的是**挂单卖出**侧 (cap 判定只在
 *                          MarketEngine.place 里), 买入一件不占 —— 徽标挂在买入页, 不写限定词必被误读。
 *                          回执把 activeHeld / soldToday 拆开给, 因为只有后者随日切归零
 *   A7  player.inventory   经 mock 的真域镜像读"我背包里已有几件", 用于买入后果可见
 *   A5  player.profile     余额基线。mirror 已不再持有钱包 (F057: 全库零读取方, 字段已删), walletOverlay
 *                          也已恒为 0 (见 mock/store.ts 该字段注释); 余额统一读 profile.wallet, 不再从
 *                          别处拼一份, 避免与顶栏的数字漂移
 *
 * 刻意**不**在买入侧查 market.tradable: 标的合法性由 market.place 在挂单源头堵死 (place 与 tradable
 * 共用同一份白名单实现), 违规标的进不了挂单表; 而买入侧只有 listing.itemId, 拿不到托管件的 NBT 品质,
 * 对塔罗牌这类同 id 不同品质的物品判不准 —— 判不准的二次检查比不检查更糟。
 *
 * 受阻项 (不是本页能解的, 只能在 UI 上如实标出):
 *   A14 中文输入 BLOCKED   搜索框走 TextInput 的 onRequestEdit 接口位, 当前点击只喊话不接收输入
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
 * 取 5 有两层考虑: 平板内容区一屏能完整看到的行数在这个量级;
 * 另外它恰好等于 bridge.mock 种子里的挂单总数, 于是"满页 -> 点下一页 -> 空页"这条由 B1 缺陷决定的
 * 路径在 dev 下必然被走到, 而不是等上线才被玩家发现。
 */
const PAGE_SIZE = 5

/**
 * MarketDaoSqlite 的排序白名单全集。白名单外的任何字符串 (含服务端自己的缺省值 "created_at")
 * 都会被静默映射回 newest —— 静默是关键: 传错了不会报错, 只是排序没生效。
 */
const MARKET_SORTS: readonly MarketSort[] = ['newest', 'price_asc', 'price_desc']

const SORT_OPTIONS: readonly DropdownOption<MarketSort>[] = [
  { value: 'newest', label: '最新上架' },
  { value: 'price_asc', label: '单价升序' },
  { value: 'price_desc', label: '单价降序' },
]

/** 下拉给回的值只是个字符串; 在这里收窄回白名单, 越界即抛而不是悄悄落回 newest。 */
function toMarketSort(value: string): MarketSort {
  const matched = MARKET_SORTS.find((candidate) => candidate === value)
  if (matched === undefined) {
    throw new Error(`不在 MarketDaoSqlite 排序白名单内的排序键: ${value}`)
  }
  return matched
}

/**
 * 分类叶子 label (翻译键) 的显示名。挂单行不走这条 —— 它们可能是 NBT 变体件, 名字由 useItemDisplayNames
 * 按 nameParts 拼, 而分类叶子是 Item 级的, 只有一个键。
 *
 * useItemNames 对每个入参键都会给值 (未解析出中文名时退回键本身), 故这里的 undefined 只可能是
 * "这个键压根没进过入参数组"。退回键本身而不是显示空白, 让缺口在界面上看得见。
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
  readonly tone: FeedbackTone
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
  /** 第二参是该叶子的中文名 —— 树里已经解好了, 传上去省掉页面层再发一轮 i18n 请求。 */
  readonly onSelect: (itemId: string | null, displayName: string | null) => void
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
          className={`flex w-full items-center gap-2 rounded-md py-1 text-foreground text-sm outline-none hover:bg-accent focus-visible:bg-accent ${indentClass(depth)}`}
        >
          {open ? (
            <ChevronDownIcon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
          ) : (
            <ChevronRightIcon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
          )}
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
          onSelect(active ? null : node.itemId, active ? null : name)
        }}
        className={`flex w-full items-center gap-2 rounded-md py-1 text-sm outline-none hover:bg-accent focus-visible:bg-accent ${indentClass(depth)} ${
          active ? 'font-medium text-brand' : 'text-foreground'
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
  readonly onSelect: (itemId: string | null, displayName: string | null) => void
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
      <span className="text-muted-foreground text-sm">{label}</span>
      <span className="min-w-0 truncate text-foreground text-sm">{value}</span>
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
      <p className="text-muted-foreground text-sm">该物品没有基准价, 无从判断这个价格是高是低。</p>
    )
  }
  if (result.v0 <= 0) {
    // 基准价为 0 或负数是服务端不该产生的状态; 如实显示原值并跳过百分比, 不用 0 去做除数。
    return (
      <p className="text-sm text-warning">基准价异常: {String(result.v0)}</p>
    )
  }
  const premium = Math.round(((unitPrice - result.v0) / result.v0) * 100)
  const tone: Tone = premium > 0 ? 'warning' : premium < 0 ? 'success' : 'neutral'
  return (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <span className="text-muted-foreground text-sm">基准价 {String(result.v0)}</span>
      <Tag size="sm" tone={tone}>
        {premium >= 0 ? `高 ${String(premium)}%` : `低 ${String(-premium)}%`}
      </Tag>
    </div>
  )
}

/**
 * 每日额度徽标的悬浮说明。
 *
 * 必须点名"只管铜/铁": 这条 cap 只覆盖回执里那 6 个 item_id (低价大宗矿的对倒防线), 写成笼统的
 * "今日交易额度"会让玩家以为买把枪也占额度。受限标的直接列注册名 —— 前端推不出它们的翻译键
 * (方块类是 block.* 而物品是 item.*), 编一份对照表就是又一个会漂移的镜像。
 *
 * 还必须点名"约束的是挂单卖出": 这个徽标挂在买入页, 不写限定词的话玩家会读成"我今天只能买 512 件铜"。
 * 服务端的 cap 判定只在 MarketEngine.place 里, 买入侧一件不占。
 *
 * 归零时刻只承诺 soldToday 那一段。ACTIVE 挂单的占用不看 created_at, 到点一件都不会掉 ——
 * 老文案"额度在 X 重置"是服务端兑现不了的承诺, 玩家挂着卖不掉的货等到零点会发现数字纹丝不动。
 * 时刻按**服务器本地时区**渲染 (toLocaleString 用客户端时区显示这个绝对时刻), 不写 UTC:
 * 服务端的当日窗口用的就是系统默认时区, 按 UTC 讲会与真实归零错位。
 */
function p2pCapHint(cap: MarketP2pCapResult): string {
  const resetsAt = new Date(cap.resetsAt).toLocaleString('zh-CN', { hour12: false })
  const mockNote = isMockActive() ? ' (假数据: 买入与挂单都不会写回额度)' : ''
  return (
    `每日限量只管挂单卖出这几件铜/铁标的: ${cap.scopeItemIds.join(', ')}; 其余物品不占额度, 买入也不占。` +
    `在挂中的 ${String(cap.activeHeld)} 件始终占额度, 撤单即释放; ` +
    `今日已成交的 ${String(cap.soldToday)} 件在 ${resetsAt} 归零。${mockNote}`
  )
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
  const profile = useMockAction('player.profile', EMPTY_PAYLOAD)

  /*
   * 总价前端自己乘, 因为 MarketListing.total 是**整单**总价, 部分购买时用不上。
   * 服务端回执里的 total 才是权威值, 这里算的只用于下单前展示。
   */
  const total = listing.unitPrice * count
  const totalIsSafe = Number.isSafeInteger(total)
  const balance = profile.status === 'ready' ? profile.data.wallet.credit : null
  const shortfall = balance === null || !totalIsSafe ? null : total - balance

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
    <Dialog
      onOpenChange={(nextOpen) => {
        // 提交中不许关: 请求仍在飞, 关掉会让玩家以为自己取消了这笔买入。
        if (!nextOpen && !submitting) {
          onClose()
        }
      }}
      open
    >
      <DialogPopup>
        <DialogHeader className="pb-3">
          <DialogTitle>购买确认</DialogTitle>
        </DialogHeader>

        <div className="flex flex-col gap-3 px-6 pb-4">
          <div className="flex items-center gap-2">
            <ItemIcon
              customModelData={listing.customModelData}
              itemId={listing.itemId}
              label={itemName}
              scale={1}
            />
            <span className="min-w-0 truncate text-foreground text-sm">{itemName}</span>
          </div>

          <DialogRow label="卖家" value={listing.sellerName} />
          <DialogRow label="剩余" value={String(listing.count)} />
          <DialogRow label="上架" value={formatAge(Date.now() - listing.createdAt)} />
          <DialogRow
            label="单价"
            value={
              <Currency amount={listing.unitPrice} className="break-all" currency="credit" size="sm" />
            }
          />
          <DialogRow label="背包持有" value={ownedCount === null ? '读取中' : String(ownedCount)} />

          <Surface>
            <div className="flex flex-col gap-2">
              <span className="text-muted-foreground text-sm">购买数量 (可只买一部分)</span>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <NumberInput max={listing.count} min={1} onChange={setCount} size="sm" value={count} />
                <Button
                  disabled={count >= listing.count}
                  onClick={() => {
                    setCount(listing.count)
                  }}
                  size="sm"
                  variant="outline"
                >
                  全部
                </Button>
              </div>
            </div>
          </Surface>

          <div className="flex items-center justify-between gap-2">
            <span className="text-muted-foreground text-sm">总价</span>
            {totalIsSafe ? (
              <Currency amount={total} className="break-all" currency="credit" size="sm" />
            ) : (
              <Tag size="sm" tone="danger">
                数值过大
              </Tag>
            )}
          </div>
          {totalIsSafe ? null : (
            <p className="text-destructive text-sm">
              这笔总价太大, 上面显示的数字已经不准; 实际扣多少以购买后的结果为准。
            </p>
          )}

          {baseValue.status === 'loading' ? <LoadingBlock label="读取基准价" size="sm" /> : null}
          {baseValue.status === 'error' ? (
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-destructive text-sm">
                基准价读取失败: {baseValue.error.message}
              </span>
              <Button onClick={baseValue.reload} size="sm" variant="outline">
                重试
              </Button>
            </div>
          ) : null}
          {baseValue.status === 'ready' ? (
            <BaseValueLine result={baseValue.data} unitPrice={listing.unitPrice} />
          ) : null}

          {/*
            买入侧不再查 market.tradable: 标的合法性由 market.place 在挂单源头堵死 (两边共用同一份
            MarketTradeWhitelist), 违规标的根本进不了挂单表。而买入侧手里只有 listing.itemId, 拿不到托管件的
            NBT 品质, 对塔罗牌这类"同 id 不同品质"的物品永远判不准 —— 留着就是第二套判不准的规则。
          */}

          {profile.status === 'loading' ? <LoadingBlock label="读取余额" size="sm" /> : null}
          {profile.status === 'error' ? (
            <p className="text-destructive text-sm">余额读取失败: {profile.error.message}</p>
          ) : null}
          {shortfall !== null && shortfall > 0 ? (
            // 不禁用确认: 余额裁决在服务端, 前端预检只是提前告知; 拦下来反而会掩盖服务端真实的拒绝路径。
            <p className="text-sm text-warning">
              余额大约还差 {String(shortfall)} 信用点, 现在买多半会失败。
            </p>
          ) : null}

          {submitError === null ? null : (
            <FeedbackAlert message={submitError} title="购买未生效" tone="danger" />
          )}
        </div>

        <DialogFooter>
          <Button disabled={submitting} onClick={onClose} variant="outline">
            取消
          </Button>
          <Button
            loading={submitting}
            onClick={() => {
              void handleConfirm()
            }}
            variant="brand"
          >
            确认购买
          </Button>
        </DialogFooter>
      </DialogPopup>
    </Dialog>
  )
}

// ============================================================
// 页面
// ============================================================


/** 跳蚤市场的四个平级子页。顺序按使用频次: 先看再挂, 挂完才管自己的单与收件箱。 */
const MARKET_TABS = [
  { label: '浏览', route: ROUTE_MARKET },
  { label: '挂单', route: ROUTE_MARKET_SELL },
  { label: '我的挂单', route: ROUTE_MARKET_MINE },
  { label: '成交历史', route: ROUTE_MARKET_HISTORY },
  { label: '收件箱', route: ROUTE_MARKET_INBOX },
] as const

export function BrowsePage(): ReactElement {
  const world = useMockWorld()
  const [sort, setSort] = useState<MarketSort>('newest')
  const [page, setPage] = useState(0)
  const [categoryItemId, setCategoryItemId] = useState<string | null>(null)
  /** 只为标签好看: 过滤本身仍按 itemId 发给服务端, 这个名字不参与任何查询。 */
  const [categoryItemName, setCategoryItemName] = useState<string | null>(null)
  const navigate = useNavigate()
  const match = useRouteMatch()
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
     * 判据直接看 inventory 本身: 本页真正关心的是"背包持有量算不算得出来", 镜像现在也只剩背包这一块
     * (F057), inventory 就是最直接的那个信号, 不必再绕经 refreshedAt。
     */
    if (getWorld().mirror.inventory !== null) {
      return
    }
    refreshInventoryMirror().catch((error: unknown) => {
      console.error('[market-browse] 真域镜像预热失败, 背包持有量将显示为未知:', error)
    })
  }, [])

  const pushToast = (tone: FeedbackTone, message: string): void => {
    toastIdRef.current += 1
    const entry: ToastEntry = { id: toastIdRef.current, tone, message }
    setToasts((current) => [...current, entry])
  }

  const dismissToast = (id: number): void => {
    setToasts((current) => current.filter((entry) => entry.id !== id))
  }

  const pageListings: readonly MarketListing[] =
    listQuery.status === 'ready' ? listQuery.data.listings : []
  const nameOf = useItemDisplayNames(pageListings)

  const keyword = localFilter.trim().toLowerCase()
  const rows =
    keyword === ''
      ? pageListings
      : pageListings.filter(
          (listing) =>
            listing.itemId.toLowerCase().includes(keyword) ||
            nameOf(listing).toLowerCase().includes(keyword) ||
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
   * 列一律不给 sortValue (DataTable 的表头排序因此关闭)。排序权在服务端: 表头排序只能重排当前这一页,
   * 与 sort 参数并存时, 玩家看到的顺序取决于两者谁最后生效 —— 那是个查不出来的现象。
   */
  const columns: readonly DataTableColumn<MarketListing>[] = [
    {
      key: 'item',
      header: '物品',
      render: (row) => (
        <span className="flex items-center gap-2">
          <ItemIcon
            customModelData={row.customModelData}
            itemId={row.itemId}
            label={nameOf(row)}
            scale={1}
          />
          <span className="text-foreground text-sm">{nameOf(row)}</span>
        </span>
      ),
    },
    {
      key: 'count',
      header: '数量',
      numeric: true,
      render: (row) => <span className="text-foreground text-sm">{String(row.count)}</span>,
    },
    {
      key: 'unitPrice',
      header: '单价',
      numeric: true,
      // break-all: 种子里那条 unitPrice = 2^53-1 的挂单是 21 个字符的连续数字, 不许它把整张表撑出容器。
      render: (row) => (
        <Currency amount={row.unitPrice} className="break-all" currency="credit" size="sm" />
      ),
    },
    {
      key: 'seller',
      header: '卖家',
      render: (row) => <span className="text-muted-foreground text-sm">{row.sellerName}</span>,
    },
    {
      key: 'age',
      header: '上架',
      render: (row) => (
        <span className="text-muted-foreground text-sm">{formatAge(now - row.createdAt)}</span>
      ),
    },
    {
      key: 'action',
      header: '操作',
      render: (row) => (
        <Button
          onClick={() => {
            setSelected(row)
          }}
          size="sm"
          variant="brand"
        >
          购买
        </Button>
      ),
    },
  ]

  const handleRefresh = (): void => {
    listQuery.reload()
    categories.reload()
    p2pCap.reload()
    refreshInventoryMirror().catch((error: unknown) => {
      // 控制台那条留着 (带完整堆栈, 排障要用); 但只写控制台等于玩家点了刷新之后看着旧的持有量当新的用,
      // 故同时在页面上给一条 danger 回执 —— 本页已经有 toast 通道, 不必另造一套。
      console.error('[market-browse] 手动刷新真域镜像失败:', error)
      pushToast(
        'danger',
        `刷新失败, 背包持有量还是刷新前的: ${error instanceof Error ? error.message : String(error)}`,
      )
    })
  }

  const handleSelectCategory = (itemId: string | null, displayName: string | null): void => {
    setCategoryItemId(itemId)
    setCategoryItemName(displayName)
    // 换过滤条件必须回到第 0 页: 保留页码会让人停在一个新条件下根本不存在的页上, 表现为"点了分类就空了"。
    setPage(0)
  }

  return (
    <section className="flex flex-col gap-4">
      {/*
        市场四个子页此前只有路由没有入口: 侧栏只指到浏览页, 页面里也没有任何地方能点去挂单, 于是
        "怎么上架"根本无从发现。四个页面平级, 用一排标签而不是把它们塞进侧栏 —— 侧栏是功能域的一级
        导航, 一个域展开四条会把其它域挤下去。
      */}
      <Panel>
        <nav aria-label="跳蚤市场子页" className="flex flex-wrap items-center gap-2">
          {MARKET_TABS.map((tab) => (
            <Button
              key={tab.route}
              onClick={() => {
                navigate(tab.route)
              }}
              size="sm"
              variant={match.path === tab.route ? 'secondary' : 'ghost'}
            >
              {tab.label}
            </Button>
          ))}
        </nav>
      </Panel>
      <Panel>
        <div className="flex flex-wrap items-center gap-2">
          <TextInput
            className="w-64"
            onChange={setLocalFilter}
            onRequestEdit={(current) => {
              /*
               * A14 (MC EditBox 叠加) 未实现: 接口位在这里接好了, 宿主日后把玩家输入经 onChange 回填即可,
               * 本页一行不用改。现在只能如实喊一声, 不做浏览器端假输入 —— MCEF 里键盘根本到不了 CEF。
               */
              pushToast(
                'warning',
                current === ''
                  ? '中文输入暂未开放, 可先用左侧的分类筛选'
                  : `中文输入暂未开放, 暂时改不了当前的搜索词: ${current}`,
              )
            }}
            placeholder="搜索本页 (物品名/卖家)"
            size="sm"
            value={localFilter}
          />
          <Hint content="搜索只在当前这一页里查找物品名或卖家; 想换范围请用左侧分类。">
            <Tag size="sm" tone="warning">
              暂不支持中文输入
            </Tag>
          </Hint>
          {keyword === '' ? null : (
            <Button
              onClick={() => {
                setLocalFilter('')
              }}
              size="sm"
              variant="outline"
            >
              清除搜索
            </Button>
          )}

          <Dropdown
            className="w-40"
            onChange={(next) => {
              setSort(toMarketSort(next))
              setPage(0)
            }}
            options={SORT_OPTIONS}
            size="sm"
            value={sort}
          />

          {categoryItemId === null ? null : (
            <>
              <Tag size="sm" tone="brand">
                分类 {categoryItemName ?? categoryItemId}
              </Tag>
              <Button
                onClick={() => {
                  handleSelectCategory(null, null)
                }}
                size="sm"
                variant="outline"
              >
                清除分类
              </Button>
            </>
          )}

          {p2pCap.status === 'ready' ? (
            <Hint content={p2pCapHint(p2pCap.data)}>
              <Tag size="sm" tone={p2pCap.data.remaining > 0 ? 'info' : 'danger'}>
                铜铁挂单额度 {String(p2pCap.data.usedToday)}/{String(p2pCap.data.capPerDay)}
              </Tag>
            </Hint>
          ) : null}
          {p2pCap.status === 'error' ? (
            <Tag size="sm" tone="danger">
              铜铁挂单额度暂不可用
            </Tag>
          ) : null}

          <Button className="ml-auto" onClick={handleRefresh} size="sm" variant="outline">
            <RefreshCwIcon />
            刷新
          </Button>
        </div>
      </Panel>

      <div className="flex gap-4">
        <aside className="w-56 shrink-0">
          <Panel description="点开分类, 选一件具体物品即可筛选" title="分类">
            {categories.status === 'loading' ? <LoadingBlock label="读取分类树" size="sm" /> : null}
            {categories.status === 'error' ? (
              <ErrorBlock
                message={`分类树读取失败: ${categories.error.message}`}
                onRetry={categories.reload}
              />
            ) : null}
            {categories.status === 'ready' ? (
              categories.data.length === 0 ? (
                <EmptyBlock hint="没有拿到分类数据, 可点右上角刷新重试" title="没有分类" />
              ) : (
                <div aria-label="市场分类树" className="max-h-96 overflow-y-auto">
                  <CategoryTree
                    nodes={categories.data}
                    selectedItemId={categoryItemId}
                    onSelect={handleSelectCategory}
                  />
                </div>
              )
            ) : null}
          </Panel>
        </aside>

        <div className="flex min-w-0 flex-1 flex-col gap-3">
          {listQuery.status === 'loading' ? (
            <Panel>
              <LoadingBlock label="正在读取挂单" />
            </Panel>
          ) : null}
          {listQuery.status === 'error' ? (
            <ErrorBlock
              message={`挂单列表读取失败: ${listQuery.error.message}`}
              onRetry={listQuery.reload}
            />
          ) : null}
          {listQuery.status === 'ready' && rows.length === 0 ? (
            <EmptyBlock
              hint={
                page === 0
                  ? keyword === ''
                    ? '换个分类, 或等别人上架'
                    : `当前页没有匹配 "${localFilter.trim()}" 的挂单 (搜索只查当前页)`
                  : '已经翻到最后了, 回上一页继续看'
              }
              icon={<SearchIcon aria-hidden="true" />}
              title={page === 0 ? '这里还没有挂单' : '本页是空的'}
            />
          ) : null}
          {listQuery.status === 'ready' && rows.length > 0 ? (
            <Panel padded={false}>
              <DataTable
                columns={columns}
                rowKey={(row) => String(row.id)}
                rows={rows}
                {...(selected === null ? {} : { selectedRowKey: String(selected.id) })}
              />
            </Panel>
          ) : null}

          <div className="flex flex-wrap items-center justify-center gap-3">
            <Button
              disabled={page === 0}
              onClick={() => {
                setPage((current) => Math.max(0, current - 1))
              }}
              size="sm"
              variant="outline"
            >
              <ArrowLeftIcon />
              上一页
            </Button>
            <span className="text-muted-foreground text-sm">
              第 {String(page + 1)} 页 · 本页 {String(pageListings.length)} 条
              {keyword === '' ? '' : ` (过滤后 ${String(rows.length)} 条)`}
            </span>
            <Button
              disabled={!hasNextPage}
              onClick={() => {
                setPage((current) => current + 1)
              }}
              size="sm"
              variant="outline"
            >
              下一页
              <ArrowRightIcon />
            </Button>
            {/*
              "为什么没有页码"是实现层的解释 (B1 的回执没有 total), 玩家读不懂也用不上, 故只留给假数据模式;
              生产构建里 isMockActive() 恒为 false, 这枚标签整个不存在。
            */}
            {isMockActive() ? (
              <Hint
                content={
                  <span>
                    market.list 的回执只有 listings/page/pageSize, 没有 total (接线清单 B1), 前端算不出总页数,
                    故没有页码。下一页按"本页拿满 {String(PAGE_SIZE)} 条"点亮, 恰好翻到头时会多给一次, 翻过去是空页。
                  </span>
                }
              >
                <Tag size="sm" tone="info">
                  为什么没有页码
                </Tag>
              </Hint>
            ) : null}
          </div>
        </div>
      </div>

      {/*
        回执条的挂载点由消费页面决定 (它本身不抢视口); z-40 压在对话框的 z-50 之下,
        购买失败的回执因此留在对话框内部, 不会被浮到对话框上面去。

        容器让出指针事件 (子元素各自要回来, 见 FeedbackAlert): 这块 fixed 区域按最多几条回执的高度撑着,
        条与条之间的空隙、以及某条正在退场时它腾出的位置, 都会压在下面的挂单表格与翻页按钮上。
        真页实测过: 只给退场中的 alert 加 pointer-events-none 不够 —— elementFromPoint 命中的是这个容器,
        点击照样到不了底下的单元格, 症状与没修一模一样。
      */}
      <div className="pointer-events-none fixed right-4 bottom-4 z-40 flex w-96 flex-col gap-2">
        {/* entry.id 当 key: 每条各自挂载, FeedbackAlert 的 4 秒倒计时因此各算各的。 */}
        {toasts.map((entry) => (
          <FeedbackAlert
            className="bg-popover shadow-lg/5"
            key={entry.id}
            message={entry.message}
            onDismiss={() => {
              dismissToast(entry.id)
            }}
            tone={entry.tone}
          />
        ))}
      </div>

      {selected === null ? null : (
        <BuyDialog
          listing={selected}
          itemName={nameOf(selected)}
          ownedCount={ownedCountOf(selected.itemId)}
          onClose={() => {
            setSelected(null)
          }}
          onBought={(message) => {
            setSelected(null)
            pushToast('success', message)
            // 买入后这一页的数量/条目都变了; 背包由 callMock 在 market.buy 成功后自动刷进真域镜像,
            // 本页"背包持有"因此跟着动 (顶栏余额则由 TabletShell 那边监听世界版本变化重查 profile 带回来),
            // 不需要本页再各刷一遍。
            listQuery.reload()
          }}
        />
      )}
    </section>
  )
}
