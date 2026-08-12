import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { assertPixelGrid } from './dev/assertPixelGrid'
import { initTheme } from './lib/theme'
import './styles/index.css'

const container = document.getElementById('root')
if (container === null) {
  throw new Error('未找到 #root 挂载点: index.html 与入口脚本不同步')
}

// 样式表在本模块求值前就已生效 (dev 下 import 同步注入 style, 构建后是 head 里的 link),
// 因此这里能读到最终的 --px。刻意不加 DEV 守卫: 生产环境的半像素同样致命, 且批 1 标定可能跑构建产物。
assertPixelGrid()

// 必须早于首次渲染: 晚一步就是先按默认档画一帧再改类名, 在整屏换色的场景下这一帧的闪烁肉眼可见。
initTheme()

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
