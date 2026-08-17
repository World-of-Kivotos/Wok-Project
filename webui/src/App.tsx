import type { ReactElement } from 'react'
import { useEffect, useRef } from 'react'
import { installWebUiEventBridge, subscribeWebUiEvent } from './bridge/events'
import { Button, EmptyBlock } from './components/kit'
import { TabletShell } from './components/shell/TabletShell'
import { closePanel, useTextFocusReporting } from '@/lib/host-panel'
import { installPanelVisibility } from '@/lib/panel-visibility'
import { invalidateAll } from '@/lib/refresh'
import { installWheelNormalizer } from '@/lib/wheel'
import { TooltipProvider } from './components/ui/tooltip'
import { AdminPage } from './pages/admin/AdminPage'
import { CasePage } from './pages/CasePage'
import { CodexPage } from './pages/CodexPage'
import { ComponentsPage } from './pages/ComponentsPage'
import { HomePage } from './pages/HomePage'
import { JobDetailPage } from './pages/jobs/JobDetailPage'
import { JobsOverviewPage } from './pages/jobs/JobsOverviewPage'
import { BrowsePage } from './pages/market/BrowsePage'
import { HistoryPage } from './pages/market/HistoryPage'
import { InboxPage } from './pages/market/InboxPage'
import { MyListingsPage } from './pages/market/MyListingsPage'
import { SellPage } from './pages/market/SellPage'
import { MarriagePage } from './pages/MarriagePage'
import { MiningPage } from './pages/MiningPage'
import { QuestsPage } from './pages/QuestsPage'
import { SettingsPage } from './pages/SettingsPage'
import { ShopPage } from './pages/ShopPage'
import type { RouteMatch, RoutePattern } from './router'
import {
  ROUTE_ADMIN,
  ROUTE_CASE,
  ROUTE_CODEX,
  ROUTE_COMPONENTS,
  ROUTE_HOME,
  ROUTE_JOBS,
  ROUTE_JOB_DETAIL,
  ROUTE_MARKET,
  ROUTE_MARKET_HISTORY,
  ROUTE_MARKET_INBOX,
  ROUTE_MARKET_MINE,
  ROUTE_MARKET_SELL,
  ROUTE_MARRIAGE,
  ROUTE_MINING,
  ROUTE_QUESTS,
  ROUTE_SETTINGS,
  ROUTE_SHOP,
  useNavigate,
  useRouteMatch,
} from './router'

/**
 * 路由出口。全部页面组件在此一次性登记, 面板批次只替换自己那个页面文件的内容, 不碰本表。
 *
 * 用 Record<RoutePattern, ...> 而不是 switch: 少登记一条路由即 tsc 报缺键, 多登记一条报多余键 ——
 * 换成 switch + default 的话, 漏掉一条的表现是"点进去是未知路由页", 而这不会在任何检查里报错。
 */
const ROUTE_ELEMENTS: Record<RoutePattern, () => ReactElement> = {
  [ROUTE_HOME]: () => <HomePage />,
  [ROUTE_MARKET]: () => <BrowsePage />,
  [ROUTE_MARKET_SELL]: () => <SellPage />,
  [ROUTE_MARKET_MINE]: () => <MyListingsPage />,
  [ROUTE_MARKET_HISTORY]: () => <HistoryPage />,
  [ROUTE_MARKET_INBOX]: () => <InboxPage />,
  [ROUTE_SHOP]: () => <ShopPage />,
  [ROUTE_JOBS]: () => <JobsOverviewPage />,
  [ROUTE_JOB_DETAIL]: () => <JobDetailPage />,
  [ROUTE_MINING]: () => <MiningPage />,
  [ROUTE_QUESTS]: () => <QuestsPage />,
  [ROUTE_CODEX]: () => <CodexPage />,
  [ROUTE_MARRIAGE]: () => <MarriagePage />,
  [ROUTE_CASE]: () => <CasePage />,
  [ROUTE_SETTINGS]: () => <SettingsPage />,
  [ROUTE_ADMIN]: () => <AdminPage />,
  [ROUTE_COMPONENTS]: () => <ComponentsPage />,
}

function UnknownRoute({ path }: { path: string }): ReactElement {
  const navigate = useNavigate()
  return (
    <EmptyBlock
      action={
        <Button
          onClick={() => {
            navigate(ROUTE_HOME)
          }}
        >
          返回首页
        </Button>
      }
      hint={path}
      title="页面不存在"
    />
  )
}

function renderRoute(match: RouteMatch): ReactElement {
  if (match.pattern === null) {
    return <UnknownRoute path={match.path} />
  }
  const render = ROUTE_ELEMENTS[match.pattern]
  return render()
}

export function App(): ReactElement {
  const match = useRouteMatch()
  const frameRef = useRef<HTMLDivElement>(null)

  // 事件入口在挂载期存在即可: 服务端零业务调用方, 此刻它是一条接住但不依赖的空管道 (决策 J2)。
  useEffect(() => installWebUiEventBridge(), [])

  // 全局跟踪可编辑焦点并上报宿主: 打开键 (默认 G) 兼作关闭键, 在输入框里打字时必须让位给字符。
  useTextFocusReporting()

  /*
   * 滚轮接管: 把"原版 sensitivity x 本 mod 的 40 x MCEF 的 3"这条三层放大链钳成一个确定的步长, 并顺带
   * 做平滑滚动。装在最外层是因为它按事件目标向上找滚动容器, 一处即覆盖全部页面与浮层 (见 lib/wheel.ts)。
   */
  useEffect(() => installWheelNormalizer(), [])

  /*
   * 平板可见性。宿主在开/关面板时各派一条事件, 这里把它们收敛成一个全站可读的布尔, 供全部定时器
   * (倒计时时钟与轮询) 判断"还要不要继续跑"。
   *
   * 必须装在 installWebUiEventBridge 之后是个错觉 —— 两者互不依赖: 事件入口装的是 window 上那个分发函数,
   * 本模块登记的是分发表里的订阅者, 顺序任意。真正有顺序要求的是它必须在**任何页面挂载之前**装好,
   * 而它在 App 顶层 effect 里, 天然满足。
   */
  useEffect(() => installPanelVisibility(), [])

  /*
   * 面板重开即全量作废 + 重播一次开面板动画。
   *
   * 关面板只是隐藏 MC 的 Screen, 这个 SPA 原样活着 —— 不接这条的话, 玩家挖完矿打开平板看到的还是上次
   * 打开时的余额与任务进度。宿主在 setScreen 之前派 panelOpened (见 WebUiClient.openScreen)。
   *
   * 作废只是把缓存标记为过期, 不清空: 屏幕上的数字原样留着, 后台重查回来才换 —— 这是"重开面板不闪"
   * 与"重开面板必须看到新数据"两个要求的唯一交点 (见 lib/query-cache.ts)。
   *
   * 动画的重播走"摘掉属性 -> 下一帧再挂上": 同一个属性值不变时 CSS 动画不会重新开始, 而强制重排那种
   * 写法 (读一下 offsetWidth) 靠的是副作用不被优化掉, 跨浏览器版本不可靠。
   */
  useEffect(() => subscribeWebUiEvent('panelOpened', () => {
    invalidateAll()
    const frame = frameRef.current
    if (frame === null) {
      return
    }
    delete frame.dataset.opening
    window.requestAnimationFrame(() => {
      frame.dataset.opening = 'true'
    })
  }), [])

  /*
   * 平板外壳恒在最外层, 路由只决定内容区画什么: 统一入口的意思就是"不存在脱离平板的页面",
   * 组件预览页同样在壳内 —— 它要验的是这套外壳里的真实观感, 单独裸跑反而看不出与导航、
   * 边框叠在一起时的层级关系。
   *
   * onClose 接宿主的 client.closePanel: 关的是 MC 的 Screen 栈, 页面自己关不掉。
   *
   * 根节点用 h-screen + overflow-hidden 把滚动关在壳内, 而不是 min-h-screen 让文档级滚动接管:
   * 平板外壳是一个有边框的矩形, 文档级滚动会让整块平板跟着页面滚出视口, 而不是内容在平板里滚。
   *
   * TooltipProvider 挂在最外层: Base UI 的 Tooltip 靠它共享"组内已有气泡打开时, 后续气泡零延迟"
   * 这份状态。缺了它每个 Hint 各自计时, 表现为在一排图标上划过时每个都要等一遍延迟。
   */
  return (
    <TooltipProvider>
      {/*
        圆角 + overflow-hidden 挂在这一层: 它是页面里唯一铺满视口的元素, 也就是宿主那张离屏贴图的边界。
        html/body 已改成透明 (styles/index.css), 于是圆角之外的四个小三角是真透明, 游戏画面透得出来。
        border 让面板在暗背景上有一条明确的边, 否则深色内容与压暗的背景糊在一起看不出边界。

        圆角与内边距一律走 .tablet-frame 而不是 rounded-* / p-* 工具类: 外框圆角、边框宽度、内边距与
        内屏圆角是一组必须同心的量 (内圈 = 外圈 - 间距), 散成四个工具类之后没有任何东西能阻止它们漂移 ——
        上一版就是这么漂的。关系写在 styles/index.css 的 --radius-frame / --radius-screen 里。
      */}
      <div
        className="tablet-frame h-screen overflow-hidden border border-border bg-background text-foreground"
        data-opening="true"
        ref={frameRef}
      >
        <TabletShell onClose={closePanel}>{renderRoute(match)}</TabletShell>
      </div>
    </TooltipProvider>
  )
}
