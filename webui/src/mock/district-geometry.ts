/**
 * 自管区地块的范围校验 (模拟服务端 K17 / K18 的权威校验)。纯函数, 只 import 类型:
 * district-seed.ts 用它核对种子地块本身合不合规, district-handlers.ts 用它拒绝不合规的划 / 调。
 *
 * 页面另有一份同口径的实时提示 (pages/district/plotArea.ts): 那份只为边拖边告诉操作者哪里不对,
 * 这份在开发构建的假数据里代表"服务端说了算"; 真服的权威校验是 Java 的 PlotGeometry, 两边有出入时改这里。
 *
 * 口径 (与 lib/types.ts K17-K26 段头一致, 按顺序报第一条不满足的):
 *   INVALID_AREA / OUT_OF_DISTRICT / TOO_CLOSE_TO_EDGE / SIZE_OUT_OF_RANGE / OVERLAPS_PLOT。
 * 范围一律是含两端的整数方块坐标; 贴边 (共用一条边线外侧) 不算重叠。
 */

import type { DistrictBounds, PlotArea } from '../lib/types'

export interface PlotAreaRules {
  edgeGap: number
  minSide: number
  maxSide: number
}

export interface PlotAreaNeighbor {
  plotId: string
  code: string
  area: PlotArea
}

export interface PlotAreaProblem {
  code: 'INVALID_AREA' | 'OUT_OF_DISTRICT' | 'TOO_CLOSE_TO_EDGE' | 'SIZE_OUT_OF_RANGE' | 'OVERLAPS_PLOT'
  message: string
}

export function areaOfPlot(area: PlotArea): number {
  return (area.maxX - area.minX + 1) * (area.maxZ - area.minZ + 1)
}

export function plotAreaOf(bounds: DistrictBounds): PlotArea {
  return { minX: bounds.minX, minZ: bounds.minZ, maxX: bounds.maxX, maxZ: bounds.maxZ }
}

function overlaps(left: PlotArea, right: PlotArea): boolean {
  return left.minX <= right.maxX && right.minX <= left.maxX && left.minZ <= right.maxZ && right.minZ <= left.maxZ
}

export function checkPlotArea(
  district: DistrictBounds,
  area: PlotArea,
  rules: PlotAreaRules,
  others: readonly PlotAreaNeighbor[],
): PlotAreaProblem | null {
  const values = [area.minX, area.minZ, area.maxX, area.maxZ]
  if (values.some((value) => !Number.isInteger(value)) || area.minX > area.maxX || area.minZ > area.maxZ) {
    return { code: 'INVALID_AREA', message: '坐标必须是整数，且两个角要分得开' }
  }
  if (area.minX < district.minX || area.maxX > district.maxX || area.minZ < district.minZ || area.maxZ > district.maxZ) {
    return {
      code: 'OUT_OF_DISTRICT',
      message: `超出自管区范围（X ${String(district.minX)} ~ ${String(district.maxX)}，Z ${String(district.minZ)} ~ ${String(district.maxZ)}）`,
    }
  }
  const gap = rules.edgeGap
  if (
    area.minX - district.minX < gap ||
    district.maxX - area.maxX < gap ||
    area.minZ - district.minZ < gap ||
    district.maxZ - area.maxZ < gap
  ) {
    return {
      code: 'TOO_CLOSE_TO_EDGE',
      message: `离自管区边界太近：四周至少要留 ${String(gap)} 格公共区域`,
    }
  }
  const width = area.maxX - area.minX + 1
  const depth = area.maxZ - area.minZ + 1
  if (width < rules.minSide || depth < rules.minSide || width > rules.maxSide || depth > rules.maxSide) {
    return {
      code: 'SIZE_OUT_OF_RANGE',
      message: `每边要在 ${String(rules.minSide)} 到 ${String(rules.maxSide)} 格之间（现在 ${String(width)} × ${String(depth)}）`,
    }
  }
  const hit = others.find((other) => overlaps(area, other.area))
  if (hit !== undefined) {
    return { code: 'OVERLAPS_PLOT', message: `和地块 ${hit.code} 重叠了` }
  }
  return null
}
