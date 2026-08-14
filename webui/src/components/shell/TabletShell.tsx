import {
  BookOpenIcon,
  BriefcaseIcon,
  GiftIcon,
  HeartIcon,
  HomeIcon,
  type LucideIcon,
  PickaxeIcon,
  SettingsIcon,
  ShieldCheckIcon,
  ShoppingCartIcon,
  StoreIcon,
  XIcon,
} from 'lucide-react'
import type { ReactElement, ReactNode } from 'react'
import { useEffect, useRef, useState } from 'react'
import { Button, Currency, LoadingBlock, Tag, Toggle } from '@/components/kit'
import { isMockActive } from '@/lib/bridge'
import { useBrand } from '@/lib/brand'
import { useTheme } from '@/lib/theme'
import { mutateWorld, primeRealDomainMirror, useMockAction, useMockWorld } from '@/mock'
import {
  ROUTE_ADMIN,
  ROUTE_CASE,
  ROUTE_CODEX,
  ROUTE_COMPONENTS,
  ROUTE_HOME,
  ROUTE_JOBS,
  ROUTE_MARKET,
  ROUTE_MARRIAGE,
  ROUTE_MINING,
  ROUTE_SETTINGS,
  ROUTE_SHOP,
  ROUTE_TITLES,
  useNavigate,
  useRouteMatch,
} from '@/router'
import type { Tone } from '@/components/kit'

/**
 * 平板 hub 外壳。真源: 接线清单第一章信息架构 + 记忆项 unified-ui-entry-plan。
 *
 * 存在的理由是入口纪律: 全部功能面板经这一个平板进入, 不给每个功能接 ad-hoc 独立入口
 * (那条路走下去就是"八个职业八个键位", 而键位与物品都还没有)。外壳因此是唯一持有导航、身份与
 * 余额的地方, 面板只管自己那块内容。
 *
 * 导航从上一版的横排页签改成左侧栏。不是审美偏好, 是横排装不下: 十个入口排成一行, 加上图标后
 * 整条导航约 900 CSS px, 而外壳还要在同一行塞玩家名/双货币/在线人数/TPS。上一版为此把每个入口
 * 压成两字短名 (跳蚤市场 -> 市场) 且一个图标都不给, 仍然逼近极限。竖排之后宽度是常量, 加第 11 个
 * 入口不会挤掉任何东西。
 *
 * 数据一律走 mock 层的 callMock 而不是直接读 store: player.profile 是专为首屏设计的聚合
 * (不做这条, 顶栏要串行 6+ 次 MCEF 往返), 它回来的 wallet 已经把 planned 域的收支叠加层算进去了 ——
 * 外壳自己再拼一遍 base + overlay 等于把这条账目规则复制成两份, 必然漂移。
 */

/** planned 域空入参。提到模块级只是为了让"外壳一共发几种请求"一眼可数, 不是性能优化。 */
const EMPTY_PAYLOAD: Record<string, never> = {}

interface ShellNavEntry {
  readonly id: string
  readonly label: string
  readonly route: string
  readonly icon: LucideIcon
  /** 真为 OP 专属: 非 OP 时整个入口不渲染 (而不是渲染成禁用态)。 */
  readonly opOnly: boolean
}

/** 一级导航。顺序照接线清单第一章的信息架构树, 不按字母序 —— 首页与市场是高频入口, 必须在最上。 */
const SHELL_NAV_ENTRIES: readonly ShellNavEntry[] = [
  { icon: HomeIcon, id: 'home', label: '首页', opOnly: false, route: ROUTE_HOME },
  { icon: StoreIcon, id: 'market', label: '跳蚤市场', opOnly: false, route: ROUTE_MARKET },
  { icon: ShoppingCartIcon, id: 'shop', label: '系统商店', opOnly: false, route: ROUTE_SHOP },
  { icon: BriefcaseIcon, id: 'jobs', label: '职业', opOnly: false, route: ROUTE_JOBS },
  { icon: PickaxeIcon, id: 'mining', label: '矿洞', opOnly: false, route: ROUTE_MINING },
  { icon: BookOpenIcon, id: 'codex', label: '图鉴', opOnly: false, route: ROUTE_CODEX },
  { icon: HeartIcon, id: 'marriage', label: '婚姻', opOnly: false, route: ROUTE_MARRIAGE },
  { icon: GiftIcon, id: 'case', label: '开箱', opOnly: false, route: ROUTE_CASE },
  { icon: SettingsIcon, id: 'settings', label: '设置', opOnly: false, route: ROUTE_SETTINGS },
  { icon: ShieldCheckIcon, id: 'admin', label: '管理后台', opOnly: true, route: ROUTE_ADMIN },
]

/**
 * 入口高亮判定: 子路由 (如 /market/sell) 必须点亮它所属的一级入口, 否则从浏览页点进挂单页时整条导航
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
function tpsTone(tps: number): Tone {
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
  /*
   * OP 判定单独走 player.isOp, 不再从 profile 里取 (D9)。
   *
   * 理由是这一个布尔决定的是导航栏画不画管理入口, 而 profile 每次都要打 3 次 SQLite 并遍历 8 个职业 ——
   * 让"管理入口出不出现"排在那份重聚合后面, 是把最贵的一条请求挡在最轻的一个判定前面。
   *
   * 诚实备注: profile 这条请求删不掉 —— 下面的 playerName 与双货币余额目前只有它提供。本改动省的不是
   * 一次往返, 而是让 OP 判定不必等 profile 就绪; 真要省一份得另开题把顶栏改吃 player.wallet 加一个
   * playerName 来源, 不在本批范围。
   */
  const opState = useMockAction('player.isOp', EMPTY_PAYLOAD)
  /*
   * 账号偏好在外壳这一层拉一次, 而不是只在设置页拉。
   *
   * 不这么做的话账号级主题/强调色只有玩家**主动点进设置页**的那一刻才生效: 换台机器 (或清了浏览器缓存)
   * 开平板, 首页/市场/开箱全按本机 localStorage 的默认档渲染, 而设置页的文案却写着"换一台电脑登录同一个
   * 账号, 这四项还在" —— 玩家读到时那句话是假的。外壳是全部面板的共同祖先, 对齐点只能在这里。
   */
  const prefs = useMockAction('player.prefs.get', EMPTY_PAYLOAD)
  const [mirrorError, setMirrorError] = useState<Error | null>(null)

  const reloadProfile = profile.reload
  const reloadServer = server.reload
  const reloadIsOp = opState.reload

  useEffect(() => {
    // hub 是真域镜像的预热点 (mock/handlers.ts primeRealDomainMirror 的文件注释): 在这里拉一次,
    // 后续每个面板就不必各自去发现镜像还是 null。失败不吞 —— 落进状态并在顶栏显形。
    primeRealDomainMirror().catch((error: unknown) => {
      setMirrorError(toError(error))
    })
  }, [])

  /*
   * 账号偏好落到全局: 主题与强调色在 React 渲染前已按 localStorage 生效 (initTheme/initBrand 防首帧闪色),
   * 这里是它们与账号那一份的对齐点。
   *
   * 只对齐一次 (prefsAppliedRef 守卫): theme/brand 是本 effect 的写入目标, 放进依赖表会变成
   * "玩家在设置页刚改完 -> 这里又按账号旧值改回去"。设置页自己也有一份同源对齐, 两处值相同故幂等 ——
   * 且经本对齐后, 玩家点进设置页时那边通常已无差可对。
   */
  const { theme, toggle: toggleTheme } = useTheme()
  const { brand, setBrand } = useBrand()
  const prefsAppliedRef = useRef(false)
  useEffect(() => {
    if (prefs.status !== 'ready' || prefsAppliedRef.current) {
      return
    }
    prefsAppliedRef.current = true
    if (prefs.data.theme !== theme) {
      toggleTheme()
    }
    if (prefs.data.brandHue !== Math.round(brand.hue)) {
      setBrand({ ...brand, hue: prefs.data.brandHue })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- theme/brand 是写入目标而非触发源, 见上
  }, [prefs.status, prefs.data])

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
    reloadIsOp()
  }, [world.revision, reloadProfile, reloadServer, reloadIsOp])

  // 未就绪一律按非 OP 处理: 管理入口宁可晚一帧出现, 也不能先画出来再收回去。
  const isOp = opState.status === 'ready' && opState.data.isOp
  const visibleEntries = SHELL_NAV_ENTRIES.filter((entry) => !entry.opOnly || isOp)
  const title = match.pattern === null ? '页面不存在' : ROUTE_TITLES[match.pattern]

  return (
    <div className="flex h-full min-h-0 overflow-hidden rounded-xl border bg-card shadow-lg/5">
      {/* ==================== 左侧导航栏 ==================== */}
      <nav
        aria-label="平板主导航"
        className="flex w-44 shrink-0 flex-col gap-1 border-r bg-sidebar p-2"
      >
        <div className="flex flex-col gap-0.5 px-2 py-3">
          <span className="font-medium text-foreground text-sm tracking-wide">WORLD OF KIVOTOS</span>
          <div className="flex items-center gap-1.5">
            {profile.status === 'ready' ? (
              <span className="truncate text-muted-foreground text-xs">{profile.data.playerName}</span>
            ) : null}
            {isOp ? (
              <Tag size="sm" tone="brand">
                OP
              </Tag>
            ) : null}
          </div>
        </div>

        {visibleEntries.map((entry) => {
          const active = isNavActive(entry, match.path)
          const Icon = entry.icon
          return (
            <button
              aria-current={active ? 'page' : undefined}
              className={`flex items-center gap-2.5 rounded-md px-2.5 py-2 text-left text-sm transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                active
                  ? 'bg-brand-muted font-medium text-foreground'
                  : 'text-muted-foreground hover:bg-sidebar-accent hover:text-foreground'
              }`}
              key={entry.id}
              onClick={() => {
                navigate(entry.route)
              }}
              type="button"
            >
              <Icon aria-hidden="true" className={`size-4 shrink-0 ${active ? 'text-brand' : ''}`} />
              <span className="truncate">{entry.label}</span>
            </button>
          )
        })}

        {/*
          组件预览页不属于玩家功能, 故不占一级入口, 只在假数据模式下从侧栏底部进入。
          路由只读不写 location.hash (见 router.ts 的偏离说明), 玩家没法靠改地址栏跳过去,
          而 hash 只在整页加载时被读一次 —— 缺了这个入口它就是打不开的。
          isMockActive 在生产构建里恒为 false, 装进游戏后整条不存在。
        */}
        {isMockActive() ? (
          <button
            className={`mt-auto rounded-md px-2.5 py-2 text-left text-xs transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring ${
              match.path === ROUTE_COMPONENTS
                ? 'bg-sidebar-accent text-foreground'
                : 'text-muted-foreground hover:bg-sidebar-accent'
            }`}
            onClick={() => {
              navigate(ROUTE_COMPONENTS)
            }}
            type="button"
          >
            {ROUTE_TITLES[ROUTE_COMPONENTS]}
          </button>
        ) : null}
      </nav>

      {/* ==================== 右侧内容区 ==================== */}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex shrink-0 flex-wrap items-center justify-between gap-3 border-b px-4 py-2.5">
          <h1 className="truncate font-medium text-base text-foreground">{title}</h1>

          <div className="flex items-center gap-4">
            {profile.status === 'loading' ? <LoadingBlock label="读取钱包" size="sm" /> : null}
            {profile.status === 'error' ? (
              <span className="text-destructive text-xs">钱包读取失败: {profile.error.message}</span>
            ) : null}
            {profile.status === 'ready' ? (
              <>
                <Currency amount={profile.data.wallet.credit} currency="credit" size="sm" />
                <Currency amount={profile.data.wallet.azure} currency="azure" size="sm" />
              </>
            ) : null}

            {server.status === 'ready' ? (
              <>
                <span className="text-muted-foreground text-xs tabular-nums">
                  在线 {String(server.data.online)}/{String(server.data.maxPlayers)}
                </span>
                <Tag size="sm" tone={tpsTone(server.data.tps)}>
                  TPS {server.data.tps.toFixed(1)}
                </Tag>
              </>
            ) : null}
            {server.status === 'error' ? (
              <Tag size="sm" tone="danger">
                服务器状态不可用
              </Tag>
            ) : null}
            {mirrorError === null ? null : (
              <Tag size="sm" tone="danger">
                数据加载失败: {mirrorError.message}
              </Tag>
            )}

            {/*
              OP 视图开关只在假数据模式下存在 (isMockActive 在生产构建里恒为 false, 见 lib/bridge)。
              它改的是 mock 世界里的身份位, 好让"管理入口有/无"两种形态都能在设计评审里当场切换;
              真服的 OP 判定在服务端, 前端没有也不该有这个开关。
              勾选态直接读世界 (点下去即时翻转), 而入口的显隐跟着 player.isOp 的回执走 ——
              两者之间那一次往返延迟正是接线后的真实手感, 不该用本地状态抹掉。
            */}
            {isMockActive() ? (
              <Toggle
                checked={world.player.isOp}
                label="OP 视图"
                onChange={(next) => {
                  mutateWorld((draft) => {
                    draft.player.isOp = next
                  })
                }}
                size="sm"
              />
            ) : null}

            <Button
              aria-label={onClose === undefined ? '关闭平板 (暂不可用)' : '关闭平板'}
              disabled={onClose === undefined}
              onClick={() => {
                onClose?.()
              }}
              size="icon-sm"
              variant="ghost"
            >
              <XIcon />
            </Button>
          </div>
        </header>

        {/*
          内容区自己承接滚动: 根节点是 h-screen + overflow-hidden (见 App.tsx), 若这里不给滚动容器,
          超长页面会被直接裁掉而不是可滚。
          min-h-0 是必须的 —— flex 子项默认 min-height:auto, 不归零则 flex-1 撑不下去, overflow 永不触发。
        */}
        <main className="min-h-0 flex-1 overflow-y-auto p-4">{children}</main>
      </div>
    </div>
  )
}
