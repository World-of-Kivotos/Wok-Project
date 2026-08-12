import type { ReactElement } from 'react'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { PixelFrameScale, PixelFrameVariant } from '../components/pixel/PixelFrame'
import { PIXEL_FRAME_ASSETS, PixelFrame } from '../components/pixel/PixelFrame'
import { ROUTE_HOME, useNavigate } from '../router'

/**
 * 批 1 单点验证页 (PixelUI 规格第十一章第 1 步)。
 *
 * 这一页要回答的问题只有一个: **同一份 9-slice 资产能否同时撑住最小与最大两个尺寸端而不失真。**
 * 答不了就不进第 2 步 —— 度量体系 + 成套资产 + 点阵字体的建制成本远高于一个组件, 必须先证明路线成立。
 *
 * 因此页面刻意不做成"看着挺好看"的示例页, 而是做成读数面板: 所有能暴露失真的数值 (devicePixelRatio、
 * 视口尺寸、border-width 解析值、元素布局盒) 全部打在明面上, 非整数一律高亮。这些数只在真客户端
 * (MCEF 内嵌 Chromium) 里有意义 —— 桌面 Chrome 读到的 devicePixelRatio 不含 MC GUI Scale 那一层叠加。
 */

/** 切换器开放的 --px 档位。故意不含 0 与非整数: 半像素是像素风的致命伤, 不给它入口。 */
const PX_STEPS = [1, 2, 3, 4] as const
type PxStep = (typeof PX_STEPS)[number]

const SCALE_STEPS: readonly PixelFrameScale[] = [1, 2, 3, 4]

const VARIANTS: readonly PixelFrameVariant[] = ['window', 'panel', 'inset']

const VARIANT_LABEL: Record<PixelFrameVariant, string> = {
  window: 'window · 外凸窗口框 (平板 / 弹窗)',
  panel: 'panel · 平面板 (分区 / 卡片)',
  inset: 'inset · 内凹凹槽 (输入框 / 列表底 / 进度槽)',
}

interface ViewportReadout {
  devicePixelRatio: number
  innerWidth: number
  innerHeight: number
  rootWidth: number
  rootHeight: number
  px: string
  pixelScale: string
}

function readViewport(): ViewportReadout {
  const rootStyle = getComputedStyle(document.documentElement)
  // getBoundingClientRect 会给出小数, innerWidth 不会 —— 两者并列才看得出布局盒是否落在半像素上。
  const rect = document.documentElement.getBoundingClientRect()
  return {
    devicePixelRatio: window.devicePixelRatio,
    innerWidth: window.innerWidth,
    innerHeight: window.innerHeight,
    rootWidth: rect.width,
    rootHeight: rect.height,
    px: rootStyle.getPropertyValue('--px').trim(),
    pixelScale: rootStyle.getPropertyValue('--pixel-scale').trim(),
  }
}

interface FrameMetric {
  label: string
  variant: PixelFrameVariant
  top: number
  right: number
  bottom: number
  left: number
  width: number
  height: number
  /** 该节点自身继承到的 --pixel-scale 计算值原文 (可能是空串: 变量被清掉了)。 */
  pixelScale: string
  /** border-image-source 是否解析成了一张图。'none' 即 border-image 整条链没生效。 */
  imageSource: string
  /** border-image-slice 计算值, 形如 "8 fill"。 */
  imageSlice: string
  imageRepeat: string
  imageRendering: string
}

function measureFrame(
  label: string,
  variant: PixelFrameVariant,
  node: HTMLDivElement,
): FrameMetric {
  const style = getComputedStyle(node)
  const rect = node.getBoundingClientRect()
  return {
    label,
    variant,
    top: Number.parseFloat(style.borderTopWidth),
    right: Number.parseFloat(style.borderRightWidth),
    bottom: Number.parseFloat(style.borderBottomWidth),
    left: Number.parseFloat(style.borderLeftWidth),
    width: rect.width,
    height: rect.height,
    pixelScale: style.getPropertyValue('--pixel-scale').trim(),
    imageSource: style.borderImageSource,
    imageSlice: style.borderImageSlice,
    imageRepeat: style.borderImageRepeat,
    imageRendering: style.imageRendering,
  }
}

/** 非整数才是本页要抓的东西, 故整数原样显示, 小数保留三位以便看清是 .5 还是 .333。 */
function fmt(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(3)
}

/** 无单位正整数 (--pixel-scale 的合法形态)。 */
const POSITIVE_INTEGER = /^[1-9]\d*$/

/**
 * 逐帧判据。
 *
 * 这里有意验到 border-image-* 的计算值, 而不是只看 border-width —— 后者是本页最容易自欺的地方:
 * border-image 整条链失效 (资产取不到 / source 被覆盖成 none / fill 丢了 / slice 与资产对不上)
 * 时, border-width 依然是我们自己算出来的那个漂亮整数, 四边依然相等, 布局盒依然完好。
 * 于是"读数全绿、控件其实没有边框"完全可能同时成立。同理, border-width 是整数也不等于倍率是整数:
 * slice=8 配 scale=1.5 得到的 12px 是整数, 但边角位图按 1.5 倍重采样。故倍率必须单独验。
 */
function metricVerdict(metric: FrameMetric): string {
  const asset = PIXEL_FRAME_ASSETS[metric.variant]
  const problems: string[] = []

  if (![metric.top, metric.right, metric.bottom, metric.left].every(Number.isInteger)) {
    problems.push('border-width 非整数 (边角位图落在半像素上)')
  }
  if (metric.top !== metric.left || metric.top !== metric.right || metric.top !== metric.bottom) {
    problems.push('四边边框粗细不等 (9-slice 未生效或 slice 与资产不符)')
  }
  if (!Number.isInteger(metric.width) || !Number.isInteger(metric.height)) {
    problems.push('元素布局盒非整数 (右/下边框会被重采样)')
  }

  if (!POSITIVE_INTEGER.test(metric.pixelScale)) {
    problems.push(`--pixel-scale="${metric.pixelScale}" 非无单位正整数 (边角必被非整数倍重采样)`)
  } else {
    const expected = asset.slice * Number.parseInt(metric.pixelScale, 10)
    if (metric.top !== expected) {
      problems.push(
        `border-width ${fmt(metric.top)} 与 slice x scale = ${String(expected)} 不符 (派生链被外部覆盖)`,
      )
    }
  }

  if (metric.imageSource === 'none' || metric.imageSource === '') {
    problems.push('border-image-source 为 none (控件此刻完全没有边框, 且不会报错)')
  }
  if (!metric.imageSlice.includes('fill')) {
    problems.push('border-image-slice 丢了 fill 关键字 (中心块被丢弃, 控件变空心框)')
  }
  const sliceNumber = Number.parseFloat(metric.imageSlice)
  if (Number.isNaN(sliceNumber) || sliceNumber !== asset.slice) {
    problems.push(`border-image-slice=${metric.imageSlice} 与资产登记 slice ${String(asset.slice)} 不符`)
  }
  if (metric.imageRendering !== 'pixelated') {
    problems.push(`image-rendering=${metric.imageRendering} 非 pixelated (放大走默认平滑插值)`)
  }

  return problems.length === 0 ? 'PASS' : `FAIL: ${problems.join('; ')}`
}

interface AssetProbe {
  status: 'pending' | 'loaded' | 'missing'
  width: number
  height: number
}

const INITIAL_PROBES: Record<PixelFrameVariant, AssetProbe> = {
  window: { status: 'pending', width: 0, height: 0 },
  panel: { status: 'pending', width: 0, height: 0 },
  inset: { status: 'pending', width: 0, height: 0 },
}

function probeVerdict(variant: PixelFrameVariant, probe: AssetProbe): string {
  const asset = PIXEL_FRAME_ASSETS[variant]
  if (probe.status === 'pending') {
    return '探测中'
  }
  if (probe.status === 'missing') {
    /*
     * 资产走 ESM import 由 vite 打包, 正常情况下不会走到这里。真走到了说明产物里的图取不到或解不开
     * (MCEF 内嵌 Chromium 对 data: URI 或该 PNG 位深的支持与桌面 Chrome 不同就是一种可能),
     * 而 border-image 取不到图时是静默无边框、不报错, 因此必须由这张表主动喊出来。
     */
    return 'FAIL: 图片加载失败 (控件将完全无边框且不报错)'
  }
  if (probe.width !== probe.height || probe.width !== asset.size) {
    return `FAIL: 实际 ${String(probe.width)}x${String(probe.height)}, 登记 ${String(asset.size)} 见方`
  }
  if (probe.width < asset.slice * 2 + 1) {
    return `FAIL: 边长小于 slice*2+1 = ${String(asset.slice * 2 + 1)}, 中心块为空`
  }
  return 'PASS'
}

function Readout({
  label,
  value,
  warning,
}: {
  label: string
  value: string
  /** 无异常时显式传 undefined。工程开了 exactOptionalPropertyTypes, 可选属性收不下 undefined。 */
  warning: string | undefined
}): ReactElement {
  return (
    <div className="flex gap-4">
      <dt className="w-48 text-muted">{label}</dt>
      <dd className={warning === undefined ? 'text-fg' : 'text-danger'}>
        {value}
        {warning === undefined ? '' : ` <- ${warning}`}
      </dd>
    </div>
  )
}

export function PixelCheckPage(): ReactElement {
  const navigate = useNavigate()
  const [viewport, setViewport] = useState<ViewportReadout>(readViewport)
  const [metrics, setMetrics] = useState<FrameMetric[]>([])
  const [probes, setProbes] = useState<Record<PixelFrameVariant, AssetProbe>>(INITIAL_PROBES)

  /*
   * null = 不覆盖, 用样式表里的 --px。刻意不给一个"默认档位"作初值: 那会在进页面的瞬间悄悄改掉
   * 全局度量, 读数就不再是样式表的真实取值, 而这页存在的意义正是读出真实取值。
   */
  const [pxOverride, setPxOverride] = useState<PxStep | null>(null)

  // resize 与 --px 切换都会改变布局盒, 读数必须重测; 用计数器把两条触发路径汇到同一个测量 effect。
  const [measureTick, setMeasureTick] = useState(0)

  const windowRef = useRef<HTMLDivElement | null>(null)
  const panelRef = useRef<HTMLDivElement | null>(null)
  const insetRef = useRef<HTMLDivElement | null>(null)
  const tabletRef = useRef<HTMLDivElement | null>(null)

  const measure = useCallback(() => {
    const targets: readonly (readonly [string, PixelFrameVariant, HTMLDivElement | null])[] = [
      ['window 对比组', 'window', windowRef.current],
      ['panel 对比组', 'panel', panelRef.current],
      ['inset 对比组', 'inset', insetRef.current],
      ['满视口平板 (window)', 'window', tabletRef.current],
    ]
    const next: FrameMetric[] = []
    for (const [label, variant, node] of targets) {
      if (node !== null) {
        next.push(measureFrame(label, variant, node))
      }
    }
    setMetrics(next)
    setViewport(readViewport())
  }, [])

  useEffect(() => {
    const onResize = (): void => {
      setMeasureTick((tick) => tick + 1)
    }
    // MC 窗口尺寸变化与 GUI Scale 调整都会让 MCEF 重设浏览器视口。
    window.addEventListener('resize', onResize)
    return () => {
      window.removeEventListener('resize', onResize)
    }
  }, [])

  /*
   * 进页面时根元素上原本挂着的内联 --px (含 !important 优先级), 挂载时抓一次存住。
   *
   * 不能用 removeProperty 代替"还原": 宿主完全可能在打开页面前就用内联样式给过 --px (那正是 MCEF
   * 侧按 GUI Scale 下发度量最顺手的做法)。切回"样式表原值"档、或离开本页时若一律 removeProperty,
   * 抹掉的是宿主设的值而不是本页设的值 —— 本页会拿一个从没生效过的基线去验证, 离开后还把宿主的度量弄丢了。
   */
  const originalInlinePx = useRef<{ value: string; priority: string } | null>(null)
  if (originalInlinePx.current === null) {
    const inline = document.documentElement.style
    originalInlinePx.current = {
      value: inline.getPropertyValue('--px'),
      priority: inline.getPropertyPriority('--px'),
    }
  }

  useEffect(() => {
    const root = document.documentElement
    const original = originalInlinePx.current
    const restore = (): void => {
      if (original === null || original.value === '') {
        root.style.removeProperty('--px')
      } else {
        root.style.setProperty('--px', original.value, original.priority)
      }
    }
    if (pxOverride === null) {
      restore()
    } else {
      root.style.setProperty('--px', `${String(pxOverride)}px`)
    }
    setMeasureTick((tick) => tick + 1)
    // 切换器是验证工具而非全局偏好: 离开本页即还原, 否则会把试出来的值带进其它面板。
    return restore
  }, [pxOverride])

  useEffect(() => {
    measure()
  }, [measure, measureTick])

  useEffect(() => {
    let cancelled = false
    for (const variant of VARIANTS) {
      const probe = new Image()
      probe.onload = () => {
        if (!cancelled) {
          setProbes((prev) => ({
            ...prev,
            [variant]: { status: 'loaded', width: probe.naturalWidth, height: probe.naturalHeight },
          }))
        }
      }
      probe.onerror = () => {
        if (!cancelled) {
          setProbes((prev) => ({ ...prev, [variant]: { status: 'missing', width: 0, height: 0 } }))
        }
      }
      probe.src = PIXEL_FRAME_ASSETS[variant].src
    }
    return () => {
      cancelled = true
    }
  }, [])

  const ratioWarning = Number.isInteger(viewport.devicePixelRatio)
    ? undefined
    : '非整数: 位图被非整数倍重采样, 需在游戏内把 GUI Scale 调到整数档'
  const rootBoxWarning =
    Number.isInteger(viewport.rootWidth) && Number.isInteger(viewport.rootHeight)
      ? undefined
      : '非整数: 布局盒落在半像素上'
  /*
   * 两处都要求"正整数"而不是"整数": 0 与空串同样致命且更隐蔽 ——
   * --px:0px 会让整套 calc 派生尺寸塌成 0 (间距字号全没), --pixel-scale 缺席或为 0 会让 border-width
   * 的 calc 整条失效或算成 0 (边框直接消失)。而 /^\d+px$/ 收 "0px"、Number("") 是 0 且被 Number.isInteger
   * 判为整数, 两个旧判据都会对这类值给出"无警告", 恰好在界面已经废了的时候显示一切正常。
   */
  const pxWarning = /^[1-9]\d*px$/.test(viewport.px)
    ? undefined
    : `"${viewport.px}" 不是正整数 px: 整套派生尺寸都会落在半像素上或塌成 0`
  const scaleWarning = /^[1-9]\d*$/.test(viewport.pixelScale)
    ? undefined
    : `"${viewport.pixelScale}" 不是无单位正整数: 9-slice 边角必糊, 取 0 则边框直接消失`

  return (
    <section className="flex flex-col gap-8">
      <header className="flex flex-col gap-2">
        <h1 className="text-2x text-fg">像素单点验证 (批 1)</h1>
        <p className="text-1x text-muted">
          同一份 frame-window.png 同时撑起行内小按钮与满视口平板。此步不通过, 后面全部作废。
        </p>
      </header>

      <PixelFrame variant="inset" className="flex flex-col gap-2 p-4">
        <h2 className="text-1x text-accent">看什么 (三条目视验收)</h2>
        <p className="text-1x text-fg">1. 四角是否被拉扁 —— 小按钮与满视口平板的角必须完全同形同大。</p>
        <p className="text-1x text-fg">2. 横竖边框粗细是否失衡 —— 上下边与左右边的视觉厚度必须相等。</p>
        <p className="text-1x text-fg">
          3. 边缘是否出现半透明模糊像素 —— 出现即发生了非整数倍重采样, 对照下方高亮为红的读数。
        </p>
      </PixelFrame>

      <div className="flex flex-col gap-4">
        <h2 className="text-1x text-accent">--px 切换器</h2>
        <p className="text-1x text-muted">
          --px 决定布局尺寸 (间距 / 字号 / 普通边框), --pixel-scale 决定 9-slice 边角放大倍率, 两者正交。
          在真客户端逐档试, 找出不产生半像素的取值。
        </p>
        <div className="flex flex-wrap gap-4">
          <button
            type="button"
            className={
              pxOverride === null
                ? 'border border-accent bg-surface px-4 py-2 text-1x text-accent shadow-hard'
                : 'border border-muted bg-surface px-4 py-2 text-1x text-muted'
            }
            onClick={() => {
              setPxOverride(null)
            }}
          >
            样式表原值
          </button>
          {PX_STEPS.map((step) => (
            <button
              key={step}
              type="button"
              className={
                pxOverride === step
                  ? 'border border-accent bg-surface px-4 py-2 text-1x text-accent shadow-hard'
                  : 'border border-muted bg-surface px-4 py-2 text-1x text-muted'
              }
              onClick={() => {
                setPxOverride(step)
              }}
            >
              --px: {step}px
            </button>
          ))}
        </div>
      </div>

      <dl className="flex flex-col gap-2 border border-muted bg-surface p-4 text-1x">
        <Readout
          label="devicePixelRatio"
          value={fmt(viewport.devicePixelRatio)}
          warning={ratioWarning}
        />
        <Readout
          label="视口 CSS 像素"
          value={`${String(viewport.innerWidth)} x ${String(viewport.innerHeight)}`}
          warning={undefined}
        />
        <Readout
          label="根元素布局盒"
          value={`${fmt(viewport.rootWidth)} x ${fmt(viewport.rootHeight)}`}
          warning={rootBoxWarning}
        />
        <Readout label="--px (计算值)" value={viewport.px} warning={pxWarning} />
        <Readout label="--pixel-scale" value={viewport.pixelScale} warning={scaleWarning} />
      </dl>

      <div className="flex flex-col gap-4">
        <h2 className="text-1x text-accent">border-width 与 border-image 实际解析值</h2>
        <div className="flex flex-col gap-2 border border-muted bg-surface p-4 text-1x">
          {metrics.map((metric) => {
            const verdict = metricVerdict(metric)
            return (
              <div key={metric.label} className="flex flex-wrap gap-4">
                <span className="w-48 text-muted">{metric.label}</span>
                <span className="text-fg">
                  上 {fmt(metric.top)} / 右 {fmt(metric.right)} / 下 {fmt(metric.bottom)} / 左{' '}
                  {fmt(metric.left)}
                </span>
                <span className="text-fg">
                  盒 {fmt(metric.width)} x {fmt(metric.height)}
                </span>
                <span className="text-fg">scale {metric.pixelScale === '' ? '(空)' : metric.pixelScale}</span>
                <span className="text-fg">
                  slice {metric.imageSlice} / {metric.imageRepeat} /{' '}
                  {metric.imageSource === 'none' ? 'source=none' : 'source=ok'} /{' '}
                  {metric.imageRendering}
                </span>
                <span className={verdict === 'PASS' ? 'text-fg' : 'text-danger'}>{verdict}</span>
              </div>
            )
          })}
        </div>
      </div>

      <div className="flex flex-col gap-4">
        <h2 className="text-1x text-accent">资产探测</h2>
        <div className="flex flex-col gap-2 border border-muted bg-surface p-4 text-1x">
          {VARIANTS.map((variant) => {
            const asset = PIXEL_FRAME_ASSETS[variant]
            const probe = probes[variant]
            const verdict = probeVerdict(variant, probe)
            return (
              <div key={variant} className="flex flex-wrap gap-4">
                <span className="w-48 text-muted">{variant}</span>
                <span className="text-fg">{asset.file}</span>
                <span className="text-fg">slice {asset.slice}</span>
                <span className={verdict === 'PASS' ? 'text-fg' : 'text-danger'}>{verdict}</span>
              </div>
            )
          })}
        </div>
      </div>

      <div className="flex flex-col gap-4">
        <h2 className="text-1x text-accent">最小尺寸端 · 行内小按钮 (window 资产, 四档倍率)</h2>
        <p className="text-1x text-muted">
          四个按钮是同一张图。倍率越大边角像素格越粗, 但角的形状必须完全不变 —— 变形即 9-slice 失效。
        </p>
        <div className="flex flex-wrap items-start gap-8">
          {SCALE_STEPS.map((scale) => (
            <PixelFrame
              key={scale}
              variant="window"
              scale={scale}
              className="inline-block px-4 py-2 text-1x text-fg"
            >
              确认 x{scale}
            </PixelFrame>
          ))}
        </div>
      </div>

      <div className="flex flex-col gap-4">
        <h2 className="text-1x text-accent">三档层级对比 (外凸 / 平面 / 内凹)</h2>
        <p className="text-1x text-muted">
          明暗关系必须能一眼分辨: 外凸的高光在上/左, 内凹的高光在下/右。分不出即层级维度的出图不成立,
          换色也救不回来。
        </p>
        <div className="flex flex-wrap gap-8">
          <PixelFrame ref={windowRef} variant="window" className="flex w-96 flex-col gap-2 p-4">
            <span className="text-1x text-fg">{VARIANT_LABEL.window}</span>
            <span className="text-1x text-muted">承载平板与弹窗的最外层</span>
          </PixelFrame>
          <PixelFrame ref={panelRef} variant="panel" className="flex w-96 flex-col gap-2 p-4">
            <span className="text-1x text-fg">{VARIANT_LABEL.panel}</span>
            <span className="text-1x text-muted">窗口内的分区与卡片</span>
          </PixelFrame>
          <PixelFrame ref={insetRef} variant="inset" className="flex w-96 flex-col gap-2 p-4">
            <span className="text-1x text-fg">{VARIANT_LABEL.inset}</span>
            <span className="text-1x text-muted">输入框 / 列表底 / 进度槽</span>
          </PixelFrame>
        </div>
      </div>

      <div className="flex flex-col gap-4">
        <h2 className="text-1x text-accent">最大尺寸端 · 满视口平板 (window 资产)</h2>
        <PixelFrame ref={tabletRef} variant="window" className="flex h-screen w-full flex-col gap-4 p-8">
          <span className="text-2x text-fg">平板 hub 尺寸样板</span>
          <span className="text-1x text-muted">
            与上方行内小按钮是同一张 frame-window.png。把这一块的角与小按钮的角并排比对: 尺寸差了两个数量级,
            角必须依然 1:1。
          </span>
          <div className="flex flex-1 items-center justify-center">
            <span className="text-1x text-muted">
              此处若透出页面底色而非中心块纹理, 说明 border-image-slice 的 fill 关键字丢了。
            </span>
          </div>
        </PixelFrame>
      </div>

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
