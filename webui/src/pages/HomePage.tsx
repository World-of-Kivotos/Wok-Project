import type { ReactElement } from 'react'
import { ROUTE_PIXEL_CHECK, useNavigate } from '../router'

/**
 * 平板 hub 首页占位。真正的信息架构 (个人档案 / 跳蚤市场 / 8 职业 / 矿洞 ...) 见接线清单第一章,
 * 其后端覆盖率当前约 15%, 故此处只留导航骨架, 不提前铺没有数据源的页签。
 */
export function HomePage(): ReactElement {
  const navigate = useNavigate()

  return (
    <section className="flex flex-col gap-8">
      <header className="flex flex-col gap-2">
        <h1 className="text-3x text-fg">WORLD OF KIVOTOS</h1>
        <p className="text-1x text-muted">游戏内 Web UI · 像素设计系统地基</p>
      </header>

      <nav className="flex flex-col gap-4">
        <button
          type="button"
          className="w-96 border border-accent bg-surface px-4 py-3 text-1x text-fg shadow-hard"
          onClick={() => {
            navigate(ROUTE_PIXEL_CHECK)
          }}
        >
          像素单点验证 (批 1)
        </button>
      </nav>
    </section>
  )
}
