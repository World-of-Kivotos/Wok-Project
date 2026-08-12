import type { ReactElement } from 'react'
import { useEffect } from 'react'
import { installWebUiEventBridge } from './bridge/events'
import { HomePage } from './pages/HomePage'
import { PixelCheckPage } from './pages/PixelCheckPage'
import { ROUTE_HOME, ROUTE_PIXEL_CHECK, useNavigate, useRoute } from './router'

function UnknownRoute({ path }: { path: string }): ReactElement {
  const navigate = useNavigate()
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2x text-danger">未知路由</h1>
      <p className="text-1x text-muted">{path}</p>
      <button
        type="button"
        className="w-48 border border-accent bg-surface px-4 py-2 text-1x text-fg shadow-hard"
        onClick={() => {
          navigate(ROUTE_HOME)
        }}
      >
        返回首页
      </button>
    </section>
  )
}

function renderRoute(route: string): ReactElement {
  switch (route) {
    case ROUTE_HOME:
      return <HomePage />
    case ROUTE_PIXEL_CHECK:
      return <PixelCheckPage />
    default:
      return <UnknownRoute path={route} />
  }
}

export function App(): ReactElement {
  const route = useRoute()

  // 事件入口在挂载期存在即可: 服务端零业务调用方, 此刻它是一条接住但不依赖的空管道 (决策 J2)。
  useEffect(() => installWebUiEventBridge(), [])

  return <main className="min-h-screen bg-bg p-8 font-pixel text-1x text-fg">{renderRoute(route)}</main>
}
