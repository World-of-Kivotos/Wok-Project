import type { ReactElement } from 'react'
import { useEffect } from 'react'
import { installWebUiEventBridge } from './bridge/events'
import { PixelButton, PixelEmpty } from './components/pixel'
import { TabletShell } from './components/shell/TabletShell'
import { AdminPage } from './pages/admin/AdminPage'
import { CasePage } from './pages/CasePage'
import { CodexPage } from './pages/CodexPage'
import { ColorCheckPage } from './pages/ColorCheckPage'
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
import { PixelCheckPage } from './pages/PixelCheckPage'
import { SettingsPage } from './pages/SettingsPage'
import { ShopPage } from './pages/ShopPage'
import type { RouteMatch, RoutePattern } from './router'
import {
  ROUTE_ADMIN,
  ROUTE_CASE,
  ROUTE_CODEX,
  ROUTE_COLOR_CHECK,
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
  ROUTE_PIXEL_CHECK,
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
  [ROUTE_CODEX]: () => <CodexPage />,
  [ROUTE_MARRIAGE]: () => <MarriagePage />,
  [ROUTE_CASE]: () => <CasePage />,
  [ROUTE_SETTINGS]: () => <SettingsPage />,
  [ROUTE_ADMIN]: () => <AdminPage />,
  [ROUTE_PIXEL_CHECK]: () => <PixelCheckPage />,
  [ROUTE_COLOR_CHECK]: () => <ColorCheckPage />,
  [ROUTE_COMPONENTS]: () => <ComponentsPage />,
}

function UnknownRoute({ path }: { path: string }): ReactElement {
  const navigate = useNavigate()
  return (
    <section className="flex flex-col items-start gap-4">
      <PixelEmpty title="未知路由" hint={path} icon="warning" />
      <PixelButton
        tone="accent"
        onClick={() => {
          navigate(ROUTE_HOME)
        }}
      >
        返回首页
      </PixelButton>
    </section>
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

  // 事件入口在挂载期存在即可: 服务端零业务调用方, 此刻它是一条接住但不依赖的空管道 (决策 J2)。
  useEffect(() => installWebUiEventBridge(), [])

  /*
   * 平板外壳恒在最外层, 路由只决定内容区画什么: 统一入口的意思就是"不存在脱离平板的页面",
   * 验证页 (#/pixel-check / #/color-check / #/components) 同样在壳内 —— 它们要验的是这套外壳里的
   * 真实观感, 单独裸跑反而看不出与导航、边框叠在一起时的层级关系。
   *
   * onClose 不传: 宿主侧还没有关闭通道 (接线清单第四章), 外壳据此把关闭按钮渲染成禁用态。
   */
  /*
   * 用 h-screen + overflow-hidden 把滚动关在壳内, 而不是 min-h-screen 让文档级滚动接管。
   *
   * 理由是硬红线而非洁癖: 文档级滚动会拉出 Chromium 的原生滚动条, 那是一根圆角抗锯齿矢量控件 ——
   * 恰好是整套界面里唯一破像素线的东西, 且出现在最容易被看到的位置。PixelScrollArea 的自绘纪律
   * 只覆盖它包住的内部容器, 管不到最外层。
   *
   * 代价: 内容超出视口时由壳内的滚动容器负责, 页面自身必须把长列表放进 PixelScrollArea。
   */
  return (
    <main className="flex h-screen flex-col overflow-hidden bg-bg p-4 font-pixel text-1x text-fg">
      <TabletShell>{renderRoute(match)}</TabletShell>
    </main>
  )
}
