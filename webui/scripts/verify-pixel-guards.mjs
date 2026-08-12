/*
 * 构建期机械守卫。lint 可以被跳过、也可以被 eslint-disable 局部关掉, 而本脚本挂在 build 脚本首位,
 * 违反即构建失败。覆盖四条无法靠 review 保证的红线:
 *   1. 依赖清单里不得出现 stroke-based 矢量图标库与非点阵字体包 (规格第二章硬红线第 3 条);
 *   2. 源码里不得出现内联 <svg> 与 .svg 模块导入 (被禁包之后最容易的绕路);
 *   3. className 里不得出现被 corePlugins 关掉的工具类 (见下方 BANNED_CLASS_PREFIXES 的理由);
 *   4. 9-slice 资产的实际像素尺寸必须落在白名单里 (差一像素即整体错位, 规格 4.4)。
 */

import { readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, extname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = dirname(dirname(fileURLToPath(import.meta.url)))

/** 依赖名前缀黑名单。矢量图标库与非点阵字体包一律不得进 package.json。 */
const BANNED_DEPENDENCIES = [
  'lucide-react',
  'lucide',
  'react-icons',
  '@heroicons/react',
  'heroicons',
  'react-feather',
  'feather-icons',
  '@phosphor-icons/react',
  'phosphor-react',
  '@fortawesome/',
  '@fontsource/',
  '@fontsource-variable/',
]

/*
 * 被 corePlugins 关掉的工具类前缀。这条本该由 eslint 的 tailwindcss/no-custom-classname 覆盖,
 * 但实测 (eslint-plugin-tailwindcss 3.18.3) 它的类名有效性判定不认 corePlugins:false ——
 * blur-sm / drop-shadow-lg 这类写法能过 lint, 只是生成不出任何 CSS, 症状是"样式静默不生效"。
 * 故在此补一道机械扫描, 让它变成构建期报错。
 */
const BANNED_CLASS_PREFIXES = ['blur', 'backdrop-blur', 'drop-shadow', 'sepia', 'hue-rotate']

/** className 属性值的三种写法: "..." / {'...'} / {`...`}。 */
const CLASS_ATTRIBUTE = /className\s*=\s*(?:"([^"]*)"|\{\s*'([^']*)'\s*\}|\{\s*`([^`]*)`\s*\})/g

/** 9-slice 与图标资产的允许边长 (1x 密度出图; 严禁美术端预放大)。 */
const ALLOWED_ASSET_SIZES = [16, 24]

const SOURCE_EXTENSIONS = new Set(['.ts', '.tsx', '.css', '.html'])

const violations = []

function checkDependencies() {
  const manifest = JSON.parse(readFileSync(join(projectRoot, 'package.json'), 'utf8'))
  const declared = [
    ...Object.keys(manifest.dependencies ?? {}),
    ...Object.keys(manifest.devDependencies ?? {}),
  ]
  for (const name of declared) {
    const banned = BANNED_DEPENDENCIES.find((prefix) => name === prefix || name.startsWith(prefix))
    if (banned !== undefined) {
      violations.push(`package.json 依赖 "${name}" 命中禁令 (矢量图标库/非点阵字体包)`)
    }
  }
}

function* walk(directory) {
  let entries
  try {
    entries = readdirSync(directory, { withFileTypes: true })
  } catch (error) {
    /*
     * 只放过"目录不存在"。早先这里是无条件 catch, 于是权限不足 (EACCES)、路径损坏、句柄耗尽
     * 全都被翻译成"这个目录是空的" —— 源码扫描整个被跳过, 脚本照样输出 PASS。守卫失效时必须比
     * 被守卫的东西更早报错, 否则它只是一句安慰。
     */
    if (error.code !== 'ENOENT') {
      throw error
    }
    return
  }
  for (const entry of entries) {
    const full = join(directory, entry.name)
    if (entry.isDirectory()) {
      yield* walk(full)
    } else if (entry.isFile()) {
      yield full
    }
  }
}

function checkSources() {
  const roots = [join(projectRoot, 'src'), join(projectRoot, 'index.html')]
  const files = []
  for (const root of roots) {
    let info
    try {
      info = statSync(root)
    } catch (error) {
      // 同 walk: 只放过不存在, 其余 (权限/IO) 必须炸出来, 不能变成"少扫了一个根"。
      if (error.code !== 'ENOENT') {
        throw error
      }
      continue
    }
    if (info.isDirectory()) {
      files.push(...walk(root))
    } else {
      files.push(root)
    }
  }

  for (const file of files) {
    if (!SOURCE_EXTENSIONS.has(extname(file))) {
      continue
    }
    const text = readFileSync(file, 'utf8')
    const shown = relative(projectRoot, file)
    if (/<svg[\s>]/i.test(text)) {
      violations.push(`${shown} 出现内联 <svg>: 图标一律走 PixelIcon 的 PNG 蒙版管线`)
    }
    if (/from\s+['"][^'"]+\.svg(\?[^'"]*)?['"]/.test(text)) {
      violations.push(`${shown} 导入了 .svg 模块: 矢量资产与像素资产互斥`)
    }
    checkClassNames(text, shown)
  }
}

function checkClassNames(text, shown) {
  for (const match of text.matchAll(CLASS_ATTRIBUTE)) {
    const value = match[1] ?? match[2] ?? match[3]
    if (value === undefined) {
      continue
    }
    for (const token of value.split(/\s+/)) {
      // 去掉变体前缀 (hover: / md: / group-hover: 等), 只看工具类本体。
      const utility = token.slice(token.lastIndexOf(':') + 1)
      const banned = BANNED_CLASS_PREFIXES.find(
        (prefix) => utility === prefix || utility.startsWith(`${prefix}-`),
      )
      if (banned !== undefined) {
        violations.push(
          `${shown} 出现被禁用的工具类 "${token}": ${banned}-* 已由 corePlugins 关闭, 写了也生成不出 CSS`,
        )
      }
    }
  }
}

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])

const CRC_TABLE = (() => {
  const table = new Int32Array(256)
  for (let n = 0; n < 256; n += 1) {
    let c = n
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    table[n] = c
  }
  return table
})()

function crc32(buf) {
  let c = 0xffffffff
  for (let i = 0; i < buf.length; i += 1) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

/**
 * 完整走一遍 PNG 分块并校验 CRC, 返回 {width,height} 或一句失败原因。
 *
 * 不能只认"签名对 + 长度 >= 24": 那样一个被截断成 24 字节的文件同样能报出 24x24 的宽高并通过守卫,
 * 而 Chromium 解不开它 —— border-image 取不到图时是**静默无边框**, 不报错、不留痕。守卫在这里放行,
 * 后果就是没人再有机会发现。故 IHDR 必须是第一块且长度 13, 每块 CRC 必须对, IDAT 与 IEND 必须齐。
 */
function readPngSize(file) {
  const bytes = readFileSync(file)
  if (bytes.length < 8 || !bytes.subarray(0, 8).equals(PNG_SIGNATURE)) {
    return { error: 'PNG 签名不符' }
  }
  let offset = 8
  let header = null
  let sawData = false
  let sawEnd = false
  while (offset + 12 <= bytes.length) {
    const length = bytes.readUInt32BE(offset)
    const end = offset + 12 + length
    if (end > bytes.length) {
      return { error: `分块 ${bytes.toString('ascii', offset + 4, offset + 8)} 越界 (文件被截断)` }
    }
    const type = bytes.toString('ascii', offset + 4, offset + 8)
    if (crc32(bytes.subarray(offset + 4, offset + 8 + length)) !== bytes.readUInt32BE(offset + 8 + length)) {
      return { error: `分块 ${type} CRC 校验失败 (文件已损坏)` }
    }
    if (header === null && type !== 'IHDR') {
      return { error: `首块是 ${type} 而非 IHDR` }
    }
    if (type === 'IHDR') {
      if (length !== 13) {
        return { error: `IHDR 长度 ${length} 不是 13` }
      }
      header = { width: bytes.readUInt32BE(offset + 8), height: bytes.readUInt32BE(offset + 12) }
    }
    if (type === 'IDAT') sawData = true
    if (type === 'IEND') sawEnd = true
    offset = end
  }
  if (offset !== bytes.length) {
    return { error: '文件尾部有无法解析的残留字节' }
  }
  if (header === null) return { error: '缺少 IHDR' }
  if (!sawData) return { error: '缺少 IDAT (没有像素数据)' }
  if (!sawEnd) return { error: '缺少 IEND (文件不完整)' }
  return header
}

/**
 * 从 PixelFrame.tsx 抠出 PIXEL_FRAME_ASSETS 的逐条登记。
 *
 * 用正则读 TSX 而不是 import: 本脚本是纯 node、跑在 tsc 之前, 引不动 TS 与 png 模块。代价是形状一变
 * 正则就失配, 因此下面把"没抠到 3 条"直接判失败 —— 宁可因为改了写法而报错, 也不要退化成静默不检查。
 * 这条链存在的理由: 资产的真实像素尺寸与消费侧登记的 size/slice 是一对必须相等的值, 而通用的
 * 16/24 白名单管不住它 —— 把 24x24 换成一张合法的 16x16, 白名单照样放行, 但 slice 仍是 8,
 * 左右切片之和正好等于图宽, 中心块宽度为 0, fill 中心与四条边条全部消失。
 */
function readFrameAssetRegistry() {
  const source = readFileSync(join(projectRoot, 'src', 'components', 'pixel', 'PixelFrame.tsx'), 'utf8')
  const entry = /(\w+):\s*\{\s*src:\s*\w+,\s*file:\s*'([^']+)',\s*slice:\s*(\d+),\s*size:\s*(\d+)\s*\}/g
  const entries = []
  for (const match of source.matchAll(entry)) {
    entries.push({
      variant: match[1],
      file: match[2],
      slice: Number.parseInt(match[3], 10),
      size: Number.parseInt(match[4], 10),
    })
  }
  return entries
}

const EXPECTED_FRAME_VARIANTS = ['window', 'panel', 'inset']

function checkFrameAssetContract() {
  const entries = readFrameAssetRegistry()
  const variants = entries.map((item) => item.variant)
  if (entries.length !== EXPECTED_FRAME_VARIANTS.length
    || !EXPECTED_FRAME_VARIANTS.every((variant) => variants.includes(variant))) {
    violations.push(
      `无法从 PixelFrame.tsx 解出三档 9-slice 资产登记 (实得 ${JSON.stringify(variants)}): `
        + '要么 PIXEL_FRAME_ASSETS 的写法变了, 要么档位增删; 本守卫必须同步更新, 不得放行',
    )
    return
  }
  for (const { variant, file, slice, size } of entries) {
    const shown = file
    const actual = readPngSize(join(projectRoot, file))
    if (actual.error !== undefined) {
      violations.push(`${shown} (${variant}) 不是可解码的 PNG: ${actual.error}`)
      continue
    }
    if (actual.width !== size || actual.height !== size) {
      violations.push(
        `${shown} (${variant}) 实际 ${actual.width}x${actual.height}, 与 PIXEL_FRAME_ASSETS 登记的 `
          + `size ${size} 不符: border-image-slice 与资产必须成对, 差一像素即整体错位`,
      )
    }
    if (size < slice * 2 + 1) {
      violations.push(
        `${shown} (${variant}) size ${size} < slice*2+1 = ${slice * 2 + 1}: `
          + '左右(上下)切片之和已吃满整图, 中心块与四条边条宽度为 0, fill 中心不会被绘制',
      )
    }
  }
}

/*
 * 位图资产的存放根。webui/public 不是 Vite 的 publicDir (那个被 vite.config.ts 指向了 mod 的
 * textures 目录), 只是普通目录, 9-slice 资产经 PixelFrame 的 ESM import 进包 —— 但尺寸守卫必须
 * 照样覆盖它, 否则"差一像素即整体错位"这条红线在唯一真实存在的资产上从未被检查过。
 */
const ASSET_ROOTS = [join(projectRoot, 'src', 'assets'), join(projectRoot, 'public')]

function checkAssets() {
  let count = 0
  for (const assetRoot of ASSET_ROOTS) {
    for (const file of walk(assetRoot)) {
      if (extname(file).toLowerCase() !== '.png') {
        continue
      }
      count += 1
      const shown = relative(projectRoot, file)
      const size = readPngSize(file)
      if (size.error !== undefined) {
        violations.push(`${shown} 不是可解码的 PNG: ${size.error}`)
        continue
      }
      if (size.width !== size.height || !ALLOWED_ASSET_SIZES.includes(size.width)) {
        violations.push(
          `${shown} 尺寸 ${size.width}x${size.height} 不在白名单 ${ALLOWED_ASSET_SIZES.join('/')} 内: ` +
            '源图须按 1x 像素密度出图, 放大交给 image-rendering 与整数 border-width',
        )
      }
    }
  }
  // 扫到零张即视为守卫失效: 资产目录改名 / 迁移时, 静默的 "PASS (0 张)" 与真通过无法区分。
  if (count === 0) {
    violations.push(
      `资产尺寸守卫扫到 0 张 PNG, 扫描根 ${ASSET_ROOTS.map((root) => relative(projectRoot, root)).join(' / ')} ` +
        '与实际资产落点已脱节, 请同步修正扫描根',
    )
  }
  return count
}

checkDependencies()
checkSources()
const assetCount = checkAssets()
checkFrameAssetContract()

if (violations.length > 0) {
  console.error('像素守卫 FAIL:')
  for (const violation of violations) {
    console.error(`  - ${violation}`)
  }
  process.exit(1)
}

console.log(
  `像素守卫 PASS (依赖与源码零矢量图标, 已校验 ${assetCount} 张位图资产, `
    + '三档 9-slice 资产与 PIXEL_FRAME_ASSETS 的 size/slice 登记一致)',
)
