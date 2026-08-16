import type { ReactElement } from 'react'
import { useEffect } from 'react'
import { installWebUiEventBridge } from './bridge/events'
import { Button, EmptyBlock } from './components/kit'
import { TabletShell } from './components/shell/TabletShell'
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

  // 事件入口在挂载期存在即可: 服务端零业务调用方, 此刻它是一条接住但不依赖的空管道 (决策 J2)。
  useEffect(() => installWebUiEventBridge(), [])

  /*
   * 平板外壳恒在最外层, 路由只决定内容区画什么: 统一入口的意思就是"不存在脱离平板的页面",
   * 组件预览页同样在壳内 —— 它要验的是这套外壳里的真实观感, 单独裸跑反而看不出与导航、
   * 边框叠在一起时的层级关系。
   *
   * onClose 不传: 宿主侧还没有关闭通道 (接线清单第四章), 外壳据此把关闭按钮渲染成禁用态。
   *
   * 根节点用 h-screen + overflow-hidden 把滚动关在壳内, 而不是 min-h-screen 让文档级滚动接管:
   * 平板外壳是一个有边框的矩形, 文档级滚动会让整块平板跟着页面滚出视口, 而不是内容在平板里滚。
   *
   * TooltipProvider 挂在最外层: Base UI 的 Tooltip 靠它共享"组内已有气泡打开时, 后续气泡零延迟"
   * 这份状态。缺了它每个 Hint 各自计时, 表现为在一排图标上划过时每个都要等一遍延迟。
   */
  return (
    <TooltipProvider>
      <div className="h-screen overflow-hidden bg-background p-3 text-foreground">
        <TabletShell>{renderRoute(match)}</TabletShell>
      </div>
    </TooltipProvider>
  )
}
