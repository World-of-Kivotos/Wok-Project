import type { CSSProperties, ReactElement, ReactNode, Ref } from 'react'
import frameInsetUrl from '../../../public/ui/frame-inset.png'
import framePanelUrl from '../../../public/ui/frame-panel.png'
import frameWindowUrl from '../../../public/ui/frame-window.png'

/**
 * 全库唯一的 9-slice 容器原语。真源: docs/PixelUI_DesignSystem_DesignSpec.md 第四章 / 接线清单 L0。
 *
 * 存在的理由只有一条: 一份资产撑出从行内小按钮到全屏平板的全部矩形容器, 且两端的边角形状与边框
 * 粗细完全一致。直接对整图 scale 会把边角拉扁、横竖边框粗细失衡, 尺寸差越大崩得越明显 —— 9-slice
 * 正是为消除该失真存在。因此本组件是收口点: 上层控件 (PixelButton/PixelInput/PixelSlot ...) 一律
 * 复用它, 不得各自去写 border-image, 否则 slice 与资产的成对关系会散落到 N 处并逐个错位。
 *
 * 零第三方库、零 canvas、零 WebGL: CSS 原生 border-image 就是各引擎 9-slice 设施 (Unity Sprite
 * Editor / Godot NinePatchRect / Unreal Box Brush) 的对应物, 引入运行时反而多一层可失真的重绘。
 */

export type PixelFrameVariant = 'window' | 'panel' | 'inset'

/**
 * 语义色档。与 variant 正交: variant 决定形状与明暗关系 (外凸/平面/内凹, 必须分别出图),
 * tone 决定这套明暗被染成什么颜色 (只换 CSS 变量, 不增发资产) —— 3 张图 x 6 档 = 18 种框体。
 *
 * 上色链路本身在 src/styles/index.css 的 [data-pixel-tone]::after 一段, 选型与降级见同目录 README.md。
 */
export type PixelFrameTone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger' | 'info'

/** 全部语义档。验证页与调色页按这份清单穷举, 加档只改这一处。 */
export const PIXEL_FRAME_TONES: readonly PixelFrameTone[] = [
  'neutral',
  'accent',
  'success',
  'warning',
  'danger',
  'info',
]

/**
 * 边角放大倍率。只开放整数档: 非整数倍会让边角位图落在半像素上, 而症状是"边缘糊一圈半透明像素"
 * 而非报错, 极易蒙混过关。上限取 4 是因为 24x24 的资产在 4 倍下边框已达 32 CSS px, 再大即便
 * 像素对齐正确, 观感上也是边框吃掉内容区。
 */
export type PixelFrameScale = 1 | 2 | 3 | 4

export interface PixelFrameAsset {
  /**
   * 资产 URL。小图会被 vite 在构建期内联成 data: URI (当前三张占位图各约 100 字节, 远低于内联阈值),
   * 换成体积更大的美术资产后则变为带 hash 的独立文件 —— 两种形态对 border-image 等价, 故此处只当 URL 用。
   */
  readonly src: string
  /** 磁盘文件名。src 可能是 data: URI, 排障时人要看的是"哪张图", 故单列一份可读标识。 */
  readonly file: string
  /** 资产四边的实际边框像素宽。与 border-image-slice 必须精确相等, 差一像素即整体错位。 */
  readonly slice: number
  /** 资产边长 (正方形)。用于校验 slice 合法性: 最小尺寸 = slice*2+1, 否则中心块为空。 */
  readonly size: number
}

/**
 * 三档层级各持有独立资产。层级维度必须分别出图 —— 外凸窗口与内凹凹槽的明暗关系相反 (高光在上边
 * 还是在下边), 换色表达不出来; 而状态 (normal/hover/pressed) 与语义 (普通/强调/危险) 优先靠
 * CSS 变量换色, 不增发资产 (规格第六章与第七章的压缩原则)。
 *
 * 上一段的"换色"已接线, 走的是分层合成: 本组件只负责把资产 URL 与语义档下发成 --pixel-frame-src /
 * data-pixel-tone, 真正的染色层是样式表里一层与框体等大的伪元素 (index.css 的 [data-pixel-tone]::after),
 * 用 overlay 混合把颜色叠回灰度图上。纯蒙版路径 (mask-border / mask-image) 在这里走不通 ——
 * 三张资产的 alpha 只有 0/255 两个值, 斜面明暗全记在灰度通道里, 取 alpha 只会得到一块四角挖空的纯色板。
 * 选型推导、六色实测与降级表现见同目录 README.md 与 #/color-check 页。
 *
 * slice 逐张登记而非取全局默认值: 它必须与每张图的实际边框像素宽相等, 给一个"看起来能用"的全局
 * 默认值等于埋一个错位来源。三张资产当前统一按 24x24 / slice 8 出图 (16x16 配 slice 8 时中心块
 * 宽度为 0, 不满足 slice*2+1 的下限)。
 *
 * 走 ESM import 而不是拼 `${BASE_URL}ui/xxx.png` 这类 URL, 原因是本工程的 vite `publicDir` 已被
 * 指向 `src/main/resources/assets/miningdim/textures`(为让 mod 贴图作为唯一真源直供 ItemIcon)。
 * Vite 只认一个 publicDir, 于是 `webui/public/` 既不被 dev server 服务、也不会被 build 复制进 dist ——
 * 实测 dev 下请求 /ui/frame-window.png 返回的是 SPA 回退的 index.html(200 但不是图片)。
 * import 让 Vite 把这三张图当普通资产打包 (自动带 hash 与正确的相对基址), 因此 dev 与 build 两端
 * 都不依赖 publicDir 的归属, 而美术"直接覆盖同名文件"的替换流程不受影响。
 */
export const PIXEL_FRAME_ASSETS: Record<PixelFrameVariant, PixelFrameAsset> = {
  window: { src: frameWindowUrl, file: 'public/ui/frame-window.png', slice: 8, size: 24 },
  panel: { src: framePanelUrl, file: 'public/ui/frame-panel.png', slice: 8, size: 24 },
  inset: { src: frameInsetUrl, file: 'public/ui/frame-inset.png', slice: 8, size: 24 },
}

/**
 * CSSProperties 不含自定义属性, 但 9-slice 的参数与上色链路的输入都必须以 CSS 变量下发:
 * 前者见 borderWidth 的派生链, 后者见 index.css 里那层伪元素 —— 伪元素取不到 React props,
 * 资产 URL 与语义色只能经变量过桥。
 */
interface PixelFrameStyle extends CSSProperties {
  '--pixel-slice': string
  /** undefined = 不写行内声明, 回落到 :root 的继承值。exactOptionalPropertyTypes 下必须显式带上 undefined。 */
  '--pixel-scale': string | undefined
  '--pixel-frame-src': string
  '--pixel-tone': string
}

/**
 * 本组件独占的 CSS 属性: 它们要么承载 9-slice 与资产的成对关系, 要么承载上色链路, 要么就是规格第二章
 * 那几条硬红线本身。从对外 style 类型里剔除, 使"用行内样式绕过红线"在编译期就写不出来 —— 光靠把它们
 * 排在展开之后只挡住手滑, 挡不住有人认真地去覆盖它。需要不同边框粗细请走 scale prop, 需要不同资产请加
 * variant, 需要不同颜色请走 tone prop。
 *
 * isolation 与 mixBlendMode 一并剔除: 前者被覆盖成 auto 会让染色层掉到框体背景之后 (上色静默失效),
 * 后者被覆盖会让染色层变成一块盖住框体的纯色板。两者都不报错, 只是画面废掉。
 * position 刻意**不**剔除 —— 它是留给绝对定位调用方的逃生口, 见 index.css 里 [data-pixel-tone] 一段。
 */
type ProtectedFrameStyleKey =
  | 'isolation'
  | 'mixBlendMode'
  | 'borderStyle'
  | 'borderColor'
  | 'borderWidth'
  | 'borderImage'
  | 'borderImageSource'
  | 'borderImageSlice'
  | 'borderImageRepeat'
  | 'borderImageWidth'
  | 'borderImageOutset'
  | 'imageRendering'
  | 'borderRadius'

export interface PixelFrameProps {
  variant: PixelFrameVariant
  /**
   * 覆盖本框及其子树的放大倍率。缺省时继承 :root 的 --pixel-scale ——
   * 于是嵌套框默认与父框同倍率, 需要不同倍率的子框显式给值。
   */
  scale?: PixelFrameScale
  /**
   * 语义色档, 缺省 neutral。与 scale 不同, tone 不继承: 每个框各自决定颜色,
   * 否则一个 danger 弹窗里的所有子面板都会跟着变红。
   */
  tone?: PixelFrameTone
  className?: string
  /** 布局类行内样式 (宽高/定位/背景...)。9-slice 与红线相关的属性已被排除, 见 ProtectedFrameStyleKey。 */
  style?: Omit<CSSProperties, ProtectedFrameStyleKey>
  children?: ReactNode
  ref?: Ref<HTMLDivElement>
}

export function PixelFrame({
  variant,
  scale,
  tone = 'neutral',
  className,
  style,
  children,
  ref,
}: PixelFrameProps): ReactElement {
  const asset = PIXEL_FRAME_ASSETS[variant]

  const frameStyle: PixelFrameStyle = {
    /*
     * 调用方样式排在最前, 受保护属性一律压在其后。顺序不是风格问题: 展开在后者胜出, 早先把 ...style
     * 放在末尾等于把 borderRadius / imageRendering / border-image-slice 的最终决定权交给了每一个调用点,
     * 一处写错就是一处静默破线 (圆角有了、插值糊了、fill 丢了都不会报错)。类型层已经把这些键剔除,
     * 这里的排序是同一件事的运行期保证 —— 两道一起在, 才既写不出来也覆盖不掉。
     */
    ...style,

    '--pixel-slice': String(asset.slice),

    /*
     * 无条件写这一键 (缺省给 undefined 让 React 把行内声明整个删掉, 从而回落到 :root 的继承值)。
     * 早先是 `scale === undefined ? {} : {...}` 的条件展开, 于是 scale 缺省时这条键根本不出现 ——
     * 调用方只要用一个 `CSSProperties & { '--pixel-scale': string }` 的**变量**传 style 就能把 1.5
     * 之类的非整数倍率灌进来 (自定义属性不在 CSSProperties 的键里, Omit 拦不住; 而 TS 的多余属性检查
     * 只对对象字面量生效, 走变量一律放行)。非整数倍率的症状是边缘糊一圈半透明像素而非报错 (硬红线第 4 条),
     * 属于典型的"看不出来的破线", 故这里补上运行期那道门。
     */
    '--pixel-scale': scale === undefined ? undefined : String(scale),

    /*
     * 资产 URL 与语义色的过桥变量。染色层是伪元素, 拿不到 props, 只能从这里读;
     * 而 border-image-source 也改读同一个变量, 使"框体画的是哪张图"与"蒙版挖的是哪张图"物理上无法分叉 ——
     * 两者只要差一张图, 角孔就会挖在错误的位置, 且不报错。
     */
    '--pixel-frame-src': `url("${asset.src}")`,
    '--pixel-tone': `var(--color-tone-${tone})`,

    /*
     * 与 index.css 的 [data-pixel-tone] 规则同值, 在这里再写一次的理由与上面的 --pixel-scale 相同:
     * isolation 虽已列入 ProtectedFrameStyleKey, 但 Omit 只挡对象字面量, 一个 CSSProperties 类型的
     * 变量里带着 isolation: 'auto' 照样能传进来, 且行内样式压得过样式表。一旦被压成 auto, 染色层
     * (负 z-index 的伪元素) 会掉到父级背景之后被不透明的 border-image 完全盖住 —— 表现是"上色毫无效果",
     * 不报错。类型层与运行期两道门必须都在, 这里补的是缺的那道。
     */
    isolation: 'isolate',

    // 边框区域必须先被撑开, border-image 才有地方绘制; 透明色保证资产未加载时不留一圈实色边。
    borderStyle: 'solid',
    borderColor: 'transparent',

    /*
     * 唯一允许出现裸 1px 的地方 (stylelint 的三处例外之一): 9-slice 的边框宽走
     * slice x scale 这条独立派生链, 与 --px 无关 —— --px 管布局尺寸, --pixel-scale 管资产放大倍率,
     * 两者是两个正交的旋钮。乘 1px 只是把无单位整数转成长度, 结果必为整数 CSS 像素。
     */
    borderWidth: 'calc(var(--pixel-slice) * var(--pixel-scale) * 1px)',

    borderImageSource: 'var(--pixel-frame-src)',

    // fill 不可省略: 省了中心块会被丢弃, 控件变成空心框 (背景直接透出), 规格 4.2 第 2 条。
    borderImageSlice: `${String(asset.slice)} fill`,

    /*
     * 带图案的边框一律 round: stretch 会把铆钉/纹路线性拉糊, repeat 会在末端截断图案。
     * 代价须记在案: round 是"微缩放边条使其整数次填满", 该微缩放本身不保证是整数倍 —— 边上带图案的
     * 真资产在某些容器尺寸下仍可能出现半像素。当前占位资产的边沿沿延展轴是均匀的 (round/repeat/stretch
     * 三者结果一致), 这个变量被刻意从批 1 里消掉; 美术真资产接入后必须回到本页按各档尺寸复验。
     */
    borderImageRepeat: 'round',

    // 位图放大的默认平滑插值会把像素资产糊掉。html 上已设且该属性可继承, 此处显式再写一次,
    // 使本组件挂到任何被第三方样式改过的容器下仍然成立。
    imageRendering: 'pixelated',

    // 抗锯齿平滑弧线是本规格头号反例。Tailwind theme 与 base 层已各压一道, 这里是就近的第三道。
    borderRadius: 0,
  }

  /*
   * data-pixel-tone 既是染色层的挂载选择器, 也是排障时的可读标记 (DevTools 里一眼看出这框是哪一档)。
   * 不用 class 挂载是因为 eslint 的 tailwindcss/no-custom-classname 会把自造类名判成错 ——
   * 那条规则是让"打错的工具类"显形的主力, 不该为一个组件在白名单上开口子。
   */
  return (
    <div ref={ref} className={className} style={frameStyle} data-pixel-tone={tone}>
      {children}
    </div>
  )
}
