import type { Config } from 'tailwindcss'

/**
 * 像素 UI 的 Tailwind 覆盖层。真源: docs/PixelUI_DesignSystem_DesignSpec.md 第二章(硬红线)/第五章(度量)。
 *
 * 全部尺寸档位都从 CSS 变量 --px 派生 (定义在 src/styles/index.css, 全库唯一的长度字面量),
 * 因此标定 --px 时整套间距/边框/字号同步缩放, 组件一行不用改。
 *
 * 注意几处是 theme 顶层覆盖而非 extend —— 覆盖才会把默认档位真正删掉, 配 eslint 的
 * tailwindcss/no-custom-classname 让 rounded-lg / text-sm 这类写法在构建期报错, 而不是静默无样式。
 */

/** n 个逻辑像素格。0 直接给 0px, 省掉一次无意义的 calc。 */
const cell = (n: number): string => (n === 0 ? '0px' : `calc(var(--px) * ${n})`)

/** 字号: 点阵字体设计档位 (--font-cell) 的整数倍, 再乘 --px 落到像素网格上。 */
const glyph = (multiple: number): string => `calc(var(--font-cell) * ${multiple} * var(--px))`

/** 行高同样取整数倍像素格 —— 无单位倍数 (如 1.5) 乘出来必落半像素。行间距固定留 4 格。 */
const leading = (multiple: number): string => `calc((var(--font-cell) * ${multiple} + 4) * var(--px))`

// 间距档位的键就是像素格倍数本身 (p-4 = 4 个像素格), 不是 Tailwind 默认的 0.25rem 递增。
const SPACING_STEPS = [0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 20, 24, 32, 40, 48, 64, 80, 96, 128]

const spacing: Record<string, string> = Object.fromEntries(
  SPACING_STEPS.map((n) => [String(n), cell(n)]),
)

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    // 全档只留 none。rounded / rounded-sm / rounded-lg / rounded-full 由此变成未定义类名,
    // 抗锯齿平滑弧线是本规格头号反例, 像素风圆角只能由资产位图的阶梯硬边表达。
    borderRadius: {
      none: '0px',
    },
    spacing,
    // 默认档的 1px 是与 --px 网格脱钩的裸像素。注意 PixelFrame 的 border-width 不走这套工具类,
    // 它由 --pixel-slice * --pixel-scale 派生 (9-slice 的边框宽与资产 slice 有强制整数倍关系)。
    borderWidth: {
      DEFAULT: cell(1),
      0: cell(0),
      1: cell(1),
      2: cell(2),
      3: cell(3),
      4: cell(4),
    },
    // 模糊半径与扩散半径硬编码为 0, 偏移量取像素格整数倍。毛玻璃与高斯投影是明确反例,
    // 另见下方 corePlugins: blur/dropShadow 一类工具类被整组关掉, 工程上拼不出来。
    boxShadow: {
      none: 'none',
      hard: `${cell(1)} ${cell(1)} 0 0 var(--color-shadow)`,
      'hard-2': `${cell(2)} ${cell(2)} 0 0 var(--color-shadow)`,
      'hard-3': `${cell(3)} ${cell(3)} 0 0 var(--color-shadow)`,
    },
    // 只给 1x/2x/3x 三档乘数。点阵字体在非设计档位整数倍下必糊 (12px 字体用 18px 就是插值),
    // 故默认 scale (text-sm = 0.875rem 一类) 整档删除。
    fontSize: {
      '1x': [glyph(1), { lineHeight: leading(1) }],
      '2x': [glyph(2), { lineHeight: leading(2) }],
      '3x': [glyph(3), { lineHeight: leading(3) }],
    },
    extend: {
      fontFamily: {
        // 只引用族名, 真实字体文件由 @font-face 单点绑定 (规格第九章 PENDING, 候选未核实前不绑)。
        pixel: 'var(--font-pixel)',
      },
      /*
       * 语义色板一律指向 CSS 变量而非字面色值: 亮暗双主题靠 :root / :root.light 换变量实现,
       * 只要工具类里没有硬色值, 换主题就不需要任何 dark: 变体 —— 那套变体会让每个组件多背一份颜色分支。
       * 变量表与各档取值的理由见 src/styles/index.css。
       *
       * 刻意不映射 --color-tone-*: 它们是喂给 9-slice overlay 混合的基色, 不是能直接刷在文字/边框上的颜色
       * (中心块最终色 = 基色被灰度抬亮之后的值)。开成工具类等于邀请人拿它当普通背景色用, 必然对不上。
       */
      colors: {
        bg: 'var(--color-bg)',
        surface: 'var(--color-surface)',
        raised: 'var(--color-raised)',
        fg: 'var(--color-fg)',
        muted: 'var(--color-muted)',
        'on-accent': 'var(--color-on-accent)',
        border: 'var(--color-border)',
        'border-strong': 'var(--color-border-strong)',
        accent: 'var(--color-accent)',
        'accent-hover': 'var(--color-accent-hover)',
        'accent-active': 'var(--color-accent-active)',
        success: 'var(--color-success)',
        warning: 'var(--color-warning)',
        danger: 'var(--color-danger)',
        info: 'var(--color-info)',
        shadow: 'var(--color-shadow)',
      },
    },
  },
  // 关掉这些组之后 blur-* / backdrop-blur-* / drop-shadow-* / backdrop-filter 类根本不存在,
  // 毛玻璃因此是"工程上拼不出来", 而不是"约定不要写"。
  // sepia 与 hueRotate 一并关: 规格第六章明确否决 filter:sepia() hue-rotate() 那套上色 hack,
  // stylelint 只管得住手写 CSS, 工具类通道要靠这里堵, 否则同一条红线漏掉一半。
  corePlugins: {
    blur: false,
    backdropBlur: false,
    dropShadow: false,
    backdropFilter: false,
    sepia: false,
    hueRotate: false,
  },
} satisfies Config
