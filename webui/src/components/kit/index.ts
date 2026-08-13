/**
 * 业务页唯一的 UI 导入口。
 *
 * 分层纪律 (违反了就等于把换皮成本重新装回 15 个页面里):
 *
 *   src/components/ui/    Coss UI 的 copy-paste 产物, 上游代码, 除必要的档位增补外不改。
 *   src/components/kit/   本项目的 UI 契约层 —— 就是这里。把 Coss 的原语按本项目的语义词汇
 *                         (Tone / ControlSize / 双货币 / 物品格) 收敛成朴素受控签名。
 *   src/pages/            业务页。**只从 '@/components/kit' 导入**, 不直接碰 @/components/ui/*。
 *
 * 唯一的例外是 Button: 它由本 barrel 直接转出 Coss 原件, 因为它的 variant/size/loading 签名已经
 * 正好是本项目要的样子, 再包一层只会制造一个除了改名什么都不做的中间件。
 *
 * 为什么值得多这一层: 用户明确要求像素风"后面慢慢跑"
 * (docs/PixelUI_DesignSystem_DesignSpec.md 已标 DEFERRED, 资产与旧实现封存在 webui/_pixel-archive/)。
 * 届时要改的是 kit 的内部实现, 业务页的 <Panel> / <Tag tone="danger"> 一个字都不用动。
 * 上一版正是因为页面直接消费视觉组件, 换皮才要重写 8000 行。
 */

export { Button, type ButtonProps } from '@/components/ui/button'
export { Separator } from '@/components/ui/separator'
export { Skeleton } from '@/components/ui/skeleton'
export { ItemIcon, type ItemIconProps, type ItemIconScale } from '@/components/ItemIcon'

export * from './ConfirmDangerDialog'
export * from './Controls'
export * from './Currency'
export * from './DataTable'
export * from './Feedback'
export * from './ItemSlot'
export * from './Panel'
export * from './Stat'
export * from './StatusBlocks'
export * from './tokens'
