import type { ReactElement } from 'react'
import { useEffect } from 'react'
import { installWebUiEventBridge } from './bridge/events'
import { Button, EmptyBlock } from './components/kit'
import { TabletShell } from './components/shell/TabletShell'
import { closePanel, useTextFocusReporting } from '@/lib/host-panel'
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

  // 全局跟踪可编辑焦点并上报宿主: 打开键 (默认 G) 兼作关闭键, 在输入框里打字时必须让位给字符。
  useTextFocusReporting()

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
      */}
      <div className="h-screen overflow-hidden rounded-2xl border border-border bg-background p-3 text-foreground">
        <TabletShell onClose={closePanel}>{renderRoute(match)}</TabletShell>
      </div>
    </TooltipProvider>
  )
}
