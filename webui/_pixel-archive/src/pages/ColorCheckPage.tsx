import type { ReactElement } from 'react'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { PixelFrameTone, PixelFrameVariant } from '../components/pixel/PixelFrame'
import { PIXEL_FRAME_ASSETS, PIXEL_FRAME_TONES, PixelFrame } from '../components/pixel/PixelFrame'
import { useTheme } from '../lib/theme'
import { ROUTE_HOME, useNavigate } from '../router'

/**
 * 灰度上色链路验证页 (规格第六章)。路由 #/color-check。
 *
 * 这一页要回答的问题只有一个: **同一张灰度 9-slice 资产, 能否只换一个 CSS 变量就变成 N 种语义色,
 * 且斜面明暗关系一起跟着走。** 答不了就等于规格第六章的压缩原则不成立 —— 每档颜色都得单独出图,
 * 资产量与美术工作量按语义档数翻倍。
 *
 * 没有浏览器自动化, 所以本页不做"看着挺好看"的示例, 而是做成可判读的对照装置, 三层验证叠在一起:
 *
 *   1. 特性支持矩阵: CSS.supports 直接问引擎认不认这条链上的每个属性 (MCEF 的 Chromium 版本未核实,
 *      规格第十二章的 PENDING 就靠在真客户端打开本页来销)。
 *   2. 染色层实测读数: 读染色伪元素的 computed style。@supports 不通过时伪元素根本不生成,
 *      content 会是 none —— 这条比"眼看颜色变了"可靠, 因为浅色档下"没染上色"与"染上了浅色"肉眼难分。
 *   3. 预测色对照: 从资产 PNG 里采出中心块真实灰度, 按 overlay 混合公式算出中心块应有的颜色,
 *      再把该颜色的实色块贴在框体中心。链路正确则色块与框体中心无缝, 错一点点就能看见接缝。
 *
 * 一眼判据: 下方六个框若颜色各不相同, 链路成立; 若六个框长得一模一样 (全是资产自身的灰), 链路没生效。
 */

const REFERENCE_VARIANT: PixelFrameVariant = 'window'

const VARIANTS: readonly PixelFrameVariant[] = ['window', 'panel', 'inset']

const TONE_LABEL: Record<PixelFrameTone, string> = {
  neutral: 'neutral · 默认容器',
  accent: 'accent · 强调 / 选中',
  success: 'success · 成交 / 完成',
  warning: 'warning · 库存不足 / 待确认',
  danger: 'danger · 破坏性操作',
  info: 'info · 提示 / 说明',
}

/** 色板 token 分组。顺序即视觉层级, 排版上要能看出"底 -> 面 -> 前景 -> 线 -> 语义"这条递进。 */
const TOKEN_GROUPS: readonly (readonly [string, readonly string[]])[] = [
  ['中性三层', ['--color-bg', '--color-surface', '--color-raised']],
  ['前景', ['--color-fg', '--color-muted', '--color-on-accent']],
  ['边框两级', ['--color-border', '--color-border-strong']],
  ['强调三态', ['--color-accent', '--color-accent-hover', '--color-accent-active']],
  ['语义四色', ['--color-success', '--color-warning', '--color-danger', '--color-info']],
  ['阴影', ['--color-shadow']],
  [
    '9-slice 上色锚点 (基色, 非最终框体色)',
    [
      '--color-tone-neutral',
      '--color-tone-accent',
      '--color-tone-success',
      '--color-tone-warning',
      '--color-tone-danger',
      '--color-tone-info',
    ],
  ],
]

/**
 * 逐条特性探测。写成"属性: 值"整条去问而不是只问属性名, 是因为引擎认得属性名却不认某个值的情况确实存在
 * (mask-border-* 的各家实现进度并不一致), 而那种半支持恰好是最容易被当成"支持"的一档。
 */
const FEATURE_PROBES: readonly (readonly [string, string, string])[] = [
  ['mix-blend-mode: overlay', 'mix-blend-mode', 'overlay'],
  ['isolation: isolate', 'isolation', 'isolate'],
  ['mask-border-source (无前缀)', 'mask-border-source', 'url("a.png")'],
  ['-webkit-mask-box-image-source (前缀)', '-webkit-mask-box-image-source', 'url("a.png")'],
  ['mask-border-slice 的 fill', 'mask-border-slice', '8 fill'],
  ['-webkit-mask-box-image-slice 的 fill', '-webkit-mask-box-image-slice', '8 fill'],
  // 只有简写活、四条长写全死的引擎是存在的; 真遇上就把样式表那四条换成简写, 故这一行必须单独探。
  ['-webkit-mask-box-image (简写)', '-webkit-mask-box-image', 'url("a.png") 8 fill round'],
  ['image-rendering: pixelated', 'image-rendering', 'pixelated'],
]

/** overlay 混合 (CSS Compositing 规范): 灰度作背景层, 基色作源层, 0.5 灰是提亮与压暗的支点。 */
function overlayChannel(gray: number, tone: number): number {
  const b = gray / 255
  const s = tone / 255
  const result = b <= 0.5 ? 2 * b * s : 1 - 2 * (1 - b) * (1 - s)
  return Math.round(result * 255)
}

const HEX_COLOR = /^#([0-9a-f]{6})$/i

/** 只认 6 位十六进制: 色板 token 全部按这个形态写死, 出现别的形态说明有人绕过了 token 表。 */
function parseHexColor(raw: string): readonly [number, number, number] | null {
  const match = HEX_COLOR.exec(raw.trim())
  if (match === null) {
    return null
  }
  const body = match[1]
  if (body === undefined) {
    return null
  }
  const value = Number.parseInt(body, 16)
  return [(value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff]
}

function toHexColor(rgb: readonly [number, number, number]): string {
  return `#${rgb.map((channel) => channel.toString(16).padStart(2, '0')).join('')}`
}

function readToken(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

interface AssetSample {
  /** 中心块 (fill 区) 的灰度。overlay 的预测色按它算。 */
  fillGray: number
  /** 四角像素的 alpha。0 才有"角孔"可挖; 非 0 说明资产没挖角, 蒙版那一环白做。 */
  cornerAlpha: number
  /** 采样失败原因; 为空串表示成功。canvas 在某些嵌入式 Chromium 里可能被禁, 必须能报出来。 */
  error: string
}

/** 把资产画进 canvas 采真实像素。硬编码灰度值等于把资产的事实抄一份, 资产一改这页就开始说谎。 */
async function sampleAsset(src: string, size: number): Promise<AssetSample> {
  const image = new Image()
  const loaded = new Promise<boolean>((resolve) => {
    image.onload = () => {
      resolve(true)
    }
    image.onerror = () => {
      resolve(false)
    }
  })
  image.src = src
  if (!(await loaded)) {
    return { fillGray: 0, cornerAlpha: 0, error: '资产加载失败' }
  }

  const canvas = document.createElement('canvas')
  canvas.width = size
  canvas.height = size
  const context = canvas.getContext('2d')
  if (context === null) {
    return { fillGray: 0, cornerAlpha: 0, error: 'canvas 2d 上下文不可用, 无法采样' }
  }
  context.drawImage(image, 0, 0)
  const center = context.getImageData(Math.floor(size / 2), Math.floor(size / 2), 1, 1).data
  const corner = context.getImageData(0, 0, 1, 1).data
  const gray = center[0]
  const alpha = corner[3]
  if (gray === undefined || alpha === undefined) {
    return { fillGray: 0, cornerAlpha: 0, error: 'getImageData 返回的数据不足 4 通道' }
  }
  return { fillGray: gray, cornerAlpha: alpha, error: '' }
}

interface TintReadout {
  content: string
  blendMode: string
  background: string
  maskStandard: string
  maskPrefixed: string
  offsetTop: string
}

function readTintLayer(node: HTMLDivElement): TintReadout {
  const style = getComputedStyle(node, '::after')
  return {
    content: style.content,
    blendMode: style.mixBlendMode,
    background: style.backgroundColor,
    maskStandard: style.getPropertyValue('mask-border-source'),
    maskPrefixed: style.getPropertyValue('-webkit-mask-box-image-source'),
    offsetTop: style.top,
  }
}

/**
 * 染色层判决。
 *
 * 蒙版整条失效只判成"降级"而不是 FAIL: 那一档丢的只是四角各 slice x scale 见方的挖空,
 * 上色本身完全成立。把它判成 FAIL 会让真正的失效 (伪元素没生成 / 混合模式没生效) 淹没在噪声里。
 */
function tintVerdict(readout: TintReadout, expectedOffset: number): string {
  const problems: string[] = []
  if (readout.content === 'none' || readout.content === 'normal') {
    problems.push('染色伪元素未生成 (@supports 未通过): 框体退回灰度原样, 六个框将完全同色')
  }
  if (readout.blendMode !== 'overlay') {
    problems.push(`mix-blend-mode=${readout.blendMode} 非 overlay: 染色层会盖成一块纯色板`)
  }
  if (readout.background === 'rgba(0, 0, 0, 0)' || readout.background === 'transparent') {
    problems.push('染色层背景色为透明: --pixel-tone 没接上, 或 --color-tone-* 缺失')
  }
  const offset = Number.parseFloat(readout.offsetTop)
  if (Number.isNaN(offset) || offset !== expectedOffset) {
    problems.push(
      `染色层上偏移 ${readout.offsetTop} 与 slice x scale = ${String(expectedOffset)}px 不符: 色层与框体错位`,
    )
  }

  const hasMask = readout.maskStandard !== 'none' || readout.maskPrefixed !== 'none'
  if (problems.length > 0) {
    return `FAIL: ${problems.join('; ')}`
  }
  return hasMask
    ? 'PASS'
    : 'PASS (降级: 两套 mask-border 均未生效, 四角不挖空, 每角多出 slice x scale 见方的实色)'
}

function Row({ label, value, bad }: { label: string; value: string; bad: boolean }): ReactElement {
  return (
    <div className="flex flex-wrap gap-4">
      <span className="w-48 text-muted">{label}</span>
      <span className={bad ? 'text-danger' : 'text-fg'}>{value}</span>
    </div>
  )
}

export function ColorCheckPage(): ReactElement {
  const navigate = useNavigate()
  const { theme, toggle } = useTheme()
  const referenceRef = useRef<HTMLDivElement | null>(null)

  const [sample, setSample] = useState<AssetSample>({ fillGray: 0, cornerAlpha: 0, error: '采样中' })
  const [tint, setTint] = useState<TintReadout | null>(null)
  const [tokens, setTokens] = useState<readonly (readonly [string, string])[]>([])
  const [tonePreview, setTonePreview] = useState<readonly (readonly [PixelFrameTone, string, string])[]>([])

  const asset = PIXEL_FRAME_ASSETS[REFERENCE_VARIANT]

  useEffect(() => {
    let cancelled = false
    void sampleAsset(asset.src, asset.size).then((result) => {
      if (!cancelled) {
        setSample(result)
      }
    })
    return () => {
      cancelled = true
    }
  }, [asset.src, asset.size])

  /*
   * 主题一换整张色表都变, 所以读数必须跟着 theme 重跑 —— 否则预测色块用的是上一档主题的基色,
   * 会在正确的链路上凭空造出接缝, 反过来诬告实现。
   */
  useEffect(() => {
    const node = referenceRef.current
    if (node !== null) {
      setTint(readTintLayer(node))
    }
    setTokens(TOKEN_GROUPS.flatMap(([, names]) => names.map((name) => [name, readToken(name)] as const)))
  }, [theme])

  useEffect(() => {
    if (sample.error !== '') {
      setTonePreview([])
      return
    }
    setTonePreview(
      PIXEL_FRAME_TONES.map((tone) => {
        const raw = readToken(`--color-tone-${tone}`)
        const parsed = parseHexColor(raw)
        if (parsed === null) {
          return [tone, raw, ''] as const
        }
        const predicted: readonly [number, number, number] = [
          overlayChannel(sample.fillGray, parsed[0]),
          overlayChannel(sample.fillGray, parsed[1]),
          overlayChannel(sample.fillGray, parsed[2]),
        ]
        return [tone, raw, toHexColor(predicted)] as const
      }),
    )
  }, [theme, sample])

  const scale = Number.parseInt(readToken('--pixel-scale'), 10)
  const expectedOffset = -(asset.slice * (Number.isNaN(scale) ? 1 : scale))

  const verdict = tint === null ? '读数未采集' : tintVerdict(tint, expectedOffset)

  const goHome = useCallback(() => {
    navigate(ROUTE_HOME)
  }, [navigate])

  return (
    <section className="flex flex-col gap-8">
      <header className="flex flex-col gap-2">
        <h1 className="text-2x text-fg">灰度上色链路验证 (规格第六章)</h1>
        <p className="text-1x text-muted">
          三张灰度 9-slice 资产 x 六档语义色 = 十八种框体, 不增发一张图。链路走"伪元素色层 + overlay 混合",
          选型推导与降级表现见 src/components/pixel/README.md。
        </p>
      </header>

      <div className="flex flex-wrap items-center gap-4">
        <button
          type="button"
          className="border border-border-strong bg-surface px-4 py-2 text-1x text-fg shadow-hard"
          onClick={toggle}
        >
          当前主题: {theme} (点击切换)
        </button>
        <span className="text-1x text-muted">
          两档主题都要看: 亮色档的基色贴近白, 是 overlay 抬亮路径; 暗色档的基色很暗, 是压暗路径。
          只验一档等于只验了公式的一半。
        </span>
      </div>

      <div className="flex flex-col gap-4">
        <h2 className="text-1x text-accent">一、特性支持矩阵 (引擎自答)</h2>
        <div className="flex flex-col gap-2 border border-border bg-surface p-4 text-1x">
          {FEATURE_PROBES.map(([label, property, value]) => {
            const supported = CSS.supports(property, value)
            return (
              <Row
                key={label}
                label={label}
                value={supported ? 'supported' : 'NOT supported'}
                bad={!supported}
              />
            )
          })}
        </div>
        <p className="text-1x text-muted">
          两套 mask-border 至少活一套即可 (谁活用谁); mix-blend-mode 与 isolation 任一为 NOT supported,
          整条上色链路即不成立, 需回到 README 的候选方案重选。
        </p>
      </div>

      <div className="flex flex-col gap-4">
        <h2 className="text-1x text-accent">二、染色层实测读数</h2>
        <div className="flex flex-col gap-2 border border-border bg-surface p-4 text-1x">
          <Row label="资产" value={`${asset.file} (${String(asset.size)} 见方, slice ${String(asset.slice)})`} bad={false} />
          <Row
            label="中心块灰度 (采样)"
            value={sample.error === '' ? String(sample.fillGray) : sample.error}
            bad={sample.error !== ''}
          />
          <Row
            label="四角 alpha (采样)"
            value={sample.error === '' ? String(sample.cornerAlpha) : sample.error}
            bad={sample.error === '' && sample.cornerAlpha !== 0}
          />
          <Row label="::after content" value={tint === null ? '-' : tint.content} bad={false} />
          <Row label="::after mix-blend-mode" value={tint === null ? '-' : tint.blendMode} bad={false} />
          <Row label="::after background-color" value={tint === null ? '-' : tint.background} bad={false} />
          <Row label="::after 上偏移" value={tint === null ? '-' : tint.offsetTop} bad={false} />
          <Row
            label="mask-border-source"
            value={tint === null ? '-' : tint.maskStandard === '' ? '(属性不存在)' : tint.maskStandard}
            bad={false}
          />
          <Row
            label="-webkit-mask-box-image-source"
            value={tint === null ? '-' : tint.maskPrefixed === '' ? '(属性不存在)' : tint.maskPrefixed}
            bad={false}
          />
          <Row label="判决" value={verdict} bad={verdict.startsWith('FAIL')} />
        </div>
      </div>

      <div className="flex flex-col gap-4">
        <h2 className="text-1x text-accent">三、六档语义色对照 (同一张 frame-window.png)</h2>
        <p className="text-1x text-muted">
          每个框中间那条实色带是按 overlay 公式算出的**预测中心块色**。链路正确时它与框体中心块无缝;
          能看出接缝, 说明引擎的实际混合结果与规范公式不一致, 基色需按实测重调。
        </p>
        <div className="flex flex-wrap gap-8">
          {PIXEL_FRAME_TONES.map((tone) => {
            const preview = tonePreview.find(([name]) => name === tone)
            const base = preview === undefined ? '' : preview[1]
            const predicted = preview === undefined ? '' : preview[2]
            return (
              <PixelFrame
                key={tone}
                variant={REFERENCE_VARIANT}
                tone={tone}
                ref={tone === 'neutral' ? referenceRef : null}
                className="flex w-96 flex-col gap-2 p-4"
              >
                <span className="text-1x text-fg">{TONE_LABEL[tone]}</span>
                <span className="text-1x text-fg">基色 {base === '' ? '(未读到)' : base}</span>
                <div
                  className="h-8 w-full"
                  style={predicted === '' ? undefined : { backgroundColor: predicted }}
                />
                <span className="text-1x text-fg">预测中心块 {predicted === '' ? '(未算出)' : predicted}</span>
              </PixelFrame>
            )
          })}
        </div>
      </div>

      <div className="flex flex-col gap-4">
        <h2 className="text-1x text-accent">四、tone 与 variant 正交 (accent 档 x 三档层级)</h2>
        <p className="text-1x text-muted">
          外凸/平面/内凹的明暗方向必须在染色后依然分得出来 —— 上色若把斜面压平, 层级维度就失效了,
          那正是纯 alpha 蒙版方案被否掉的原因。
        </p>
        <div className="flex flex-wrap gap-8">
          {VARIANTS.map((variant) => (
            <PixelFrame
              key={variant}
              variant={variant}
              tone="accent"
              className="flex w-96 flex-col gap-2 p-4"
            >
              <span className="text-1x text-fg">{variant}</span>
              <span className="text-1x text-muted">accent 档下的同一层级关系</span>
            </PixelFrame>
          ))}
        </div>
      </div>

      <div className="flex flex-col gap-4">
        <h2 className="text-1x text-accent">五、色板 token 计算值</h2>
        <div className="flex flex-col gap-4 border border-border bg-surface p-4">
          {TOKEN_GROUPS.map(([group, names]) => (
            <div key={group} className="flex flex-col gap-2">
              <span className="text-1x text-muted">{group}</span>
              <div className="flex flex-wrap gap-4">
                {names.map((name) => {
                  const value = tokens.find(([token]) => token === name)?.[1] ?? ''
                  return (
                    <div key={name} className="flex items-center gap-2">
                      <span
                        className="h-8 w-8 border border-border-strong"
                        style={value === '' ? undefined : { backgroundColor: value }}
                      />
                      <span className="text-1x text-fg">
                        {name} {value}
                      </span>
                    </div>
                  )
                })}
              </div>
            </div>
          ))}
        </div>
      </div>

      <button
        type="button"
        className="w-48 border border-accent bg-surface px-4 py-2 text-1x text-fg shadow-hard"
        onClick={goHome}
      >
        返回首页
      </button>
    </section>
  )
}
