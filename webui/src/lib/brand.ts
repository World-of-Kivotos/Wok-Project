import { useCallback, useEffect, useState } from 'react'

/**
 * 可调强调色。色板本体在 src/styles/index.css, 本模块只负责把用户的选择写到根元素的行内样式上。
 *
 * 为什么只开放色相与彩度两个自由度, 不给完整取色器:
 *
 * 强调色要承载焦点环、当前导航项、进度条填充、选中行这些位置, 其中一部分要在上面压白字
 * (Button variant="brand")。放开亮度就意味着用户能调出淡黄底白字这种读不出来的组合, 而这类问题
 * 只在真客户端里、只在某几个页面上才暴露。把 oklch 的 L 锁死在样式表的常量上 (暗色档 0.64,
 * 亮色档 0.55), 对比度就成了结构性保证而不是用户自觉。
 *
 * 彩度可以拧到 0 —— 那是纯中性灰强调, 整套界面退化为完全无彩色。这是设计终点之一, 不是退化。
 *
 * 与 theme.ts 的分工: 那边管亮/暗两档的整体切换 (写类名), 这边管强调色 (写行内自定义属性)。
 * 两者正交, 换主题不会重置强调色。
 */

export interface Brand {
  /** oklch 色相角, 0-360。 */
  hue: number
  /** oklch 彩度, 0 到 BRAND_CHROMA_MAX。0 即纯中性灰。 */
  chroma: number
}

/** 彩度上限。再高会在暗色档冲出 sRGB 色域, 表现为不同色相下的强调色明度对不齐。 */
export const BRAND_CHROMA_MAX = 0.2

/** 默认值必须与 index.css 里 :root 的 --brand-h / --brand-c 一致, 否则首帧会闪一次色。 */
const DEFAULT_BRAND: Brand = { hue: 250, chroma: 0.15 }

/** 与 theme.ts 同理: MCEF 的 localStorage 按 origin 隔离, 本站与将来同源的其它页面共用, 必须带前缀。 */
const STORAGE_KEY = 'wok-brand'

/** 预设色相。给取色器一排一键可选的锚点, 免得所有人都得自己拖滑块找一个能看的角度。 */
export const BRAND_PRESETS: readonly { label: string; hue: number }[] = [
  { label: '靛蓝', hue: 250 },
  { label: '天青', hue: 225 },
  { label: '青碧', hue: 195 },
  { label: '松绿', hue: 155 },
  { label: '琥珀', hue: 75 },
  { label: '绯红', hue: 25 },
  { label: '品红', hue: 340 },
  { label: '紫罗兰', hue: 295 },
]

function isValidBrand(value: unknown): value is Brand {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const candidate = value as Record<string, unknown>
  return (
    typeof candidate.hue === 'number' &&
    Number.isFinite(candidate.hue) &&
    candidate.hue >= 0 &&
    candidate.hue <= 360 &&
    typeof candidate.chroma === 'number' &&
    Number.isFinite(candidate.chroma) &&
    candidate.chroma >= 0 &&
    candidate.chroma <= BRAND_CHROMA_MAX
  )
}

/**
 * 未设置过 -> 默认值, 这是正常路径。
 * 设置过但读不出来 -> 直接抛。写入方只有本模块一处, 值形状固定为两个数字, 能坏只可能是本模块自己写错了,
 * 那是必须当场暴露的缺陷, 不是该被容错掩盖的用户数据问题。
 */
function readStored(): Brand {
  const raw = localStorage.getItem(STORAGE_KEY)
  if (raw === null) {
    return DEFAULT_BRAND
  }
  const parsed: unknown = JSON.parse(raw)
  if (!isValidBrand(parsed)) {
    throw new Error(`强调色存储值不合法, 期望 { hue, chroma } 两个数字, 实得: ${raw}`)
  }
  return parsed
}

/**
 * 行内自定义属性写在 <html> 上。它压得过样式表里 :root / .dark / .light 的任何一档,
 * 于是亮暗切换时强调色不受影响 —— 两档各自的 --brand 都是从这同一对 h/c 派生的。
 */
function apply(brand: Brand): void {
  const root = document.documentElement
  root.style.setProperty('--brand-h', String(brand.hue))
  root.style.setProperty('--brand-c', String(brand.chroma))
}

/** 在 React 渲染前调用 (main.tsx 入口), 防首屏换色闪烁。 */
export function initBrand(): void {
  apply(readStored())
}

export interface BrandControl {
  brand: Brand
  setBrand: (next: Brand) => void
  reset: () => void
}

export function useBrand(): BrandControl {
  const [brand, setBrandState] = useState<Brand>(readStored)

  useEffect(() => {
    apply(brand)
    localStorage.setItem(STORAGE_KEY, JSON.stringify(brand))
  }, [brand])

  const setBrand = useCallback((next: Brand) => {
    setBrandState(next)
  }, [])

  const reset = useCallback(() => {
    setBrandState(DEFAULT_BRAND)
  }, [])

  return { brand, setBrand, reset }
}
