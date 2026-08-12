/*
 * 功能图标生成器 (PixelUI 规格第八章第 2 层: MC 原版无对应物、须自绘的 16x16 像素图)。
 *
 * 存在理由: 当前工程功能图标为 0 张, 而硬红线第 3 条封死了矢量图标库这条捷径 —— 界面上每一个
 * "关闭 / 排序 / 筛选" 都要等美术, 前端就永远接不了线。本脚本用与 gen-nineslice.mjs 同一条路子
 * (纯 node:zlib + 手写 PNG 分块, 零第三方依赖) 把这批图程序化出出来。
 *
 * 图形以**字符矩阵字面量**表达 ('.' 透明 / '#' 实心), 而不是坐标数组或路径指令: 矩阵在编辑器里
 * 就是图本身, 美术要调形只改矩阵、不必读代码, 而坐标数组改一个数没人看得出改了哪个像素。
 *
 * 全部图标是**单色蒙版**: 只有 alpha 有意义, 灰度恒为 255。颜色由 PixelIcon 的
 * background-color: currentColor 给 (规格 6.1 主路径), 因此 normal/hover/pressed 与
 * 普通/强调/危险 一律换色表达, 不为任何状态增发第二张图 (规格第七章压缩原则)。
 *
 * PNG 编解码与 gen-nineslice.mjs 各持一份实现: 两个生成器都要求零依赖且可单独执行, 抽公共模块
 * 须同时改动 gen-nineslice.mjs, 属另一次改动。
 *
 * 确定性与 gen-nineslice.mjs 同口径: 同一 Node (同 zlib) 下同输入必得同字节, 反复重跑不产生 git 噪声;
 * 跨 Node 大版本不作此保证 (DEFLATE 允许多种合法编码), 此时 --check 报 STALE 重跑写盘即可 ——
 * 正确性的真判据是本文件的六层像素级自检, 不是字节相等。
 *
 * 用法:
 *   node tools/gen-icons.mjs          写盘并自检
 *   node tools/gen-icons.mjs --check  只校验现有文件是否与生成结果一致 (不写盘, 不一致退出码 1)
 */

import { deflateSync, inflateSync } from 'node:zlib'
import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))

/*
 * 输出目录。webui/public 不是 Vite 的 publicDir (那个被 vite.config.ts 指向了 mod 贴图目录),
 * 图标靠 PixelIcon 的 ESM import 进包 —— 与 9-slice 三张图同一处置。构建期尺寸守卫
 * (scripts/verify-pixel-guards.mjs) 的扫描根含 public 且递归, 本目录自动纳入 16/24 白名单校验。
 */
const OUT_DIR = join(HERE, '..', 'public', 'ui', 'icons')

/** 消费侧组件。名单必须与本表逐个对上, 见 assertRegistryMatchesComponent。 */
const COMPONENT_FILE = join(HERE, '..', 'src', 'components', 'pixel', 'PixelIcon.tsx')

/** 规格第八章: 自绘功能图标统一 16x16、1x 像素密度, 与 9-slice 资产同规格 (4.4)。 */
const SIZE = 16

const OPAQUE = '#'
const TRANSPARENT = '.'

/*
 * 蒙版的灰度恒定值。整张图 (含透明像素) 都写 255, 只让 alpha 携带形状:
 * mask-mode: alpha 只读 alpha, 灰度写什么都不影响渲染; 但万一某个引擎按 luminance 解,
 * 恒 255 会让整块方块直接显形 —— 那是一眼可见的错, 而灰度写 0 时症状是图标整体消失, 不可见。
 */
const MASK_GRAY = 255

/*
 * 图标主体的最少 / 最多不透明像素数。空矩阵与近乎填满的矩阵都不是设计, 是编辑事故:
 * 前者渲染出来什么都没有 (蒙版全透明 = 元素整块不可见, 且不报错), 后者是一个实心方块。
 * 上界 140 略高于本表最重的 bag(116), 给美术调形留出余量。
 */
const MIN_OPAQUE = 12
const MAX_OPAQUE = 140

/**
 * 图标矩阵表。
 *
 * 三条画法纪律 (16x16 下不守就必然糊):
 *   1. 主体留 1px 边距, 不顶满画布 —— 顶满时图标与相邻元素之间没有呼吸位, 且放大后边缘像素
 *      容易被容器裁掉半格; 由 assertMargin 机械保证。
 *   2. 对角线一律走阶梯 (每级至少 2 像素同行或同列), 不画 1 像素宽的近似直线 —— 纯对角线上的
 *      像素彼此只有对角相邻, 视觉上是断续的点列; 由 assertOrthogonalConnectivity 机械保证。
 *   3. 笔画不小于 2 像素宽, 形状克制、边缘对齐网格。
 */
const ICONS = {
  // ---- 窗口与全局控制 ----
  close: {
    title: '关闭 (窗口 / 弹窗右上角)',
    rows: [
      '................',
      '................',
      '..##........##..',
      '..###......###..',
      '...###....###...',
      '....###..###....',
      '.....######.....',
      '......####......',
      '......####......',
      '.....######.....',
      '....###..###....',
      '...###....###...',
      '..###......###..',
      '..##........##..',
      '................',
      '................',
    ],
  },
  menu: {
    title: '菜单 (汉堡, 三条等长横杠)',
    rows: [
      '................',
      '................',
      '................',
      '..############..',
      '..############..',
      '................',
      '................',
      '..############..',
      '..############..',
      '................',
      '................',
      '..############..',
      '..############..',
      '................',
      '................',
      '................',
    ],
  },
  settings: {
    title: '设置 (齿轮, 四齿 + 中心孔)',
    rows: [
      '................',
      '................',
      '.....##..##.....',
      '.....##..##.....',
      '....########....',
      '..############..',
      '..####....####..',
      '....##....##....',
      '....##....##....',
      '..####....####..',
      '..############..',
      '....########....',
      '.....##..##.....',
      '.....##..##.....',
      '................',
      '................',
    ],
  },
  search: {
    title: '搜索 (放大镜, 环 + 阶梯手柄)',
    rows: [
      '................',
      '................',
      '.....####.......',
      '....##..##......',
      '...##....##.....',
      '...##....##.....',
      '...##....##.....',
      '...##....##.....',
      '....##..##......',
      '.....######.....',
      '..........##....',
      '...........##...',
      '............##..',
      '................',
      '................',
      '................',
    ],
  },
  refresh: {
    title: '刷新 (开口圆环 + 箭头)',
    rows: [
      '................',
      '................',
      '......##........',
      '....#####..##...',
      '...###....####..',
      '...##......##...',
      '..##........##..',
      '..##........##..',
      '..##........##..',
      '..##........##..',
      '...##......##...',
      '...###....###...',
      '....########....',
      '......####......',
      '................',
      '................',
    ],
  },

  // ---- 确认 / 取消 ----
  check: {
    title: '确认 (勾, 3 像素笔画)',
    rows: [
      '................',
      '................',
      '................',
      '................',
      '...........###..',
      '..........###...',
      '.........###....',
      '........###.....',
      '..##...###......',
      '..###.###.......',
      '...#####........',
      '....###.........',
      '................',
      '................',
      '................',
      '................',
    ],
  },
  /*
   * 取消刻意不画成第二张裸 X: 那与 close 只差笔画粗细, 在 16x16 下等于两张重复资产。
   * 这里走"环内 X", 与 info(环内 i) / warning(三角内 !) 同属状态标记族, 语义读作"该操作被否决",
   * 而 close 的裸 X 只表示"把这个界面收起来"。
   */
  cross: {
    title: '取消 (环内 X, 状态标记族)',
    rows: [
      '................',
      '................',
      '......####......',
      '....########....',
      '...###....###...',
      '...####..####...',
      '..##..####..##..',
      '..##...##...##..',
      '..##...##...##..',
      '..##..####..##..',
      '...####..####...',
      '...###....###...',
      '....########....',
      '......####......',
      '................',
      '................',
    ],
  },

  // ---- 增减 ----
  plus: {
    title: '加 (十字)',
    rows: [
      '................',
      '................',
      '................',
      '.......##.......',
      '.......##.......',
      '.......##.......',
      '.......##.......',
      '...##########...',
      '...##########...',
      '.......##.......',
      '.......##.......',
      '.......##.......',
      '.......##.......',
      '................',
      '................',
      '................',
    ],
  },
  minus: {
    title: '减 (横杠, 与 plus 同宽同粗)',
    rows: [
      '................',
      '................',
      '................',
      '................',
      '................',
      '................',
      '................',
      '...##########...',
      '...##########...',
      '................',
      '................',
      '................',
      '................',
      '................',
      '................',
      '................',
    ],
  },

  // ---- 四向箭头 (互为镜像 / 转置, 由 assertArrowFamily 盯住) ----
  'arrow-up': {
    title: '上 (三角头 + 2 像素杆)',
    rows: [
      '................',
      '................',
      '................',
      '.......##.......',
      '......####......',
      '.....######.....',
      '....########....',
      '.......##.......',
      '.......##.......',
      '.......##.......',
      '.......##.......',
      '.......##.......',
      '.......##.......',
      '................',
      '................',
      '................',
    ],
  },
  'arrow-down': {
    title: '下 (arrow-up 的上下镜像)',
    rows: [
      '................',
      '................',
      '................',
      '.......##.......',
      '.......##.......',
      '.......##.......',
      '.......##.......',
      '.......##.......',
      '.......##.......',
      '....########....',
      '.....######.....',
      '......####......',
      '.......##.......',
      '................',
      '................',
      '................',
    ],
  },
  'arrow-left': {
    title: '左 (arrow-up 的转置)',
    rows: [
      '................',
      '................',
      '................',
      '................',
      '......#.........',
      '.....##.........',
      '....###.........',
      '...##########...',
      '...##########...',
      '....###.........',
      '.....##.........',
      '......#.........',
      '................',
      '................',
      '................',
      '................',
    ],
  },
  'arrow-right': {
    title: '右 (arrow-left 的左右镜像)',
    rows: [
      '................',
      '................',
      '................',
      '................',
      '.........#......',
      '.........##.....',
      '.........###....',
      '...##########...',
      '...##########...',
      '.........###....',
      '.........##.....',
      '.........#......',
      '................',
      '................',
      '................',
      '................',
    ],
  },

  // ---- 列表操作 ----
  sort: {
    title: '排序 (三条递减横杠)',
    rows: [
      '................',
      '................',
      '................',
      '..############..',
      '..############..',
      '................',
      '................',
      '..########......',
      '..########......',
      '................',
      '................',
      '..####..........',
      '..####..........',
      '................',
      '................',
      '................',
    ],
  },
  filter: {
    title: '筛选 (漏斗)',
    rows: [
      '................',
      '................',
      '................',
      '..############..',
      '...##########...',
      '....########....',
      '.....######.....',
      '......####......',
      '.......##.......',
      '.......##.......',
      '.......##.......',
      '.......##.......',
      '.......##.......',
      '................',
      '................',
      '................',
    ],
  },

  // ---- 状态标记 ----
  warning: {
    title: '警告 (三角 + 挖空感叹号)',
    rows: [
      '................',
      '................',
      '.......##.......',
      '......####......',
      '......####......',
      '.....##..##.....',
      '.....##..##.....',
      '....###..###....',
      '....###..###....',
      '...####..####...',
      '...##########...',
      '..#####..#####..',
      '..#####..#####..',
      '.##############.',
      '................',
      '................',
    ],
  },
  info: {
    title: '信息 (圆盘 + 挖空 i)',
    rows: [
      '................',
      '................',
      '......####......',
      '....########....',
      '...####..####...',
      '...####..####...',
      '..############..',
      '..#####..#####..',
      '..#####..#####..',
      '..#####..#####..',
      '...####..####...',
      '...####..####...',
      '....########....',
      '......####......',
      '................',
      '................',
    ],
  },
  lock: {
    title: '锁 (锁梁 + 锁体 + 挖空锁孔)',
    rows: [
      '................',
      '................',
      '................',
      '......####......',
      '.....##..##.....',
      '.....##..##.....',
      '.....##..##.....',
      '.....##..##.....',
      '...##########...',
      '...##########...',
      '...####..####...',
      '...####..####...',
      '...##########...',
      '...##########...',
      '................',
      '................',
    ],
  },

  // ---- 收藏与生命值 ----
  star: {
    title: '星 (五角)',
    rows: [
      '................',
      '................',
      '.......##.......',
      '.......##.......',
      '......####......',
      '......####......',
      '.##############.',
      '..############..',
      '...##########...',
      '....########....',
      '....########....',
      '...##########...',
      '..###......###..',
      '.###........###.',
      '................',
      '................',
    ],
  },
  heart: {
    title: '心',
    rows: [
      '................',
      '................',
      '................',
      '...###....###...',
      '..#####..#####..',
      '..############..',
      '..############..',
      '..############..',
      '...##########...',
      '....########....',
      '.....######.....',
      '......####......',
      '.......##.......',
      '................',
      '................',
      '................',
    ],
  },

  // ---- 货币 (两种货币必须一眼可分: 圆盘 vs 宝石) ----
  'coin-credit': {
    title: '信用点 (圆盘 + 挖空 C)',
    rows: [
      '................',
      '................',
      '......####......',
      '....########....',
      '...##########...',
      '...###....###...',
      '..###..#######..',
      '..###..#######..',
      '..###..#######..',
      '..###..#######..',
      '...###....###...',
      '...##########...',
      '....########....',
      '......####......',
      '................',
      '................',
    ],
  },
  'coin-azure': {
    title: '青辉石 (宝石切面轮廓)',
    rows: [
      '................',
      '................',
      '................',
      '......####......',
      '....########....',
      '...##########...',
      '..############..',
      '..############..',
      '...##########...',
      '....########....',
      '.....######.....',
      '......####......',
      '.......##.......',
      '................',
      '................',
      '................',
    ],
  },

  // ---- 其它 ----
  bag: {
    title: '背包 (提手 + 包体 + 挖空扣带)',
    rows: [
      '................',
      '................',
      '......####......',
      '.....##..##.....',
      '...##########...',
      '..############..',
      '..############..',
      '..############..',
      '..############..',
      '..####....####..',
      '..####....####..',
      '..############..',
      '..############..',
      '...##########...',
      '................',
      '................',
    ],
  },
  clock: {
    title: '时钟 (圆盘 + 挖空指针)',
    rows: [
      '................',
      '................',
      '......####......',
      '....########....',
      '...####..####...',
      '...####..####...',
      '..#####..#####..',
      '..#####.....##..',
      '..#####.....##..',
      '..############..',
      '...##########...',
      '...##########...',
      '....########....',
      '......####......',
      '................',
      '................',
    ],
  },
}

const ICON_NAMES = Object.keys(ICONS)

function fail(name, message) {
  throw new Error(`[${name}] ${message}`)
}

/** 矩阵必须严格 16 行 x 16 列, 且只含两种字符 —— 少一列的行会让整张图从该行起横向错位。 */
function assertShape(name, rows) {
  if (!Array.isArray(rows) || rows.length !== SIZE) {
    fail(name, `矩阵应为 ${SIZE} 行, 实为 ${Array.isArray(rows) ? rows.length : typeof rows}`)
  }
  rows.forEach((row, y) => {
    if (typeof row !== 'string' || row.length !== SIZE) {
      fail(name, `第 ${y} 行应为 ${SIZE} 个字符, 实为 ${typeof row === 'string' ? row.length : typeof row}`)
    }
    for (let x = 0; x < SIZE; x += 1) {
      const ch = row[x]
      if (ch !== OPAQUE && ch !== TRANSPARENT) {
        fail(name, `(${x},${y}) 出现非法字符 "${ch}", 只允许 "${OPAQUE}" 与 "${TRANSPARENT}"`)
      }
    }
  })
}

/** 主体留 1px 边距: 最外一圈必须全透明。 */
function assertMargin(name, rows) {
  for (let i = 0; i < SIZE; i += 1) {
    const edges = [
      [i, 0, `第 0 行第 ${i} 列`],
      [i, SIZE - 1, `第 ${SIZE - 1} 行第 ${i} 列`],
      [0, i, `第 ${i} 行第 0 列`],
      [SIZE - 1, i, `第 ${i} 行第 ${SIZE - 1} 列`],
    ]
    for (const [x, y, where] of edges) {
      if (rows[y][x] === OPAQUE) {
        fail(name, `${where} 顶到画布边缘, 图标主体须留 1px 边距`)
      }
    }
  }
}

function countOpaque(rows) {
  let count = 0
  for (const row of rows) {
    for (const ch of row) {
      if (ch === OPAQUE) count += 1
    }
  }
  return count
}

function assertCoverage(name, rows) {
  const count = countOpaque(rows)
  if (count < MIN_OPAQUE || count > MAX_OPAQUE) {
    fail(name, `不透明像素 ${count} 个, 超出 [${MIN_OPAQUE}, ${MAX_OPAQUE}]: 空图与实心块都不是图标`)
  }
  return count
}

/**
 * 每个不透明像素至少有一个上下左右方向的不透明邻居。
 *
 * 这一条同时封死两种 16x16 下的典型失败: 孤立单像素 (放大后是一个游离方块, 缩小到 1x 时读不出形状),
 * 以及 1 像素宽的近似对角直线 (相邻像素只在对角相接, 观感是断续点列)。要过这一关, 对角线只能画成
 * 阶梯 —— 每一级至少两个像素同行或同列, 这正是像素图标的标准画法。
 */
function assertOrthogonalConnectivity(name, rows) {
  for (let y = 0; y < SIZE; y += 1) {
    for (let x = 0; x < SIZE; x += 1) {
      if (rows[y][x] !== OPAQUE) continue
      const neighbours = [
        [x, y - 1],
        [x, y + 1],
        [x - 1, y],
        [x + 1, y],
      ]
      const linked = neighbours.some(
        ([nx, ny]) => nx >= 0 && nx < SIZE && ny >= 0 && ny < SIZE && rows[ny][nx] === OPAQUE,
      )
      if (!linked) {
        fail(name, `(${x},${y}) 四邻域内无相连像素: 孤立点或 1 像素宽对角线, 须改画成阶梯`)
      }
    }
  }
}

const mirrorX = (rows) => rows.map((row) => [...row].reverse().join(''))
const mirrorY = (rows) => [...rows].reverse()
const transpose = (rows) =>
  rows.map((_, y) => rows.map((row) => row[y]).join(''))

/** 左右对称的图标 (每行都是回文)。不对称即形状在水平方向偏了, 放到按钮里会看着"歪"。 */
const SYMMETRIC_X = [
  'close', 'menu', 'settings', 'cross', 'plus', 'minus',
  'arrow-up', 'arrow-down', 'filter', 'warning', 'info', 'lock',
  'star', 'heart', 'coin-azure', 'bag',
]

/** 上下对称的图标。 */
const SYMMETRIC_Y = ['close', 'menu', 'settings', 'cross', 'plus', 'minus', 'arrow-left', 'arrow-right']

function assertSymmetry(matrices) {
  for (const name of SYMMETRIC_X) {
    if (mirrorX(matrices[name]).join('\n') !== matrices[name].join('\n')) {
      fail(name, '应左右对称, 实测不对称')
    }
  }
  for (const name of SYMMETRIC_Y) {
    if (mirrorY(matrices[name]).join('\n') !== matrices[name].join('\n')) {
      fail(name, '应上下对称, 实测不对称')
    }
  }
}

/**
 * 四向箭头必须是同一个形状的四个朝向, 而不是四张各画各的图。
 *
 * 单独看每一张都"像箭头"却互不同构, 是这类图标最常见的翻车方式: 并排放进分页器或排序头时,
 * 头的大小、杆的长短各差一两像素, 观感立刻散架。故三条关系一并机械校验, 改一张就必须改到全家。
 */
function assertArrowFamily(matrices) {
  const pairs = [
    ['arrow-left', 'arrow-right', mirrorX, '左右镜像'],
    ['arrow-up', 'arrow-down', mirrorY, '上下镜像'],
    ['arrow-up', 'arrow-left', transpose, '转置 (上 -> 左)'],
  ]
  for (const [from, to, transform, relation] of pairs) {
    if (transform(matrices[from]).join('\n') !== matrices[to].join('\n')) {
      fail(to, `与 ${from} 的 ${relation} 关系不成立, 四向箭头已不同构`)
    }
  }
}

/**
 * 生成器的图标名单必须与 PixelIcon.tsx 的 PIXEL_ICON_NAMES 逐个对上。
 *
 * 两边脱节的后果不对称且都不报错: 出了图而组件没登记, 那张图永远不会被打包引用 (白出);
 * 组件登记了而没出图, ESM import 会在构建期炸 —— 但若哪天改成运行期拼 URL, 症状就变成
 * mask 取不到图、元素整块不可见且控制台一声不吭。故在生成侧就把名单钉死。
 *
 * 用正则读 TSX 而不是 import: 本脚本是纯 node, 引不动 TS 与 png 模块。代价是写法一变正则就失配,
 * 因此"解不出名单"直接判失败, 不退化成静默跳过。
 */
function assertRegistryMatchesComponent() {
  const source = readFileSync(COMPONENT_FILE, 'utf8')
  const block = /export const PIXEL_ICON_NAMES = \[([\s\S]*?)\] as const/.exec(source)
  if (block === null) {
    throw new Error(
      'PixelIcon.tsx 中解不出 PIXEL_ICON_NAMES 数组字面量: 要么写法变了, 要么名单被挪走; '
        + '本校验必须同步更新, 不得放行',
    )
  }
  const declared = [...block[1].matchAll(/'([a-z][a-z0-9-]*)'/g)].map((match) => match[1])
  const duplicated = declared.filter((name, index) => declared.indexOf(name) !== index)
  if (duplicated.length > 0) {
    throw new Error(`PIXEL_ICON_NAMES 出现重复项: ${duplicated.join(', ')}`)
  }
  const missing = ICON_NAMES.filter((name) => !declared.includes(name))
  const extra = declared.filter((name) => !ICON_NAMES.includes(name))
  if (missing.length > 0 || extra.length > 0) {
    throw new Error(
      '图标名单与 PixelIcon.tsx 的 PIXEL_ICON_NAMES 不一致: '
        + `组件缺 [${missing.join(', ')}], 组件多出 [${extra.join(', ')}]`,
    )
  }
  return declared.length
}

/** 生成 SIZE*SIZE 的灰度+alpha 原始像素缓冲 (每像素 2 字节, 灰度恒 MASK_GRAY)。 */
function renderIcon(rows) {
  const pixels = Buffer.alloc(SIZE * SIZE * 2)
  for (let y = 0; y < SIZE; y += 1) {
    for (let x = 0; x < SIZE; x += 1) {
      const at = (y * SIZE + x) * 2
      pixels[at] = MASK_GRAY
      pixels[at + 1] = rows[y][x] === OPAQUE ? 255 : 0
    }
  }
  return pixels
}

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

function chunk(type, data) {
  const head = Buffer.alloc(8)
  head.writeUInt32BE(data.length, 0)
  head.write(type, 4, 'ascii')
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(Buffer.concat([head.subarray(4), data])), 0)
  return Buffer.concat([head, data, crc])
}

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
const COLOR_TYPE_GRAY_ALPHA = 4

function encodePng(pixels) {
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(SIZE, 0)
  ihdr.writeUInt32BE(SIZE, 4)
  ihdr[8] = 8 // bit depth
  ihdr[9] = COLOR_TYPE_GRAY_ALPHA
  ihdr[10] = 0 // compression: deflate
  ihdr[11] = 0 // filter method
  ihdr[12] = 0 // interlace: none

  // 每行统一 filter 0 (None), 与 gen-nineslice.mjs 同口径: 固定 0 让解码校验可以逐字节直比。
  const stride = SIZE * 2
  const raw = Buffer.alloc((stride + 1) * SIZE)
  for (let y = 0; y < SIZE; y += 1) {
    raw[y * (stride + 1)] = 0
    pixels.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride)
  }

  return Buffer.concat([
    PNG_SIGNATURE,
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

/** 把生成的 PNG 解回像素, 与源缓冲逐字节比对。写盘成功不等于内容正确, 编码链路必须自证。 */
function decodePng(png) {
  if (!png.subarray(0, 8).equals(PNG_SIGNATURE)) throw new Error('PNG 签名不符')

  let offset = 8
  let header = null
  const idatParts = []
  while (offset < png.length) {
    const length = png.readUInt32BE(offset)
    const type = png.toString('ascii', offset + 4, offset + 8)
    const data = png.subarray(offset + 8, offset + 8 + length)
    const declaredCrc = png.readUInt32BE(offset + 8 + length)
    if (crc32(png.subarray(offset + 4, offset + 8 + length)) !== declaredCrc) {
      throw new Error(`分块 ${type} CRC 校验失败`)
    }
    if (type === 'IHDR') {
      header = {
        width: data.readUInt32BE(0),
        height: data.readUInt32BE(4),
        bitDepth: data[8],
        colorType: data[9],
      }
    }
    if (type === 'IDAT') idatParts.push(data)
    offset += 12 + length
  }

  if (!header) throw new Error('缺少 IHDR')
  const stride = header.width * 2
  const raw = inflateSync(Buffer.concat(idatParts))
  const pixels = Buffer.alloc(stride * header.height)
  for (let y = 0; y < header.height; y += 1) {
    const filter = raw[y * (stride + 1)]
    if (filter !== 0) throw new Error(`第 ${y} 行 filter 应为 0, 实为 ${filter}`)
    raw.copy(pixels, y * stride, y * (stride + 1) + 1, (y + 1) * (stride + 1))
  }
  return { header, pixels }
}

/** 蒙版契约: alpha 只许 0/255 (中间值即抗锯齿), 灰度恒 MASK_GRAY (只有 alpha 携带形状)。 */
function assertMaskChannels(name, pixels) {
  for (let i = 0; i < SIZE * SIZE; i += 1) {
    const gray = pixels[i * 2]
    const alpha = pixels[i * 2 + 1]
    if (gray !== MASK_GRAY) {
      fail(name, `第 ${i} 个像素灰度 ${gray} != ${MASK_GRAY}: 单色蒙版的灰度必须恒定`)
    }
    if (alpha !== 0 && alpha !== 255) {
      fail(name, `第 ${i} 个像素 alpha=${alpha}, 存在抗锯齿半透明`)
    }
  }
}

function main() {
  const checkOnly = process.argv.includes('--check')
  // --check 承诺不写盘, 那就一个字节也不写: 无条件 mkdir 会在只读校验时留下副作用。
  if (!checkOnly) {
    mkdirSync(OUT_DIR, { recursive: true })
  }

  const matrices = {}
  const opaqueCounts = {}
  for (const [name, icon] of Object.entries(ICONS)) {
    assertShape(name, icon.rows)
    assertMargin(name, icon.rows)
    assertOrthogonalConnectivity(name, icon.rows)
    opaqueCounts[name] = assertCoverage(name, icon.rows)
    matrices[name] = icon.rows
  }
  assertSymmetry(matrices)
  assertArrowFamily(matrices)
  const declaredCount = assertRegistryMatchesComponent()

  let dirty = false
  for (const [name, icon] of Object.entries(ICONS)) {
    const pixels = renderIcon(icon.rows)
    assertMaskChannels(name, pixels)
    const png = encodePng(pixels)

    const { header, pixels: roundTrip } = decodePng(png)
    if (header.width !== SIZE || header.height !== SIZE) {
      throw new Error(`${name}: IHDR 尺寸 ${header.width}x${header.height} 与规格 ${SIZE}x${SIZE} 不符`)
    }
    if (header.bitDepth !== 8 || header.colorType !== COLOR_TYPE_GRAY_ALPHA) {
      throw new Error(
        `${name}: 应为 8 位灰度+alpha, 实为 depth=${header.bitDepth} colorType=${header.colorType}`,
      )
    }
    if (!roundTrip.equals(pixels)) throw new Error(`${name}: PNG 往返解码与源像素不一致`)

    const file = join(OUT_DIR, `${name}.png`)
    const same = existsSync(file) && readFileSync(file).equals(png)
    if (!same) {
      dirty = true
      if (!checkOnly) writeFileSync(file, png)
    }
    const state = same ? 'UNCHANGED' : checkOnly ? 'STALE' : 'WRITTEN'
    process.stdout.write(
      `${state.padEnd(9)} ${`${name}.png`.padEnd(20)} ${SIZE}x${SIZE}  `
        + `${String(opaqueCounts[name]).padStart(3)} 实心像素  ${String(png.length).padStart(4)}B  ${icon.title}\n`,
    )
  }

  if (checkOnly && dirty) {
    process.stdout.write('FAIL 现有资产与生成结果不一致, 请重跑 node tools/gen-icons.mjs\n')
    process.exitCode = 1
    return
  }
  process.stdout.write(
    `PASS ${ICON_NAMES.length} 张功能图标校验通过 (与 PixelIcon.tsx 的 ${declaredCount} 条登记一致, 输出目录 ${OUT_DIR})\n`,
  )
}

main()
