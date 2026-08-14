import js from '@eslint/js'
import react from 'eslint-plugin-react'
import reactHooks from 'eslint-plugin-react-hooks'
import globals from 'globals'
import tseslint from 'typescript-eslint'

/*
 * 像素时代那份配置里的三组规则已整体撤除, 记录理由以免有人照着 git 历史加回来:
 *   - 矢量图标库禁令 (no-restricted-imports + react/forbid-elements 封 <svg>): 现在功能图标的
 *     唯一来源就是 lucide-react, 禁令与实现直接冲突;
 *   - tailwindcss/no-custom-classname 与 no-arbitrary-value: 插件只支持 Tailwind v3, 本工程已升 v4;
 *     且 Coss UI 的组件源码大量使用任意值 (before:rounded-[calc(var(--radius-lg)-1px)] 一类),
 *     这些是组件库自己的实现细节, 不该被本项目的 lint 判违规。
 *
 * src/components/ui/ 是 Coss UI 的 copy-paste 产物, 单列一段放宽规则 —— 那份代码由上游维护,
 * 用本项目的严格档去改它, 下次更新组件时改动会被整体覆盖回去。
 */

export default tseslint.config(
  // _pixel-archive 是封存区, 不在 tsconfig 的 include 里 (typed lint 会因此报"文件不属于任何项目"),
  // 也不参与构建。它的代码按 Tailwind v3 那套写, 用现在的规则去查只会得到一堆无意义的报错。
  // .vite 是 vite 的依赖预打包缓存 (压缩后的第三方源码)。扁平配置不再默认忽略点目录, 不写在这里
  // 就会去 lint react-dom 的构建产物, 一次刷出四百多条与本项目无关的 no-undef。
  { ignores: ['dist/**', '_pixel-archive/**', '.vite/**'] },
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
    },
    plugins: {
      react,
      'react-hooks': reactHooks,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
    },
  },
  {
    // 上游组件源码: 保留类型检查, 关掉那些"写法风格"类规则。
    files: ['src/components/ui/**/*.{ts,tsx}', 'src/hooks/**/*.ts', 'src/lib/utils.ts'],
    rules: {
      '@typescript-eslint/no-unsafe-assignment': 'off',
      '@typescript-eslint/no-unsafe-member-access': 'off',
      '@typescript-eslint/no-unsafe-call': 'off',
      '@typescript-eslint/no-unsafe-argument': 'off',
      '@typescript-eslint/no-unsafe-return': 'off',
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-empty-object-type': 'off',
      '@typescript-eslint/no-unnecessary-type-assertion': 'off',
      '@typescript-eslint/no-floating-promises': 'off',
      '@typescript-eslint/no-misused-promises': 'off',
      'react-hooks/refs': 'off',
      'react-hooks/set-state-in-effect': 'off',
      'react-hooks/purity': 'off',
      'react-hooks/immutability': 'off',
      'react-hooks/preserve-manual-memoization': 'off',
      'react-hooks/static-components': 'off',
      'react-hooks/incompatible-library': 'off',
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
