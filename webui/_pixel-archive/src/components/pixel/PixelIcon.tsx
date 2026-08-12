import type { CSSProperties, ReactElement } from 'react'
import arrowDownUrl from '../../../public/ui/icons/arrow-down.png'
import arrowLeftUrl from '../../../public/ui/icons/arrow-left.png'
import arrowRightUrl from '../../../public/ui/icons/arrow-right.png'
import arrowUpUrl from '../../../public/ui/icons/arrow-up.png'
import bagUrl from '../../../public/ui/icons/bag.png'
import checkUrl from '../../../public/ui/icons/check.png'
import clockUrl from '../../../public/ui/icons/clock.png'
import closeUrl from '../../../public/ui/icons/close.png'
import coinAzureUrl from '../../../public/ui/icons/coin-azure.png'
import coinCreditUrl from '../../../public/ui/icons/coin-credit.png'
import crossUrl from '../../../public/ui/icons/cross.png'
import filterUrl from '../../../public/ui/icons/filter.png'
import heartUrl from '../../../public/ui/icons/heart.png'
import infoUrl from '../../../public/ui/icons/info.png'
import lockUrl from '../../../public/ui/icons/lock.png'
import menuUrl from '../../../public/ui/icons/menu.png'
import minusUrl from '../../../public/ui/icons/minus.png'
import plusUrl from '../../../public/ui/icons/plus.png'
import refreshUrl from '../../../public/ui/icons/refresh.png'
import searchUrl from '../../../public/ui/icons/search.png'
import settingsUrl from '../../../public/ui/icons/settings.png'
import sortUrl from '../../../public/ui/icons/sort.png'
import starUrl from '../../../public/ui/icons/star.png'
import warningUrl from '../../../public/ui/icons/warning.png'

/**
 * 功能图标 (PixelUI 规格第八章第 2 层 + 6.1 单色蒙版 / 接线清单 L0 的 PixelIcon 行)。
 *
 * 与 ItemIcon 的分工是硬的: 物品与方块一律复用 MC 原版贴图走 ItemIcon, 只有原版没有对应物的
 * 界面动作 (关闭 / 排序 / 筛选 / 刷新 ...) 才落到本组件的自绘图标上。两者不得互相顶替 ——
 * 自绘一个已有原版贴图的物品图标是白干且必然与游戏内不一致。
 *
 * 上色走规格 6.1 的主路径: 资产只有 alpha (形状), 颜色由 background-color: currentColor 给,
 * 于是同一张图靠父级的 text-accent / text-danger / text-muted 直接变色, normal/hover/pressed 与
 * 普通/强调/危险 全部不增发资产。**明确否决 filter: sepia() hue-rotate() 那套上色 hack** ——
 * 不精确、不可预测、命不中指定色值; 工程侧 tailwind.config.ts 的 corePlugins 已把 sepia/hueRotate
 * 整组关掉, 这里是同一条红线在组件层的表达。
 *
 * 资产由 tools/gen-icons.mjs 从字符矩阵生成, 名单与本文件的 PIXEL_ICON_NAMES 由该脚本双向校验。
 * 走 ESM import 而不是拼 URL, 与 PixelFrame 同因: 本工程的 vite publicDir 已被指向 mod 贴图目录,
 * webui/public 不是站点静态根, 按 /ui/icons/xxx.png 取图在 dev 下拿到的是 SPA 回退的 index.html。
 */

/**
 * 全部图标名。用 as const 元组而不是裸 string 类型, 是为了让 <PixelIcon name="clsoe" /> 这类拼写错误
 * 在 tsc 阶段就报错 —— 蒙版取不到图时元素是整块不可见且控制台无声, 运行期发现不了。
 */
export const PIXEL_ICON_NAMES = [
  // 窗口与全局控制
  'close',
  'menu',
  'settings',
  'search',
  'refresh',
  // 确认 / 取消
  'check',
  'cross',
  // 增减
  'plus',
  'minus',
  // 四向箭头
  'arrow-up',
  'arrow-down',
  'arrow-left',
  'arrow-right',
  // 列表操作
  'sort',
  'filter',
  // 状态标记
  'warning',
  'info',
  'lock',
  // 收藏与生命值
  'star',
  'heart',
  // 货币
  'coin-credit',
  'coin-azure',
  // 其它
  'bag',
  'clock',
] as const

export type PixelIconName = (typeof PIXEL_ICON_NAMES)[number]

/**
 * 名字到资产 URL 的登记。类型写成 Record<PixelIconName, string> 而不是自动推导:
 * 少登记一张会在 tsc 报缺键, 多登记一张会报多余键, 名单与资产的成对关系由编译器盯住。
 */
const PIXEL_ICON_SOURCES: Record<PixelIconName, string> = {
  close: closeUrl,
  menu: menuUrl,
  settings: settingsUrl,
  search: searchUrl,
  refresh: refreshUrl,
  check: checkUrl,
  cross: crossUrl,
  plus: plusUrl,
  minus: minusUrl,
  'arrow-up': arrowUpUrl,
  'arrow-down': arrowDownUrl,
  'arrow-left': arrowLeftUrl,
  'arrow-right': arrowRightUrl,
  sort: sortUrl,
  filter: filterUrl,
  warning: warningUrl,
  info: infoUrl,
  lock: lockUrl,
  star: starUrl,
  heart: heartUrl,
  'coin-credit': coinCreditUrl,
  'coin-azure': coinAzureUrl,
  bag: bagUrl,
  clock: clockUrl,
}

/**
 * 放大倍率 k: 图标边长 = 16 个源像素 x k, 即 16k 个逻辑像素格。
 *
 * 只能这么取。源图是 16x16, 元素边长写成 16k 格后, 实际缩放倍率 = k x (--px 的 px 数), 恒为整数;
 * 换成"任意格数"就会出现 12 格配 16 像素源图这种 0.75 倍缩放 —— 症状是边缘糊一圈半透明像素,
 * 不报错 (硬红线第 4 条)。上限取 3 (48 格) 是因为再大已属插画尺寸, 该走物品贴图或专门的大图资产。
 */
export type PixelIconScale = 1 | 2 | 3

/** Tailwind 扫源码文本生成类, 拼接出来的类名不会被生成, 故各档必须是完整字面量 (16k 格)。 */
const SCALE_CLASS: Record<PixelIconScale, string> = {
  1: 'block h-16 w-16',
  2: 'block h-32 w-32',
  3: 'block h-48 w-48',
}

/**
 * 与上表逐档等值的长度, 行内再压一遍。
 *
 * 只有类名这一道时, 调用方拿一个 `CSSProperties` 类型的**变量**传 style 就能塞进 width/height ——
 * width/height 虽已列入 ProtectedIconStyleKey, 但 Omit 只挡对象字面量, TS 对变量赋值不做多余属性检查;
 * 而行内样式的优先级又高于类名, 于是 16 源像素的蒙版会被拉成任意尺寸 (如 17px = 1.0625 倍),
 * 症状是边缘糊一圈半透明像素而非报错 (硬红线第 4 条)。类型层挡"写不出来"、这里挡"覆盖不掉", 缺一不成立。
 */
const SCALE_LENGTH: Record<PixelIconScale, string> = {
  1: 'calc(var(--px) * 16)',
  2: 'calc(var(--px) * 32)',
  3: 'calc(var(--px) * 48)',
}

/**
 * 本组件独占的 CSS 属性: 它们要么承载单色蒙版的上色链路, 要么就是硬红线本身。
 * 从对外 style 类型里剔除, 使"用行内样式绕过红线"在编译期就写不出来 (与 PixelFrame 同一道防线)。
 * 需要换色请在父级或 className 上给 text-*, currentColor 会跟着走; 需要改尺寸请走 scale。
 */
type ProtectedIconStyleKey =
  | 'maskImage'
  | 'maskMode'
  | 'maskSize'
  | 'maskRepeat'
  | 'WebkitMaskImage'
  | 'WebkitMaskSize'
  | 'WebkitMaskRepeat'
  | 'backgroundColor'
  | 'backgroundImage'
  | 'imageRendering'
  | 'borderRadius'
  | 'width'
  | 'height'

export interface PixelIconProps {
  name: PixelIconName
  scale?: PixelIconScale
  /**
   * 无障碍名。省略即视为装饰性图标 (aria-hidden), 用于图标与文字并列、文字已经说清语义的场景;
   * 图标是唯一语义载体时 (纯图标按钮) 必须给。
   */
  label?: string
  className?: string
  /** 布局类行内样式 (定位 / 外边距 / color ...)。蒙版与红线相关的属性已被排除, 见 ProtectedIconStyleKey。 */
  style?: Omit<CSSProperties, ProtectedIconStyleKey>
}

export function PixelIcon({
  name,
  scale = 1,
  label,
  className,
  style,
}: PixelIconProps): ReactElement {
  const source = `url("${PIXEL_ICON_SOURCES[name]}")`

  const iconStyle: CSSProperties = {
    /*
     * 调用方样式排在最前, 受保护属性一律压在其后 —— 与 PixelFrame 同理: 类型层剔除挡住"写不出来",
     * 这里的顺序挡住"覆盖不掉", 两道一起在才成立。
     */
    ...style,

    // 单色蒙版上色: 形状取自 alpha, 颜色取自继承来的 color。
    backgroundColor: 'currentColor',

    maskImage: source,
    // PNG 作 mask 源时默认 match-source 已等价于 alpha, 显式写死是为了不依赖默认值。
    maskMode: 'alpha',
    // 默认 mask-size 是资产的固有尺寸 (16 CSS px), 不写就无论 scale 多大都只在左上角画 16px 一小块。
    maskSize: '100% 100%',
    // 元素盒若被外层挤成非整数尺寸, repeat 会在边上露出一条重复的图边; no-repeat 把它压成裁切。
    maskRepeat: 'no-repeat',

    /*
     * 前缀属性同值兜底。这不是跨浏览器妥协 (规格第一章已言明渲染目标只有 MCEF 内嵌 Chromium 一个),
     * 而是同一引擎的**版本下限未知**: 无前缀 mask 系列到 Chromium 120 才全量落地, 而 MCEF 捆绑的
     * CEF 版本尚未在本项目标定。取不到 mask 时元素会退化成一个 currentColor 实心方块 —— 有色块可见,
     * 比整块消失容易发现, 但仍属真客户端必验项。
     */
    WebkitMaskImage: source,
    WebkitMaskSize: '100% 100%',
    WebkitMaskRepeat: 'no-repeat',

    // 蒙版位图按整数倍放大, 默认平滑插值会把边缘糊成半透明羽化。html 上已设且可继承, 此处就近再压一道。
    imageRendering: 'pixelated',

    // 抗锯齿平滑弧线是本规格头号反例。Tailwind theme 与 base 层已各压一道, 这里是第三道。
    borderRadius: 0,

    // 见 SCALE_LENGTH 的注释: 尺寸的运行期那道门, 必须排在 ...style 之后才压得住变量中转进来的 width/height。
    width: SCALE_LENGTH[scale],
    height: SCALE_LENGTH[scale],
  }

  const sizeClass = SCALE_CLASS[scale]

  return (
    <span
      className={className === undefined ? sizeClass : `${sizeClass} ${className}`}
      style={iconStyle}
      role={label === undefined ? undefined : 'img'}
      aria-label={label}
      aria-hidden={label === undefined ? true : undefined}
    />
  )
}
