/**
 * 像素组件库的唯一对外导入口。
 *
 * 存在的理由: 后续 20 个控件由多个并行批次交付、被 8 个面板批次消费。若各面板自己拼深层路径
 * (`../components/pixel/PixelButton`), 组件一旦改名或换目录, 要改的是散在几十个文件里的 import;
 * 收在一个 barrel 后, 面板层只认 `../components/pixel` 一条路径, 移动组件只改这一个文件。
 *
 * API 约定 (props 名、档位词汇、颜色与类名的取用面、无障碍下限) 见同目录 conventions.md ——
 * 那份是强制依据, 不是建议。9-slice 与灰度上色链路的选型推导见同目录 README.md。
 *
 * 用 `export *` 而不是逐个具名 re-export: 每个组件文件只导出自身公开 API, 星号导出让"新增一个导出"
 * 不需要在 barrel 里同步两行 (值一行、类型一行), 更重要的是把并行批次对本文件的编辑面压到**一行**,
 * 减少多人同时改同一个文件时互相冲掉的概率。代价是重名会被 tsc 判成 TS2308, 而命名前缀约定
 * (Pixel<名词>) 已经让重名不可能自然发生。
 *
 * === 预期导出清单 (名字已冻结, 后续批次照此交付) ===
 *
 * L0 地基 (已在库):
 *   PixelFrame        唯一 9-slice 容器原语, variant = window / panel / inset
 *   PixelIcon         16x16 单色蒙版功能图标, 上色靠 currentColor
 *   ItemIcon          MC 物品贴图 (原版 CDN / 本 mod 贴图 / 像素占位块 三层回退)
 *   controlSize       文本控件三档尺寸的 union 与两张类表
 *
 * L1 控件 (待交付):
 *   PixelButton  PixelInput  PixelSelect  PixelStepper  PixelCheckbox
 *   PixelSlot  PixelSlotGrid  PixelTable  PixelScrollArea
 *   PixelTabs  PixelProgress  PixelBadge  PixelTooltip  PixelCurrency
 *
 * L2 状态件 (待交付):
 *   PixelLoading  PixelEmpty  PixelError  PixelConfirmDanger  PixelModal  PixelToast
 *
 * 每个组件落地时, 在下方导出区按字母序追加**一行** `export * from './<组件名>'`。
 * 追加前重新读一遍本文件, 只加自己那一行 —— 整段重写会把并行批次刚加的行冲掉。
 * 仅供某个组件内部使用的子件 (如 PixelTableRow) 不进本文件。
 */

/*
 * ItemIcon 的文件落在上一级目录而不是 pixel/ 下: 它不是"用像素资产画出来的控件", 而是一条
 * 带网络回退链的贴图解析管线 (原版镜像站 -> 本 mod 贴图 -> 占位块), 与 9-slice 那套原语不同源。
 * 但接线清单第二章把它与 PixelFrame / PixelIcon 并列为 L0 地基, 消费方也总是三者一起用,
 * 故导出面收在同一个 barrel 里 —— 让面板层只记一条导入路径, 不必知道文件为什么分居两处。
 */
export * from '../ItemIcon'

export * from './PixelBadge'
export * from './PixelButton'
export * from './PixelCheckbox'
export * from './PixelConfirmDanger'
export * from './PixelCurrency'
export * from './PixelEmpty'
export * from './PixelError'
export * from './PixelFrame'
export * from './PixelIcon'
export * from './PixelInput'
export * from './PixelLoading'
export * from './PixelModal'
export * from './PixelProgress'
export * from './PixelScrollArea'
export * from './PixelSelect'
export * from './PixelSlot'
export * from './PixelSlotGrid'
export * from './PixelStepper'
export * from './PixelTable'
export * from './PixelTabs'
export * from './PixelToast'
export * from './PixelTooltip'
export * from './controlSize'
