/**
 * mock 面板层的唯一调用口 —— callMock(action, payload), 与 lib/bridge 的 call(action, payload) 同签名。
 *
 * 它覆盖两种 action, 且两种走完全不同的路:
 *   1. **真契约那 26 个** (lib/actions.ts 的 SERVER_ACTIONS + CLIENT_LOCAL_ACTIONS): 原样转调 call(),
 *      即 dev 下落 bridge.mock、装进游戏后落真服。本层一行业务规则都不加, 只在写操作成功后顺手把回执
 *      抄进 store.mirror, 好让别的面板 (hub 首页的余额、背包格) 立刻看见后果。
 *   2. **planned.ts 里那 43 个**: 后端还不存在, 全部由 store 的内存世界回答, 带 150-400ms 人为延迟。
 *
 * 为什么要有第 1 类的转调层 (而不是让面板直接用 call):
 * 跨面板的可见后果必须有人负责。市场页买入之后 hub 首页的余额得跟着降 —— 若各页各自 call, 没人知道
 * 该通知谁; 集中到这一层, "写操作成功 -> 刷新镜像 -> 广播" 就只有一处实现。
 *
 * 接线纪律: 每接通一条 planned action, 就把它从 PLANNED_ACTIONS 与 PlannedContractMap 删掉, 于是本文件
 * 的 isPlannedAction 判定自动把它甩进第 1 类路径 —— 换言之, 接线的最后一步不需要改 callMock 的调用点,
 * 只需要改契约表。面板代码全程不动。
 *
 * 已知偏差 (mock 阶段独有, 接线即消失, 面板不得据此推断服务端行为):
 *   1. 背包权威在 bridge.mock 内部, 外部没有写入口。因此 job.farmer.sell 只校验背包里确实有那件作物,
 *      **不扣物品**; 真服是先扣后发。演示时会看到"卖完菜作物还在", 这是 mock 的缺陷不是设计。
 *   2. 同理, planned 域的收支 (卖菜 / 买卡包 / 买戒指 / 系统商店下单) 只能记进 store.walletOverlay 叠加层,
 *      再与 bridge.mock 的真钱包合成后展示。叠加层仅在 isMockActive() 为真时计入, 生产构建下恒为 0。
 *   3. 手续费率、掉率、等级曲线一律不复刻服务端规则 —— 与 bridge.mock 头部同一条纪律: 漂移的假规则比
 *      没有规则更误导。凡是必须给个数才能画界面的地方都标了"占位比例"。
 */

import type { WebUiActionName } from '../lib/actions'
import type { WebUiContract } from '../lib/bridge'
import { SERVER_FAILURE_CODE, WebUiCallError, call, isMockActive } from '../lib/bridge'
import type { PlayerInventoryItem, WebUiWallet } from '../lib/types'
import type {
  PlannedActionName,
  PlannedBlockPos,
  PlannedContractMap,
  PlannedPayloadOf,
  PlannedResultOf,
  PlannedSealTarget,
  PlannedShopEntry,
  PlannedTarotDraw,
} from './planned'
import { PLANNED_ACTIONS } from './planned'
import type { MockWalletOverlay, MockWorld } from './store'
import {
  addWalletOverlay,
  cloneResult,
  findOtherPlayer,
  getWorld,
  mutateWorld,
  nowMs,
  requireJobProgress,
} from './store'

// ============================================================
// 契约合并 (两张表并排放, 永不合并成一张)
// ============================================================

type MockContractMap = WebUiContract & PlannedContractMap

export type MockActionName = keyof MockContractMap
export type MockPayloadOf<A extends MockActionName> = MockContractMap[A]['payload']
export type MockResultOf<A extends MockActionName> = MockContractMap[A]['result']

const PLANNED_ACTION_SET = new Set<string>(PLANNED_ACTIONS)

function isPlannedAction(action: MockActionName): action is PlannedActionName {
  return PLANNED_ACTION_SET.has(action)
}

/**
 * call 的类型擦除视图。
 *
 * 泛型 A 在派发点无法被收窄成某个具体 action, 于是没有任何写法能让 call 的实参类型与运行期的 action
 * 对上号 —— 与 bridge.mock.resolveMock 里那处 as 同源。集中成这一个具名常量而不是在每个分支各写一次
 * as, 是为了让"本文件一共有几处类型断言"这件事一眼可数。
 */
const callErased = call as unknown as (
  action: WebUiActionName,
  payload: unknown,
) => Promise<unknown>

// ============================================================
// 延迟与失败
// ============================================================

/**
 * 人为往返延迟 150-400ms 随机。
 *
 * 固定值会让 loading 态"每次都一样快", 设计上就不会去处理先后到达的竞态; 而完全没有延迟, loading 态
 * 在真机上根本没被看见过一次, 上线才发现闪一下白屏。取随机区间是为了让两者都暴露出来。
 */
function plannedLatencyMs(): number {
  return 150 + Math.floor(Math.random() * 251)
}

function sleep(ms: number): Promise<void> {
  return new Promise<void>((resolve) => {
    setTimeout(resolve, ms)
  })
}

/**
 * 业务失败。刻意复用 bridge.mock 用的同一个 WebUiCallError + SERVER_FAILURE_CODE ——
 * 面板的错误处理分支因此对两条路径完全一致, 接线时不必再改一遍 catch。
 */
function fail(action: MockActionName, message: string): WebUiCallError {
  return new WebUiCallError(action, SERVER_FAILURE_CODE, message, null)
}

function requireOp(action: MockActionName, world: MockWorld): void {
  if (!world.player.isOp) {
    throw fail(action, '该操作需要 OP 权限')
  }
}

// ============================================================
// 真域镜像 (第 1 类 action 的回执抄本)
// ============================================================

/** 写操作之后要刷哪几块镜像。key 是真 action 名, 不要往里塞 planned 名。 */
const MIRROR_AFTER_WALLET_INVENTORY = new Set<string>(['market.place', 'market.buy', 'market.cancel'])
const MIRROR_AFTER_CASE = new Set<string>(['case.open', 'case.apply'])

/** 拉一遍钱包 / 背包 / 我的挂单并写进镜像。三条并发发出, 因为它们彼此无依赖。 */
export async function refreshWalletAndInventory(): Promise<void> {
  const [wallet, inventory, mine] = await Promise.all([
    call('player.wallet', {}),
    call('player.inventory', {}),
    call('market.mine', {}),
  ])
  mutateWorld((draft) => {
    draft.mirror.wallet = wallet
    draft.mirror.inventory = inventory.items
    draft.mirror.myListings = mine.listings
    draft.mirror.refreshedAt = nowMs()
  })
}

/** 开箱相关: 只需要钱包与皮肤资产总数, 不必再拉背包。 */
export async function refreshCaseTotals(): Promise<void> {
  const state = await call('case.state', {})
  mutateWorld((draft) => {
    draft.mirror.wallet = state.wallet
    draft.mirror.caseOwnedTotal = state.ownedTotal
    draft.mirror.refreshedAt = nowMs()
  })
}

/** 首屏预热: hub 挂载时调一次, 免得每个面板各自去发现镜像是 null。 */
export async function primeRealDomainMirror(): Promise<void> {
  await Promise.all([refreshWalletAndInventory(), refreshCaseTotals()])
}

/**
 * 取钱包基线; 镜像为空时先拉一次。
 * 拉完仍为空说明 call 回了个不符契约的东西, 直接抛 —— 余额这种数字上兜一个 0 出来是最坏的处理。
 */
async function ensureWallet(action: MockActionName): Promise<WebUiWallet> {
  if (getWorld().mirror.wallet === null) {
    await refreshWalletAndInventory()
  }
  const wallet = getWorld().mirror.wallet
  if (wallet === null) {
    throw fail(action, '钱包镜像仍为空: player.wallet 没有回出可用数据')
  }
  return wallet
}

async function ensureInventory(action: MockActionName): Promise<PlayerInventoryItem[]> {
  if (getWorld().mirror.inventory === null) {
    await refreshWalletAndInventory()
  }
  const inventory = getWorld().mirror.inventory
  if (inventory === null) {
    throw fail(action, '背包镜像仍为空: player.inventory 没有回出可用数据')
  }
  return inventory
}

/** 基线 + 叠加层 = 面板该显示的余额。生产构建下叠加层恒不计入 (见 store.MockWalletOverlay)。 */
function withOverlay(base: WebUiWallet, overlay: MockWalletOverlay): WebUiWallet {
  if (!isMockActive()) {
    return { ...base }
  }
  return { credit: base.credit + overlay.credit, azure: base.azure + overlay.azure }
}

// ============================================================
// planned handler 用的小工具
// ============================================================

/**
 * mock 玩家所在坐标。真服由 sender 自带, mock 没有这个来源, 故钉一个常量 ——
 * 探测/封印/矿洞面板只关心"有没有一组坐标能画", 不关心它在哪。
 */
const MOCK_PLAYER_POS: PlannedBlockPos = { x: 128, y: 40, z: -64 }

const MINER_SCAN_COOLDOWN_MS = 30_000
const MINER_SCAN_PULSE_MS = 8_000
const AGENT_SCAN_COOLDOWN_MS = 45_000
const AGENT_SCAN_SNAPSHOT_MS = 20_000
const CARDS_PER_PACK = 3
/** 重复卡折算的碎片数。占位值, 真实兑换比在塔罗 spec 里, 不在这。 */
const DUPLICATE_FRAGMENTS = 40

/** 围绕原点铺一圈确定性的命中点: 同样的入参永远画出同一组坐标, 截图可复现。 */
function deterministicHits(count: number, radius: number): PlannedBlockPos[] {
  const hits: PlannedBlockPos[] = []
  for (let index = 0; index < count; index += 1) {
    const angle = (index / count) * Math.PI * 2
    const distance = radius * (0.35 + (index % 4) * 0.15)
    hits.push({
      x: MOCK_PLAYER_POS.x + Math.round(Math.cos(angle) * distance),
      y: MOCK_PLAYER_POS.y - (index % 9),
      z: MOCK_PLAYER_POS.z + Math.round(Math.sin(angle) * distance),
    })
  }
  return hits
}

function buildSealTargets(world: MockWorld): PlannedSealTarget[] {
  const positions = deterministicHits(3, 20)
  return world.champion.samples.map((sample, index) => {
    const pos = positions[index]
    return {
      targetNetworkId: sample.entityId,
      entityLabel: sample.displayName,
      star: sample.star,
      affixIds: [...sample.affixIds],
      pos: pos === undefined ? MOCK_PLAYER_POS : pos,
    }
  })
}

/** 系统商店比价: 同一件物品在别家店的报价 (H4 在真服是要新建的反向索引)。 */
function comparableShops(world: MockWorld, shop: PlannedShopEntry): PlannedShopEntry[] {
  return world.shops.filter(
    (candidate) => candidate.itemId === shop.itemId && candidate.shopId !== shop.shopId,
  )
}

// ============================================================
// planned handler 表
// ============================================================

/**
 * 每个 planned action 一个实现。用映射类型而不是 switch, 换来两件事:
 * 少写一条即编译失败 (键必须齐全), 且每个实现的 payload 已按自身 action 收窄, 不必逐个 as。
 */
type PlannedHandlerMap = {
  [A in PlannedActionName]: (
    payload: PlannedPayloadOf<A>,
  ) => PlannedResultOf<A> | Promise<PlannedResultOf<A>>
}

const PLANNED_HANDLERS: PlannedHandlerMap = {
  'market.feePreview': async (payload) => {
    if (payload.count <= 0 || payload.unitPrice <= 0) {
      throw fail('market.feePreview', '数量与单价都必须为正整数')
    }
    // v0 直接问真契约的 market.baseValue, 不在 mock 里另存一份基准价 —— 两份基准价必然漂移。
    const base = await call('market.baseValue', { itemId: payload.itemId })
    /*
     * 4% 是占位比例, 与 bridge.mock.mockMarketPlace 用的是同一个数, 好让预览与回执对得上。
     * 真费率按挂价对 V0 的偏离度浮动, 量级能差好几倍; 玩家看到的最终值永远以 market.place 的回执为准。
     */
    const ratio = 0.04
    return {
      listFee: Math.max(1, Math.round(payload.unitPrice * payload.count * ratio)),
      ratio,
      v0: base.v0,
      source: base.source,
    }
  },

  'market.p2pCap': () => {
    const world = getWorld()
    return {
      usedToday: world.market.p2pUsedToday,
      capPerDay: world.market.p2pCapPerDay,
      remaining: Math.max(0, world.market.p2pCapPerDay - world.market.p2pUsedToday),
      resetsAt: world.economy.today.resetsAt,
    }
  },

  'market.transactions': (payload) => {
    const world = getWorld()
    const page = Math.max(0, payload.page)
    const pageSize = Math.min(200, Math.max(1, payload.pageSize))
    const offset = page * pageSize
    return {
      transactions: cloneResult(world.market.transactions.slice(offset, offset + pageSize)),
      page,
      pageSize,
      total: world.market.transactions.length,
    }
  },

  'market.pendingPayout': () => cloneResult(getWorld().market.pendingPayout),

  'market.tradable': (payload) => {
    const rule = getWorld().market.nonTradable.find((entry) => entry.itemId === payload.itemId)
    if (rule === undefined) {
      return { itemId: payload.itemId, tradable: true, reasonCode: null, reason: null }
    }
    return {
      itemId: payload.itemId,
      tradable: false,
      reasonCode: rule.reasonCode,
      reason: rule.reason,
    }
  },

  'job.progress': () => ({ jobs: cloneResult(getWorld().jobs.progress) }),

  'job.miner.state': () => cloneResult(getWorld().jobs.miner),

  'job.miner.scan': (payload) => {
    const world = getWorld()
    const miner = world.jobs.miner
    if (miner.level < miner.scanUnlockLevel) {
      throw fail('job.miner.scan', `探测需要矿工 ${String(miner.scanUnlockLevel)} 级`)
    }
    const now = nowMs()
    if (miner.scanReadyAt > now) {
      throw fail('job.miner.scan', `探测冷却中, 还需 ${String(Math.ceil((miner.scanReadyAt - now) / 1000))} 秒`)
    }
    const ore = miner.dailyOres.find((entry) => entry.itemId === payload.oreItemId)
    if (ore === undefined) {
      throw fail('job.miner.scan', `不支持探测 ${payload.oreItemId}`)
    }
    const readyAt = now + MINER_SCAN_COOLDOWN_MS
    mutateWorld((draft) => {
      draft.jobs.miner.scanReadyAt = readyAt
    })
    return {
      oreItemId: payload.oreItemId,
      // 命中数与半径都是服务端裁决的, 前端不得放大 —— 防 X 光靠的正是"单矿种一次 + 有限半径 + 脉冲熄灭"。
      hits: deterministicHits(6, miner.scanRadius),
      radius: miner.scanRadius,
      expiresAt: now + MINER_SCAN_PULSE_MS,
      scanReadyAt: readyAt,
    }
  },

  'job.farmer.state': () => cloneResult(getWorld().jobs.farmer),

  'job.farmer.sell': async (payload) => {
    const world = getWorld()
    const farmer = world.jobs.farmer
    if (farmer.level < farmer.sellUnlockLevel) {
      throw fail('job.farmer.sell', `卖菜需要农夫 ${String(farmer.sellUnlockLevel)} 级`)
    }
    if (payload.count <= 0) {
      throw fail('job.farmer.sell', '出售数量必须为正整数')
    }
    const inventory = await ensureInventory('job.farmer.sell')
    const stack = inventory.find((item) => item.slot === payload.slot)
    if (stack === undefined) {
      throw fail('job.farmer.sell', `槽位 ${String(payload.slot)} 是空的`)
    }
    if (payload.count > stack.count) {
      throw fail('job.farmer.sell', `该槽位只有 ${String(stack.count)} 件`)
    }
    const crop = farmer.crops.find((entry) => entry.itemId === stack.itemId)
    if (crop === undefined) {
      throw fail('job.farmer.sell', '收购站不收这件物品')
    }
    const credited = crop.unitPrice * payload.count
    /*
     * 收购曲线在这里只做一件事: 让"再卖会更便宜"这个后果肉眼可见。
     * 每卖 32 件单价降 1、下限 1 —— 纯占位规则, 真曲线在农夫 spec 与 FarmerSellEngine 里, 不在这。
     * 背包不扣物 (见文件头偏差 1), 因此同一槽位可以反复卖, 演示时能一路把单价压到底。
     */
    mutateWorld((draft) => {
      const target = draft.jobs.farmer.crops.find((entry) => entry.itemId === stack.itemId)
      if (target !== undefined) {
        target.soldToday += payload.count
        target.unitPrice = Math.max(1, target.basePrice - Math.floor(target.soldToday / 32))
      }
      draft.jobs.farmer.soldToday += payload.count
      addWalletOverlay(draft, 'CREDIT', credited)
      const faucet = draft.economy.today.faucets.find((entry) => entry.faucetKey === 'farming')
      if (faucet !== undefined) {
        faucet.earnedToday += credited
      }
      draft.economy.today.totalCreditIn += credited
    })
    const updated = getWorld().jobs.farmer
    const updatedCrop = updated.crops.find((entry) => entry.itemId === stack.itemId)
    return {
      itemId: stack.itemId,
      count: payload.count,
      credited,
      soldToday: updated.soldToday,
      unitPriceAfter: updatedCrop === undefined ? crop.unitPrice : updatedCrop.unitPrice,
    }
  },

  'job.chef.state': () => cloneResult(getWorld().jobs.chef),

  'job.brewer.state': () => cloneResult(getWorld().jobs.brewer),

  'job.tarot.state': () => cloneResult(getWorld().jobs.tarot),

  'job.tarot.buyPack': async (payload) => {
    const world = getWorld()
    const tarot = world.jobs.tarot
    if (payload.count <= 0) {
      throw fail('job.tarot.buyPack', '购买数量必须为正整数')
    }
    const remaining = tarot.packDailyLimit - tarot.packsBoughtToday
    if (payload.count > remaining) {
      throw fail('job.tarot.buyPack', `今日限购剩余 ${String(remaining)} 包`)
    }
    const base = await ensureWallet('job.tarot.buyPack')
    const spentCredit = tarot.packPriceCredit * payload.count
    const available = withOverlay(base, world.walletOverlay)
    if (available.credit < spentCredit) {
      throw fail('job.tarot.buyPack', '信用点不足')
    }
    const drawn: PlannedTarotDraw[] = []
    let fragmentsGained = 0
    mutateWorld((draft) => {
      const deck = draft.jobs.tarot.deck
      for (let index = 0; index < payload.count * CARDS_PER_PACK; index += 1) {
        // 按已购包数推进的确定性抽取: 同一状态下开出的牌恒定, 但连开会一路走过整副牌, 不会永远只出同一张。
        const seed = (draft.jobs.tarot.packsBoughtToday + 1) * 7 + index * 5
        const card = deck[seed % deck.length]
        if (card === undefined) {
          throw fail('job.tarot.buyPack', 'mock 种子缺陷: 牌组为空')
        }
        const duplicate = card.owned > 0
        if (duplicate) {
          fragmentsGained += DUPLICATE_FRAGMENTS
        } else {
          card.owned = 1
        }
        drawn.push({
          cardId: card.cardId,
          displayName: card.displayName,
          quality: card.quality,
          duplicate,
        })
      }
      draft.jobs.tarot.packsBoughtToday += payload.count
      draft.jobs.tarot.fragments += fragmentsGained
      addWalletOverlay(draft, 'CREDIT', -spentCredit)
      const sink = draft.economy.today.sinks.find((entry) => entry.sinkKey === 'tarot_pack')
      if (sink !== undefined) {
        sink.spentToday += spentCredit
      }
      draft.economy.today.totalCreditOut += spentCredit
    })
    const after = getWorld().jobs.tarot
    return {
      drawn,
      spentCredit,
      fragmentsGained,
      packsBoughtToday: after.packsBoughtToday,
      packsRemainingToday: after.packDailyLimit - after.packsBoughtToday,
    }
  },

  'job.agent.state': () => cloneResult(getWorld().jobs.agent),

  'job.agent.scan': () => {
    const world = getWorld()
    const now = nowMs()
    if (world.jobs.agent.scanReadyAt > now) {
      throw fail(
        'job.agent.scan',
        `战术扫描冷却中, 还需 ${String(Math.ceil((world.jobs.agent.scanReadyAt - now) / 1000))} 秒`,
      )
    }
    const seals = buildSealTargets(world)
    const readyAt = now + AGENT_SCAN_COOLDOWN_MS
    mutateWorld((draft) => {
      draft.jobs.agent.seals = seals
      draft.jobs.agent.scanReadyAt = readyAt
    })
    return {
      seals: cloneResult(seals),
      radius: world.jobs.agent.scanRadius,
      scanReadyAt: readyAt,
      expiresAt: now + AGENT_SCAN_SNAPSHOT_MS,
    }
  },

  'job.agent.seal': (payload) => {
    const world = getWorld()
    const target = world.jobs.agent.seals.find(
      (entry) => entry.targetNetworkId === payload.targetNetworkId,
    )
    if (target === undefined) {
      throw fail('job.agent.seal', '目标不在当前扫描快照内, 请重新扫描')
    }
    if (!target.affixIds.includes(payload.affixId)) {
      throw fail('job.agent.seal', '该目标身上没有这个词条')
    }
    /*
     * 成败判定只用"星级越高越难封"这一条占位规则。真裁决是 SealOutcome 九态, 逻辑在服务端;
     * 这里绝不复刻它, 只保证成功与失败两条 UI 分支都能被走到。
     */
    const ok = target.star <= 6
    if (ok) {
      mutateWorld((draft) => {
        const entry = draft.jobs.agent.seals.find(
          (candidate) => candidate.targetNetworkId === payload.targetNetworkId,
        )
        if (entry !== undefined) {
          entry.affixIds = entry.affixIds.filter((affixId) => affixId !== payload.affixId)
        }
      })
    }
    return {
      ok,
      outcomeCode: ok ? 'SEALED' : 'RESISTED',
      message: ok ? `已封印 ${payload.affixId}` : `目标星级过高, 封印被抵抗 (${String(target.star)} 星)`,
    }
  },

  'job.munitions.state': () => cloneResult(getWorld().jobs.munitions),

  'job.blueprints': () => cloneResult(getWorld().jobs.blueprints),

  'job.engineer.state': () => cloneResult(getWorld().jobs.engineer),

  'economy.status': () => cloneResult(getWorld().economy.status),

  'economy.today': () => cloneResult(getWorld().economy.today),

  'economy.priceTable': () => cloneResult(getWorld().economy.priceTable),

  'marriage.state': () => cloneResult(getWorld().marriage),

  'marriage.buyRing': async () => {
    const world = getWorld()
    if (world.marriage.ringOwned) {
      throw fail('marriage.buyRing', '你已经有一枚婚戒了')
    }
    const base = await ensureWallet('marriage.buyRing')
    const cost = world.marriage.ringPriceCredit
    if (withOverlay(base, world.walletOverlay).credit < cost) {
      throw fail('marriage.buyRing', '信用点不足以购买婚戒')
    }
    mutateWorld((draft) => {
      draft.marriage.ringOwned = true
      addWalletOverlay(draft, 'CREDIT', -cost)
      draft.economy.today.totalCreditOut += cost
    })
    const after = getWorld()
    const updatedBase = after.mirror.wallet
    return {
      ok: true,
      costCredit: cost,
      wallet: withOverlay(updatedBase === null ? base : updatedBase, after.walletOverlay),
    }
  },

  'marriage.propose': (payload) => {
    const world = getWorld()
    if (!world.marriage.ringOwned) {
      throw fail('marriage.propose', '求婚需要先购买婚戒')
    }
    if (world.marriage.status !== 'single') {
      throw fail('marriage.propose', '当前状态无法求婚')
    }
    if (world.marriage.outgoingProposal !== null) {
      throw fail('marriage.propose', '已有一份求婚在等待答复')
    }
    const target = findOtherPlayer(world, payload.targetName)
    if (target === undefined) {
      // A16 的现实形态: 真服也要经 GameProfileCache 解析, 解不出就是这个错。
      throw fail('marriage.propose', `找不到玩家 ${payload.targetName}`)
    }
    const now = nowMs()
    const proposal = {
      proposalId: `prop_out_${String(now)}`,
      playerName: target.name,
      playerUuid: target.uuid,
      createdAt: now,
      expiresAt: now + 60 * 60_000,
    }
    mutateWorld((draft) => {
      draft.marriage.outgoingProposal = proposal
    })
    return {
      proposalId: proposal.proposalId,
      targetName: target.name,
      expiresAt: proposal.expiresAt,
    }
  },

  'marriage.respond': (payload) => {
    const world = getWorld()
    const proposal = world.marriage.incomingProposals.find(
      (entry) => entry.proposalId === payload.proposalId,
    )
    if (proposal === undefined) {
      throw fail('marriage.respond', '该求婚已失效')
    }
    mutateWorld((draft) => {
      draft.marriage.incomingProposals = draft.marriage.incomingProposals.filter(
        (entry) => entry.proposalId !== payload.proposalId,
      )
      if (!payload.accept) {
        return
      }
      draft.marriage.status = 'engaged'
      draft.marriage.spouseName = proposal.playerName
      draft.marriage.spouseUuid = proposal.playerUuid
      const spouse = findOtherPlayer(draft, proposal.playerName)
      draft.marriage.spouseOnline = spouse !== undefined && spouse.online
      // 接受一份即作废其余: 真服同样不允许同时与两人订婚。
      draft.marriage.incomingProposals = []
      draft.marriage.outgoingProposal = null
    })
    const after = getWorld().marriage
    return { status: after.status, spouseName: after.spouseName }
  },

  'marriage.wed': () => {
    const world = getWorld()
    if (world.marriage.status !== 'engaged') {
      // wed 在真服有六态失败枚举; 这里只保留字符串位, 枚举名以 Java 为准 (见 planned.ts)。
      return { ok: false, outcomeCode: 'NOT_ENGAGED', message: '尚未订婚, 无法举行典礼', weddedAt: null }
    }
    const now = nowMs()
    mutateWorld((draft) => {
      draft.marriage.status = 'married'
      draft.marriage.weddedAt = now
      draft.marriage.marriageDays = 0
    })
    return { ok: true, outcomeCode: 'OK', message: '典礼完成', weddedAt: now }
  },

  'marriage.divorce': () => {
    const world = getWorld()
    if (world.marriage.status !== 'married') {
      return { ok: false, outcomeCode: 'NOT_MARRIED', message: '当前并未处于婚姻状态', cooldownUntil: 0 }
    }
    const cooldownUntil = nowMs() + 3 * 24 * 60 * 60_000
    mutateWorld((draft) => {
      draft.marriage.status = 'cooldown'
      draft.marriage.spouseName = null
      draft.marriage.spouseUuid = null
      draft.marriage.spouseOnline = false
      draft.marriage.weddedAt = null
      draft.marriage.marriageDays = 0
      draft.marriage.divorceCount += 1
      draft.marriage.remarryCooldownUntil = cooldownUntil
    })
    return { ok: true, outcomeCode: 'OK', message: '已离婚, 进入再婚冷却', cooldownUntil }
  },

  'marriage.sharedInv': () => {
    const world = getWorld()
    if (world.marriage.status !== 'married') {
      throw fail('marriage.sharedInv', '共享背包仅对已婚玩家开放')
    }
    return cloneResult(world.sharedInv)
  },

  'mining.overview': () => {
    const world = getWorld()
    return {
      instances: cloneResult(world.mining.instances),
      myDifficulty: world.mining.myStatus.difficulty,
    }
  },

  'mining.myStatus': () => cloneResult(getWorld().mining.myStatus),

  'mining.enter': (payload) => {
    const world = getWorld()
    if (world.mining.myStatus.inside) {
      return {
        entered: false,
        difficulty: payload.difficulty,
        reasonCode: 'ALREADY_INSIDE',
        message: '你已经在矿洞里了, 请先离开',
      }
    }
    const instance = world.mining.instances.find((entry) => entry.difficulty === payload.difficulty)
    if (instance === undefined) {
      throw fail('mining.enter', `未知难度 ${payload.difficulty}`)
    }
    const minerLevel = requireJobProgress(world, 'miner').level
    if (minerLevel < instance.requiredMinerLevel) {
      return {
        entered: false,
        difficulty: payload.difficulty,
        reasonCode: 'LEVEL_GATE',
        message: `需要矿工 ${String(instance.requiredMinerLevel)} 级 (当前 ${String(minerLevel)} 级)`,
      }
    }
    mutateWorld((draft) => {
      const target = draft.mining.instances.find((entry) => entry.difficulty === payload.difficulty)
      if (target !== undefined) {
        target.playersInside += 1
      }
      draft.mining.myStatus = {
        inside: true,
        difficulty: payload.difficulty,
        regionX: instance.difficulty === 'easy' ? 0 : instance.difficulty === 'medium' ? 1 : 2,
        regionZ: 0,
        danger: instance.danger,
        // F8 新手保护: 进入后一小段时间不刷怪, 真服有这个态但从不告知客户端。
        spawnFreezeUntil: nowMs() + 30_000,
        minerLevel,
      }
    })
    return { entered: true, difficulty: payload.difficulty, reasonCode: null, message: '已进入矿洞' }
  },

  'mining.leave': () => {
    const world = getWorld()
    const current = world.mining.myStatus.difficulty
    if (!world.mining.myStatus.inside || current === null) {
      return { left: false, message: '你当前不在矿洞里' }
    }
    mutateWorld((draft) => {
      const target = draft.mining.instances.find((entry) => entry.difficulty === current)
      if (target !== undefined) {
        target.playersInside = Math.max(0, target.playersInside - 1)
      }
      draft.mining.myStatus = {
        inside: false,
        difficulty: null,
        regionX: 0,
        regionZ: 0,
        danger: 0,
        spawnFreezeUntil: 0,
        minerLevel: draft.mining.myStatus.minerLevel,
      }
    })
    return { left: true, message: '已离开矿洞' }
  },

  'champion.codex': () => cloneResult(getWorld().champion.codex),

  'champion.inspect': (payload) => {
    const sample = getWorld().champion.samples.find((entry) => entry.entityId === payload.entityId)
    if (sample === undefined) {
      throw fail('champion.inspect', `没有找到实体 ${String(payload.entityId)}`)
    }
    return cloneResult(sample)
  },

  'shop.catalog': () => ({ shops: cloneResult(getWorld().shops) }),

  'shop.detail': (payload) => {
    const world = getWorld()
    const shop = world.shops.find((entry) => entry.shopId === payload.shopId)
    if (shop === undefined) {
      throw fail('shop.detail', `没有找到商店 ${payload.shopId}`)
    }
    return { shop: cloneResult(shop), comparable: cloneResult(comparableShops(world, shop)) }
  },

  'shop.buy': async (payload) => {
    const world = getWorld()
    const shop = world.shops.find((entry) => entry.shopId === payload.shopId)
    if (shop === undefined) {
      throw fail('shop.buy', `没有找到商店 ${payload.shopId}`)
    }
    if (shop.buyPrice === null) {
      throw fail('shop.buy', '该商店不出售此物品')
    }
    if (payload.count <= 0) {
      throw fail('shop.buy', '购买数量必须为正整数')
    }
    if (shop.stock !== null && payload.count > shop.stock) {
      throw fail('shop.buy', `库存仅剩 ${String(shop.stock)} 件`)
    }
    const base = await ensureWallet('shop.buy')
    const paidCredit = shop.buyPrice * payload.count
    if (withOverlay(base, world.walletOverlay).credit < paidCredit) {
      throw fail('shop.buy', '信用点不足')
    }
    mutateWorld((draft) => {
      const target = draft.shops.find((entry) => entry.shopId === payload.shopId)
      if (target !== undefined && target.stock !== null) {
        target.stock -= payload.count
      }
      addWalletOverlay(draft, 'CREDIT', -paidCredit)
      draft.economy.today.totalCreditOut += paidCredit
    })
    const after = getWorld()
    const updatedBase = after.mirror.wallet
    return {
      itemId: shop.itemId,
      count: payload.count,
      paidCredit,
      wallet: withOverlay(updatedBase === null ? base : updatedBase, after.walletOverlay),
    }
  },

  'admin.economy.balance': (payload) => {
    const world = getWorld()
    requireOp('admin.economy.balance', world)
    const target = findOtherPlayer(world, payload.playerName)
    if (target === undefined) {
      throw fail('admin.economy.balance', `找不到玩家 ${payload.playerName}`)
    }
    return {
      playerName: target.name,
      playerUuid: target.uuid,
      wallet: { ...target.wallet },
    }
  },

  'admin.economy.set': async (payload) => {
    const world = getWorld()
    requireOp('admin.economy.set', world)
    if (!Number.isInteger(payload.amount) || payload.amount < 0) {
      throw fail('admin.economy.set', '金额必须是非负整数')
    }
    if (payload.playerName === world.player.name) {
      /*
       * 调自己的账: 钱包权威在 bridge.mock 里改不动, 只能反推一个叠加量, 让合成后的余额等于目标值。
       * 这条是 walletOverlay 存在理由最直白的一处 —— 接线后服务端直接改余额, 整段删掉即可。
       */
      const base = await ensureWallet('admin.economy.set')
      const before = withOverlay(base, world.walletOverlay)
      mutateWorld((draft) => {
        if (payload.currency === 'CREDIT') {
          draft.walletOverlay.credit = payload.amount - base.credit
          return
        }
        draft.walletOverlay.azure = payload.amount - base.azure
      })
      return {
        playerName: payload.playerName,
        before,
        wallet: withOverlay(base, getWorld().walletOverlay),
      }
    }
    const target = findOtherPlayer(world, payload.playerName)
    if (target === undefined) {
      throw fail('admin.economy.set', `找不到玩家 ${payload.playerName}`)
    }
    const before = { ...target.wallet }
    mutateWorld((draft) => {
      const entry = findOtherPlayer(draft, payload.playerName)
      if (entry === undefined) {
        return
      }
      if (payload.currency === 'CREDIT') {
        entry.wallet.credit = payload.amount
        return
      }
      entry.wallet.azure = payload.amount
    })
    const updated = findOtherPlayer(getWorld(), payload.playerName)
    return {
      playerName: payload.playerName,
      before,
      wallet: updated === undefined ? before : { ...updated.wallet },
    }
  },

  'admin.job.setLevel': (payload) => {
    const world = getWorld()
    requireOp('admin.job.setLevel', world)
    if (!Number.isInteger(payload.level) || payload.level < 1 || payload.level > 10) {
      throw fail('admin.job.setLevel', '等级必须是 1-10 的整数')
    }
    if (payload.playerName === world.player.name) {
      mutateWorld((draft) => {
        requireJobProgress(draft, payload.jobId).level = payload.level
      })
      return { playerName: payload.playerName, jobId: payload.jobId, level: payload.level }
    }
    const target = findOtherPlayer(world, payload.playerName)
    if (target === undefined) {
      throw fail('admin.job.setLevel', `找不到玩家 ${payload.playerName}`)
    }
    mutateWorld((draft) => {
      const entry = findOtherPlayer(draft, payload.playerName)
      if (entry !== undefined) {
        entry.jobLevels[payload.jobId] = payload.level
      }
    })
    return { playerName: payload.playerName, jobId: payload.jobId, level: payload.level }
  },

  'admin.mining.reset': (payload) => {
    const world = getWorld()
    requireOp('admin.mining.reset', world)
    const instance = world.mining.instances.find((entry) => entry.difficulty === payload.difficulty)
    if (instance === undefined) {
      throw fail('admin.mining.reset', `未知难度 ${payload.difficulty}`)
    }
    const evictedPlayers = instance.playersInside
    const resetAt = nowMs()
    mutateWorld((draft) => {
      const target = draft.mining.instances.find((entry) => entry.difficulty === payload.difficulty)
      if (target !== undefined) {
        target.playersInside = 0
        target.lastResetAt = resetAt
        target.nextResetAt = resetAt + 12 * 60 * 60_000
        target.danger = 0
      }
      // 重置会把里面的人踢出来, 包括我自己 —— 面板必须能看见自己被踢出去这一后果。
      if (draft.mining.myStatus.difficulty === payload.difficulty) {
        draft.mining.myStatus = {
          inside: false,
          difficulty: null,
          regionX: 0,
          regionZ: 0,
          danger: 0,
          spawnFreezeUntil: 0,
          minerLevel: draft.mining.myStatus.minerLevel,
        }
      }
    })
    return { difficulty: payload.difficulty, resetAt, evictedPlayers }
  },
}

/**
 * 派发一个 planned action。
 *
 * 这里的 as 是整张表里唯一一处: 联合类型的函数不能用联合类型的实参调用 (TS 不做逐分支配对), 而每个
 * handler 的实参类型已由 PlannedHandlerMap 在定义点保证, 转换不引入运行期风险。
 */
function invokePlanned(action: PlannedActionName, payload: unknown): unknown {
  const handler = PLANNED_HANDLERS[action] as (input: unknown) => unknown
  return handler(payload)
}

async function delegateReal(action: WebUiActionName, payload: unknown): Promise<unknown> {
  const result = await callErased(action, payload)
  if (MIRROR_AFTER_WALLET_INVENTORY.has(action)) {
    await refreshWalletAndInventory()
  } else if (MIRROR_AFTER_CASE.has(action)) {
    await refreshCaseTotals()
  }
  return result
}

/**
 * 调一个 action。与 lib/bridge 的 call 同签名, 面板从头到尾只认这一个函数。
 *
 * 失败一律以 WebUiCallError 抛出, 不做任何默认值兜底 —— 与 bridge.call 同纪律:
 * 余额/库存回假值比报错危险得多。调用方只在页面级错误边界或提交按钮处收口。
 */
export async function callMock<A extends MockActionName>(
  action: A,
  payload: MockPayloadOf<A>,
): Promise<MockResultOf<A>> {
  if (isPlannedAction(action)) {
    /*
     * planned 域是前端假定契约 (planned.ts), 服务端**根本没有**对应 action。开发构建里它是设计预览的
     * 数据源; 生产构建里必须硬失败。
     *
     * 这道门不能用 isMockActive() —— 它的判据含"桥未注入", 而真客户端加载 dev server 时桥是注入的,
     * 那样会把设计预览也一并锁死。判据只看构建模式: DEV 放行 (预览), 生产拒绝 (未接线)。
     *
     * 为什么必须拒绝而不是继续回假数据: 玩家看到的余额、今日收支、职业等级若来自内存假世界, 表现是
     * "一切正常但数字全错", 且写操作还会显示成功。那比抛错难查得多 —— 报错至少在界面上是可见的。
     */
    if (!import.meta.env.DEV) {
      throw new WebUiCallError(
        action,
        SERVER_FAILURE_CODE,
        `${action} 尚未接入服务端 (前端假定契约, 见 mock/planned.ts)`,
        { errorCode: 'NOT_WIRED', retrySameOpeningId: false },
      )
    }
    // 只给 planned 路径加延迟: 真域那条已经带着 bridge.mock 的延迟 (装进游戏后是真实往返), 再叠一层
    // 只会让接线前后的手感对不上。
    await sleep(plannedLatencyMs())
    return (await invokePlanned(action, payload)) as MockResultOf<A>
  }
  return (await delegateReal(action, payload)) as MockResultOf<A>
}
