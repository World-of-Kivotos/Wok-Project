/*
 * 9-slice 占位资产生成器 (PixelUI 规格第四章 4.4 / 第六章 / 接线清单"资产缺口")。
 *
 * 存在理由: 美术侧当前 0 张 9-slice 资产, 而 PixelFrame 与真客户端像素对齐验证不能等美术。
 * 本脚本用纯 Node (zlib + 手写 PNG 分块) 程序化出图, 不引入任何图像库 —— 依赖越少, 这份
 * 一次性占位资产越不会在美术交付后留下清理负担。
 *
 * 输出在同一 Node (同 zlib) 下是确定性的 —— 同输入必得同字节, 反复执行不产生 git 噪声。
 * 跨 Node 大版本不作此保证: DEFLATE 允许多种合法编码, zlib 换实现后解出来的像素完全一样、字节流却可能不同,
 * 此时 --check 会报 STALE。那不是资产坏了, 重跑一次写盘即可; 真正的正确性判据是本文件的三层像素级自检。
 *
 * 用法:
 *   node tools/gen-nineslice.mjs          写盘并自检
 *   node tools/gen-nineslice.mjs --check  只校验现有文件是否与生成结果一致 (不写盘, 不一致退出码 1)
 */

import { deflateSync, inflateSync } from 'node:zlib'
import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))

/*
 * 输出目录 (处置见 public/ui/README.md 第五节)。本目录不是 Vite 的 publicDir —— 那个被 vite.config.ts
 * 指向了 mod 贴图目录 —— 三张图靠 PixelFrame 的 ESM import 进包, 并已纳入 scripts/verify-pixel-guards.mjs
 * 的扫描根与逐文件 size/slice 契约校验。改落点需同步改那三处 (本常量 / PIXEL_FRAME_ASSETS.file / 守卫扫描根)。
 */
const OUT_DIR = join(HERE, '..', 'public', 'ui')

/*
 * 尺寸与 slice 是一对硬耦合常量: SLICE 必须与 CSS 的 border-image-slice 精确相等, 差一像素即整体错位
 * (规格 4.2 第 1 条)。SIZE 取 24 = SLICE*3, 使四角/四边/中心恰好各 8x8, 边与中心都有可辨识空间。
 */
const SIZE = 24
const SLICE = 8

/*
 * 层级三档。灰度值只表达明暗关系, 不表达颜色 —— 颜色由 CSS 变量在上色层给 (规格第六章, 形状与颜色正交)。
 *
 * outline: 最外一圈硬描边, 保证任意背景上轮廓都咬得住。
 * bevelLight / bevelDark: 第 1、2 圈斜面。window 与 inset 的这两组值互为镜像 —— 外凸是左上亮右下暗,
 *   内凹是左上暗右下亮。这正是"层级维度必须单独出图"的原因: 换色改不出明暗方向 (规格第七章压缩原则)。
 * face: 第 3 圈, 斜面与填充之间的过渡平面。
 * fill: 中心块 (border-image-slice 的 fill 关键字保留它), 即控件底色的明度基准。
 */
const VARIANTS = {
  'frame-window': {
    title: '外凸窗口框 (平板 / 弹窗)',
    outline: 16,
    bevelLight: [236, 196],
    bevelDark: [80, 112],
    face: 160,
    fill: 160,
  },
  'frame-panel': {
    // 平面板刻意不给方向性斜面: 两圈都是均匀值, 于是它在视觉上既不凸也不凹, 用于分区与卡片这类
    // 不该抢层级的容器。均匀值同时意味着它旋转对称, 是三档里最不容易暴露占位资产粗糙感的一档。
    title: '平面板 (分区 / 卡片)',
    outline: 16,
    bevelLight: [184, 144],
    bevelDark: [184, 144],
    face: 144,
    fill: 144,
  },
  'frame-inset': {
    title: '内凹凹槽 (输入框 / 列表底 / 进度槽)',
    outline: 16,
    bevelLight: [80, 112],
    bevelDark: [236, 196],
    // 凹槽的中心比 panel/window 更暗: 光被槽壁挡住是内凹的第二个提示, 与斜面方向共同成立。
    face: 104,
    fill: 96,
  },
}

/**
 * 计算单个像素的灰度与 alpha。
 *
 * 三档共用同一套几何: 环深 d = 到最近边的距离, 决定取哪一圈的值; 最近边是横边还是竖边, 决定取亮组还是暗组。
 * 平局 (dx === dy, 即 45 度对角线) 归给横边, 使四角形成规整的斜接缝 —— 这是位图斜面的标准画法,
 * 也保证左上/右下两角内部不出现明暗撕裂。
 */
function shade(variant, x, y) {
  const dx = Math.min(x, SIZE - 1 - x)
  const dy = Math.min(y, SIZE - 1 - y)
  const d = Math.min(dx, dy)

  // 最外角单像素挖空 = 阶梯状硬边"圆角"。规格第二章把 border-radius 列为头号反例, 圆角只能由位图表达。
  if (dx === 0 && dy === 0) return [0, 0]

  if (d === 0) return [variant.outline, 255]

  const useHorizontalEdge = dy <= dx
  const isLightSide = useHorizontalEdge ? y < SIZE / 2 : x < SIZE / 2
  const ramp = isLightSide ? variant.bevelLight : variant.bevelDark

  if (d === 1) return [ramp[0], 255]
  if (d === 2) return [ramp[1], 255]
  if (d === 3) return [variant.face, 255]
  return [variant.fill, 255]
}

/** 生成 SIZE*SIZE 的灰度+alpha 原始像素缓冲 (每像素 2 字节)。 */
function renderVariant(variant) {
  const pixels = Buffer.alloc(SIZE * SIZE * 2)
  for (let y = 0; y < SIZE; y += 1) {
    for (let x = 0; x < SIZE; x += 1) {
      const [gray, alpha] = shade(variant, x, y)
      const at = (y * SIZE + x) * 2
      pixels[at] = gray
      pixels[at + 1] = alpha
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

  // 每行统一 filter 0 (None): 图案是大片同值平面, 预测滤波省不下体积, 而固定 0 让解码校验可以逐字节直比。
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

function grayAt(pixels, x, y) {
  return pixels[(y * SIZE + x) * 2]
}

function alphaAt(pixels, x, y) {
  return pixels[(y * SIZE + x) * 2 + 1]
}

/**
 * 资产级不变量。这些不是单元测试的替代品, 而是 9-slice 能否正常拉伸的前置条件:
 * 边条一旦沿延展轴不均匀, border-image-repeat 的 round/repeat/stretch 三种模式会给出三种结果,
 * 占位资产阶段必须先把这个变量消掉。
 */
function assertInvariants(name, pixels) {
  const fail = (msg) => {
    throw new Error(`[${name}] ${msg}`)
  }

  const CORNERS = [
    [0, 0],
    [SIZE - 1, 0],
    [0, SIZE - 1],
    [SIZE - 1, SIZE - 1],
  ]
  const isCorner = (x, y) => CORNERS.some(([cx, cy]) => cx === x && cy === y)

  for (let y = 0; y < SIZE; y += 1) {
    for (let x = 0; x < SIZE; x += 1) {
      const a = alphaAt(pixels, x, y)
      // alpha 只允许 0 / 255: 出现中间值即意味着抗锯齿混入 (规格第二章硬红线)。
      if (a !== 0 && a !== 255) fail(`(${x},${y}) alpha=${a}, 存在抗锯齿半透明`)
      // 挖空只有四角那 4 个像素; 别处出现全透明就是边框上破了个洞, 而 border-image 画出来只是"少一块", 不报错。
      if (a === 0 && !isCorner(x, y)) fail(`(${x},${y}) 非四角却 alpha=0, 边框出现透明缺口`)
    }
  }

  /*
   * 均匀性与中心单值性一律按 [灰度, alpha] 整对比较, 不能只比灰度: 只比灰度时把边条上某个像素改成
   * "同灰度但 alpha=0" 能全身而退 —— alpha 二值检查看它合法, 边条灰度检查看它没变, 于是自检 PASS,
   * 而那一列在拉伸后会变成一条贯穿边框的透明缝。
   */
  const sampleAt = (x, y) => `${grayAt(pixels, x, y)}/${alphaAt(pixels, x, y)}`

  // 上/下边条沿 x 轴均匀。
  for (let y = 0; y < SLICE; y += 1) {
    for (const row of [y, SIZE - 1 - y]) {
      const ref = sampleAt(SLICE, row)
      for (let x = SLICE; x < SIZE - SLICE; x += 1) {
        if (sampleAt(x, row) !== ref) fail(`横边条第 ${row} 行沿 x 不均匀 (x=${x})`)
      }
    }
  }

  // 左/右边条沿 y 轴均匀。
  for (let x = 0; x < SLICE; x += 1) {
    for (const col of [x, SIZE - 1 - x]) {
      const ref = sampleAt(col, SLICE)
      for (let y = SLICE; y < SIZE - SLICE; y += 1) {
        if (sampleAt(col, y) !== ref) fail(`竖边条第 ${col} 列沿 y 不均匀 (y=${y})`)
      }
    }
  }

  // 中心块必须单一值: fill 区域会被双轴拉伸, 任何图案都会在大尺寸容器上被抹成条带。
  const center = sampleAt(SLICE, SLICE)
  for (let y = SLICE; y < SIZE - SLICE; y += 1) {
    for (let x = SLICE; x < SIZE - SLICE; x += 1) {
      if (sampleAt(x, y) !== center) fail(`中心块非单一值 (${x},${y})`)
    }
  }

  // 四角单像素挖空, 形成阶梯状硬边圆角。
  for (const [x, y] of CORNERS) {
    if (alphaAt(pixels, x, y) !== 0) fail(`角像素 (${x},${y}) 未挖空`)
  }
}

/**
 * 外凸与内凹的明暗必须严格相反 —— 这是三张图不能靠换色合并成一张的唯一依据, 必须被机器盯住。
 *
 * 四条边全验, 不只验上下。shade() 里横边取亮/暗由 `y < SIZE/2` 决定、竖边由 `x < SIZE/2` 决定, 是两条
 * 独立分支: 只采样上下边中点时, 竖边分支写成两侧同取亮组照样全绿, 结果是"上亮下暗但左右一样亮"——
 * 立体感只剩一半, 而 README 承诺的是"左上亮、右下暗"。此外光比大小还不够, 还要保证 window 与 inset
 * 互为镜像 (同一位置的两组值对调), 否则两张图可能各自成立却不再是同一套斜面的正反面。
 */
function assertBevelPolarity(rendered) {
  const mid = SLICE + Math.floor((SIZE - 2 * SLICE) / 2)
  // 四条边各取中点, 避开四角的斜接缝 (那里由 dx/dy 平局规则决定, 不属于单边的明暗表达)。
  const edges = {
    top: (name, ring) => grayAt(rendered[name], mid, ring),
    bottom: (name, ring) => grayAt(rendered[name], mid, SIZE - 1 - ring),
    left: (name, ring) => grayAt(rendered[name], ring, mid),
    right: (name, ring) => grayAt(rendered[name], SIZE - 1 - ring, mid),
  }

  for (const ring of [1, 2]) {
    // 外凸: 左上亮、右下暗。
    for (const [lightEdge, darkEdge] of [['top', 'bottom'], ['left', 'right']]) {
      if (!(edges[lightEdge]('frame-window', ring) > edges[darkEdge]('frame-window', ring))) {
        throw new Error(
          `frame-window 第 ${ring} 圈 ${lightEdge}(${edges[lightEdge]('frame-window', ring)}) `
            + `未亮于 ${darkEdge}(${edges[darkEdge]('frame-window', ring)}), 外凸不成立`,
        )
      }
      // 内凹: 左上暗、右下亮, 与外凸严格相反。
      if (!(edges[lightEdge]('frame-inset', ring) < edges[darkEdge]('frame-inset', ring))) {
        throw new Error(
          `frame-inset 第 ${ring} 圈 ${lightEdge}(${edges[lightEdge]('frame-inset', ring)}) `
            + `未暗于 ${darkEdge}(${edges[darkEdge]('frame-inset', ring)}), 内凹不成立`,
        )
      }
    }

    // 平面板四边同值: 有任何方向性明暗就不再是"不抢层级的平面"。
    for (const edge of ['bottom', 'left', 'right']) {
      if (edges[edge]('frame-panel', ring) !== edges.top('frame-panel', ring)) {
        throw new Error(`frame-panel 第 ${ring} 圈 ${edge} 与 top 不同值, 存在方向性明暗`)
      }
    }

    // 镜像关系: window 的亮侧值 = inset 的暗侧值, 反之亦然。两张图必须是同一套斜面的正反面。
    for (const [a, b] of [['top', 'bottom'], ['left', 'right']]) {
      if (edges[a]('frame-window', ring) !== edges[b]('frame-inset', ring)
        || edges[b]('frame-window', ring) !== edges[a]('frame-inset', ring)) {
        throw new Error(`frame-window 与 frame-inset 第 ${ring} 圈在 ${a}/${b} 轴上不互为镜像`)
      }
    }
  }
}

function main() {
  const checkOnly = process.argv.includes('--check')
  // --check 承诺不写盘, 那就一个字节也不写: 无条件 mkdir 会在目录不存在时先把目录建出来再报 STALE,
  // 让"只读校验"在 CI 或别人的工作树上留下副作用。
  if (!checkOnly) {
    mkdirSync(OUT_DIR, { recursive: true })
  }

  const rendered = {}
  for (const [name, variant] of Object.entries(VARIANTS)) {
    const pixels = renderVariant(variant)
    assertInvariants(name, pixels)
    rendered[name] = pixels
  }
  assertBevelPolarity(rendered)

  let dirty = false
  for (const [name, variant] of Object.entries(VARIANTS)) {
    const png = encodePng(rendered[name])

    const { header, pixels: roundTrip } = decodePng(png)
    if (header.width !== SIZE || header.height !== SIZE) {
      throw new Error(`${name}: IHDR 尺寸 ${header.width}x${header.height} 与规格 ${SIZE}x${SIZE} 不符`)
    }
    if (header.bitDepth !== 8 || header.colorType !== COLOR_TYPE_GRAY_ALPHA) {
      throw new Error(`${name}: 应为 8 位灰度+alpha, 实为 depth=${header.bitDepth} colorType=${header.colorType}`)
    }
    if (!roundTrip.equals(rendered[name])) throw new Error(`${name}: PNG 往返解码与源像素不一致`)

    const file = join(OUT_DIR, `${name}.png`)
    const same = existsSync(file) && readFileSync(file).equals(png)
    if (!same) {
      dirty = true
      if (!checkOnly) writeFileSync(file, png)
    }
    const state = same ? 'UNCHANGED' : checkOnly ? 'STALE' : 'WRITTEN'
    process.stdout.write(
      `${state.padEnd(9)} ${name}.png  ${SIZE}x${SIZE} slice=${SLICE}  ${png.length}B  ${variant.title}\n`,
    )
  }

  if (checkOnly && dirty) {
    process.stdout.write('FAIL 现有资产与生成结果不一致, 请重跑 node tools/gen-nineslice.mjs\n')
    process.exitCode = 1
    return
  }
  process.stdout.write(`PASS 3 张占位资产校验通过 (输出目录 ${OUT_DIR})\n`)
}

main()
