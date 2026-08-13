import type { CSSProperties, ReactElement } from 'react'
import { useEffect, useState } from 'react'

/**
 * 物品图标。来源分三层, 严格按 PixelUI 规格第八章:
 *   1. 原版物品 (minecraft:*) —— 第三方镜像站的原版资产 CDN, 回退链 item -> block -> 模型 parent 链;
 *   2. 本 mod 物品 (miningdim:*) —— 贴图与前端同 monorepo, 由 vite publicDir 直接映射为静态资源;
 *   3. 回退 —— 像素占位块。**不是矢量图标**: 规格明确要求改掉旧实现回退到 lucide 的做法。
 *
 * 第三方 mod (TACZ / Champions / farmersdelight ...) 的贴图上述两处都没有, 一律落第三层。
 * 这是接线清单 A13 记录的已知缺口, 取图方案是决策 J1 (待定), 本组件不自行发明。
 *
 * 全链路 image-rendering: pixelated —— 16x16 源图按整数倍放大, 任何平滑插值都会直接毁掉像素观感。
 */

const VANILLA_NAMESPACE = 'minecraft'
const MOD_NAMESPACE = 'miningdim'

/** 原版资产镜像站。1.20.1 是本项目锁定的游戏版本, 版本号写死以免取到与服务端不同版本的贴图。 */
const VANILLA_ASSET_ROOT = 'https://assets.mcasset.cloud/1.20.1/assets/minecraft/'

/**
 * 本 mod 贴图的挂载前缀。由 vite.config.ts 的 miningdim-mod-textures 插件把 mod resources 下的
 * item/ 与 block/ 挂到 /mc/ (dev 走中间件, build 期复制进 dist/mc/), 故 item/<id>.png 拼上前缀即可。
 *
 * 刻意不挂站点根: 根留给 vite 真正的 public/ (9-slice 边框等前端自有资产), 二者不得互相顶掉。
 * 用 BASE_URL 拼而不是写死 "/mc/": 构建产物用相对基址, 托管在站点子路径时绝对路径会 404。
 */
const MOD_TEXTURE_ROOT = `${import.meta.env.BASE_URL}mc/`

/**
 * 模型 parent 链的最大跳数。原版最深的链 (方块模型 -> cube_all -> block -> builtin) 也在 4 跳内,
 * 上限存在的意义是防御资源包里的环, 而不是覆盖更深的合法链。
 */
const MAX_MODEL_HOPS = 4

/** 贴图变量取用顺序: 物品模型出 layer0, 方块模型多为 all / side, particle 只作最后兜底。 */
const TEXTURE_SLOTS = ['layer0', 'all', 'texture', 'side', 'north', 'particle']

/** 渲染器内建模型: 自身不带任何贴图, 走到这里说明这条链取不出图, 继续往上是白跑请求。 */
const BUILTIN_PARENTS = new Set(['item/generated', 'item/handheld', 'builtin/generated', 'builtin/entity'])

interface ModelJson {
  parent?: string
  textures?: Record<string, string>
}

/** itemId -> 贴图 URL; null 表示三层都没取到, 该物品固定走占位块。 */
const textureUrlCache = new Map<string, string | null>()

/** 在途解析。列表滚动时同一 itemId 会被多个单元格同时挂载, 没有它就会把整条回退链重跑 N 遍。 */
const pendingResolves = new Map<string, Promise<string | null>>()

interface ResourceRef {
  namespace: string
  path: string
}

/** 拆 "ns:path"。无 namespace 时按原版处理, 与 MC 的 ResourceLocation 默认行为一致。 */
function splitRef(reference: string): ResourceRef {
  const separator = reference.indexOf(':')
  if (separator < 0) {
    return { namespace: VANILLA_NAMESPACE, path: reference }
  }
  return { namespace: reference.slice(0, separator), path: reference.slice(separator + 1) }
}

/**
 * 探测一个 URL 是否存在可解码的图像。用 Image 而不是 fetch: 图片加载不受 CORS 约束
 * (镜像站是否发 CORS 头不受本项目控制), 且探测成功后浏览器已把它放进缓存, 真正渲染时是零延迟命中。
 */
function probeImage(url: string): Promise<boolean> {
  return new Promise<boolean>((resolve) => {
    const probe = new Image()
    probe.onload = () => {
      resolve(true)
    }
    probe.onerror = () => {
      resolve(false)
    }
    probe.src = url
  })
}

/** 贴图引用 ("minecraft:item/diamond" / "item/diamond") 转 CDN URL; 非原版命名空间在这条链上取不到。 */
function textureUrlFromRef(reference: string): string | null {
  const { namespace, path } = splitRef(reference)
  if (namespace !== VANILLA_NAMESPACE) {
    return null
  }
  return `${VANILLA_ASSET_ROOT}textures/${path}.png`
}

async function fetchVanillaModel(reference: string): Promise<ModelJson | null> {
  const { namespace, path } = splitRef(reference)
  if (namespace !== VANILLA_NAMESPACE) {
    return null
  }
  try {
    const response = await fetch(`${VANILLA_ASSET_ROOT}models/${path}.json`)
    if (!response.ok) {
      return null
    }
    const model = (await response.json()) as ModelJson
    return typeof model === 'object' && model !== null ? model : null
  } catch (networkError) {
    /*
     * 这不是吞异常: 模型解析本身就是回退链的一级, 镜像站不可达或未发 CORS 头时整条 CDN 链失效,
     * 正确行为是继续落到第三层占位块。真正的失败信号是"图标变占位块", 在界面上直接可见。
     */
    console.debug('[item-icon] 模型请求失败, 回退链继续下沉:', reference, networkError)
    return null
  }
}

function pickTextureRef(textures: Record<string, string> | undefined): string | null {
  if (textures === undefined) {
    return null
  }
  for (const slot of TEXTURE_SLOTS) {
    const reference = textures[slot]
    // "#" 开头是模型内变量引用 (值由子模型填), 不是贴图路径。
    if (typeof reference === 'string' && reference !== '' && !reference.startsWith('#')) {
      return reference
    }
  }
  for (const reference of Object.values(textures)) {
    if (typeof reference === 'string' && reference !== '' && !reference.startsWith('#')) {
      return reference
    }
  }
  return null
}

/** 顺 parent 链找第一个可用的 layer0/贴图变量。原版里名字与贴图不同名的物品 (工具、方块形物品) 靠这级救回。 */
async function resolveByModelChain(startReference: string): Promise<string | null> {
  let reference = startReference
  for (let hop = 0; hop < MAX_MODEL_HOPS; hop += 1) {
    const model = await fetchVanillaModel(reference)
    if (model === null) {
      return null
    }
    const textureRef = pickTextureRef(model.textures)
    if (textureRef !== null) {
      const url = textureUrlFromRef(textureRef)
      if (url !== null && (await probeImage(url))) {
        return url
      }
      return null
    }
    const parent = model.parent
    if (parent === undefined || BUILTIN_PARENTS.has(splitRef(parent).path)) {
      return null
    }
    reference = parent
  }
  return null
}

async function resolveTexture(itemId: string): Promise<string | null> {
  const { namespace, path } = splitRef(itemId)

  if (namespace === MOD_NAMESPACE) {
    // 第二层: 本 mod 贴图名与注册名同名, 直取 item/ 再试 block/; 取不到即占位块 (mod 模型未映射为静态资源)。
    for (const url of [`${MOD_TEXTURE_ROOT}item/${path}.png`, `${MOD_TEXTURE_ROOT}block/${path}.png`]) {
      if (await probeImage(url)) {
        return url
      }
    }
    return null
  }

  if (namespace !== VANILLA_NAMESPACE) {
    // 第三方 mod: 贴图既不在本仓库也不在原版镜像站, 直接落占位块 (决策 J1 待定前不臆造取图路径)。
    return null
  }

  // 第一层: 先直猜同名贴图 (绝大多数原版物品命中), 未命中再花一次 JSON 往返走模型解析。
  for (const url of [
    `${VANILLA_ASSET_ROOT}textures/item/${path}.png`,
    `${VANILLA_ASSET_ROOT}textures/block/${path}.png`,
  ]) {
    if (await probeImage(url)) {
      return url
    }
  }
  return await resolveByModelChain(`${VANILLA_NAMESPACE}:item/${path}`)
}

function readCachedTexture(itemId: string): string | null {
  const cached = textureUrlCache.get(itemId)
  return cached === undefined ? null : cached
}

function resolveItemTexture(itemId: string): Promise<string | null> {
  const cached = textureUrlCache.get(itemId)
  if (cached !== undefined) {
    return Promise.resolve(cached)
  }
  const inFlight = pendingResolves.get(itemId)
  if (inFlight !== undefined) {
    return inFlight
  }
  const task = resolveTexture(itemId)
    .then((url) => {
      textureUrlCache.set(itemId, url)
      return url
    })
    .finally(() => {
      pendingResolves.delete(itemId)
    })
  pendingResolves.set(itemId, task)
  return task
}

/**
 * 放大倍率 k: 图标边长 = 16 个源像素 x k, 即 16k 个逻辑像素格。
 *
 * 与 PixelIcon 的 PixelIconScale 共用同一套档位词汇 (conventions.md 第二节 2.1): 两者渲染的都是
 * 16x16 源图, 各造一套档位的直接后果是同一排里的物品图标与功能图标像素密度对不上, 一眼可见。
 * 类型不从 PixelIcon import 而是各自声明: 二者的资产管线与降级路径完全无关, 为一个三元 union
 * 建立跨文件依赖, 换来的是改动一处波及另一处; 但**取值必须同步**, 改档位时两个文件一起改。
 *
 * 只能按 16 的整数倍取格数。早先那档"8 格"是 0.5 倍源图密度 —— --px 为偶数时侥幸落回整数 CSS 像素,
 * 而 --px 的真实取值要到真客户端标定 (规格第十二章仍是 PENDING), 一旦是奇数就成了 0.5 x 奇数的半像素,
 * 症状是边缘糊一圈半透明像素且不报错 (硬红线第 4 条), 故该档删除。
 */
export type ItemIconScale = 1 | 2 | 3

/**
 * 三档实际边长: 32 / 48 / 64 CSS px。
 *
 * 全是 16 的整数倍 —— 源贴图是 16x16, 非整数倍放大即便开了 pixelated 也会出现宽窄不一的像素列
 * (2.5 倍下有一半像素占 2 格、一半占 3 格), 在 MC 物品图标这种低分辨率资产上一眼可见。
 *
 * Tailwind 扫源码文本生成类, 拼接出来的类名不会被生成, 故各档必须是完整字面量。
 */
const SCALE_CLASS: Record<ItemIconScale, string> = {
  1: 'block size-8',
  2: 'block size-12',
  3: 'block size-16',
}

/*
 * 第三层占位块的棋盘格。取自 MC missing texture 的观感, 但用中性色板而非原版品红 ——
 * J1 未定之前所有第三方 mod 物品都会落在这里, 满屏品红会把"待接线"读成"报错"。
 * 用硬停 conic 渐变而不是位图: 零资产、边界是轴对齐硬边, 不引入任何抗锯齿弧线。
 *
 * 两支色都取**结构类** token (分隔线 + 次级面), 而不是 --muted-foreground: 那是前景文字的次级色,
 * 拿它当填充块会让"没图的物品"比有图的物品更抢眼, 也违反了语义 token 各司其职的前提。
 */
const CHECKER_IMAGE = 'repeating-conic-gradient(var(--border) 0% 25%, var(--muted) 0% 50%)'

/**
 * 格子边长恒为图标边长的四分之一 (即 4x4 棋盘), 故须随 scale 同倍放大:
 * 写死成常数会让档位越大格子越密, 占位块在不同尺寸下看着像两种东西。
 */
function checkerStyle(tilePx: number): CSSProperties {
  const tile = `${String(tilePx)}px`
  return {
    backgroundImage: CHECKER_IMAGE,
    backgroundSize: `${tile} ${tile}`,
    imageRendering: 'pixelated',
  }
}

const PLACEHOLDER_STYLE: Record<ItemIconScale, CSSProperties> = {
  1: checkerStyle(8),
  2: checkerStyle(12),
  3: checkerStyle(16),
}

/** 全局 html 规则已设 pixelated 且该属性可继承; 此处再显式写一次, 使本组件挂到任何容器下都不依赖外部继承。 */
const IMAGE_STYLE: CSSProperties = {
  imageRendering: 'pixelated',
}

export interface ItemIconProps {
  /** 形如 "minecraft:diamond" / "miningdim:casing"; 省略 namespace 时按原版处理。 */
  itemId: string
  /**
   * 无障碍名。显示名请由调用方经 useItemNames 解出后传入, 本组件不代发 i18n 请求。
   * 缺省回退为 itemId 本身: 图标脱离文字时仍需要一个可读且唯一的名字, 而空名字会让读屏直接跳过该元素。
   *
   * 叫 label 而不是 alt, 是因为组件库内无障碍名 prop 统一为 label (conventions.md 第九节第 8 条) ——
   * 与 PixelIcon.label 对齐, 免得同一屏上两种图标的同一件事有两个名字。
   */
  label?: string
  scale?: ItemIconScale
}

interface ResolvedTexture {
  readonly itemId: string
  readonly url: string | null
}

export function ItemIcon({ itemId, label, scale = 1 }: ItemIconProps): ReactElement {
  const [resolved, setResolved] = useState<ResolvedTexture>(() => ({
    itemId,
    url: readCachedTexture(itemId),
  }))

  useEffect(() => {
    let cancelled = false
    // 换 itemId 时先落到新 id 的缓存值, 否则解析期间会继续显示上一个物品的贴图。
    setResolved({ itemId, url: readCachedTexture(itemId) })
    resolveItemTexture(itemId)
      .then((url) => {
        if (!cancelled) {
          setResolved({ itemId, url })
        }
      })
      .catch((error: unknown) => {
        console.error('[item-icon] 贴图回退链异常, 落占位块:', itemId, error)
        if (!cancelled) {
          setResolved({ itemId, url: null })
        }
      })
    return () => {
      cancelled = true
    }
  }, [itemId])

  // effect 尚未跑完的那一帧 resolved 仍指向旧 itemId, 此时直接读缓存, 避免错帧。
  const textureUrl = resolved.itemId === itemId ? resolved.url : readCachedTexture(itemId)
  const accessibleName = label === undefined ? itemId : label

  if (textureUrl === null) {
    return (
      <div
        className={SCALE_CLASS[scale]}
        style={PLACEHOLDER_STYLE[scale]}
        role="img"
        aria-label={accessibleName}
      />
    )
  }

  return (
    <img
      className={SCALE_CLASS[scale]}
      style={IMAGE_STYLE}
      src={textureUrl}
      alt={accessibleName}
      onError={() => {
        // 探测通过但正式加载失败 (缓存被逐出后镜像站抽风): 把该 id 钉成占位块, 不让破图留在界面上。
        textureUrlCache.set(itemId, null)
        setResolved({ itemId, url: null })
      }}
    />
  )
}
