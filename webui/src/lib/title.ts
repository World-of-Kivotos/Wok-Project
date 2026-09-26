/**
 * 称号文字的前端辅助: 专属称号编辑器的本地渲染, 以及 TextSegment (服务端拍平下发的带样式文字) 的取键与取色。
 *
 * 本地渲染只服务于"边打字边看效果"这一件事。权威结果永远以服务端为准: 数据包称号与成就标题直接画服务端下发的
 * 片段, 专属称号的合不合格由 title.customPreview 说了算 —— 这里的渐变只是让编辑器不必每敲一个字就等一次往返。
 */

import type { TextSegment } from './types'

/** 游戏内聊天、Tab 与名牌的默认文字色 (没有颜色的片段取它)。 */
export const INGAME_DEFAULT_COLOR = '#FFFFFF'
const INGAME_DEFAULT_COLOR_RGB = 0xffffff

const HEX_COLOR = /^#[0-9A-Fa-f]{6}$/

/** `#RRGGBB` -> 0xRRGGBB; 写法不对返回 null (与服务端只收 #RRGGBB 同一口径)。 */
export function parseHexColor(text: string): number | null {
  return HEX_COLOR.test(text) ? Number.parseInt(text.slice(1), 16) : null
}

/** 0xRRGGBB -> 大写 `#RRGGBB`。 */
export function hexColor(rgb: number): string {
  return `#${rgb.toString(16).padStart(6, '0').toUpperCase()}`
}

/**
 * Java `Math.round(from + (to - from) * t)` 在 float 精度下的结果。
 *
 * 插值必须按 float 算而不是直接用 double: 服务端 TierPalette.lerpChannel 的 t 与中间结果都是 float, 恰好落在 .5 附近的
 * 通道值用 double 算会偶尔差 1, 本地预览与聊天里的颜色就对不上了。取整反过来不能再 fround: Java 的
 * Math.round(float) 按精确值取 floor(x + 0.5), 而 float 精度的 x 加 0.5 在 double 里没有舍入误差。
 */
function lerpChannel(from: number, to: number, t: number): number {
  const value = Math.fround(from + Math.fround((to - from) * t))
  return Math.floor(value + 0.5)
}

function lerpColor(from: number, to: number, t: number): number {
  const r = lerpChannel((from >> 16) & 0xff, (to >> 16) & 0xff, t)
  const g = lerpChannel((from >> 8) & 0xff, (to >> 8) & 0xff, t)
  const b = lerpChannel(from & 0xff, to & 0xff, t)
  return (r << 16) | (g << 8) | b
}

function colorAt(stops: readonly number[], position: number): number {
  const first = stops[0]
  if (first === undefined) {
    throw new Error('渐变至少要有一个色标')
  }
  if (stops.length === 1) {
    return first
  }
  const clamped = Math.fround(Math.max(0, Math.min(1, position)))
  const scaled = Math.fround(clamped * (stops.length - 1))
  const segment = Math.min(Math.trunc(scaled), stops.length - 2)
  const from = stops[segment]
  const to = stops[segment + 1]
  if (from === undefined || to === undefined) {
    throw new Error(`渐变色标下标越界: ${String(segment)}`)
  }
  return lerpColor(from, to, Math.fround(scaled - segment))
}

/**
 * 逐字渐变给 count 个字分配的颜色, 与服务端 TierPalette.gradientColors 同一份插值: 第 i 个字取渐变轴上
 * i/(count-1) 处的颜色, 首字恰为首色标、末字恰为末色标。
 */
export function gradientColors(stops: readonly number[], count: number): number[] {
  const colors: number[] = []
  for (let index = 0; index < count; index += 1) {
    const position = count === 1 ? 0 : Math.fround(index / (count - 1))
    colors.push(colorAt(stops, position))
  }
  return colors
}

/**
 * 专属称号的本地渲染, 与服务端 TitleRenderer.renderBadge 同一口径: 文字原样 (不加方括号), 一个色标整段同色,
 * 两到三个色标按码点逐字渐变 (代理对不拆开)。色标写法不对或个数不在 1~3 时返回 null —— 那种草稿在游戏里
 * 根本画不出来, 让预览空着比编一个颜色更诚实, 具体哪里不对交给服务端校验逐条说。
 */
export function customTitleSegments(
  text: string,
  colors: readonly string[],
  bold: boolean,
): TextSegment[] | null {
  const stops = colors.map(parseHexColor)
  if (stops.length < 1 || stops.length > 3 || stops.some((stop) => stop === null)) {
    return null
  }
  const parsed = stops.filter((stop): stop is number => stop !== null)
  const [only] = parsed
  if (parsed.length === 1 && only !== undefined) {
    return text === '' ? [] : [{ t: text, color: hexColor(only), bold }]
  }
  const characters = Array.from(text)
  const perCharacter = gradientColors(parsed, characters.length)
  return characters.map((character, index) => ({
    t: character,
    color: hexColor(perCharacter[index] ?? INGAME_DEFAULT_COLOR_RGB),
    bold,
  }))
}

/** 一组片段序列里的全部翻译键 (去重), 供 useItemNames 一次批量解析。 */
export function segmentKeys(list: readonly (readonly TextSegment[] | null)[]): string[] {
  const keys = new Set<string>()
  for (const segments of list) {
    for (const segment of segments ?? []) {
      if (segment.k !== undefined) {
        keys.add(segment.k)
      }
    }
  }
  return [...keys]
}

/**
 * 一个片段的文字。翻译键解不出来时先退 fallback, 再退键本身 —— 与游戏里原版 translate 的回退顺序一致,
 * 且让"名字没解出来"看得见, 不悄悄少一段。
 */
export function segmentString(segment: TextSegment, names: Readonly<Record<string, string>>): string {
  if (segment.k !== undefined) {
    const resolved = names[segment.k]
    if (resolved !== undefined && resolved !== segment.k) {
      return resolved
    }
    return segment.f ?? segment.k
  }
  return segment.t ?? ''
}

/** 整串片段拼成纯文本 (读屏名、复制用)。 */
export function segmentsText(
  segments: readonly TextSegment[],
  names: Readonly<Record<string, string>>,
): string {
  return segments.map((segment) => segmentString(segment, names)).join('')
}

/**
 * 原版文字阴影色: 前景色每个通道除以 4 (Font.drawInternal 的 shadow 分支)。游戏内每个字都带这层阴影,
 * 预览不画它, 深色的字在预览条里会比游戏里显得更"扁"。
 */
export function ingameShadowColor(color: string | undefined): string {
  const rgb = parseHexColor(color ?? INGAME_DEFAULT_COLOR) ?? INGAME_DEFAULT_COLOR_RGB
  const r = ((rgb >> 16) & 0xff) >> 2
  const g = ((rgb >> 8) & 0xff) >> 2
  const b = (rgb & 0xff) >> 2
  return hexColor((r << 16) | (g << 8) | b)
}
