import { join } from 'node:path'
import js from '@eslint/js'
import react from 'eslint-plugin-react'
import reactHooks from 'eslint-plugin-react-hooks'
import tailwindcss from 'eslint-plugin-tailwindcss'
import globals from 'globals'
import tseslint from 'typescript-eslint'

/*
 * 矢量图标禁令 (规格第二章硬红线第 3 条) 需要两条规则同时开:
 *   - no-restricted-imports 封住整包引入与任何 .svg 模块导入;
 *   - react/forbid-elements 封住手写内联 <svg>。
 * 只封 import 不封内联元素会漏 —— 图标库被禁后最容易的绕路就是直接粘一段 svg path。
 * 第三道是 scripts/verify-pixel-guards.mjs (构建期扫依赖与源码), 保证 lint 被跳过时仍拦得住。
 */
const BANNED_ICON_PACKAGES = [
  'lucide-react',
  'lucide-react/*',
  'react-icons',
  'react-icons/*',
  '@heroicons/react',
  '@heroicons/react/*',
  'react-feather',
  'feather-icons',
  '@phosphor-icons/react',
  'phosphor-react',
  '@fortawesome/*',
]

export default tseslint.config(
  { ignores: ['dist/**'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommendedTypeChecked],
    languageOptions: {
      globals: globals.browser,
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    settings: {
      react: { version: 'detect' },
      // 插件默认找 tailwind.config.js, 本工程用 .ts; 且必须给绝对路径 ——
      // 插件按配置文件所在目录解析 tailwindcss 包, 相对路径会让它从 "." 解析并抛 Could not resolve tailwindcss。
      tailwindcss: { config: join(import.meta.dirname, 'tailwind.config.ts') },
    },
    plugins: {
      react,
      'react-hooks': reactHooks,
      tailwindcss,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: BANNED_ICON_PACKAGES,
              message:
                'stroke-based 矢量图标库与像素资产互斥 (PixelUI 规格第二章硬红线第 3 条); 图标一律走 PixelIcon 的 PNG 蒙版管线。',
            },
            {
              group: ['*.svg', '*.svg?*'],
              message: 'SVG 是矢量资产, 放大不会产生阶梯硬边; 图标资产一律 16x16 PNG (规格第八章)。',
            },
          ],
        },
      ],
      'react/forbid-elements': [
        'error',
        {
          forbid: [
            {
              element: 'svg',
              message: '禁止手写内联 SVG 图标; 走 PixelIcon 的 PNG 蒙版管线 (规格第八章)。',
            },
          ],
        },
      ],
      // 任意值绕过整条 --px 派生链, 是半像素混进来的主要通道。
      'tailwindcss/no-arbitrary-value': 'error',
      // 让被删掉的默认类 (rounded-lg / blur-sm / text-sm) 从"静默失效"变成"报错"。
      // 缺了这条, 前面所有 theme 覆盖只是让类名不产出样式, 人看不见。
      'tailwindcss/no-custom-classname': 'error',
      'tailwindcss/no-contradicting-classname': 'error',
    },
  },
  {
    files: ['**/*.js', '**/*.mjs'],
    extends: [js.configs.recommended],
    languageOptions: {
      globals: globals.node,
    },
  },
)
