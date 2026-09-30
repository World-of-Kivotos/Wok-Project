/**
 * 划 / 调地块时的实时提示: 两个角 -> 范围、尺寸、面积, 以及哪里不合规。
 *
 * 这只是**提示**, 不是判定: 服务端 (plot.create / plot.resize) 按同一口径再验一遍, 以它的拒绝为准
 * (见 lib/types.ts K17-K26 段头的校验顺序与错误码)。规则的参数 (离边界几格、每边上下限) 来自服务端下发的
 * rules, 这里不另存一份数值; 只把"在区内、离边界、尺寸、不重叠"四条几何关系在前端算一遍, 好让操作者边拖边看。
 */

import type {
  DistrictBounds,
  PlotArea,
  PlotRules,
  PlotSummary,
} from '@/lib/types'

/** 坐标输入框的内容: 可带负号的整数。游戏里只需要数字和减号, 不涉及中文输入。 */
const INTEGER_PATTERN = /^-?\d{1,8}$/

export interface CornerInputs {
  x1: string
  z1: string
  x2: string
  z2: string
}

export const EMPTY_CORNERS: CornerInputs = { x1: '', z1: '', x2: '', z2: '' }

export function cornersOf(area: PlotArea): CornerInputs {
  return { x1: String(area.minX), z1: String(area.minZ), x2: String(area.maxX), z2: String(area.maxZ) }
}

export function isIntegerText(text: string): boolean {
  return INTEGER_PATTERN.test(text.trim())
}

/** 两个角 (顺序随意) -> 范围; 任一格不是整数则为 null。 */
export function areaFromCorners(corners: CornerInputs): PlotArea | null {
  const values = [corners.x1, corners.z1, corners.x2, corners.z2]
  if (!values.every(isIntegerText)) {
    return null
  }
  const [x1, z1, x2, z2] = values.map((value) => Number(value.trim()))
  if (x1 === undefined || z1 === undefined || x2 === undefined || z2 === undefined) {
    return null
  }
  return { minX: Math.min(x1, x2), minZ: Math.min(z1, z2), maxX: Math.max(x1, x2), maxZ: Math.max(z1, z2) }
}

export function areaWidth(area: PlotArea): number {
  return area.maxX - area.minX + 1
}

export function areaDepth(area: PlotArea): number {
  return area.maxZ - area.minZ + 1
}

export function areaBlocks(area: PlotArea): number {
  return areaWidth(area) * areaDepth(area)
}

export function sameArea(left: PlotArea, right: PlotArea): boolean {
  return left.minX === right.minX && left.minZ === right.minZ && left.maxX === right.maxX && left.maxZ === right.maxZ
}

function overlaps(left: PlotArea, right: PlotArea): boolean {
  return left.minX <= right.maxX && right.minX <= left.maxX && left.minZ <= right.maxZ && right.minZ <= left.maxZ
}

/** 四个坐标框里有几个还空着 (编辑器据此写"还差 N 个坐标", 而不是一填就报红)。 */
export function emptyCornerCount(corners: CornerInputs): number {
  return [corners.x1, corners.z1, corners.x2, corners.z2].filter((value) => value.trim() === '').length
}

/** 填了但不是整数的格子 (空着的不算)。 */
export function hasMalformedCorner(corners: CornerInputs): boolean {
  return [corners.x1, corners.z1, corners.x2, corners.z2].some(
    (value) => value.trim() !== '' && !isIntegerText(value),
  )
}

/** 第一条不合规的地方 (顺序同服务端); null = 看起来可以。 */
export function areaProblem(
  district: DistrictBounds,
  area: PlotArea,
  rules: PlotRules,
  plots: readonly PlotSummary[],
  ignorePlotId: string | null,
): string | null {
  if (area.minX < district.minX || area.maxX > district.maxX || area.minZ < district.minZ || area.maxZ > district.maxZ) {
    // 与服务端 OUT_OF_DISTRICT 同一句 (带上自管区的坐标范围): 填坐标的人要知道往哪边改。
    return `超出自管区范围（X ${String(district.minX)} ~ ${String(district.maxX)}，Z ${String(district.minZ)} ~ ${String(district.maxZ)}）`
  }
  const gap = rules.edgeGap
  if (
    area.minX - district.minX < gap ||
    district.maxX - area.maxX < gap ||
    area.minZ - district.minZ < gap ||
    district.maxZ - area.maxZ < gap
  ) {
    return `离自管区边界太近：四周至少要留 ${String(gap)} 格公共区域`
  }
  const width = areaWidth(area)
  const depth = areaDepth(area)
  if (width < rules.minSide || depth < rules.minSide || width > rules.maxSide || depth > rules.maxSide) {
    return `每边要在 ${String(rules.minSide)} 到 ${String(rules.maxSide)} 格之间（现在 ${String(width)} × ${String(depth)}）`
  }
  const hit = plots.find((plot) => plot.plotId !== ignorePlotId && overlaps(area, plot.bounds))
  return hit === undefined ? null : `和地块 ${hit.code} 重叠了`
}
