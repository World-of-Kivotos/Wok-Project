import {
  BookOpenIcon,
  ClipboardListIcon,
  BriefcaseIcon,
  GiftIcon,
  HeartIcon,
  HomeIcon,
  LockIcon,
  type LucideIcon,
  PickaxeIcon,
  SettingsIcon,
  ShieldCheckIcon,
  ShoppingCartIcon,
  StoreIcon,
  XIcon,
} from 'lucide-react'
import type { ReactElement, ReactNode } from 'react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { subscribeWebUiEvent } from '@/bridge/events'
import { Button, Currency, EmptyBlock, LoadingBlock, Tag, Toggle } from '@/components/kit'
import { handshake, isMockActive } from '@/lib/bridge'
import { useBrand } from '@/lib/brand'
import type { LoginGateCode } from '@/lib/login-gate'
import { clearLoginGate, currentLoginGate, LOGIN_CHECK_UNAVAILABLE, useLoginGate } from '@/lib/login-gate'
import { usePanelVisible } from '@/lib/panel-visibility'
import { prefetchQuery } from '@/lib/query-cache'
import { invalidateAll } from '@/lib/refresh'
import { SERVER_EVENTS } from '@/lib/server-events'
import { useTheme } from '@/lib/theme'
import {
  callMock,
  mockActionKey,
  mutateWorld,
  primeRealDomainMirror,
  recordMirrorError,
  useMockAction,
  useMockWorld,
} from '@/mock'
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
  ROUTE_QUESTS,
  ROUTE_SETTINGS,
  ROUTE_SHOP,
  ROUTE_TITLES,
  useNavigate,
  useRouteMatch,
} from '@/router'
import type { Tone } from '@/components/kit'
import type { HandshakeReport } from '@/lib/bridge'

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
  { icon: ClipboardListIcon, id: 'quests', label: '任务', opOnly: false, route: ROUTE_QUESTS },
  { icon: BookOpenIcon, id: 'codex', label: '图鉴', opOnly: false, route: ROUTE_CODEX },
  { icon: HeartIcon, id: 'marriage', label: '婚姻', opOnly: false, route: ROUTE_MARRIAGE },
  { icon: GiftIcon, id: 'case', label: '开箱', opOnly: false, route: ROUTE_CASE },
  { icon: SettingsIcon, id: 'settings', label: '设置', opOnly: false, route: ROUTE_SETTINGS },
  { icon: ShieldCheckIcon, id: 'admin', label: '管理后台', opOnly: true, route: ROUTE_ADMIN },
]

/**
 * 侧栏悬停预取表: 一级入口 -> 那一页首屏要用的只读 action (全是无入参的)。
 *
 * 为什么值得做: 缓存命中之后翻回已看过的页面是零请求零闪烁, 但**第一次**进某页仍要等一次往返。鼠标从
 * 侧栏划过到真的点下去有一二百毫秒, 正好够那一批数据回来 —— 于是首次进入也不闪。
 *
 * 预取的是玩家马上就要看的那一页, 不是"所有页面全预热": 后者会把冷启动的请求量翻好几倍, 与省服务端
 * 压力的初衷相反。悬停即将访问, 这份请求本来就要发, 只是提前了。
 *
 * 缺一条只是那页照旧走加载态 (不会出错), 故本表不必与页面实现严格同步; 但多写一条不存在的 action 会
 * 白发一次请求, 加的时候照页面里的 useMockAction 抄。刻意不收录 market.list (入参含筛选组合, 预取的
 * 键与页面实际用的键对不上就是白发) 与 shop.catalog (planned 域, 生产构建里必定失败)。
 */
const NAV_PREFETCH: Readonly<Record<string, readonly EmptyPayloadAction[]>> = {
  [ROUTE_HOME]: [
    'player.profile',
    'economy.today',
    'economy.status',
    'marriage.state',
    'mining.myStatus',
    'mining.overview',
    'hub.panels',
  ],
  [ROUTE_MARKET]: ['market.categories', 'market.p2pCap'],
  [ROUTE_JOBS]: ['job.progress'],
  [ROUTE_MINING]: ['mining.overview', 'mining.myStatus'],
  [ROUTE_QUESTS]: ['quest.board'],
  [ROUTE_CODEX]: ['champion.codex'],
  [ROUTE_MARRIAGE]: ['marriage.state', 'marriage.sharedInv', 'player.roster', 'player.profile'],
  [ROUTE_CASE]: ['case.state'],
  [ROUTE_SETTINGS]: ['player.prefs.get'],
}

/** 预取表里的 action 名 —— 只收无入参的那些, 好让下面一行 EMPTY_PAYLOAD 对所有条目都成立。 */
type EmptyPayloadAction =
  | 'case.state'
  | 'champion.codex'
  | 'economy.status'
  | 'economy.today'
  | 'hub.panels'
  | 'job.progress'
  | 'market.categories'
  | 'market.p2pCap'
  | 'marriage.sharedInv'
  | 'marriage.state'
  | 'mining.myStatus'
  | 'mining.overview'
  | 'player.prefs.get'
  | 'player.profile'
  | 'player.roster'
  | 'quest.board'

/**
 * 预热某个一级入口的首屏数据。已新鲜的键直接跳过 (prefetchQuery 自己判), 故反复划过侧栏不会重复发请求。
 *
 * 键必须经 mockActionKey 合成 —— 自己拼字符串拼错的症状是"预取一直不命中", 而它不报错也不可见。
 */
function prefetchRoute(route: string): void {
  const actions = NAV_PREFETCH[route]
  if (actions === undefined) {
    return
  }
  for (const action of actions) {
    prefetchQuery(mockActionKey(action, EMPTY_PAYLOAD), () => callMock(action, EMPTY_PAYLOAD))
  }
}

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

/** 契约漂移徽标的文案: 前 5 条 missing action 名, 超过 5 条追加"等 N 条"而不是把顶栏撑爆。 */
function describeMissingActions(missingOnServer: readonly string[]): string {
  const shown = missingOnServer.slice(0, 5).join(', ')
  const rest = missingOnServer.length - 5
  return rest > 0 ? `${shown} 等 ${String(missingOnServer.length)} 条` : shown
}

interface LoginGateNoticeProps {
  code: LoginGateCode
  onRetry: () => void
}

/**
 * 登录门提示 (见 lib/login-gate)。由外壳以不透明遮罩盖住整块内容区, 页面本身不卸载 (见外壳 main 处的说明)。
 *
 * 两个码分开措辞: 没登录的人该去输 /login; 登录校验不可用时输密码也没用, 只能找管理员。
 * 按钮只是兜底: 登录落地后服务端会推一条事件, 提示挂着期间外壳也在退避重试, 两条都会自动撤掉提示。
 */
function LoginGateNotice({ code, onRetry }: LoginGateNoticeProps): ReactElement {
  const unavailable = code === LOGIN_CHECK_UNAVAILABLE
  return (
    <EmptyBlock
      action={
        <Button onClick={onRetry} variant="outline">
          {unavailable ? '重新检查' : '我已登录, 重新加载'}
        </Button>
      }
      hint={
        unavailable
          ? '服务器暂时无法确认你的登录状态, 平板功能已全部暂停。请联系管理员。'
          : '服务器还没有确认你的身份。请在聊天栏输入 /login <密码> 登录, 登录成功后这里会自动恢复。'
      }
      icon={<LockIcon aria-hidden="true" />}
      title={unavailable ? '登录校验暂不可用' : '请先登录'}
    />
  )
}

/**
 * 登录提示挂着期间的自动探测节奏: 首次 1 秒后, 之后每次翻倍, 封顶 15 秒。
 *
 * 为什么要有它 (推送之外): 推送是"快", 这里是"准"。AccessHub 的 /login 在后台线程算完才回写, 玩家敲完 /login
 * 立刻打开平板往往还是被拒; 服务端会在回写落地时推 auth.loginConfirmed, 但推送按红线不保证送达 (桥没就绪时
 * 宿主直接丢弃)。起步 1 秒盖住 /login 的常见耗时, 封顶 15 秒让忘了登录、把平板开着挂机的人每分钟只多四个请求。
 */
const LOGIN_PROBE_FIRST_DELAY_MS = 1000
const LOGIN_PROBE_MAX_DELAY_MS = 15_000

/*
 * StrictMode (main.tsx) 会在 dev 下把挂载期 effect 跑两遍。握手自检没有幂等性可言 —— 两遍各发一次
 * system.handshake、各打一遍诊断日志, 模块级守卫拦掉第二遍。
 */
let handshakeStarted = false

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
  const [handshakeReport, setHandshakeReport] = useState<HandshakeReport | null>(null)
  const [handshakeError, setHandshakeError] = useState<string | null>(null)

  const reloadProfile = profile.reload
  const reloadServer = server.reload
  const reloadIsOp = opState.reload

  useEffect(() => {
    // hub 是真域镜像的预热点 (mock/handlers.ts primeRealDomainMirror 的文件注释): 在这里拉一次,
    // 后续每个面板就不必各自去发现镜像还是 null。失败不吞 —— 落进 mirror.lastError 并在顶栏显形
    // (recordMirrorError 是全库唯一的失败落点, 见 mock/handlers.ts)。
    primeRealDomainMirror().catch(recordMirrorError)
  }, [])

  /*
   * 登录门 (lib/login-gate): 服务端说"还没 /login"之后, 内容区盖上一层登录提示。
   *
   * 重试 = 摘提示 + 全量作废 + 重拉背包镜像。仍未登录的话, 重拉的第一条回执就会把提示重新挂上, 所以这里不必
   * 自己先问一次服务端。镜像要单独重拉: 它只在外壳挂载时预热一次, 登录前那次失败会一直挂在顶栏上。
   */
  const loginGate = useLoginGate()
  const panelVisible = usePanelVisible()
  const retryAfterLogin = useCallback(() => {
    clearLoginGate()
    invalidateAll()
    primeRealDomainMirror().catch(recordMirrorError)
  }, [])

  /*
   * 平板重新打开时自动重试一次: 玩家最自然的动作是关掉平板、去聊天栏 /login、再按 G 打开, 不该还要去点按钮。
   * 全量作废已由 App 的同一条 panelOpened 订阅做了, 这里只补"摘提示 + 重拉镜像"。
   */
  useEffect(
    () =>
      subscribeWebUiEvent('panelOpened', () => {
        if (currentLoginGate() !== null) {
          clearLoginGate()
          primeRealDomainMirror().catch(recordMirrorError)
        }
      }),
    [],
  )

  /*
   * 服务端确认登录 (WebUiEventNames.LOGIN_CONFIRMED): /login 的回写落地、登录门判定翻为放行时推来。
   * 没挂提示时收到就忽略 —— 没有什么要恢复的, 不该为此把全部缓存作废一遍。
   */
  useEffect(
    () =>
      subscribeWebUiEvent(SERVER_EVENTS.loginConfirmed, () => {
        if (currentLoginGate() !== null) {
          retryAfterLogin()
        }
      }),
    [retryAfterLogin],
  )

  /*
   * 推送的兜底: 提示挂着且平板在屏幕上时, 按 LOGIN_PROBE_* 的退避节奏探测一次登录门。
   *
   * 探针是 player.isOp: 最轻的一条要过登录门的只读 action (system.handshake 在登录前也放行, 拿它探测永远"成功")。
   * 它成功就说明门已放行, 当场撤提示重拉; 失败 (仍被拒会顺手把同一个码再记一遍, 不改状态) 就排下一次。
   * 平板关着时不探: 宿主的关屏门会把请求挡掉, 重新打开时 panelOpened 那条已经会重试。
   */
  useEffect(() => {
    if (loginGate === null || !panelVisible) {
      return
    }
    let cancelled = false
    let delay = LOGIN_PROBE_FIRST_DELAY_MS
    let timer: number | undefined
    const probe = (): void => {
      callMock('player.isOp', EMPTY_PAYLOAD).then(
        () => {
          if (!cancelled) {
            retryAfterLogin()
          }
        },
        () => {
          if (!cancelled) {
            delay = Math.min(delay * 2, LOGIN_PROBE_MAX_DELAY_MS)
            timer = window.setTimeout(probe, delay)
          }
        },
      )
    }
    timer = window.setTimeout(probe, delay)
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [loginGate, panelVisible, retryAfterLogin])

  useEffect(() => {
    if (handshakeStarted) {
      return
    }
    handshakeStarted = true
    handshake()
      .then((report) => {
        setHandshakeReport(report)
        // unknownToClient 不算不兼容 (服务端跑在更新的构建上), 只值得留个痕迹, 不值得占顶栏一个位置。
        if (report.unknownToClient.length > 0) {
          console.info('[webui-handshake] 服务端注册了前端未声明的 action:', report.unknownToClient)
        }
      })
      .catch((error: unknown) => {
        setHandshakeError(error instanceof Error ? error.message : String(error))
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
    /*
      内屏。圆角走 .tablet-screen (= 外框圆角 - 边距), 与 App.tsx 那层同心 —— 见 styles/index.css。

      刻意<b>不再画边框</b>: 外框已经有一条了, 两条相隔 10px 的细线在暗色下读起来是一道双框, 而层级已经
      由三档底色 (background -> card/sidebar) 表达清楚了。这也是上一版"边框吃光内容盒"的同一类毛病。
    */
    <div className="tablet-screen flex h-full min-h-0 overflow-hidden bg-card shadow-lg/5">
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
              className={`relative flex items-center gap-2.5 rounded-md px-2.5 py-2 text-left text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                active ? 'font-medium text-foreground' : 'text-muted-foreground hover:bg-sidebar-accent hover:text-foreground'
              }`}
              data-slot="nav-item"
              key={entry.id}
              onClick={() => {
                navigate(entry.route)
              }}
              /*
                悬停即预取那一页的首屏数据 (见 NAV_PREFETCH)。onFocus 一并接上, 键盘 Tab 过来的人应当享受
                同样的手感 —— 且两者都幂等 (新鲜的键直接跳过), 反复触发不会重复发请求。
              */
              onFocus={() => {
                prefetchRoute(entry.route)
              }}
              onMouseEnter={() => {
                prefetchRoute(entry.route)
              }}
              type="button"
            >
              {/*
                当前项的高亮面。单独成元素而不是给 button 加 bg-brand-muted, 是为了给它挂一个
                view-transition-name —— 导航时 Chromium 会把这一块从旧入口平移到新入口 (零 JS, 不测坐标)。
                详见 styles/index.css 里 [data-slot='nav-indicator'] 的说明。
              */}
              {active ? (
                <span
                  aria-hidden="true"
                  className="absolute inset-0 rounded-md bg-brand-muted"
                  data-slot="nav-indicator"
                />
              ) : null}
              <Icon aria-hidden="true" className={`relative size-4 shrink-0 ${active ? 'text-brand' : ''}`} />
              <span className="relative truncate">{entry.label}</span>
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
                {/* 顶栏钱包是全站唯一开滚动的两处: 卖菜/买入/领奖之后, 这两个数字自己爬到新值。 */}
                <Currency animate amount={profile.data.wallet.credit} currency="credit" size="sm" />
                <Currency animate amount={profile.data.wallet.azure} currency="azure" size="sm" />
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
            {world.mirror.lastError === null ? null : (
              <Tag size="sm" tone="danger">
                数据加载失败: {world.mirror.lastError}
              </Tag>
            )}

            {/*
              两条互斥: compatible === false 才算契约漂移 (unknownToClient 非空不算, 只值得进控制台,
              见上方 handshake effect); handshakeError 是握手请求本身失败 (连不上桥/回执畸形), 与
              "连上了但清单对不上"是两种不同的故障, 不能合并成一句话。
            */}
            {handshakeReport !== null && !handshakeReport.compatible ? (
              <Tag size="sm" tone="danger">
                契约漂移 {handshakeReport.modVersion}: {describeMissingActions(handshakeReport.missingOnServer)}
              </Tag>
            ) : null}
            {handshakeError === null ? null : (
              <Tag size="sm" tone="danger">
                契约自检失败: {handshakeError}
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

          登录提示是盖在内容区上的不透明遮罩, 不是替换 children: 页面一直挂着, 登录后回来还是原来那一页、原来的
          筛选与没提交的输入, 只是数据随全量作废重拉。遮罩必须不透明 (bg-card), 否则被挡期间每个面板的请求都会
          失败, 提示后面会透出一整页红色的"读取失败"; 被盖住的页面设 inert, 键盘 Tab 与读屏都进不去。
          遮罩放在 main 的兄弟位而不是里面: main 自己在滚, 放里面会随页面一起滚走。
        */}
        <div className="relative flex min-h-0 flex-1 flex-col">
          <main
            className="page-transition-surface min-h-0 flex-1 overflow-y-auto p-4"
            inert={loginGate !== null}
          >
            {children}
          </main>
          {loginGate === null ? null : (
            <div className="absolute inset-0 overflow-y-auto bg-card p-4">
              <LoginGateNotice code={loginGate} onRetry={retryAfterLogin} />
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
