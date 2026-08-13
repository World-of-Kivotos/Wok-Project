import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  statSync,
  writeFileSync,
} from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig, type Plugin } from 'vite'

const here = dirname(fileURLToPath(import.meta.url))

/** mod 贴图真源目录。前端与 Java 同一 monorepo, 贴图唯一真源就是它, 复制副本进 git 必然出现双源漂移。 */
const MOD_TEXTURES = resolve(here, '../src/main/resources/assets/miningdim/textures')

/** mod 物品模型目录。变体贴图映射表由这里的 overrides 生成, 见 buildVariantMap。 */
const MOD_ITEM_MODELS = resolve(here, '../src/main/resources/assets/miningdim/models/item')

/** 变体映射表的挂载路径。ItemIcon 启动时取一次, 常驻内存。 */
const VARIANT_MAP_PATH = 'variants.json'

/**
 * 只有这两个子目录会被前端当作物品图标取用 (ItemIcon 的回退链是 item/<id>.png -> block/<id>.png)。
 * gui/ 是绑定固定槽位 blit 的整屏底图, entity/ 与 mob_effect/ 是渲染用材质, 三者对 Web UI 无意义,
 * 全量挂载只会让 dist 多背十几 MB。
 */
const ICON_DIRS = ['item', 'block'] as const

/** 挂载前缀。刻意不用站点根: 根留给 vite 真正的 public/(9-slice 边框等前端自有资产), 二者不得互相顶掉。 */
const MOD_MOUNT = '/mc/'

function walkFiles(root: string): string[] {
  const out: string[] = []
  const stack = [root]
  while (stack.length > 0) {
    const dir = stack.pop()
    if (dir === undefined) {
      continue
    }
    for (const entry of readdirSync(dir)) {
      const full = join(dir, entry)
      if (statSync(full).isDirectory()) {
        stack.push(full)
      } else if (entry.endsWith('.png')) {
        out.push(full)
      }
    }
  }
  return out
}

/**
 * 生成"物品 id + CustomModelData -> 贴图路径"的映射表。
 *
 * 要解决的事: 本 mod 有一大类**靠 NBT 区分变体**的物品 —— 枪匠零件的 195 种变体全部注册在同一个
 * `miningdim:gunsmith_part` 之下, 平台/部位/品质由 NBT 决定, 贴图由 CustomModelData 经模型 overrides 选。
 * 前端的 ItemIcon 只按 itemId 取图, 于是这 195 种在市场里会画成同一张图标。
 *
 * 为什么在构建期生成而不是手写一张表: overrides 表就是这件事的**唯一真源**, 手写副本必然漂移
 * (加一个变体, 美术改了模型, 表就错了且不报错 —— 症状是"某个品质的图标不对", 极难发现)。
 * 这里逐个模型解析到 textures.layer0, 再核对 PNG 真的在磁盘上, 只把两头都对得上的写进表 ——
 * 于是运行期 ItemIcon 可以直接信任表里的路径, 不必再走一遍探测回退链。
 *
 * 只扫 overrides 是刻意的: 没有 overrides 的物品是"一 id 一贴图", 走既有的同名直取路径即可,
 * 把它们也塞进表里只会让表大出两个数量级而一条都用不上。
 *
 * **已知覆盖不到的一类 (真实存在, 不是理论风险)**: 塔罗牌 (miningdim:tarot_card) 同样是 NBT 变体件
 * (220 个 overrides / 56 张牌面), 但它的谓词是自定义 ItemProperties
 * (miningdim:tarot_card / tarot_quality / tarot_orientation 三个浮点值) 而不是 custom_model_data,
 * 故本函数正确地跳过了它 —— 于是塔罗牌在 Web UI 里目前仍是一张默认图。
 * 要覆盖它, 服务端得先把那三个谓词值随物品发下来 (WebUiItemJson 现在只发 CustomModelData),
 * 那是另一套契约形状, 不是在这里加个分支就能了事。
 */
function buildVariantMap(): Record<string, Record<string, string>> {
  const map: Record<string, Record<string, string>> = {}
  if (!existsSync(MOD_ITEM_MODELS)) {
    return map
  }

  /** 读一个模型文件的 layer0 贴图名 (形如 miningdim:item/xxx); 不是 generated 型模型则返回 null。 */
  const layer0Of = (modelPath: string): string | null => {
    const file = join(MOD_ITEM_MODELS, `${modelPath}.json`)
    if (!existsSync(file)) {
      return null
    }
    const model = JSON.parse(readFileSync(file, 'utf8')) as {
      textures?: Record<string, string>
    }
    return model.textures?.layer0 ?? null
  }

  for (const entry of readdirSync(MOD_ITEM_MODELS)) {
    if (!entry.endsWith('.json')) {
      continue
    }
    const source = JSON.parse(readFileSync(join(MOD_ITEM_MODELS, entry), 'utf8')) as {
      overrides?: { predicate?: Record<string, number>; model?: string }[]
    }
    const overrides = source.overrides
    if (overrides === undefined || overrides.length === 0) {
      continue
    }

    const itemId = `miningdim:${entry.slice(0, -'.json'.length)}`
    const variants: Record<string, string> = {}
    for (const override of overrides) {
      const code = override.predicate?.custom_model_data
      const model = override.model
      if (code === undefined || model === undefined) {
        continue
      }
      // 模型引用形如 miningdim:item/xxx —— 只认本 mod 命名空间, 原版模型的贴图不在 /mc/ 下。
      const [namespace, ...rest] = model.split(':')
      if (namespace !== 'miningdim' || rest.length === 0) {
        continue
      }
      const layer0 = layer0Of(rest.join(':').replace(/^item\//, ''))
      if (layer0 === null) {
        continue
      }
      const texture = layer0.replace(/^miningdim:/, '')
      // 核对贴图真的在磁盘上。对不上就不写进表, 让它落回占位块 —— 表里存一条指向 404 的路径,
      // 会让 ItemIcon 白跑一次请求且拿不到任何提示。
      if (!existsSync(join(MOD_TEXTURES, `${texture}.png`))) {
        continue
      }
      variants[String(code)] = texture
    }
    if (Object.keys(variants).length > 0) {
      map[itemId] = variants
    }
  }
  return map
}

/**
 * 把 mod 的 item/ 与 block/ 贴图挂到 /mc/ 下。
 *
 * 为什么不用 publicDir 直接指过去: publicDir 全局唯一, 指向 mod 目录会让 webui/public/ 变成一个
 * "看着像 public 实际不被服务" 的假目录 —— 后来人按直觉写 URL 引用会静默 404, 而 border-image
 * 取不到图是无边框不报错, 排查成本极高。改用插件后 public/ 恢复正常语义, 且能只挑需要的子目录。
 */
function modTexturesPlugin(): Plugin {
  return {
    name: 'miningdim-mod-textures',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const url = req.url ?? ''
        if (!url.startsWith(MOD_MOUNT)) {
          next()
          return
        }
        /*
         * /mc/ 挂载点专供贴图, 任何不解析的请求都真回 404, 绝不 next() 落进 vite 的 SPA fallback。
         *
         * 这不是 ItemIcon 的正确性前提 —— 它的三层回退靠 `new Image()` 的 onerror 触发, 而 index.html
         * 当图片解码同样会 onerror, 链条照样往下走 (构建产物由 vite preview / 静态托管服务时就是 200,
         * 实测过)。
         *
         * 真正的理由是排障: fallback 会让每一次取图失败都在 Network 面板里显示成 200 + 一份 HTML,
         * 于是"贴图路径写错了"与"贴图确实不存在"看起来完全一样, 而且是绿色的。开发期把这件事说实话,
         * 比多兜一层更值钱。
         */
        const notFound = (): void => {
          res.statusCode = 404
          res.end()
        }

        // 变体映射表。dev 下每次请求重新生成: 改了模型 JSON 刷新页面就生效, 不必重启 dev server。
        if (url.startsWith(`${MOD_MOUNT}${VARIANT_MAP_PATH}`)) {
          res.setHeader('Content-Type', 'application/json')
          res.end(JSON.stringify(buildVariantMap()))
          return
        }

        // 去掉查询串并拒绝路径穿越: resolve 后必须仍落在 ICON_DIRS 之内。
        const rel = decodeURIComponent(url.slice(MOD_MOUNT.length).split('?')[0] ?? '')
        const target = resolve(MOD_TEXTURES, rel)
        const inside = ICON_DIRS.some((d) => target.startsWith(join(MOD_TEXTURES, d)))
        if (!inside || !target.endsWith('.png')) {
          notFound()
          return
        }
        try {
          if (!statSync(target).isFile()) {
            notFound()
            return
          }
          res.setHeader('Content-Type', 'image/png')
          res.end(readFileSync(target))
        } catch {
          // statSync 对不存在的路径抛错, 这是 ItemIcon 下探时的正常路径, 不是异常。
          notFound()
        }
      })
    },
    closeBundle() {
      // 构建期只复制 ICON_DIRS, 而不是整个 textures —— gui/entity/mob_effect 共 42 张对 UI 无用。
      let copied = 0
      for (const d of ICON_DIRS) {
        const root = join(MOD_TEXTURES, d)
        for (const file of walkFiles(root)) {
          const dest = join(here, 'dist', 'mc', d, relative(root, file))
          mkdirSync(dirname(dest), { recursive: true })
          copyFileSync(file, dest)
          copied += 1
        }
      }
      const variants = buildVariantMap()
      writeFileSync(join(here, 'dist', 'mc', VARIANT_MAP_PATH), JSON.stringify(variants))
      const variantCount = Object.values(variants).reduce(
        (sum, table) => sum + Object.keys(table).length,
        0,
      )
      // 留两行构建期日志: 贴图数量或变体条目数突变 (有人挪了目录、模型改名) 在这里最先暴露。
      console.log(`[miningdim] 已复制 ${copied} 张 mod 物品/方块贴图到 dist/mc/`)
      console.log(
        `[miningdim] 变体贴图映射表: ${Object.keys(variants).length} 个物品 / ${variantCount} 条变体`,
      )
    },
  }
}

export default defineConfig({
  // 相对基址: 产物可能被托管在站点子路径 (分发路线 A), 也可能由服务端 mod 从 jar 内 serve (路线 B),
  // 两种落点的挂载前缀不同, 绝对 /assets 路径会在子路径下 404。
  base: './',
  plugins: [react(), tailwindcss(), modTexturesPlugin()],
  // `@/` 指向 src/。Coss UI / shadcn 的 copy-paste 组件源码内部一律按此别名互相引用,
  // 不设别名的话每次拉组件都要手改一遍 import, 且下次更新组件时改动会被覆盖回去。
  resolve: {
    alias: { '@': resolve(here, 'src') },
  },
  server: {
    port: 5173,
    // 端口被占时直接失败而非顺延: Java 侧 webui.url 是精确匹配的授权源 (WebUiBridge.setAllowedPage),
    // 静默换到 5174 的表现是"页面能开但所有 cefQuery 被拒", 极难排查。
    strictPort: true,
    // MCEF 客户端可能不在本机, 监听全部网卡以便局域网访问。
    host: true,
  },
  preview: {
    port: 5173,
    strictPort: true,
    host: true,
  },
})
