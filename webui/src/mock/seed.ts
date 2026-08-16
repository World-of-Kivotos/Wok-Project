/**
 * mock 世界的初始数据。
 *
 * 数值口径声明 (最重要的一条, 请勿越过):
 * **本文件的数值全部是演示用, 不是平衡真源。** 平衡真源在 docs/ 下各职业 spec 与 Java 常量类里。
 * 任何人要评估经济平衡、掉率、等级曲线, 都必须去读那些文件, 不能拿这里的数字当依据 —— 这里的数字
 * 只需满足两个条件: 量级读起来合理 (不会让面板排版失真), 以及能把边界形态铺满。
 *
 * 刻意铺满的边界形态 (设计稿最容易漏掉的那些):
 *   - 空列表      部分商店无比价对象
 *   - 单页刚好装满  系统商店 20 条 (按 pageSize=20 恰好一页, 第 2 页为空)
 *
 * 已随 F059 (mirror.myListings 整字段核销) 一并删除的边界样本: 超长中文名 (原 ITEM_LONG_NAME, 防弹背心
 * 45 字名) 与 NBT 变体件 (原 ITEM_GAS_CORE, customModelData/nameParts 区分同 itemId 枪匠零件) 都只在
 * "我的挂单"种子里出现过, 唯一消费方随字段一起没了。这两类边界在换皮预览里目前没有别的样本覆盖。
 *
 * 市场那一块 (成交流水 / 待结货款 / 每日额度 / 不可交易规则) 已随 W2 接线搬进 lib/bridge.mock ——
 * 它们现在都是真契约 action, 假后端只能有一个, 本文件不再留第二份。
 * 本轮同理搬走/删除的还有: 职业面板 (塔罗牌组/特勤悬赏/军械台/工程师) 、经济三表、婚姻、矿洞、精英怪图鉴、
 * 其他玩家名册 —— 对应 action 已全部落地真服, 种子留着只会变成第二份会漂移的权威。
 */

import type { PlayerJobProgressEntry, WebUiJobId } from '../lib/types'
import type { PlannedShopEntry } from './planned'
import type { MockWorld } from './store'

/** 与 bridge.mock 的 MOCK_PLAYER_NAME 逐字一致; 不一致会让"我的挂单"两边认不出同一个人。 */
const PLAYER_NAME = '测试员_Mock'

/** 假定的默认分页大小; 上面"刚好装满/差一条"两个边界都是按它凑的。 */
const ASSUMED_PAGE_SIZE = 20

/**
 * 物品三元组 (id + 翻译键 + 中文名) 集中一处, 免得同一件物品在各面板叫不同名字。
 *
 * **全部 itemId 必须是仓库里真实存在的注册名**, 中文名必须与 lang/zh_cn.json 逐字一致。
 * 编一个"看着像那么回事"的 id 的代价: 贴图取不到 -> 界面上是一格棋盘格占位块, 而这个症状看起来
 * 与"第三方 mod 贴图缺口 (决策 J1)"一模一样, 会把一个纯 mock 数据错误伪装成一条真实的架构缺口。
 * (2026-08-13 就踩了这个: 一条编造的 gunsmith_barrel_heavy_match_grade 在设计评审里被当成了真物品。)
 */
interface SeedItem {
  itemId: string
  descriptionId: string
  displayName: string
}

function seedItem(itemId: string, descriptionId: string, displayName: string): SeedItem {
  return { itemId, descriptionId, displayName }
}

const ITEM_DIAMOND = seedItem('minecraft:diamond', 'item.minecraft.diamond', '钻石')
const ITEM_GOLD = seedItem('minecraft:gold_ingot', 'item.minecraft.gold_ingot', '金锭')
const ITEM_IRON_ORE = seedItem('minecraft:iron_ore', 'block.minecraft.iron_ore', '铁矿石')
const ITEM_WHEAT = seedItem('minecraft:wheat', 'item.minecraft.wheat', '小麦')
const ITEM_CARROT = seedItem('minecraft:carrot', 'item.minecraft.carrot', '胡萝卜')
const ITEM_POTATO = seedItem('minecraft:potato', 'item.minecraft.potato', '马铃薯')
const ITEM_BEETROOT = seedItem('minecraft:beetroot', 'item.minecraft.beetroot', '甜菜根')
const ITEM_CHESTPLATE = seedItem(
  'minecraft:diamond_chestplate',
  'item.minecraft.diamond_chestplate',
  '钻石胸甲',
)
const ITEM_ARROW = seedItem('minecraft:arrow', 'item.minecraft.arrow', '箭')
const ITEM_BREAD = seedItem('minecraft:bread', 'item.minecraft.bread', '面包')

// ============================================================
// 职业进度 (8 条并列; 顺序与 JobId.values() 一致)
// ============================================================

/**
 * [jobId, 等级, 累计经验, 本级已获, 升级所需, 今日已获, 今日剩余额度]。
 * 无中文名一列: job.progress 已核销为真契约, 服务端不直给中文, 职业名由前端按 `job.miningdim.<jobId>`
 * 走 client.i18n 自解 (中文对照见 lib/bridge.mock 的 I18N_NAMES)。
 */
type JobSeedRow = readonly [WebUiJobId, number, number, number, number, number, number]

const JOB_ROWS: readonly JobSeedRow[] = [
  ['miner', 6, 48_200, 3_200, 9_000, 1_450, 2_550],
  ['farmer', 4, 12_400, 900, 4_800, 4_000, 0],
  ['engineer', 2, 2_150, 150, 1_600, 0, 4_000],
  ['tarot', 5, 26_800, 5_400, 7_200, 620, 3_380],
  ['chef', 3, 6_900, 400, 2_400, 240, 3_760],
  ['agent', 1, 0, 0, 800, 0, 4_000],
  ['munitions', 7, 91_500, 11_500, 14_000, 3_900, 100],
  // 满级形态: nextLevelXp 为 0, 前端据此判满级而不是硬编码 level === 10。
  ['brewer', 10, 412_000, 0, 0, 0, 4_000],
]

function seedJobProgress(): PlayerJobProgressEntry[] {
  return JOB_ROWS.map((row) => {
    const [jobId, level, totalXp, levelXp, nextLevelXp, dailyXp, dailyRemaining] = row
    return { jobId, level, totalXp, levelXp, nextLevelXp, dailyXp, dailyRemaining }
  })
}

// ============================================================
// 系统商店 (20 条 = 按 pageSize 20 恰好装满一页, 第 2 页为空)
// ============================================================

const SHOP_ITEMS: readonly SeedItem[] = [
  ITEM_BREAD,
  ITEM_ARROW,
  ITEM_WHEAT,
  ITEM_CARROT,
  ITEM_POTATO,
  ITEM_DIAMOND,
  ITEM_GOLD,
  ITEM_IRON_ORE,
  ITEM_CHESTPLATE,
  ITEM_BEETROOT,
]

function seedShops(): PlannedShopEntry[] {
  const shops: PlannedShopEntry[] = []
  for (let index = 0; index < ASSUMED_PAGE_SIZE; index += 1) {
    const item = SHOP_ITEMS[index % SHOP_ITEMS.length]
    if (item === undefined) {
      throw new Error('mock 种子缺陷: 商店物品表为空')
    }
    const buyPrice = 12 + index * 9
    shops.push({
      shopId: `shop_${String(index).padStart(2, '0')}`,
      // 逐维度隔离是真服 AdminShopRegistry 的既有形态, 故种子里必须有跨维度的店。
      dimension: index % 5 === 0 ? 'miningdim:mining' : 'minecraft:overworld',
      pos: { x: 120 + index * 3, y: 64, z: -40 - index },
      itemId: item.itemId,
      descriptionId: item.descriptionId,
      buyPrice,
      // 每 4 家里有 1 家只收不卖: 前端两列都要能显示"—"。
      sellPrice: index % 4 === 3 ? null : Math.round(buyPrice * 0.6),
      // 系统店无限库存记为 null, 与"库存为 0"是两回事。
      stock: index % 3 === 0 ? null : 64 * (index % 5),
    })
  }
  return shops
}

/**
 * 造一份全新的世界。每次调用都返回互不共享引用的新对象 —— resetWorld 依赖这一点,
 * 若这里返回了模块级常量的引用, 重置之后玩家上一轮的改动会跟着一起回来。
 */
export function createInitialWorld(): MockWorld {
  const epoch = Date.now()
  return {
    revision: 0,
    epoch,
    player: { name: PLAYER_NAME, isOp: true },
    mirror: {
      // inventory 为 null: 本会话还没拉过真域背包数据。面板据此显示骨架, 而不是显示一个假的空背包。
      inventory: null,
      refreshedAt: 0,
      lastError: null,
    },
    walletOverlay: { credit: 0, azure: 0 },
    jobs: { progress: seedJobProgress() },
    shops: seedShops(),
  }
}
