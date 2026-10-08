/**
 * 自管区的初始假数据 (lib/bridge.mock 里那 26 条的假后端用)。
 *
 * 学院按服主给的开服名单, 共六所: 阿拜多斯、千年、格赫娜、圣三一、百鬼夜行、狂猎艺术学院 (红冬不开)。
 * 每所一个自管区; 百鬼夜行、狂猎是后补的两个, 数据比前四个少 (住户、地块、记录都只有几条)。
 *
 * 数值口径同 seed.ts: **全部演示用**, 不是任何平衡或规划的真源。坐标、面积、人数只需满足"读起来合理"
 * 与"把边界形态铺满"两条:
 *   - 住户数从个位数 (阿拜多斯 6 人、狂猎 5 人) 到二十几人 (千年 22 人), 表格的短与长两种形态都在;
 *   - 三种 Flan 生效状态都有样本: 待生效 (千年 late_bloomer_77、圣三一 Psalm_Pip、百鬼夜行 newmoon_nomad)、
 *     同步失败 (千年 ferrite_fox、格赫娜 Magma_Mocha), 其余为已生效;
 *   - 操作记录里添加 / 移出 (带原因) / 任命 / 改权限 / 冻结地块 / 收回地块 / 暂停朋友都有, 且有区务长、管理员、
 *     服务器 (冻结期满自动收回) 三种操作人;
 *   - 权限开关 (公共区域): 阿拜多斯全是默认值; 千年给外人开了铁砧; 格赫娜关掉了外人的开门和栅栏门; 圣三一关掉了
 *     住户的改告示牌、管理员关掉了怪物自然生成; 百鬼夜行关掉了外人睡床; 狂猎关掉了住户打掉展示框和盔甲架 ——
 *     各区都能看出"与默认不同"长什么样。
 *   - 地块 (K11-K16): 每区若干块, 各有空置; 千年-02 朋友已满 8 人 (试上限); 千年-04 是 26 小时前移出 LagSpike_Lou 时
 *     冻结的 (还剩 6 天, 原户主的朋友和设置原样存着); 阿拜多斯-04 是 mirage_moth 被移出、冻结期满后由服务器自动收回的
 *     (收回前的记录已归档, 只有管理员看得到); 千年-07 (区务长 circuit_owl 的地块) 里 grief_gremlin 因违反区规被移出,
 *     朋友身份已暂停; 圣三一-03 (chapel_mouse) 三列都改过、有一位待生效的朋友、有一条"管理员代改"; 格赫娜-05 子领地写入失败。
 *   - 买地 (K17-K26): 各区单价不同, 购买一律默认关 (管理员在"管理员操作"里开)。阿拜多斯 dustyboots 刚加入、
 *     还没有地块 —— "无地块住户"的样本: 余额 3,000, 买得起阿拜多斯-04 (2,304), 买不起阿拜多斯-05 (3,840, 差 840)。
 *
 * 玩家名全部是虚构的 Minecraft 风格 ID, 刻意不用任何真实管理层昵称或角色名 —— 预览截图会被转发,
 * 撞上真人 ID 会被当成"某某已经是区务长了"的既成事实。
 *
 * 本文件只放数据、不 import store: store -> seed -> 本文件是单向的, 一旦本文件反过来 import store 就成环
 * (store 在模块求值期就调 createInitialWorld)。读写世界的逻辑在 district-handlers.ts。
 */

import { checkPlotArea, plotAreaOf } from './district-geometry'
import type {
  DistrictBounds,
  DistrictFixedRule,
  DistrictLogAction,
  DistrictLogActorRole,
  DistrictLogEntry,
  DistrictPermissionAudience,
  DistrictPermissionScope,
  DistrictPermissionValues,
  DistrictSyncStatus,
  PlotActorRole,
  PlotAudience,
  PlotLogAction,
  PlotLogEntry,
  PlotPermissionValues,
  PlotSyncStatus,
} from '../lib/types'

// ============================================================
// 世界里的自管区形状 (内存态, 不是契约; 契约形状见 lib/types.ts 自管区一节)
// ============================================================

export interface MockDistrictResidentRecord {
  playerName: string
  joinedAt: number
  addedBy: string
  syncStatus: DistrictSyncStatus
  syncError: string | null
}

export interface MockPlotFriendRecord {
  playerName: string
  addedAt: number
  addedBy: string
  /** 从没进过服的朋友为 pending (首次登录后才进朋友组)。 */
  syncStatus: DistrictSyncStatus
  /**
   * 因违反区规被移出本区时自动暂停的时刻; null = 没暂停。暂停期间在这块地不按朋友算: 按外人算, 若 TA 之后又被
   * 加回本区成了住户, 则按其他住户算。
   */
  suspendedAt: number | null
}

/** 冻结中的地块记下的东西: 原户主是谁、什么时候冻结的。朋友与三列设置原样留在地块记录上。 */
export interface MockPlotFreezeRecord {
  formerOwnerName: string
  frozenAt: number
}

/**
 * 一块地块 (自管区 Flan 领地下的一块子领地)。permissions 三列都是 boolean, 结构上就没有"跟随自管区"这一态 ——
 * 见 lib/types.ts K11 段头"防漏"。
 *
 * 三种状态由两个字段推出: ownerName 非 null = 有户主; frozen 非 null = 冻结中 (此时 ownerName 为 null, 原户主记在
 * frozen 里, friends / permissions 是原户主存下的设置, 解除冻结时照原样还回去); 两者都是 null = 空置。
 * 冻结中的 ownerName 刻意置 null: 一切按"有户主"放行的判断 (改朋友、改权限、我的地块…) 自动把冻结地块拦在外面。
 */
export interface MockDistrictPlotRecord {
  plotId: string
  code: string
  bounds: DistrictBounds
  /** 现任户主; null = 空置或冻结中。 */
  ownerName: string | null
  frozen: MockPlotFreezeRecord | null
  friends: MockPlotFriendRecord[]
  /** 按 permissionId 索引, 覆盖目录里全部非区域规则的项。 */
  permissions: Record<string, PlotPermissionValues>
  /** 本任户主期间的记录 (空置时从上一次收回那条起), 新的在前。户主本人看得到的只有这一份。 */
  log: PlotLogEntry[]
  /**
   * 历任户主期间的记录, 新的在前。收回地块时把当时的 log 整份挪进来: 下一任户主不该看到上一任加过、移除过谁。
   * 只有管理员打开这块地时接在 log 后面一起下发 (排障、查纠纷)。
   */
  archivedLog: PlotLogEntry[]
  syncStatus: PlotSyncStatus
  syncError: string | null
}

/**
 * 删掉的空置地块留下的墓碑 (K19): 这块地自己的地块记录 (本任 + 历任户主的归档) 整份挪到这里, 只有管理员看得到。
 * 区务长能删空置地块, 但不能借删地块把以前的记录一起抹掉。
 */
export interface MockDeletedPlotRecord {
  plotId: string
  code: string
  bounds: DistrictBounds
  deletedAt: number
  deletedBy: string
  /** 新的在前。 */
  log: PlotLogEntry[]
}

export interface MockDistrictRecord {
  districtId: string
  displayName: string
  /** 学院简称 (如"千年"): 地块编号的前缀、"加入千年"这类短句。 */
  academyName: string
  /** 学院全称 (如"千年学院""狂猎艺术学院"): 界面写"某某学院"的地方用它。 */
  academyFullName: string
  wardenName: string | null
  bounds: DistrictBounds
  rules: string[]
  createdAt: number
  residents: MockDistrictResidentRecord[]
  /** 新的在前。 */
  log: DistrictLogEntry[]
  /** 本区公共区域权限开关的当前值, 按 permissionId 索引, 覆盖 DISTRICT_PERMISSION_CATALOG 的每一项。 */
  permissions: Record<string, DistrictPermissionValues>
  /** 按编号排。 */
  plots: MockDistrictPlotRecord[]
  /** 删掉的地块的墓碑, 删除时间新的在前。 */
  deletedPlots: MockDeletedPlotRecord[]
  /** 下一块新地块的序号 (删掉的编号不复用, 免得记录里同一个编号指两块地)。 */
  nextPlotNo: number
  /** 每格单价 (信用点), 只有管理员能设。 */
  unitPrice: number
  /** 地块每一边的格数上下限, 管理员可改。 */
  minSide: number
  maxSide: number
  /** 是否开放购买。默认关 (真服上要等正式服把登录门 security.loginGate 设为 REQUIRED 之后再开, 见 lib/types.ts K22)。 */
  purchaseOpen: boolean
}

/** 已解除绑定的自管区 (K7 删除只解绑): 学院成员名单保留, 操作记录归档, 管理员仍可查 (K26)。 */
export interface MockArchivedDistrictRecord {
  districtId: string
  displayName: string
  academyName: string
  academyFullName: string
  unboundAt: number
  unboundBy: string
  /** 保留下来的学院成员 (仍属于这个学院, 一个人仍只能属于一个学院)。 */
  members: MockDistrictResidentRecord[]
  log: DistrictLogEntry[]
}

/** 进过服的玩家 (Flan 能查到 UUID 的那些)。不在这张表里的名字加进名单只能是待生效。 */
export interface MockKnownPlayer {
  name: string
  lastSeenAt: number
}

/**
 * 买地用的每人信用点余额。
 *
 * 为什么不直接扣页头显示的那份余额: 页头余额是**本机玩家**的 (真契约 player.profile, 权威在 lib/bridge.mock),
 * 而买地要用预览身份切换出来的别的玩家 (dustyboots 等) 去买 —— 那不是本机玩家, 页头那份既不是 TA 的钱,
 * 也没有写入口 (store.walletOverlay 已按 F057 定为无写入方)。故在自管区假世界里记一份每人余额, 只供买地演示;
 * 接线后扣的是服务端那位玩家自己的真钱包。没列出的人按 0 算。
 */
export interface MockDistrictWallet {
  playerName: string
  credit: number
}

export interface MockDistrictWorld {
  districts: MockDistrictRecord[]
  /** 已解除绑定的自管区, 解绑时间新的在前。 */
  archived: MockArchivedDistrictRecord[]
  knownPlayers: MockKnownPlayer[]
  wallets: MockDistrictWallet[]
  /**
   * 预览身份: 以哪个玩家的名义看自管区页。null = 本机玩家本人 (此时是否管理员跟随外壳顶栏的 OP 视图开关)。
   * 真服没有这个字段 —— 身份永远是发请求的那个人, 由服务端 ctx.getSender() 决定。
   */
  previewAs: string | null
  /** 操作记录 id 的自增序号 (本区记录与地块记录共用), 只为让 React key 稳定。 */
  nextLogSeq: number
}

// ============================================================
// 预览身份
// ============================================================

export type DistrictPreviewPersona = 'admin' | 'warden' | 'resident' | 'resident_no_plot' | 'outsider'

export interface DistrictPreviewOption {
  persona: DistrictPreviewPersona
  label: string
  /** 以谁的名义看; null = 本机玩家 (OP)。 */
  playerName: string | null
}

/**
 * 预览身份切换器的选项。区务长选千年 (22 人, 表格够长, 待生效与同步失败都在), 住户选圣三一,
 * 外人选一个进过服但不属于任何学院的玩家 —— 三种非管理员视角各落在不同学院, 预览时不会串成同一屏。
 * 区务长与住户都有地块 (千年-07 / 圣三一-03); "无地块住户"选阿拜多斯刚加入的 dustyboots, 看没分到地块时的空状态。
 */
export const DISTRICT_PREVIEW_OPTIONS: readonly DistrictPreviewOption[] = [
  { persona: 'admin', label: '管理员', playerName: null },
  { persona: 'warden', label: '区务长', playerName: 'circuit_owl' },
  { persona: 'resident', label: '住户', playerName: 'chapel_mouse' },
  { persona: 'resident_no_plot', label: '无地块住户', playerName: 'dustyboots' },
  { persona: 'outsider', label: '外人', playerName: 'Wanderer_Zed' },
]

/** 预览"添加住户""添加朋友"时可以试的名字 (只在假数据模式下作为提示显示)。 */
export const DISTRICT_PREVIEW_ADD_HINTS = {
  /** 进过服、不属于任何学院: 添加后立即生效。 */
  joined: ['Pebble_Fox', 'NovaLynx', 'mintcrate'],
  /** 从没进过服: 添加时会先被拦下确认, 确认后为待生效。 */
  neverJoined: 'brand_new_kid',
} as const

/** 每块地的朋友上限。真服按地块档位定 (【待拍板】), 预览一律 8 人。 */
export const PLOT_FRIEND_LIMIT = 8

/** 地块与自管区边界之间至少留几格公共区域。全服常量, 数值【待拍板】, 预览按 2 格。 */
export const PLOT_EDGE_GAP = 2

/** 户主被移出后地块冻结几天再收回 (已拍板 7 天)。 */
export const PLOT_FREEZE_DAYS = 7

/** 自管区外围多少格内不能个人圈地、不能放机械动力的机器 (已拍板 8 格; 装饰方块照常可放)。 */
export const DISTRICT_BUFFER_BLOCKS = 8

/** 服务器自己做的事 (冻结期满自动收回) 在记录里的操作人名。 */
export const SYSTEM_ACTOR_NAME = '服务器'

/**
 * 地块记录里几条固定的说明。一律固定一句, 不抄移出原因: 移出原因只进本区操作记录 (区务长与管理员可见),
 * 地块记录日后下一任户主也看得到。
 */
export const PLOT_LOG_NOTE = {
  freeze: `户主已移出本区，地块冻结 ${String(PLOT_FREEZE_DAYS)} 天`,
  reclaimExpired: '冻结期满，地块收回',
  reclaimNow: '管理员立即收回冻结中的地块',
  unfreeze: '原户主回到本区，解除冻结',
  suspend: 'TA 因违反区规被移出本区，朋友身份自动暂停；户主可以自己恢复',
} as const

/**
 * 服务端写死、谁都改不了的区域规则 (K8 fixedRules)。机械动力不是 Flan 的开关, 是服务端自己的拦截,
 * 故单独列出、画成锁定的只读行。禁的是机器, 装饰方块照常可放; OP 亲手放置例外 (服主 2026-09-29 拍板),
 * 规则本身谁都改不了。与服务端 PermissionCatalog.FIXED_RULES 逐字一致 (District_Backend_Design 22.13)。
 */
export const DISTRICT_FIXED_RULES: readonly DistrictFixedRule[] = [
  {
    ruleId: 'create_ban',
    label: '机械动力',
    valueText: '机器禁用，装饰可放，OP 例外',
    detail: `自管区内和外围 ${String(DISTRICT_BUFFER_BLOCKS)} 格内不能放机械动力的机器（会转、会动或带功能的方块，轨道也算），外壳、支架、梯子、石材、玻璃这类纯装饰方块照常可放；机械动力的机器也改不了自管区里的方块。管理员（OP）亲手放置不受限。服务器直接拦，这条规则谁都改不了`,
  },
]

// ============================================================
// 权限开关条目 (K8-K10 公共区域; K11-K16 地块沿用同一份, 去掉区域规则)
// ============================================================

/**
 * 一项权限开关的定义。真服里这是服务端配置 (随 district.permissions / plot.detail 下发), 这里只为让预览有东西可画。
 *
 * 选项口径: 只收玩家看得懂、服主真会去开关的 Flan 权限 (对照 Wiki《权限项全表》)。刻意不收的:
 *   - 管理类四条 (编辑领地 / 编辑权限 / 附加效果 / 进出提示语): 给出去等于交出领地设置, 任何人都不能经面板开;
 *   - 传送、飞行、可以停留: Wiki 两页对它们算不算"全局类"说法不一 (《权限项全表》按组列, 《箱子菜单图解》
 *     说飞行、传送是全局类), 放进住户/外人两列可能根本写不进 Flan, 等后端核实再定放哪;
 *   - 音符盒、唱片机、讲台、经验球、丢物品、考古等: 开关它们的场景太少, 列进来只会把表拉长。
 * 一项可对多条 Flan 权限 ("坐船和矿车" = boat + minecart), 后端写的时候一起写。
 */
export interface MockDistrictPermissionDef {
  permissionId: string
  label: string
  detail: string | null
  flanIds: readonly string[]
  flanInverted: boolean
  defaults: DistrictPermissionValues
  outsiderRisk: string | null
  /** 区域规则给全区开启前要确认的后果; member 项恒为 null。 */
  districtRisk: string | null
  /** 地块三列的默认值; 区域规则为 null (地块里不能改区域规则)。 */
  plotDefaults: PlotPermissionValues | null
  /** 在地块里给其他住户或外人开启前要确认的后果; 区域规则与低风险项为 null。 */
  plotRisk: string | null
}

export interface MockDistrictPermissionGroupDef {
  groupId: string
  label: string
  scope: DistrictPermissionScope
  items: readonly MockDistrictPermissionDef[]
}

/**
 * 高风险项的两句后果: outsider = 在公共区域给外人开 (K9 的确认框); plot = 在地块里给其他住户或外人开 (K13 的确认框)。
 * 两句绑在同一处写, 高风险清单就只有一份: 公共区域和地块永远拦同一批项。
 * 地块那句用"这块地"而不是"你的地块": 管理员代改时读的也是它。
 */
interface MemberRisk {
  outsider: string
  plot: string
}

/**
 * 地块三列的默认值。按"这是别人的家"定, 比公共区域保守:
 *   - 朋友: 与公共区域住户列的默认一样 (建造、容器、门与红石、生活等常用项全开), 只多关一项"伤害动物" ——
 *     宰杀家畜要户主自己点头。踩坏农田、伤害有名字的动物照旧关。
 *   - 其他住户: 只开开门、活板门、栅栏门、按钮拉杆、压力板这几项无害交互, 外加"捡地上的东西" (死在别人地里
 *     能收回自己的掉落物)。思路同 Flan 的 Visitor 组, 但去掉了睡床 (会把重生点设进别人家)、末影箱、附魔台、
 *     转展示框、下界传送门、村民交易 —— 用别人家里的东西要户主开口。
 *   - 外人: 在其他住户的基础上再收掉活板门和栅栏门, 只剩开关门、按按钮和拉杆、踩压力板、捡地上的东西。
 * 写成三张名单而不是每项五个布尔位: 读名单就是读规则本身。名单里的 id 在 createDistrictWorld 里逐个核对。
 */
const PLOT_FRIEND_DEFAULT_OFF: ReadonlySet<string> = new Set(['trample', 'hurt_animal', 'hurt_named'])
const PLOT_RESIDENT_DEFAULT_ON: ReadonlySet<string> = new Set([
  'door',
  'trapdoor',
  'fence_gate',
  'button',
  'plate',
  'pickup',
])
const PLOT_OUTSIDER_DEFAULT_ON: ReadonlySet<string> = new Set(['door', 'button', 'plate', 'pickup'])

/**
 * 住户 / 外人两列的一项 (地块另有朋友 / 其他住户 / 外人三列)。risk 非 null = 高风险项, 开启前要确认。
 *
 * 下面那行 NO_SIDE_EFFECTS 是给打包器看的 (regionItem 同): 打包器证明不了这两个函数没有副作用 (这里查了三张 Set 名单,
 * 那边解构了入参), 不标的话下面目录里每一处调用连同整份条目文案都会留在生产包里并在启动时执行 —— 而生产构建里没有
 * 任何代码读这份目录 (读它的假后端只在开发构建里)。删掉标注不影响行为, 只是目录又会回到生产包里。
 */
/* @__NO_SIDE_EFFECTS__ */
function memberItem(
  permissionId: string,
  label: string,
  detail: string | null,
  flanIds: readonly string[],
  residentDefault: boolean,
  outsiderDefault: boolean,
  risk: MemberRisk | null = null,
): MockDistrictPermissionDef {
  return {
    permissionId,
    label,
    detail,
    flanIds,
    flanInverted: false,
    defaults: { resident: residentDefault, outsider: outsiderDefault, district: null },
    outsiderRisk: risk === null ? null : risk.outsider,
    districtRisk: null,
    plotDefaults: {
      friend: !PLOT_FRIEND_DEFAULT_OFF.has(permissionId),
      resident: PLOT_RESIDENT_DEFAULT_ON.has(permissionId),
      outsider: PLOT_OUTSIDER_DEFAULT_ON.has(permissionId),
    },
    plotRisk: risk === null ? null : risk.plot,
  }
}

/**
 * 区域规则 (Flan 全局类) 的一项。默认值一律取 Flan 新领地的默认, "恢复默认"= 回到 Flan 出厂的样子。
 * risk 非 null = 给全区开启前要确认 (整片自管区一键生效, 后果写清楚)。
 */
/* @__NO_SIDE_EFFECTS__ */
function regionItem(
  permissionId: string,
  label: string,
  detail: string | null,
  flanId: string,
  districtDefault: boolean,
  { inverted = false, risk = null }: { inverted?: boolean; risk?: string | null } = {},
): MockDistrictPermissionDef {
  return {
    permissionId,
    label,
    detail,
    flanIds: [flanId],
    flanInverted: inverted,
    defaults: { resident: null, outsider: null, district: districtDefault },
    outsiderRisk: null,
    districtRisk: risk,
    plotDefaults: null,
    plotRisk: null,
  }
}

/**
 * 全部条目。公共区域的默认值:
 *   - 住户: 常用项全开; 只关"踩坏农田和海龟蛋"(护公共农场) 与"伤害有名字的动物"(护宠物)。
 *   - 外人: Flan 自带 Visitor 组的 11 项 (睡床、开门、栅栏门、活板门、按钮拉杆、压力板、末影箱、附魔台、
 *     转展示框、下界传送门、村民交易), 外加"捡地上的东西" —— Flan 新领地本来就允许, 关掉的话外人死在
 *     本区连自己的掉落物都难收。
 *   - 区域规则: 与 Flan 新领地一致。
 * 地块的默认值见上方三张名单。
 */
export const DISTRICT_PERMISSION_CATALOG: readonly MockDistrictPermissionGroupDef[] = [
  {
    groupId: 'build',
    label: '建造',
    scope: 'member',
    items: [
      memberItem('place', '放置方块', '骨粉、睡莲也算', ['flan:place'], true, false, {
        outsider: '任何路过的玩家都能在公共区域随手放方块，可能堵路、乱盖。',
        plot: '别人能在这块地里随手放方块，可能堵门、乱盖。',
      }),
      memberItem('break', '破坏方块', '挖掉这里的任何方块', ['flan:break'], true, false, {
        outsider: '任何路过的玩家都能拆公共区域的建筑、挖走方块，拆掉的只能手动修回来。',
        plot: '别人能拆这块地里的建筑、挖走方块，拆掉的只能手动修回来。',
      }),
      memberItem('bucket', '用桶', '装、倒水和岩浆', ['flan:bucket'], true, false, {
        outsider: '任何路过的玩家都能往公共区域倒岩浆、放水，可能烧掉或淹掉建筑。',
        plot: '别人能往这块地里倒岩浆、放水，房子可能被烧掉或淹掉。',
      }),
      memberItem('sign', '改告示牌', '改文字、染色、上蜡', ['flan:interact_sign'], true, false),
      // 住户默认也关 (护公共农场); 给外人开同样要确认 —— 路过的人踩坏的是公共区域的农田。
      memberItem(
        'trample',
        '踩坏农田和海龟蛋',
        '关着时在耕地上跳也踩不坏庄稼',
        ['flan:trample'],
        false,
        false,
        {
          outsider: '任何路过的玩家都能踩坏公共区域农田里的庄稼、踩碎海龟蛋。',
          plot: '别人能踩坏这块地里的庄稼、踩碎海龟蛋。',
        },
      ),
    ],
  },
  {
    groupId: 'storage',
    label: '箱子与机器',
    scope: 'member',
    items: [
      memberItem(
        'container',
        '开箱子等容器',
        '箱子、木桶、熔炉、酿造台、漏斗等带物品栏的方块，也包括运输矿车',
        ['flan:open_container'],
        true,
        false,
        {
          outsider: '任何路过的玩家都能拿走公共区域的箱子、熔炉里的东西。',
          plot: '别人能拿走这块地里箱子、熔炉里的东西。',
        },
      ),
      // flan:interact_block 是各专项都没匹配上时的兜底右键 (Wiki《权限项全表》), 本服的模组机器里
      // 没有物品栏的那些也落在这条; 带物品栏的走上一项 open_container。
      memberItem(
        'use_block',
        '用工作台等功能方块',
        '工作台、切石机、织布机等没有单独开关的方块，也包括没有物品栏的模组机器',
        ['flan:interact_block'],
        true,
        false,
      ),
      memberItem('anvil', '用铁砧', null, ['flan:anvil'], true, false),
      memberItem('enchant', '用附魔台', null, ['flan:enchantment'], true, true),
      memberItem('enderchest', '用末影箱', '里面是各人自己的东西，别人拿不到', ['flan:enderchest'], true, true),
      memberItem('beacon', '调信标', '更换信标给的效果', ['flan:beacon'], true, false),
    ],
  },
  {
    groupId: 'doors',
    label: '门与红石',
    scope: 'member',
    items: [
      memberItem('door', '开关门', null, ['flan:door'], true, true),
      memberItem('trapdoor', '开关活板门', null, ['flan:trapdoor'], true, true),
      memberItem('fence_gate', '开关栅栏门', null, ['flan:fence_gate'], true, true),
      memberItem('button', '按按钮和拉杆', null, ['flan:button_lever'], true, true),
      memberItem('plate', '踩压力板', null, ['flan:pressure_plate'], true, true),
      memberItem('redstone', '调红石元件', '中继器、比较器、红石粉、阳光探测器', ['flan:redstone'], true, false),
    ],
  },
  {
    groupId: 'living',
    label: '生活',
    scope: 'member',
    items: [
      memberItem('bed', '睡床', '也会把重生点设在这里', ['flan:bed'], true, true),
      memberItem('trading', '和村民交易', '也包括流浪商人', ['flan:trading'], true, true),
      memberItem('itemframe', '转展示框里的物品', null, ['flan:itemframe_rotate'], true, true),
      memberItem('armorstand', '给盔甲架换装备', '能把盔甲架上的装备拿下来', ['flan:armorstand'], true, false, {
        outsider: '任何路过的玩家都能从公共区域的盔甲架上拿走装备。',
        plot: '别人能从这块地的盔甲架上拿走装备。',
      }),
      // 与上两项挨着放: 想护住展示框和盔甲架的人, 在同一处就能把三项一起关掉。
      memberItem(
        'break_entity',
        '打掉展示框和盔甲架',
        '也包括停着的矿车和船',
        ['flan:break_non_living'],
        true,
        false,
        {
          outsider: '任何路过的玩家都能打掉公共区域的展示框和盔甲架，里面的东西会掉出来被捡走。',
          plot: '别人能打掉这块地里的展示框和盔甲架，里面的东西会掉出来被捡走。',
        },
      ),
    ],
  },
  {
    groupId: 'animals',
    label: '动物',
    scope: 'member',
    items: [
      // 名称直接点出剪毛、挤奶: 只写"照顾动物"的话, 在外人那一列看起来人畜无害, 实际能拿走羊毛和牛奶。
      // 名称里不用顿号: "我能做什么""外人可以"都拿顿号把各项连成一串, 名称自带顿号就分不清是几项。
      memberItem(
        'animal',
        '喂养和剪毛挤奶',
        '也包括繁殖、上鞍；自己驯服的宠物不受这项限制',
        ['flan:animal_interact'],
        true,
        false,
      ),
      memberItem('hurt_animal', '伤害动物', '包括宰杀家畜', ['flan:hurt_animal'], true, false, {
        outsider: '任何路过的玩家都能宰杀公共区域养的动物。',
        plot: '别人能宰杀这块地里养的动物。',
      }),
      memberItem(
        'hurt_named',
        '伤害有名字的动物',
        '挂了命名牌的宠物和家畜；这项关着，就算上一项开着也打不了它们',
        ['flan:hurt_named'],
        false,
        false,
        {
          outsider: '任何路过的玩家都能伤害公共区域里起了名字的宠物和家畜。',
          plot: '别人能伤害这块地里起了名字的宠物和家畜。',
        },
      ),
    ],
  },
  {
    groupId: 'items',
    label: '物品与移动',
    scope: 'member',
    items: [
      // 说明写"关了会怎样", 不写"另有保护" —— 后者读起来像"放心关", 而关掉恰恰会坑到死在本区的人。
      // 【接线前核实】flan:pickup 关着时, flan:lock_items 锁给本人的死亡掉落本人还能不能捡 (Wiki 未写明)。
      memberItem(
        'pickup',
        '捡地上的东西',
        '关着时，死在这里的人连自己的掉落物也可能捡不回来',
        ['flan:pickup'],
        true,
        true,
      ),
      memberItem('ender_pearl', '扔末影珍珠', '关着时珍珠落地就消失，不会传送', ['flan:ender_pearl'], true, false),
      memberItem('ride', '坐船和矿车', '关着时也不能坐着船进来', ['flan:boat', 'flan:minecart'], true, false),
      memberItem('portal', '走下界传送门', null, ['flan:portal'], true, true),
    ],
  },
  {
    groupId: 'region',
    label: '区域规则',
    scope: 'region',
    items: [
      regionItem('pvp', '玩家互相攻击（PvP）', '关着时谁在本区都打不了人', 'flan:hurt_player', false, {
        risk: '在本区谁都能打谁，住户也可能被路过的人打死。',
      }),
      regionItem('explosions', '爆炸伤害（方块和生物）', 'TNT、苦力怕能不能炸坏本区方块、伤到生物', 'flan:explosions', false, {
        risk: 'TNT、苦力怕会炸坏本区建筑，也会伤到区里的人和动物。',
      }),
      regionItem('wither', '凋灵破坏方块', null, 'flan:wither', false, {
        risk: '凋灵会炸掉本区的方块，建筑可能被炸出大洞。',
      }),
      regionItem('fire_spread', '火焰蔓延', '火能不能在本区烧开', 'flan:fire_spread', false, {
        risk: '火会在本区烧开，木头建筑可能整栋烧掉。',
      }),
      regionItem('mob_spawn', '怪物自然生成', '僵尸、苦力怕等能不能在本区刷出来', 'flan:mob_spawn', true, {
        inverted: true,
      }),
      regionItem('enderman', '末影人搬方块', null, 'flan:enderman', true),
      regionItem('liquid_border', '水和岩浆流过边界', '只管从区外横着流进来', 'flan:water_border', false, {
        risk: '区外的水和岩浆能流进本区，边上的建筑可能被淹掉或烧掉。',
      }),
    ],
  },
]

/** 开关表初值: 每一项都按默认值, 各自一份新对象 (resetWorld 之后不能跟上一轮共享引用)。 */
function defaultPermissions(): Record<string, DistrictPermissionValues> {
  const values: Record<string, DistrictPermissionValues> = {}
  for (const group of DISTRICT_PERMISSION_CATALOG) {
    for (const item of group.items) {
      values[item.permissionId] = { ...item.defaults }
    }
  }
  return values
}

/**
 * 地块开关表初值: 目录里每一项非区域规则都按地块默认值写满三列。收回地块 (变空置) 时也用它整张重写 ——
 * 与真服"整块重写、每格明确值"同一口径 (lib/types.ts K11 段头)。
 */
export function defaultPlotPermissions(): Record<string, PlotPermissionValues> {
  const values: Record<string, PlotPermissionValues> = {}
  for (const group of DISTRICT_PERMISSION_CATALOG) {
    for (const item of group.items) {
      if (item.plotDefaults !== null) {
        values[item.permissionId] = { ...item.plotDefaults }
      }
    }
  }
  return values
}

function permissionDefOf(permissionId: string): MockDistrictPermissionDef {
  for (const group of DISTRICT_PERMISSION_CATALOG) {
    const found = group.items.find((item) => item.permissionId === permissionId)
    if (found !== undefined) {
      return found
    }
  }
  throw new Error(`mock 种子缺陷: 权限条目里没有 ${permissionId}`)
}

/** 三张地块默认名单里的 id 必须都在目录里、且都不是区域规则, 否则名单写错了也不会有任何一格变化。 */
function assertPlotDefaultIds(): void {
  for (const ids of [PLOT_FRIEND_DEFAULT_OFF, PLOT_RESIDENT_DEFAULT_ON, PLOT_OUTSIDER_DEFAULT_ON]) {
    for (const id of ids) {
      if (permissionDefOf(id).plotDefaults === null) {
        throw new Error(`mock 种子缺陷: 地块默认名单里的 ${id} 是区域规则, 地块里不能改`)
      }
    }
  }
}

// ============================================================
// 种子
// ============================================================

const HOUR = 3_600_000
const DAY = 24 * HOUR

/** 种子里另一位管理员。预览时本机 OP 的操作会以本机玩家名记账, 与它并存, 正好演示"多个管理员"。 */
const SEED_ADMIN = 'Overseer_Rho'

/**
 * [玩家名, 几天前加入, 添加人, 几小时前在线 (null = 从没进过服), 同步失败原因 (null = 未失败)]。
 * 同步状态由后两列推出: 从没进过服 -> 待生效; 有失败原因 -> 同步失败; 其余已生效。
 */
type ResidentRow = readonly [string, number, string, number | null, string | null]

/**
 * 地块网格: 从自管区西北角往里缩 margin 格起排, 每块 size x size, 块与块之间留 gap 格路, 每行 columns 块。
 * 编号按行从左到右、从上到下。
 */
interface PlotLayout {
  size: number
  columns: number
  gap: number
  margin: number
}

/** 地块记录里朋友表之外的历史 [几小时前, 操作人, 身份, 动作, 对象, 原因]。 */
type PlotHistoryRow = readonly [
  number,
  string,
  PlotActorRole,
  Exclude<PlotLogAction, 'permission' | 'create' | 'resize'>,
  string,
  string | null,
]

/** 地块里与默认不同的开关 [几小时前, 操作人, 身份, 对谁, 哪一项, 改成]。 */
type PlotPermissionRow = readonly [number, string, PlotActorRole, PlotAudience, string, boolean]

/**
 * 现有朋友 [玩家名, 几小时前由户主加的, 几小时前被暂停 (可省)]; 同时生成"添加朋友"记录。
 * 暂停的那条"暂停朋友"记录 (给户主的通知) 写在 history 里, 与移出 TA 的那条本区记录对得上。
 */
type PlotFriendRow = readonly [string, number] | readonly [string, number, number]

interface PlotSeed {
  /** 编号, 从 1 起。 */
  no: number
  /** null = 空置或冻结中。 */
  owner: string | null
  /** 冻结中: [原户主, 几小时前冻结]。原户主的朋友和改过的开关照常写在 friends / permissionChanges 里。 */
  frozen?: readonly [string, number]
  /** 不按网格排的地块: [距西北角 x 格, 距西北角 z 格, 宽, 深]。 */
  rect?: readonly [number, number, number, number]
  friends?: readonly PlotFriendRow[]
  history?: readonly PlotHistoryRow[]
  permissionChanges?: readonly PlotPermissionRow[]
  /** 子领地上一次写入失败的原因; 缺省 = 已生效。 */
  syncError?: string
}

interface DistrictSeed {
  districtId: string
  displayName: string
  academyName: string
  academyFullName: string
  wardenName: string
  bounds: DistrictBounds
  rules: readonly string[]
  createdDaysAgo: number
  /** 每格单价 (信用点)。 */
  unitPrice: number
  residents: readonly ResidentRow[]
  /** [几小时前, 操作人, 操作人身份, 动作, 对象, 原因]。按时间倒序写。改权限的记录不写这里, 见下一项。 */
  log: readonly LogRow[]
  /**
   * 与默认不同的公共区域权限开关, 每条既改开关表也写一条操作记录:
   * [几小时前, 操作人, 操作人身份, 对谁, 哪一项, 改成]。按时间倒序写。
   */
  permissionChanges: readonly PermissionChangeRow[]
  plotLayout: PlotLayout
  plots: readonly PlotSeed[]
}

type LogRow = readonly [
  number,
  string,
  DistrictLogActorRole,
  Exclude<DistrictLogAction, 'permission'>,
  string,
  string | null,
]

type PermissionChangeRow = readonly [
  number,
  string,
  'admin' | 'warden',
  DistrictPermissionAudience,
  string,
  boolean,
]

const OVERWORLD = 'minecraft:overworld'

const DISTRICT_SEEDS: readonly DistrictSeed[] = [
  {
    districtId: 'abydos',
    displayName: '阿拜多斯自管区',
    academyName: '阿拜多斯',
    academyFullName: '阿拜多斯学院',
    wardenName: 'DuneWalker_07',
    bounds: { dimension: OVERWORLD, minX: -1280, minZ: 640, maxX: -1153, maxZ: 767 },
    rules: [
      '农场、熔炉阵列这些公共设施用完请复原',
      '别挖空主干道下面的地基',
      '新建筑动工前先跟区务长报个位置',
    ],
    createdDaysAgo: 60,
    unitPrice: 4,
    residents: [
      ['DuneWalker_07', 60, SEED_ADMIN, 5, null],
      ['sandglass_kai', 55, 'DuneWalker_07', 30, null],
      ['Oasis_Mira', 50, 'DuneWalker_07', 12, null],
      ['TumbleWeedo', 41, 'DuneWalker_07', 70, null],
      ['cactus_juice99', 18, 'DuneWalker_07', 3, null],
      ['dustyboots', 6, 'DuneWalker_07', 1, null],
    ],
    log: [
      [6 * 24, 'DuneWalker_07', 'warden', 'add', 'dustyboots', null],
      [18 * 24, 'DuneWalker_07', 'warden', 'add', 'cactus_juice99', null],
      // 冻结期满由服务器自动收回: 7 天后那一刻, 操作人是"服务器"。
      [18 * 24, SYSTEM_ACTOR_NAME, 'system', 'vacatePlot', '阿拜多斯-04', '冻结期满，自动收回'],
      // 同一刻的两条: 冻结写在移出之后 (新的在前), 与移出住户时 mock 实际写出的顺序一致。
      [25 * 24, 'DuneWalker_07', 'warden', 'freezePlot', '阿拜多斯-04', '原户主 mirage_moth 被移出本区'],
      [25 * 24, 'DuneWalker_07', 'warden', 'remove', 'mirage_moth', '长期不上线：两个月没登录'],
      [59 * 24, SEED_ADMIN, 'admin', 'appoint', 'DuneWalker_07', null],
    ],
    // 全部默认值: 预览里"与默认一致"的样子。
    permissionChanges: [],
    plotLayout: { size: 24, columns: 4, gap: 8, margin: 4 },
    // 后加入的三人 (含 dustyboots) 还没有地块。04 是收回的旧地块 (之前的记录已归档); 05 是区务长后来划的一块长条地,
    // 比 04 大, dustyboots 的余额买不起 —— 买地确认框"余额不足"的样子。
    plots: [
      { no: 1, owner: 'DuneWalker_07', friends: [['sandglass_kai', 50 * 24]] },
      { no: 2, owner: 'sandglass_kai' },
      { no: 3, owner: 'Oasis_Mira', friends: [['TumbleWeedo', 30 * 24]] },
      {
        no: 4,
        owner: null,
        history: [
          [18 * 24, SYSTEM_ACTOR_NAME, 'system', 'vacate', 'mirage_moth', PLOT_LOG_NOTE.reclaimExpired],
          [25 * 24, 'DuneWalker_07', 'warden', 'freeze', 'mirage_moth', PLOT_LOG_NOTE.freeze],
          [48 * 24, 'mirage_moth', 'owner', 'addFriend', 'Oasis_Mira', null],
        ],
      },
      { no: 5, owner: null, rect: [4, 36, 40, 24] },
    ],
  },
  {
    districtId: 'millennium',
    displayName: '千年自管区',
    academyName: '千年',
    academyFullName: '千年学院',
    wardenName: 'circuit_owl',
    bounds: { dimension: OVERWORLD, minX: 512, minZ: -896, maxX: 703, maxZ: -705 },
    rules: [
      '改红石机器之前先跟区务长说一声',
      '爆炸类实验去东北角的实验区，别在居住区试',
      '公共仓库的东西按标签放回原处',
    ],
    createdDaysAgo: 41,
    unitPrice: 6,
    residents: [
      ['circuit_owl', 40, SEED_ADMIN, 1, null],
      ['bytehopper', 38, 'circuit_owl', 3, null],
      ['Qubit_Rin', 36, 'circuit_owl', 26, null],
      ['SolderSnake', 35, 'circuit_owl', 5, null],
      ['pixel_forge', 33, 'circuit_owl', 72, null],
      ['NullPointerPie', 31, 'circuit_owl', 2, null],
      ['RedstoneRook', 30, SEED_ADMIN, 8, null],
      ['Kilobyte_K', 28, 'circuit_owl', 120, null],
      ['overclock_oz', 26, 'circuit_owl', 30, null],
      ['LatticeLynx', 24, 'circuit_owl', 12, null],
      ['CacheMiss_', 22, 'circuit_owl', 200, null],
      ['ferrite_fox', 20, 'circuit_owl', 6, '写入居民组时领地所在区块未加载，本次写入已放弃'],
      ['Voxel_Vera', 19, 'circuit_owl', 50, null],
      ['glitchmint', 17, 'circuit_owl', 4, null],
      ['TorqueTheory', 15, 'circuit_owl', 90, null],
      ['hexadeci_mo', 13, 'circuit_owl', 20, null],
      ['BitBanditX', 12, 'circuit_owl', 7, null],
      ['servo_sparrow', 11, 'circuit_owl', 160, null],
      ['Diode_Dan', 10, 'circuit_owl', 10, null],
      ['PatchNote_P', 9, 'circuit_owl', 15, null],
      ['quartz_query', 3, 'circuit_owl', 9, null],
      // 刚加进来、从没进过服: 待生效的样本。
      ['late_bloomer_77', 2 / 24, 'circuit_owl', null, null],
    ],
    log: [
      [2, 'circuit_owl', 'warden', 'add', 'late_bloomer_77', null],
      // 同一刻的两条: 冻结地块写在移出之后 (新的在前), 与移出住户时 mock 实际写出的顺序一致。
      [26, 'circuit_owl', 'warden', 'freezePlot', '千年-04', '原户主 LagSpike_Lou 被移出本区'],
      [26, 'circuit_owl', 'warden', 'remove', 'LagSpike_Lou', '长期不上线：超过 45 天没登录'],
      [3 * 24, 'circuit_owl', 'warden', 'add', 'quartz_query', null],
      // 违反区规: 连带暂停 TA 在本区别人地块的朋友身份 (只写块数, 不写是哪几块)。
      [5 * 24, SEED_ADMIN, 'admin', 'suspendFriends', 'grief_gremlin', '本区 1 块地的朋友身份已暂停'],
      [5 * 24, SEED_ADMIN, 'admin', 'remove', 'grief_gremlin', '违反区规：拆了公共仓库的箱子'],
      [9 * 24, 'circuit_owl', 'warden', 'add', 'PatchNote_P', null],
      [10 * 24, 'circuit_owl', 'warden', 'add', 'Diode_Dan', null],
      [39 * 24, SEED_ADMIN, 'admin', 'appoint', 'circuit_owl', null],
    ],
    // 广场上摆了公用铁砧, 给路过的人也开了。
    permissionChanges: [[6 * 24, 'circuit_owl', 'warden', 'outsider', 'anvil', true]],
    plotLayout: { size: 32, columns: 4, gap: 8, margin: 8 },
    plots: [
      { no: 1, owner: 'SolderSnake', friends: [['pixel_forge', 20 * 24]] },
      // 朋友已满 8 人: 管理员代改时再加一位会被 FRIEND_LIMIT_REACHED 拦下。
      {
        no: 2,
        owner: 'bytehopper',
        friends: [
          ['circuit_owl', 36 * 24],
          ['Qubit_Rin', 34 * 24],
          ['NullPointerPie', 30 * 24],
          ['RedstoneRook', 29 * 24],
          ['Kilobyte_K', 27 * 24],
          ['overclock_oz', 25 * 24],
          ['Voxel_Vera', 18 * 24],
          ['Pebble_Fox', 7 * 24],
        ],
      },
      // 其他住户可以用她家门口的工作台 —— "其他住户"那一列与默认不同, 住户视角的"别的地块"会单独列出。
      {
        no: 3,
        owner: 'Qubit_Rin',
        permissionChanges: [[14 * 24, 'Qubit_Rin', 'owner', 'resident', 'use_block', true]],
      },
      // 原户主 LagSpike_Lou 26 小时前被移出, 冻结中 (还剩 6 天)。TA 的朋友和改过的开关原样存着, 解除冻结时照原样还回去。
      {
        no: 4,
        owner: null,
        frozen: ['LagSpike_Lou', 26],
        friends: [['CacheMiss_', 35 * 24]],
        history: [[26, 'circuit_owl', 'warden', 'freeze', 'LagSpike_Lou', PLOT_LOG_NOTE.freeze]],
        permissionChanges: [[30 * 24, 'LagSpike_Lou', 'owner', 'outsider', 'door', false]],
      },
      { no: 5, owner: 'NullPointerPie', friends: [['BitBanditX', 11 * 24]] },
      {
        no: 6,
        owner: 'RedstoneRook',
        friends: [
          ['Diode_Dan', 9 * 24],
          ['NovaLynx', 3 * 24],
        ],
      },
      // 区务长自己的地块: 在"我的地块"里照常自己管。给其他住户开了调红石 (研究社的公用红石台)。
      // grief_gremlin 5 天前因违反区规被管理员移出本区, 在这里的朋友身份自动暂停 —— 户主看到"已暂停"和"恢复"。
      {
        no: 7,
        owner: 'circuit_owl',
        friends: [
          ['bytehopper', 30 * 24],
          ['grief_gremlin', 15 * 24, 5 * 24],
          ['NovaLynx', 4 * 24],
        ],
        history: [[5 * 24, SEED_ADMIN, 'admin', 'suspendFriend', 'grief_gremlin', PLOT_LOG_NOTE.suspend]],
        permissionChanges: [[2 * 24, 'circuit_owl', 'owner', 'resident', 'redstone', true]],
      },
      { no: 8, owner: 'LatticeLynx' },
      { no: 9, owner: null },
      { no: 10, owner: 'glitchmint', friends: [['hexadeci_mo', 12 * 24]] },
    ],
  },
  {
    districtId: 'gehenna',
    displayName: '格赫娜自管区',
    academyName: '格赫娜',
    academyFullName: '格赫娜学院',
    wardenName: 'EmberTusk',
    bounds: { dimension: OVERWORLD, minX: 1536, minZ: 256, maxX: 1759, maxZ: 447 },
    rules: [
      '岩浆和火只能在熔炉房里用',
      '拆别人的建筑之前先征得本人同意',
      '居住区里别放会炸的东西',
    ],
    createdDaysAgo: 49,
    unitPrice: 5,
    residents: [
      ['EmberTusk', 48, SEED_ADMIN, 2, null],
      ['cinderpaw', 46, 'EmberTusk', 14, null],
      ['Brimstone_Bo', 44, 'EmberTusk', 40, null],
      ['AshenLark', 40, 'EmberTusk', 6, null],
      ['lavaloop_22', 37, 'EmberTusk', 22, null],
      ['Hellkite_Nyx', 35, 'EmberTusk', 3, null],
      ['sootsprite', 31, 'EmberTusk', 96, null],
      ['Magma_Mocha', 27, 'EmberTusk', 11, '找不到玩家档案（UUID 解析超时）'],
      ['ScorchRunner', 24, 'EmberTusk', 8, null],
      ['flarefern', 21, 'EmberTusk', 55, null],
      ['Obsidian_Ivy', 19, 'EmberTusk', 4, null],
      ['charcoal_chai', 16, 'EmberTusk', 30, null],
      ['BlazeBunny', 14, 'EmberTusk', 1, null],
      ['pyre_pilot', 12, 'EmberTusk', 250, null],
      ['VolcanoVic', 9, 'EmberTusk', 18, null],
      ['smolder_sam', 7, 'EmberTusk', 5, null],
      ['Infernal_Ink', 1, 'EmberTusk', 2, null],
    ],
    log: [
      [24, 'EmberTusk', 'warden', 'add', 'Infernal_Ink', null],
      [4 * 24, 'EmberTusk', 'warden', 'remove', 'tnt_tommy', '违反区规：在居住区放 TNT'],
      [7 * 24, 'EmberTusk', 'warden', 'add', 'smolder_sam', null],
      [47 * 24, SEED_ADMIN, 'admin', 'appoint', 'EmberTusk', null],
    ],
    // 外人不许自己开门进来, 要进得找住户开。
    permissionChanges: [
      [3 * 24 - 0.1, 'EmberTusk', 'warden', 'outsider', 'fence_gate', false],
      [3 * 24, 'EmberTusk', 'warden', 'outsider', 'door', false],
    ],
    plotLayout: { size: 28, columns: 5, gap: 8, margin: 8 },
    plots: [
      { no: 1, owner: 'EmberTusk', friends: [['cinderpaw', 40 * 24]] },
      { no: 2, owner: 'cinderpaw' },
      {
        no: 3,
        owner: 'Hellkite_Nyx',
        permissionChanges: [[10 * 24, 'Hellkite_Nyx', 'owner', 'outsider', 'door', false]],
      },
      { no: 4, owner: null },
      // 子领地写入失败的样本: 管理员视角的"地块"列表看得到原因。
      { no: 5, owner: 'AshenLark', syncError: '写入子领地时区块未加载，本次写入已放弃' },
      {
        no: 6,
        owner: 'BlazeBunny',
        friends: [
          ['ScorchRunner', 6 * 24],
          // 朋友可以是任何玩家: 这位不属于任何学院。
          ['Wanderer_Zed', 2 * 24],
        ],
      },
    ],
  },
  {
    districtId: 'trinity',
    displayName: '圣三一自管区',
    academyName: '圣三一',
    academyFullName: '圣三一学院',
    wardenName: 'BellTowerBea',
    bounds: { dimension: OVERWORLD, minX: -640, minZ: -1536, maxX: -481, maxZ: -1377 },
    rules: [
      '钟楼和礼拜堂是公共建筑，不要改动外观',
      '花园区只种花，不要种树',
      '晚上十点以后别在广场放烟花',
    ],
    createdDaysAgo: 53,
    unitPrice: 5,
    residents: [
      ['BellTowerBea', 52, SEED_ADMIN, 7, null],
      ['chapel_mouse', 44, 'BellTowerBea', 2, null],
      ['Sage_Lumen', 41, 'BellTowerBea', 20, null],
      ['rosewindow_r', 38, 'BellTowerBea', 48, null],
      ['VespersVale', 33, 'BellTowerBea', 9, null],
      ['Candlewick_C', 29, 'BellTowerBea', 130, null],
      ['halo_hopper', 23, 'BellTowerBea', 16, null],
      ['StainedGlass_S', 17, 'BellTowerBea', 5, null],
      ['cloister_cat', 12, 'BellTowerBea', 60, null],
      ['TeaTimeTess', 8, 'BellTowerBea', 3, null],
      ['altar_ash', 4, 'BellTowerBea', 28, null],
      ['Psalm_Pip', 1, 'BellTowerBea', null, null],
    ],
    log: [
      [24, 'BellTowerBea', 'warden', 'add', 'Psalm_Pip', null],
      [4 * 24, 'BellTowerBea', 'warden', 'add', 'altar_ash', null],
      [12 * 24, 'BellTowerBea', 'warden', 'remove', 'moss_mallow', '本人申请退出'],
      [51 * 24, SEED_ADMIN, 'admin', 'appoint', 'BellTowerBea', null],
    ],
    // 广场告示牌统一由区务长写; 礼拜堂一带不刷怪 (区域规则, 管理员改的)。
    permissionChanges: [
      [2 * 24, 'BellTowerBea', 'warden', 'resident', 'sign', false],
      [20 * 24, SEED_ADMIN, 'admin', 'district', 'mob_spawn', false],
    ],
    plotLayout: { size: 24, columns: 4, gap: 8, margin: 8 },
    plots: [
      { no: 1, owner: 'BellTowerBea', friends: [['chapel_mouse', 40 * 24]] },
      { no: 2, owner: 'Sage_Lumen' },
      // 住户预览身份的地块: 三列都与默认不同, 有待生效的朋友, 记录里有一条"管理员代改"。
      {
        no: 3,
        owner: 'chapel_mouse',
        friends: [
          ['Sage_Lumen', 30 * 24],
          ['Pebble_Fox', 12 * 24],
          // 从没进过服: 待生效。
          ['choir_kid', 20],
        ],
        history: [
          // grief_gremlin 在千年拆公共仓库被移出学院的同一天, 管理员顺手把 TA 从这块地的朋友里移除了。
          [5 * 24, SEED_ADMIN, 'admin', 'removeFriend', 'grief_gremlin', null],
          [20 * 24, 'chapel_mouse', 'owner', 'addFriend', 'grief_gremlin', null],
        ],
        permissionChanges: [
          [3 * 24, 'chapel_mouse', 'owner', 'outsider', 'door', false],
          [9 * 24, 'chapel_mouse', 'owner', 'friend', 'hurt_animal', true],
          [9 * 24 + 0.1, 'chapel_mouse', 'owner', 'resident', 'use_block', true],
        ],
      },
      { no: 4, owner: 'rosewindow_r' },
      { no: 5, owner: null },
      {
        no: 6,
        owner: 'VespersVale',
        permissionChanges: [[6 * 24, 'VespersVale', 'owner', 'resident', 'trading', true]],
      },
      { no: 7, owner: 'halo_hopper' },
    ],
  },
  {
    districtId: 'hyakkiyako',
    displayName: '百鬼夜行自管区',
    academyName: '百鬼夜行',
    academyFullName: '百鬼夜行学院',
    wardenName: 'OniLantern',
    bounds: { dimension: OVERWORLD, minX: 1024, minZ: 1280, maxX: 1183, maxZ: 1439 },
    rules: [
      '祭典广场的摊位用完请收拾干净',
      '鸟居和神社一带是公共建筑，不要改动外观',
      '放烟花去河边，别在屋顶上放',
    ],
    createdDaysAgo: 36,
    unitPrice: 5,
    residents: [
      ['OniLantern', 35, SEED_ADMIN, 4, null],
      ['torii_tanuki', 33, 'OniLantern', 10, null],
      ['Maple_Mochi', 30, 'OniLantern', 26, null],
      ['ShojiShade', 24, 'OniLantern', 60, null],
      ['festival_frog', 15, 'OniLantern', 6, null],
      ['Tengu_Tempo', 9, 'OniLantern', 2, null],
      ['lantern_lark', 3, 'OniLantern', 20, null],
      // 刚加进来、从没进过服: 待生效的样本。
      ['newmoon_nomad', 5 / 24, 'OniLantern', null, null],
    ],
    log: [
      [5, 'OniLantern', 'warden', 'add', 'newmoon_nomad', null],
      [3 * 24, 'OniLantern', 'warden', 'add', 'lantern_lark', null],
      [11 * 24, 'OniLantern', 'warden', 'remove', 'paper_crane_7', '本人申请退出'],
      [34 * 24, SEED_ADMIN, 'admin', 'appoint', 'OniLantern', null],
    ],
    // 外人不许在本区睡床: 免得路过的人把重生点设在神社里。
    permissionChanges: [[4 * 24, 'OniLantern', 'warden', 'outsider', 'bed', false]],
    plotLayout: { size: 28, columns: 4, gap: 8, margin: 8 },
    plots: [
      { no: 1, owner: 'OniLantern', friends: [['torii_tanuki', 30 * 24]] },
      { no: 2, owner: 'torii_tanuki' },
      // 门口常驻一个商人村民, 给其他住户也开了交易。
      {
        no: 3,
        owner: 'Maple_Mochi',
        permissionChanges: [[8 * 24, 'Maple_Mochi', 'owner', 'resident', 'trading', true]],
      },
      { no: 4, owner: null },
      { no: 5, owner: 'festival_frog', friends: [['Tengu_Tempo', 5 * 24]] },
      { no: 6, owner: null },
    ],
  },
  {
    districtId: 'wildhunt',
    displayName: '狂猎自管区',
    academyName: '狂猎',
    academyFullName: '狂猎艺术学院',
    wardenName: 'Curator_Kestrel',
    bounds: { dimension: OVERWORLD, minX: -2176, minZ: -768, maxX: -2049, maxZ: -641 },
    rules: [
      '公共画廊里的作品别动，想改先问作者',
      '颜料工坊的材料用多少记多少',
      '雕塑园里别放红石机关，免得碰坏展品',
    ],
    createdDaysAgo: 21,
    unitPrice: 7,
    residents: [
      ['Curator_Kestrel', 20, SEED_ADMIN, 3, null],
      ['canvas_crow', 19, 'Curator_Kestrel', 8, null],
      ['EaselEel', 16, 'Curator_Kestrel', 30, null],
      ['Pigment_Pike', 10, 'Curator_Kestrel', 14, null],
      ['fresco_finch', 2, 'Curator_Kestrel', 5, null],
    ],
    log: [
      [2 * 24, 'Curator_Kestrel', 'warden', 'add', 'fresco_finch', null],
      [10 * 24, 'Curator_Kestrel', 'warden', 'add', 'Pigment_Pike', null],
      [20 * 24, SEED_ADMIN, 'admin', 'appoint', 'Curator_Kestrel', null],
    ],
    // 公共画廊的展示框和盔甲架住户也不许打掉。
    permissionChanges: [[6 * 24, 'Curator_Kestrel', 'warden', 'resident', 'break_entity', false]],
    plotLayout: { size: 24, columns: 4, gap: 8, margin: 4 },
    // 后加入的两人还没有地块; 05 是区务长后来划的一块大些的空地。
    plots: [
      { no: 1, owner: 'Curator_Kestrel' },
      { no: 2, owner: 'canvas_crow', friends: [['EaselEel', 12 * 24]] },
      { no: 3, owner: null },
      // 展示框里挂着画: 连朋友也不许转。
      {
        no: 4,
        owner: 'EaselEel',
        permissionChanges: [[4 * 24, 'EaselEel', 'owner', 'friend', 'itemframe', false]],
      },
      { no: 5, owner: null, rect: [4, 40, 40, 24] },
    ],
  },
]

/** 进过服但不属于任何学院的玩家 [名字, 几小时前在线]。含被移出过的人 (移出不等于从没进过服)。 */
const UNAFFILIATED_PLAYERS: readonly (readonly [string, number])[] = [
  ['Wanderer_Zed', 2],
  ['Pebble_Fox', 20],
  ['NovaLynx', 3],
  ['mintcrate', 100],
  ['LagSpike_Lou', 1_100],
  ['grief_gremlin', 130],
  ['mirage_moth', 1_500],
  ['tnt_tommy', 90],
  ['moss_mallow', 300],
  ['paper_crane_7', 400],
  [SEED_ADMIN, 1],
]

/**
 * 买地演示用的余额 [玩家名, 信用点] (见 MockDistrictWallet 为什么单记一份)。没列出的人按 0 算。
 * dustyboots 的数刻意卡在阿拜多斯-04 (2,304) 与 -05 (3,840) 之间: 一块买得起、一块差 840。
 */
const SEED_WALLETS: readonly (readonly [string, number])[] = [
  ['dustyboots', 3_000],
  ['cactus_juice99', 9_500],
  ['TumbleWeedo', 1_200],
  ['circuit_owl', 12_000],
  ['quartz_query', 7_000],
  ['chapel_mouse', 4_500],
  ['altar_ash', 2_000],
  ['Infernal_Ink', 6_000],
  ['LagSpike_Lou', 800],
]

/** 每区地块尺寸上下限的初值 (管理员可改)。 */
const SEED_MIN_SIDE = 8
const SEED_MAX_SIDE = 48

export function pad2(value: number): string {
  return String(value).padStart(2, '0')
}

function plotBounds(district: DistrictBounds, layout: PlotLayout, plot: PlotSeed): DistrictBounds {
  if (plot.rect !== undefined) {
    const [dx, dz, width, depth] = plot.rect
    return {
      dimension: district.dimension,
      minX: district.minX + dx,
      minZ: district.minZ + dz,
      maxX: district.minX + dx + width - 1,
      maxZ: district.minZ + dz + depth - 1,
    }
  }
  const index = plot.no - 1
  const column = index % layout.columns
  const row = Math.floor(index / layout.columns)
  const step = layout.size + layout.gap
  const minX = district.minX + layout.margin + column * step
  const minZ = district.minZ + layout.margin + row * step
  return {
    dimension: district.dimension,
    minX,
    minZ,
    maxX: minX + layout.size - 1,
    maxZ: minZ + layout.size - 1,
  }
}

/**
 * 种子地块本身必须满足划地块的全部规则 (在区内、离边界够远、尺寸在上下限内、互不重叠) ——
 * 否则区务长一调范围就会被自己本来就不合规的地块卡住, 预览里看起来像是校验写错了。
 */
function assertSeedPlotsValid(seed: DistrictSeed, plots: readonly MockDistrictPlotRecord[]): void {
  const rules = { edgeGap: PLOT_EDGE_GAP, minSide: SEED_MIN_SIDE, maxSide: SEED_MAX_SIDE }
  plots.forEach((plot, index) => {
    const others = plots
      .slice(0, index)
      .map((other) => ({ plotId: other.plotId, code: other.code, area: plotAreaOf(other.bounds) }))
    const problem = checkPlotArea(seed.bounds, plotAreaOf(plot.bounds), rules, others)
    if (problem !== null) {
      throw new Error(`mock 种子缺陷: ${plot.code} 不合规 (${problem.code}: ${problem.message})`)
    }
  })
}

/** 种子里管理员替户主做的改动: 与 district-handlers 写记录时同一口径 (管理员提前收回、划地块不算)。 */
const OVERRIDE_ACTIONS: ReadonlySet<PlotLogAction> = new Set([
  'addFriend',
  'removeFriend',
  'restoreFriend',
  'permission',
  'resize',
])

function seedOnBehalfOfOwner(role: PlotActorRole, action: PlotLogAction): boolean {
  return role === 'admin' && OVERRIDE_ACTIONS.has(action)
}

type PlotLogDraft = Omit<PlotLogEntry, 'entryId'>

/**
 * 造一份全新的自管区世界。与 createInitialWorld 同纪律: 每次调用都返回互不共享引用的新对象,
 * 否则 resetWorld 之后上一轮的增删会跟着回来。
 */
export function createDistrictWorld(epoch: number): MockDistrictWorld {
  assertPlotDefaultIds()
  // 先把所有进过服的人登记齐, 再造地块: 朋友可能是排在后面的别的学院的住户, 边造边登记会把 TA 误判成待生效。
  const knownPlayers: MockKnownPlayer[] = UNAFFILIATED_PLAYERS.map(([name, hoursAgo]) => ({
    name,
    lastSeenAt: epoch - hoursAgo * HOUR,
  }))
  for (const seed of DISTRICT_SEEDS) {
    for (const [playerName, , , lastSeenHoursAgo] of seed.residents) {
      if (lastSeenHoursAgo !== null) {
        knownPlayers.push({ name: playerName, lastSeenAt: epoch - lastSeenHoursAgo * HOUR })
      }
    }
  }
  const isKnown = (name: string): boolean =>
    knownPlayers.some((player) => player.name.toLowerCase() === name.toLowerCase())

  let logSeq = 0
  function stamp<T extends { at: number }>(drafts: T[], prefix: string): (T & { entryId: string })[] {
    return drafts
      .sort((left, right) => right.at - left.at)
      .map((draft) => {
        logSeq += 1
        return { ...draft, entryId: `${prefix}-${String(logSeq)}` }
      })
  }

  function buildPlot(seed: DistrictSeed, plot: PlotSeed): MockDistrictPlotRecord {
    const owner = plot.owner
    const frozen = plot.frozen
    if (owner !== null && frozen !== undefined) {
      throw new Error(`mock 种子缺陷: ${seed.districtId} 第 ${String(plot.no)} 块地既有户主又在冻结`)
    }
    // 朋友是谁加的: 现任户主, 冻结中的就是原户主。
    const keeper = owner ?? frozen?.[0] ?? null
    const friendRows = plot.friends ?? []
    if (keeper === null && friendRows.length > 0) {
      throw new Error(`mock 种子缺陷: ${seed.districtId} 第 ${String(plot.no)} 块地空置却有朋友`)
    }
    const friends: MockPlotFriendRecord[] = friendRows.map(([playerName, hoursAgo, suspendedHoursAgo]) => ({
      playerName,
      addedAt: epoch - hoursAgo * HOUR,
      addedBy: keeper ?? '',
      syncStatus: isKnown(playerName) ? 'synced' : 'pending',
      suspendedAt: suspendedHoursAgo === undefined ? null : epoch - suspendedHoursAgo * HOUR,
    }))
    const drafts: PlotLogDraft[] = [
      ...friends.map(
        (friend): PlotLogDraft => ({
          at: friend.addedAt,
          actorName: friend.addedBy,
          actorRole: 'owner',
          action: 'addFriend',
          targetName: friend.playerName,
          reason: null,
          permission: null,
          area: null,
          onBehalfOfOwner: false,
        }),
      ),
      ...(plot.history ?? []).map(
        ([hoursAgo, actorName, actorRole, action, targetName, reason]): PlotLogDraft => ({
          at: epoch - hoursAgo * HOUR,
          actorName,
          actorRole,
          action,
          targetName,
          reason,
          permission: null,
          area: null,
          onBehalfOfOwner: seedOnBehalfOfOwner(actorRole, action),
        }),
      ),
    ]

    // 同公共区域: 从旧到新依次落到开关表上, "改之前"取落到那一刻的值。
    const permissions = defaultPlotPermissions()
    const changes = [...(plot.permissionChanges ?? [])].sort((left, right) => right[0] - left[0])
    for (const [hoursAgo, actorName, actorRole, audience, permissionId, to] of changes) {
      const values = permissions[permissionId]
      if (values === undefined) {
        throw new Error(`mock 种子缺陷: 地块里没有 ${permissionId} 这一项 (区域规则不能在地块里改)`)
      }
      const from = values[audience]
      values[audience] = to
      drafts.push({
        at: epoch - hoursAgo * HOUR,
        actorName,
        actorRole,
        action: 'permission',
        targetName: null,
        reason: null,
        permission: { permissionId, label: permissionDefOf(permissionId).label, audience, from, to },
        area: null,
        onBehalfOfOwner: seedOnBehalfOfOwner(actorRole, 'permission'),
      })
    }

    // 收回过的地块: 最近一次收回之前的记录归档 (同 district-handlers 的 vacatePlot), 户主看不到。
    const log = stamp(drafts, 'plotlog')
    const lastVacate = log.findIndex((entry) => entry.action === 'vacate')
    return {
      plotId: `${seed.districtId}-${pad2(plot.no)}`,
      code: `${seed.academyName}-${pad2(plot.no)}`,
      bounds: plotBounds(seed.bounds, seed.plotLayout, plot),
      ownerName: owner,
      frozen: frozen === undefined ? null : { formerOwnerName: frozen[0], frozenAt: epoch - frozen[1] * HOUR },
      friends,
      permissions,
      log: lastVacate === -1 ? log : log.slice(0, lastVacate + 1),
      archivedLog: lastVacate === -1 ? [] : log.slice(lastVacate + 1),
      syncStatus: plot.syncError === undefined ? 'synced' : 'failed',
      syncError: plot.syncError ?? null,
    }
  }

  const districts = DISTRICT_SEEDS.map((seed): MockDistrictRecord => {
    const residents = seed.residents.map(([playerName, daysAgo, addedBy, lastSeenHoursAgo, syncError]) => {
      const syncStatus: DistrictSyncStatus =
        lastSeenHoursAgo === null ? 'pending' : syncError === null ? 'synced' : 'failed'
      return { playerName, joinedAt: epoch - daysAgo * DAY, addedBy, syncStatus, syncError }
    })
    type LogDraft = Omit<DistrictLogEntry, 'entryId'>
    const drafts: LogDraft[] = seed.log.map(([hoursAgo, actorName, actorRole, action, targetName, reason]) => ({
      at: epoch - hoursAgo * HOUR,
      actorName,
      actorRole,
      action,
      targetName,
      reason,
      permission: null,
      area: null,
    }))

    // 权限改动从旧到新依次落到开关表上, 每条的"改之前"取落到那一刻的值, 与真服逐次写入的记录一致。
    const permissions = defaultPermissions()
    for (const [hoursAgo, actorName, actorRole, audience, permissionId, to] of [...seed.permissionChanges].reverse()) {
      const def = permissionDefOf(permissionId)
      const values = permissions[permissionId]
      const from = values?.[audience]
      if (values === undefined || from === null || from === undefined) {
        throw new Error(`mock 种子缺陷: ${seed.districtId} 的 ${permissionId} 没有"${audience}"这一列`)
      }
      values[audience] = to
      drafts.push({
        at: epoch - hoursAgo * HOUR,
        actorName,
        actorRole,
        action: 'permission',
        targetName: null,
        reason: null,
        permission: { permissionId, label: def.label, audience, from, to },
        area: null,
      })
    }

    const isResident = (name: string): boolean =>
      seed.residents.some(([residentName]) => residentName.toLowerCase() === name.toLowerCase())
    const plots = seed.plots.map((plot) => {
      const owner = plot.owner
      if (owner !== null && !isResident(owner)) {
        throw new Error(`mock 种子缺陷: ${seed.districtId} 第 ${String(plot.no)} 块地的户主不是本区住户`)
      }
      if (plot.frozen !== undefined && isResident(plot.frozen[0])) {
        throw new Error(`mock 种子缺陷: ${seed.districtId} 第 ${String(plot.no)} 块地冻结中, 原户主却还是本区住户`)
      }
      return buildPlot(seed, plot)
    })
    assertSeedPlotsValid(seed, plots)

    return {
      districtId: seed.districtId,
      displayName: seed.displayName,
      academyName: seed.academyName,
      academyFullName: seed.academyFullName,
      wardenName: seed.wardenName,
      bounds: { ...seed.bounds },
      rules: [...seed.rules],
      createdAt: epoch - seed.createdDaysAgo * DAY,
      residents,
      log: stamp(drafts, 'log'),
      permissions,
      plots,
      deletedPlots: [],
      nextPlotNo: Math.max(0, ...seed.plots.map((plot) => plot.no)) + 1,
      unitPrice: seed.unitPrice,
      minSide: SEED_MIN_SIDE,
      maxSide: SEED_MAX_SIDE,
      // 一律默认关, 与真服的出厂值一致 (真服开之前要先确认登录门是 REQUIRED 档)。预览里由管理员在"管理员操作"里打开再试买地。
      purchaseOpen: false,
    }
  })

  return {
    districts,
    archived: [],
    knownPlayers,
    wallets: SEED_WALLETS.map(([playerName, credit]) => ({ playerName, credit })),
    previewAs: null,
    nextLogSeq: logSeq + 1,
  }
}
