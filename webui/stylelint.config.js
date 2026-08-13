/*
 * 手写 CSS 侧的守卫。工具类通道由 Tailwind v4 自身负责 (未定义的 utility 直接不产出),
 * 本文件只管 src/styles/*.css 这一份手写样式表。
 *
 * 与像素时代那份的区别: 裸长度禁令、圆角归零禁令、image-rendering 白名单全部撤除 ——
 * 它们服务的是"整套 UI 落在整数像素格上"这条已作废的红线。现在的红线只剩一条,
 * 见下方 declaration-property-value-disallowed-list。
 */

export default {
  extends: ['stylelint-config-standard'],
  rules: {
    // Tailwind v4 是 CSS-first 配置, 这几个 at-rule 由它在构建期消费, stylelint 不认识。
    'at-rule-no-unknown': [
      true,
      {
        ignoreAtRules: [
          'theme',
          'source',
          'utility',
          'variant',
          'custom-variant',
          'apply',
          'reference',
          'config',
          'plugin',
        ],
      },
    ],

    // Tailwind v4 要求裸标识符 `@import 'tailwindcss'`, 写成 url() 它不认。
    'import-notation': null,

    // @theme 块里的 --color-* / --animate-* 是 Tailwind 的令牌命名法, 不是 BEM 风格的自定义属性。
    'custom-property-pattern': null,

    // 令牌表按语义分组, 组间空行是可读性的主要来源。
    'custom-property-empty-line-before': null,

    /*
     * oklch 的记法: 亮度用小数 (0.145) 而不是百分数, 色相不带 deg。
     *
     * 这不是偷懒 —— 强调色的三个派生档 (--brand / --brand-hover / --brand-muted) 是由
     * lib/brand.ts 在运行期拼字符串生成的, 而设置页的渐变轨道也按同一记法拼
     * (`oklch(0.64 ${chroma} ${hue})`)。样式表与 JS 必须用同一套记法, 否则改色时要在两处心算换算。
     * shadcn / Coss 生态同样用这一套。
     */
    'lightness-notation': null,
    'hue-degree-notation': null,
    'alpha-value-notation': null,

    // Coss UI 的 keyframes 用 `to` 而不是 `100%`, 本文件的动画定义是从它那里搬过来的。
    'keyframe-selector-notation': null,

    // 字体族名 (Consolas / Segoe UI / PingFang SC) 是专有名词, 不是关键字。
    'value-keyword-case': ['lower', { ignoreProperties: ['--font-sans', '--font-mono', '--font-heading'] }],

    /*
     * 唯一保留的红线: 中性色不得带彩度。
     *
     * 灰阶一旦掺进色相, 与游戏 3D 画面的场景光叠加后会出现"同一个面板在草原和洞穴里不是一个颜色"
     * 的漂移。规则本身查不了 oklch 的第二个分量, 故只封死最常见的写法通道 —— 中性层禁止用
     * hsl()/rgb() 直接写灰 (它们绕开 oklch 的感知均匀性, 是灰阶不齐的主要来源)。
     */
    'declaration-property-value-disallowed-list': {
      '/^--(background|foreground|card|popover|primary|secondary|muted|accent|border|input|sidebar)/':
        [/hsl\(/, /rgb\(/, /#[0-9a-f]{3,8}/i],
    },
  },
}
