import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { initBrand } from './lib/brand'
import { initTheme } from './lib/theme'
import './styles/index.css'

const container = document.getElementById('root')
if (container === null) {
  throw new Error('未找到 #root 挂载点: index.html 与入口脚本不同步')
}

// 两者都必须早于首次渲染: 晚一步就是先按默认档画一帧再改, 在整屏换色的场景下这一帧的闪烁肉眼可见。
initTheme()
initBrand()

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
