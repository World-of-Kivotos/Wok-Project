/*
 * 手写 CSS 侧的机械守卫。Tailwind 侧 (theme 覆盖 + eslint 的 no-custom-classname/no-arbitrary-value)
 * 封住工具类通道, 本文件封住手写 CSS 通道, 两者合计使 --px 之外的长度魔数无处可写。
 *
 * 真源: docs/PixelUI_DesignSystem_DesignSpec.md 第二章硬红线 / 第五章度量 / 第六章上色。
 */

/** 匹配裸长度 (数字直接跟绝对/相对长度单位)。calc(var(--px) * 3) 里没有裸长度, 故不受影响。 */
const BARE_LENGTH = /(^|[^\w.#-])\d*\.?\d+(px|rem|em|pt|pc|in|cm|mm|ch|ex)\b/

/**
 * 硬阴影: 前两段是偏移 (可为 calc), 第三段模糊半径必须字面为 0, 可选的扩散段同样只允许 0。
 * calc 段允许一层嵌套括号 (calc(var(--px) * 2) 就是一层)。
 */
const HARD_SHADOW =
  /^(?:calc\((?:[^()]|\([^()]*\))*\)|\S+)\s+(?:calc\((?:[^()]|\([^()]*\))*\)|\S+)\s+0(?:\s+0)?(?:\s+\S+)?$/

export default {
  extends: ['stylelint-config-standard'],
  rules: {
    'at-rule-no-unknown': [
      true,
      {
        ignoreAtRules: ['tailwind', 'apply', 'layer', 'screen', 'variants', 'responsive', 'config'],
      },
    ],

    // -webkit-mask-* 与非前缀 mask-* 必须双写: MCEF 内嵌 Chromium 的具体版本未核实,
    // 而 -webkit-mask-image 对 PNG 的默认行为即按 alpha, 双写保证任一路径生效 (规格 6.1)。
    'property-no-vendor-prefix': [
      true,
      { ignoreProperties: ['mask', 'mask-image', 'mask-size', 'mask-repeat', 'mask-position'] },
    ],

    // 字体族名 (PixelCN / PixelEN) 是标识符不是关键字, 但它挂在自定义属性上, 规则无从判断, 只能显式放行。
    'value-keyword-case': ['lower', { ignoreProperties: ['--font-pixel'] }],

    'declaration-property-value-allowed-list': {
      // 圆角只允许显式归零 (第三层防线, 前两层是 Tailwind theme 与 base 层通配)。
      'border-radius': ['0', '0px'],
      'box-shadow': ['none', HARD_SHADOW],
    },

    'declaration-property-value-disallowed-list': {
      // 默认的平滑插值会把像素资产糊掉, 且症状是"糊"而不是报错, 极易蒙混过关。
      'image-rendering': ['auto', 'smooth', 'high-quality'],
      // blur 与 filter 上色 hack (sepia + hue-rotate) 同属明确反例。上色的唯一合法路径是
      // mask + background-color; blur 与硬红线第 2 条同源。
      filter: [/blur\(/, /sepia\(/, /hue-rotate\(/, /saturate\(/],
      'backdrop-filter': [/blur\(/],
      /*
       * 裸长度值禁令。例外只有三处, 一律写 stylelint-disable-next-line 并注明是哪一处, 使例外可数:
       *   1. --px 自身的定义;
       *   2. @font-face 内部;
       *   3. 9-slice 的 border-width (走 --pixel-slice * --pixel-scale 的独立派生链)。
       */
      '/.*/': [BARE_LENGTH],
    },
  },
}
