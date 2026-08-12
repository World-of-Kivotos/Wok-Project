/**
 * 文本控件的三档尺寸 —— 全库单点。真源: 同目录 conventions.md 第二节 2.2。
 *
 * 单列成一个模块而不是让每个控件自己写一遍 `'sm' | 'md' | 'lg'`: 这个 union 会被 L1 的十几个控件同时用,
 * 各写各的等于把"加一档"变成十几处联动修改, 且漏改的那几个不会报错 —— 它们只是接受不了新档位的字符串,
 * 而调用方在别处传得进去。tone 走 PixelFrameTone 复用是同一条理由 (语义档的真源在 PixelFrame)。
 *
 * 两张类表刻意拆开而不合成一张 `px-4 py-2 text-1x`: 行内文本件 (PixelCurrency / PixelBadge 的紧凑档)
 * 只需要字号档而不要控件内边距, 合成一张会逼它们要么吃下不想要的 padding, 要么另起一套档位。
 * 需要盒子的控件把两张表拼起来即可。
 */

export type PixelControlSize = 'sm' | 'md' | 'lg'

/** 全部档位, 声明序即视觉从小到大。验证页与穷举渲染按这份清单走, 加档只改这一处。 */
export const PIXEL_CONTROL_SIZES: readonly PixelControlSize[] = ['sm', 'md', 'lg']

/**
 * 字号档。lg 跳到 text-2x 是因为点阵字体只能取设计档位的整数倍 (--font-cell 的 1x/2x/3x),
 * 中间不存在"稍大一点"的合法档 —— 想要中间态只能靠间距, 不能靠字号。
 *
 * 行盒高 (来自 tailwind.config.ts 的 leading(): (--font-cell * n + 4) * --px, --font-cell 当前 12):
 * text-1x = 16 格, text-2x = 28 格。对齐并排控件时按这个数算。
 */
export const PIXEL_CONTROL_TEXT_CLASS: Record<PixelControlSize, string> = {
  sm: 'text-1x',
  md: 'text-1x',
  lg: 'text-2x',
}

/**
 * 控件内边距档。键就是像素格倍数 (spacing 已被重定义), 故内容盒总高 = 行盒高 + 上下 padding:
 * sm 18 格 / md 20 格 / lg 34 格。
 *
 * 注意这不含 9-slice 边框: 用 PixelFrame 包边的控件还要再加 2 * slice * --pixel-scale 个 CSS 像素,
 * 那条链走资产放大倍率而不是 --px 网格 (见 index.css 的 --pixel-scale 一段), 必须单独算进去。
 */
export const PIXEL_CONTROL_PADDING_CLASS: Record<PixelControlSize, string> = {
  sm: 'px-3 py-1',
  md: 'px-4 py-2',
  lg: 'px-6 py-3',
}
