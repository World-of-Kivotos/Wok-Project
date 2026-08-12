import type { ReactElement, ReactNode } from 'react'
import { useEffect, useRef, useState } from 'react'
import { isMockActive } from '../../lib/bridge'
import { mutateWorld, primeRealDomainMirror, useMockAction, useMockWorld } from '../../mock'
import {
  ROUTE_ADMIN,
  ROUTE_CASE,
  ROUTE_CODEX,
  ROUTE_COLOR_CHECK,
  ROUTE_COMPONENTS,
  ROUTE_HOME,
  ROUTE_JOBS,
  ROUTE_MARKET,
  ROUTE_MARRIAGE,
  ROUTE_MINING,
  ROUTE_PIXEL_CHECK,
  ROUTE_SETTINGS,
  ROUTE_SHOP,
  ROUTE_TITLES,
  useNavigate,
  useRouteMatch,
} from '../../router'
import type { PixelFrameTone, PixelTab } from '../pixel'
import {
  PixelBadge,
  PixelButton,
  PixelCheckbox,
  PixelCurrency,
  PixelFrame,
  PixelLoading,
  PixelTabs,
} from '../pixel'

/**
 * 平板 hub 外壳。真源: 接线清单第一章信息架构 + 记忆项 unified-ui-entry-plan。
 *
 * 存在的理由是入口纪律: 全部功能面板经这一个平板进入, 不给每个功能接 ad-hoc 独立入口
 * (那条路走下去就是"八个职业八个键位", 而键位与物品都还没有 —— 清单第四章把"平板 hub 本身"
 * 列为零后端的 10 块之一)。外壳因此是唯一持有导航、身份与余额的地方, 面板只管自己那块内容。
 *
 * 层级关系用两档 9-slice 资产表达而不是靠边框颜色: 外壳本体是 window(外凸, 高光在上),
 * 内容区是 panel(平面板), 两者的斜面方向不同, 于是"内容嵌在平板里"这件事在灰度层面就成立,
 * 不依赖调用方额外加分隔线 (规格第七章的层级维度必须出图, 见 PixelFrame 文件头)。
 *
 * 数据一律走 mock 层的 callMock 而不是直接读 store: player.profile 是接线清单 A5 专为首屏设计的聚合
 * (不做这条, 顶栏要串行 6+ 次 MCEF 往返), 它回来的 wallet 已经把 planned 域的收支叠加层算进去了 ——
 * 外壳自己再拼一遍 base + overlay 等于把这条账目规则复制成两份, 必然漂移。
 */

/** planned 域空入参。提到模块级只是为了让"外壳一共发几种请求"一眼可数, 不是性能优化。 */
const EMPTY_PAYLOAD: Record<string, never> = {}

interface ShellNavEntry {
  readonly id: string
  /** 页签上的短名。 */
  readonly label: string
  readonly route: string
  /** 真为 OP 专属: 非 OP 时整个页签不渲染 (而不是渲染成禁用态)。 */
  readonly opOnly: boolean
}

/**
 * 一级导航。顺序照接线清单第一章的信息架构树, 不按字母序 —— 首页与市场是高频入口, 必须在最左。
 *
 * 页签用两字短名而不是"跳蚤市场""管理后台"这类全名: 十个页签排成一行, 全名下整条导航栏约 1500 CSS px,
 * 在 1280 宽的客户端会被挤出可视区; 而 PixelTabs 是 inline-flex 不折行, 溢出的表现是"最后几个页签点不到"。
 * 全名没有丢, 它在内容区表头 (ROUTE_TITLES), 那里一屏只出现一条, 不占横向预算。
 *
 * 刻意一个图标都不给: PIXEL_ICON_NAMES 里没有首页/矿洞/图鉴/开箱/职业的对应图标, 只给市场(bag)、
 * 婚姻(heart)、设置(settings) 三个会让十个页签里三个带图标七个不带, 高度与视觉重心全不一致。
 * 补齐这五张 16x16 图标是美术侧的事 (tools/gen-icons.mjs 的名单与 PixelIcon 双向校验), 补齐后在这里
 * 统一加 icon 字段即可。
 */
const SHELL_NAV_ENTRIES: readonly ShellNavEntry[] = [
  { id: 'home', label: '首页', route: ROUTE_HOME, opOnly: false },
  { id: 'market', label: '市场', route: ROUTE_MARKET, opOnly: false },
  { id: 'shop', label: '商店', route: ROUTE_SHOP, opOnly: false },
  { id: 'jobs', label: '职业', route: ROUTE_JOBS, opOnly: false },
  { id: 'mining', label: '矿洞', route: ROUTE_MINING, opOnly: false },
  { id: 'codex', label: '图鉴', route: ROUTE_CODEX, opOnly: false },
  { id: 'marriage', label: '婚姻', route: ROUTE_MARRIAGE, opOnly: false },
  { id: 'case', label: '开箱', route: ROUTE_CASE, opOnly: false },
  { id: 'settings', label: '设置', route: ROUTE_SETTINGS, opOnly: false },
  { id: 'admin', label: '管理', route: ROUTE_ADMIN, opOnly: true },
]

/** 验证页入口。它们不是玩家功能, 故不进一级导航, 只在假数据模式下从页脚进入。 */
const SHELL_DEV_ROUTES: readonly { readonly route: string; readonly label: string }[] = [
  { route: ROUTE_PIXEL_CHECK, label: ROUTE_TITLES[ROUTE_PIXEL_CHECK] },
  { route: ROUTE_COLOR_CHECK, label: ROUTE_TITLES[ROUTE_COLOR_CHECK] },
  { route: ROUTE_COMPONENTS, label: ROUTE_TITLES[ROUTE_COMPONENTS] },
]

/**
 * 页签高亮判定: 子路由 (如 /market/sell) 必须点亮它所属的一级页签, 否则从浏览页点进挂单页时整条导航
 * 会失去当前位置。首页是唯一必须精确匹配的一条 —— 它的 route 是 "/", 前缀判定会把所有路径都算成首页。
 */
function isNavActive(entry: ShellNavEntry, path: string): boolean {
  if (entry.route === ROUTE_HOME) {
    return path === ROUTE_HOME
  }
  return path === entry.route || path.startsWith(`${entry.route}/`)
}

/**
 * TPS 档位。19.5 与 15 这两个坎取自服务端常识而非本项目实测: 20 是满刻, 掉到 15 以下方块交互已明显粘手。
 * 这里只决定徽标颜色, 不参与任何业务判定。
 */
function tpsTone(tps: number): PixelFrameTone {
  if (tps >= 19.5) {
    return 'success'
  }
  if (tps >= 15) {
    return 'warning'
  }
  return 'danger'
}

function toError(value: unknown): Error {
  return value instanceof Error ? value : new Error(String(value))
}

export interface TabletShellProps {
  children: ReactNode
  /**
   * 关闭平板。缺省即宿主侧尚未提供关闭通道 (清单第四章: 平板 hub 无物品、无键位、无面板注册表),
   * 此时按钮渲染成禁用态而不是接一个假的空实现 —— 一个点下去毫无反应的按钮比一个明确不可用的按钮更糟。
   */
  onClose?: () => void
}

export function TabletShell({ children, onClose }: TabletShellProps): ReactElement {
  const match = useRouteMatch()
  const navigate = useNavigate()
  const world = useMockWorld()

  const profile = useMockAction('player.profile', EMPTY_PAYLOAD)
  const server = useMockAction('system.serverStatus', EMPTY_PAYLOAD)
  const [mirrorError, setMirrorError] = useState<Error | null>(null)

  const reloadProfile = profile.reload
  const reloadServer = server.reload

  useEffect(() => {
    // hub 是真域镜像的预热点 (mock/handlers.ts primeRealDomainMirror 的文件注释): 在这里拉一次,
    // 后续每个面板就不必各自去发现镜像还是 null。失败不吞 —— 落进状态并在顶栏显形。
    primeRealDomainMirror().catch((error: unknown) => {
      setMirrorError(toError(error))
    })
  }, [])

  /*
   * useMockAction 刻意不订阅世界版本 (它自己会改世界的那些 action 一旦自动重查就会自锁),
   * 但顶栏的余额恰恰是最需要跨面板联动的一份数据: 卖菜进账、买卡包扣费都发生在别的面板里。
   * 故在外壳这一层显式补一条"世界变了就重查", 且只对这两条只读聚合生效。
   * 首次挂载不触发 (ref 初值即当前版本), 免得刚发出的首查立刻被一次重查顶掉。
   */
  const lastRevisionRef = useRef(world.revision)
  useEffect(() => {
    if (lastRevisionRef.current === world.revision) {
      return
    }
    lastRevisionRef.current = world.revision
    reloadProfile()
    reloadServer()
  }, [world.revision, reloadProfile, reloadServer])

  const isOp = profile.status === 'ready' && profile.data.isOp
  const visibleEntries = SHELL_NAV_ENTRIES.filter((entry) => !entry.opOnly || isOp)
  const tabs: readonly PixelTab[] = visibleEntries.map((entry) => ({ id: entry.id, label: entry.label }))
  // 验证页 (#/pixel-check 等) 不属于任何一级页签, 此时整条导航无高亮项 —— 空串不会命中任何 id。
  const activeEntry = visibleEntries.find((entry) => isNavActive(entry, match.path))
  const title = match.pattern === null ? '未知路由' : ROUTE_TITLES[match.pattern]

  const handleTabChange = (id: string): void => {
    const entry = visibleEntries.find((candidate) => candidate.id === id)
    if (entry === undefined) {
      return
    }
    navigate(entry.route)
  }

  return (
    <PixelFrame variant="window" className="flex flex-1 flex-col gap-4 p-4">
      <header className="flex flex-wrap items-center justify-between gap-4 border-b border-border pb-4">
        <div className="flex items-center gap-4">
          <span className="text-2x text-fg">WORLD OF KIVOTOS</span>
          {profile.status === 'ready' ? (
            <span className="text-1x text-muted">{profile.data.playerName}</span>
          ) : null}
          {isOp ? <PixelBadge tone="accent">OP</PixelBadge> : null}
        </div>

        <div className="flex items-center gap-6">
          {profile.status === 'loading' ? <PixelLoading size="sm" label="读取钱包" /> : null}
          {profile.status === 'error' ? (
            <span className="text-1x text-danger">钱包读取失败: {profile.error.message}</span>
          ) : null}
          {profile.status === 'ready' ? (
            <>
              <PixelCurrency amount={profile.data.wallet.credit} currency="credit" size="sm" />
              <PixelCurrency amount={profile.data.wallet.azure} currency="azure" size="sm" />
            </>
          ) : null}
        </div>

        <div className="flex items-center gap-4">
          {server.status === 'ready' ? (
            <>
              <span className="text-1x text-muted">
                在线 {String(server.data.online)}/{String(server.data.maxPlayers)}
              </span>
              <PixelBadge tone={tpsTone(server.data.tps)}>TPS {server.data.tps.toFixed(1)}</PixelBadge>
            </>
          ) : null}
          {server.status === 'error' ? (
            <PixelBadge tone="danger">服务器状态不可用</PixelBadge>
          ) : null}
          {mirrorError === null ? null : (
            <PixelBadge tone="danger">数据预热失败: {mirrorError.message}</PixelBadge>
          )}

          {/*
            OP 视图开关只在假数据模式下存在 (isMockActive 在生产构建里恒为 false, 见 lib/bridge)。
            它改的是 mock 世界里的身份位, 好让"管理页签有/无"两种形态都能在设计评审里当场切换;
            真服的 OP 判定在服务端, 前端没有也不该有这个开关。
            勾选态直接读世界 (点下去即时翻转), 而页签的显隐跟着 player.profile 的回执走 ——
            两者之间那一次往返延迟正是接线后的真实手感, 不该用本地状态抹掉。
          */}
          {isMockActive() ? (
            <PixelCheckbox
              checked={world.player.isOp}
              label="OP 视图"
              size="sm"
              onChange={(next) => {
                mutateWorld((draft) => {
                  draft.player.isOp = next
                })
              }}
            />
          ) : null}

          <PixelButton
            icon="close"
            label={onClose === undefined ? '关闭平板 (宿主未接线)' : '关闭平板'}
            size="sm"
            disabled={onClose === undefined}
            onClick={() => {
              onClose?.()
            }}
          />
        </div>
      </header>

      <PixelTabs
        tabs={tabs}
        activeId={activeEntry === undefined ? '' : activeEntry.id}
        onChange={handleTabChange}
      />

      <PixelFrame variant="panel" className="flex min-h-0 flex-1 flex-col gap-4 p-4">
        <h1 className="text-2x text-fg">{title}</h1>
        {/*
          内容区自己承接滚动: 根节点是 h-screen + overflow-hidden (见 App.tsx, 目的是不让文档级滚动
          拉出原生圆角滚动条), 若这里不给滚动容器, 超长页面会被直接裁掉而不是可滚。
          min-h-0 是必须的 —— flex 子项默认 min-height:auto, 不归零则 flex-1 撑不下去, overflow 永不触发。
          滚动条外观由 index.css 的 ::-webkit-scrollbar 像素化。
        */}
        <div className="flex min-h-0 flex-1 flex-col overflow-y-auto">{children}</div>
      </PixelFrame>

      {/*
        三个验证页刻意不占一级页签 (它们是给设计与前端看的, 不是玩家功能), 但也必须有入口:
        路由只读不写 location.hash, 玩家/评审没法靠改地址栏跳过去, 而 hash 只在整页加载时被读一次。
        故在假数据模式下补这一排; isMockActive 在生产构建里恒为 false, 装进游戏后整条不存在。
      */}
      {isMockActive() ? (
        <footer className="flex flex-wrap items-center gap-4 border-t border-border pt-4">
          <span className="text-1x text-muted">验证页 (仅假数据模式)</span>
          {SHELL_DEV_ROUTES.map((entry) => (
            <PixelButton
              key={entry.route}
              size="sm"
              tone={match.path === entry.route ? 'accent' : 'neutral'}
              onClick={() => {
                navigate(entry.route)
              }}
            >
              {entry.label}
            </PixelButton>
          ))}
        </footer>
      ) : null}
    </PixelFrame>
  )
}
