import { useEffect, useState } from 'react'
import { call } from './bridge'

/**
 * 物品显示名解析。真源链条: 服务端只回 descriptionId (翻译键, 如 item.minecraft.diamond) ——
 * 专用服务器不加载 lang, 中文名在服务端根本解不出来; 而玩家客户端已加载全部模组的 lang,
 * 故翻译键到显示名这一步由客户端本地动作 client.i18n 走 MC 的 I18n.get 完成 (WebUiBridge.handleClientLocal)。
 *
 * market.list / market.mine / player.inventory / admin.listItems 每行都带 descriptionId,
 * 一屏几十行意味着几十个键 —— 因此本模块的两个核心职责是批量合并与缓存, 而不是"发一次请求"。
 */

/** 客户端本地动作: {keys:[翻译键]} -> {names:{键:显示名}}, 不走服务端往返 (payload/result 定型见 contracts.ts)。 */
const ACTION_I18N = 'client.i18n'

/**
 * 已解析的显示名, 永久缓存不设失效。
 *
 * 已知代价 (不要按"反正会重载"来理解这个缓存): WebUiClient 的浏览器是常驻的, 且目标页与已加载页相同时
 * 刻意跳过 loadURL 以保住 SPA 状态, 因此关界面再打开并不会重新求值本模块 —— 玩家在游戏里换语言或重载
 * 资源包之后, 这里仍会返回上一语言的名字, 直到页面真被重新加载。要正确处理它需要宿主给出一个语言/资源
 * 代次信号 (现在没有这个通道), 属跨端接线, 不在前端单方面发明。
 */
const nameCache = new Map<string, string>()

interface PendingBatch {
  readonly keys: Set<string>
  readonly done: Promise<void>
  readonly resolve: () => void
  readonly reject: (reason: unknown) => void
}

/** 当前正在攒键、尚未发出的那一批。发出瞬间置空, 之后到达的键进下一批。 */
let openBatch: PendingBatch | null = null

/** 键 -> 正在解析它的那批的完成 promise。跨 tick 的重复请求复用在途批次, 同一物品不会被查两遍。 */
const batchByKey = new Map<string, Promise<void>>()

function openNewBatch(): PendingBatch {
  let resolve!: () => void
  let reject!: (reason: unknown) => void
  const done = new Promise<void>((batchResolve, batchReject) => {
    resolve = batchResolve
    reject = batchReject
  })
  const batch: PendingBatch = { keys: new Set<string>(), done, resolve, reject }
  openBatch = batch
  /*
   * 微任务而非 setTimeout: 一次 React 渲染提交后, 同批 useItemNames 的 effect 全在同一个宏任务里跑完,
   * 微任务恰好在它们之后、下一帧之前触发 —— 攒满整屏的键只发一次 query, 又不引入任何可感知延迟。
   */
  queueMicrotask(() => {
    void sendBatch(batch)
  })
  return batch
}

async function sendBatch(batch: PendingBatch): Promise<void> {
  if (openBatch === batch) {
    openBatch = null
  }
  const keys = [...batch.keys]
  try {
    const response = await call(ACTION_I18N, { keys })
    // 契约层只做编译期定型, 宿主实际回什么不受类型系统约束; names 缺席即契约破裂, 让它冒泡而不是当空对象处理。
    const names: unknown = response.names
    if (typeof names !== 'object' || names === null) {
      throw new Error(`client.i18n 响应缺少 names 字段: ${JSON.stringify(response)}`)
    }
    const resolvedNames = names as Record<string, unknown>
    for (const key of keys) {
      const name = resolvedNames[key]
      /*
       * 宿主对每个请求键都会写一条 (I18n.get 缺翻译时回退为键本身, 从不返回 null, 见 WebUiBridge.handleClientLocal)。
       * 因此"键不在回执里"或"值不是字符串"都不是未翻译, 是契约破裂 —— 若把它当未翻译静默退回键,
       * 界面上看到的和真的缺 lang 条目完全一样, 这条链就再也没有可观测的失败信号了。
       */
      if (typeof name !== 'string') {
        throw new Error(
          `client.i18n 回执缺少键 ${key} 或其值不是字符串 (实为 ${typeof name}): ${JSON.stringify(response)}`,
        )
      }
      /*
       * 缺翻译时 MC 的 I18n.get 原样回退为键本身。这种结果不写缓存: 它表示"这个键此刻没有对应的
       * lang 条目", 而不是"这个物品就叫这个名字", 钉进缓存会让资源包重载后仍然显示键。
       */
      if (name !== '' && name !== key) {
        nameCache.set(key, name)
      }
    }
    batch.resolve()
  } catch (error) {
    /*
     * 一律 reject, 包括"桥未注入"。这里曾对 BRIDGE_UNAVAILABLE_CODE 开过一个豁免 (静默退回翻译键),
     * 但它在 dev 下根本走不到 —— bridge.call 在桥缺失时已经转去 bridge.mock —— 于是那条豁免实际只在
     * **生产构建里桥真的断了**时生效, 效果恰好是把唯一一次接线断裂的报错吞掉。降级展示归 useItemNames
     * 那层做 (它保留完整堆栈再退回键), 传输层不替上层决定"这个错误不要紧"。
     */
    batch.reject(error)
  } finally {
    for (const key of keys) {
      batchByKey.delete(key)
    }
  }
}

function enqueue(key: string): Promise<void> {
  const inFlight = batchByKey.get(key)
  if (inFlight !== undefined) {
    return inFlight
  }
  const batch = openBatch === null ? openNewBatch() : openBatch
  batch.keys.add(key)
  batchByKey.set(key, batch.done)
  return batch.done
}

/** 从缓存组装结果; 未解析出显示名的键退回键本身, 保证调用方拿到的映射对每个入参键都有值。 */
function readNames(descriptionIds: readonly string[]): Record<string, string> {
  const names: Record<string, string> = {}
  for (const descriptionId of descriptionIds) {
    const cached = nameCache.get(descriptionId)
    names[descriptionId] = cached === undefined ? descriptionId : cached
  }
  return names
}

/**
 * 解析一组翻译键的显示名。命中缓存的键不进请求; 未命中的键合并进同一批 client.i18n。
 * 任何失败 (桥不可用 / 桥内错误 / 响应破裂) 一律以 reject 冒泡, 本层不做展示降级。
 */
export async function resolveItemNames(
  descriptionIds: readonly string[],
): Promise<Record<string, string>> {
  const missing = descriptionIds.filter(
    (descriptionId) => descriptionId !== '' && !nameCache.has(descriptionId),
  )
  if (missing.length > 0) {
    await Promise.all(missing.map((descriptionId) => enqueue(descriptionId)))
  }
  return readNames(descriptionIds)
}

/**
 * 解析一组翻译键并随解析结果重渲染, 返回 {翻译键: 显示名}。
 *
 * 调用方每次渲染都会传入新数组, 因此依赖取的是内容签名而不是数组引用 —— 用引用会让 effect 每帧重跑。
 *
 * 签名走 JSON 而不是 join(分隔符): effect 要从签名把键列表反解回来, 而 join 不可逆 —— `[]` 与 `['']`
 * join 出的都是空串, 于是 useItemNames(['']) 会被当成空列表, 返回的映射里少掉调用方传进来的那个键。
 * JSON 往返无歧义, 也不必再假设翻译键中不含某个分隔字符。
 */
export function useItemNames(descriptionIds: readonly string[]): Record<string, string> {
  const signature = JSON.stringify(descriptionIds)
  const [names, setNames] = useState<Record<string, string>>(() => readNames(descriptionIds))

  useEffect(() => {
    const keys = JSON.parse(signature) as string[]
    let cancelled = false
    resolveItemNames(keys)
      .then((resolved) => {
        if (!cancelled) {
          setNames(resolved)
        }
      })
      .catch((error: unknown) => {
        /*
         * 这里是显示名这条链的最外层: 再往上就是业务视图本身。失败时把错误完整留在控制台
         * (保留堆栈, 不降级成一行文案) 并退回翻译键, 让"名字没解出来"这件事可见, 同时不连累整屏列表。
         */
        console.error('[webui-i18n] 显示名解析失败, 退回翻译键:', error)
        if (!cancelled) {
          setNames(readNames(keys))
        }
      })
    return () => {
      cancelled = true
    }
  }, [signature])

  return names
}

// ============================================================
// NBT 变体件的显示名 (nameParts)
// ============================================================

/**
 * 显示名的一个片段: 要么是待解析的翻译键 (k), 要么是原样输出的字面量 (t)。
 *
 * 服务端为什么发结构而不发字符串: 专用服务端不加载 mod 的 lang 文件, 在那边解出来的是原始翻译键。
 * 而 getName(ItemStack) 拼出来的名字往往**不是一个键**, 是若干键与字面量的序列
 * (枪匠零件是 "平台键 + 部位键 + 字面空格 + 品质键")。真源见 Java 侧 WebUiItemJson。
 */
export interface ItemNamePart {
  /** 翻译键。与 t 互斥。 */
  k?: string
  /** 字面量。与 k 互斥。 */
  t?: string
}

/** 从若干条 nameParts 里抽出全部翻译键 (去重), 供 useItemNames 一次批量解析。 */
export function collectNamePartKeys(
  partsList: readonly (readonly ItemNamePart[] | undefined)[],
): string[] {
  const keys = new Set<string>()
  for (const parts of partsList) {
    if (parts === undefined) {
      continue
    }
    for (const part of parts) {
      if (part.k !== undefined) {
        keys.add(part.k)
      }
    }
  }
  return [...keys]
}

/**
 * 把 nameParts 拼成显示名。names 由 useItemNames 解出。
 *
 * 键在 names 里缺席时原样输出该键本身, 与 useItemNames 的降级同纪律 —— 让"名字没解出来"可见,
 * 而不是悄悄少掉一段, 变成一个读起来像正常、但缺了品质档的短名字。
 */
export function formatNameParts(
  parts: readonly ItemNamePart[],
  names: Record<string, string>,
): string {
  return parts
    .map((part) => {
      if (part.k !== undefined) {
        return names[part.k] ?? part.k
      }
      return part.t ?? ''
    })
    .join('')
}

/** 带显示名的物品条目。凡是 Java 侧过了 WebUiItemJson 的回执条目都满足这个形状。 */
export interface NamedItemLike {
  descriptionId: string
  nameParts?: ItemNamePart[]
}

/**
 * 一批物品的显示名。返回一个取名函数, 对变体件与普通物品一视同仁。
 *
 * 为什么不让各页自己判 `nameParts === undefined ? names[descriptionId] : formatNameParts(...)`:
 * 这个三元式要在 7 个页面里各写一遍, 而漏写的表现是"某一页里 195 种枪匠零件全叫枪匠零件"——
 * 一个只在特定物品上才显形、且看起来完全像正常数据的错误。收在这里, 各页改一行导入即可。
 *
 * 两类键合并成一次批量请求: 变体件的 nameParts 键与普通物品的 descriptionId 本来就要一起解,
 * 分两次 useItemNames 会让同一屏发两轮 client.i18n 往返。
 */
export function useItemDisplayNames<T extends NamedItemLike>(
  items: readonly T[],
): (item: T) => string {
  const keys = [
    ...items.map((item) => item.descriptionId),
    ...collectNamePartKeys(items.map((item) => item.nameParts)),
  ]
  const names = useItemNames(keys)
  return (item: T): string => {
    if (item.nameParts !== undefined && item.nameParts.length > 0) {
      return formatNameParts(item.nameParts, names)
    }
    return names[item.descriptionId] ?? item.descriptionId
  }
}
