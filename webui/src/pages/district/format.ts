/**
 * 自管区页共用的文案表与格式化。
 *
 * 文案口径: 面向玩家和服主, 口语短句, 不出现内部字段名。"Flan" 这个词只在管理员视角出现 ——
 * 住户和区务长只需要知道"领地权限生没生效", 不需要知道背后是哪个模组。
 */

import type { FeedbackTone, Tone } from '@/components/kit'
import { formatAmount } from '@/components/kit'
import { WebUiCallError } from '@/lib/bridge'
import { callErrorText } from '@/lib/errorText'
import type {
  DistrictLogAction,
  DistrictLogActorRole,
  DistrictPermissionAudience,
  DistrictRole,
  DistrictSyncStatus,
  PlotActorRole,
  PlotArea,
  PlotAudience,
  PlotBuyBlock,
  PlotLogAction,
  PlotStatus,
} from '@/lib/types'

export const ROLE_LABEL: Record<DistrictRole, string> = {
  admin: '管理员',
  warden: '区务长',
  resident: '住户',
  outsider: '外人',
}

/** 身份一句话说明, 画在页顶身份条里。 */
export const ROLE_SUMMARY: Record<DistrictRole, string> = {
  admin: '可以管理全部自管区：圈地、改边界、解除绑定，任命区务长，给地块定价，管理住户和权限，查看和代改各户地块',
  warden:
    '由管理员任命（不是 OP）：可以添加、移出本区住户，开关公共区域的权限，在本区划地块，查看地块列表和操作记录；自管区本身由管理员负责',
  resident: '可以在本区建造和日常交互：公共区域按区务长的设置；有地块的自己做主，没有的可以直接买一块空置的',
  outsider: '可以查看各学院自管区的公开信息',
}

/** 本区操作记录里的操作人身份。住户只会出现在自己买地那一条; 服务器是冻结期满自动收回。 */
export const LOG_ACTOR_LABEL: Record<DistrictLogActorRole, string> = {
  admin: '管理员',
  warden: '区务长',
  resident: '住户',
  system: '服务器',
}

/** 公共区域的权限开关管的是谁。 */
export const AUDIENCE_LABEL: Record<DistrictPermissionAudience, string> = {
  resident: '住户',
  outsider: '外人',
  district: '全区',
}

/** 地块的权限开关管的是谁。 */
export const PLOT_AUDIENCE_LABEL: Record<PlotAudience, string> = {
  friend: '朋友',
  resident: '其他住户',
  outsider: '外人',
}

export const PLOT_LOG_ACTION_LABEL: Record<PlotLogAction, string> = {
  create: '划出地块',
  resize: '调整范围',
  purchase: '买下地块',
  addFriend: '添加朋友',
  removeFriend: '移除朋友',
  suspendFriend: '暂停朋友',
  restoreFriend: '恢复朋友',
  permission: '改权限',
  freeze: '冻结地块',
  unfreeze: '解除冻结',
  vacate: '收回地块',
}

export const PLOT_LOG_ACTION_TONE: Record<PlotLogAction, Tone> = {
  create: 'info',
  resize: 'info',
  purchase: 'success',
  addFriend: 'success',
  removeFriend: 'danger',
  suspendFriend: 'warning',
  restoreFriend: 'success',
  permission: 'brand',
  freeze: 'warning',
  unfreeze: 'info',
  vacate: 'warning',
}

/**
 * 地块记录里操作人的身份。管理员替户主改设置的那几条不看这张表, 另标"管理员代改" (OVERRIDE_TAG):
 * 户主一眼要看出这条不是自己改的。
 */
export const PLOT_ACTOR_LABEL: Record<PlotActorRole, string> = {
  owner: '户主',
  admin: '管理员',
  warden: '区务长',
  system: '服务器',
}

export const OVERRIDE_TAG = '管理员代改'

export function onOffLabel(enabled: boolean): string {
  return enabled ? '开' : '关'
}

export const SYNC_STATUS_LABEL: Record<DistrictSyncStatus, string> = {
  synced: '已生效',
  pending: '待生效',
  failed: '同步失败',
}

export const SYNC_STATUS_TONE: Record<DistrictSyncStatus, Tone> = {
  synced: 'success',
  pending: 'warning',
  failed: 'danger',
}

export const LOG_ACTION_LABEL: Record<DistrictLogAction, string> = {
  add: '添加住户',
  remove: '移出住户',
  appoint: '任命区务长',
  revoke: '撤销区务长',
  resync: '重试同步',
  permission: '改权限',
  createPlot: '划出地块',
  resizePlot: '调整地块',
  deletePlot: '删除地块',
  buyPlot: '买下地块',
  freezePlot: '冻结地块',
  unfreezePlot: '解除冻结',
  vacatePlot: '收回地块',
  suspendFriends: '暂停朋友',
  setPlotPricing: '改地块定价',
  setPurchaseOpen: '开关购买',
}

export const LOG_ACTION_TONE: Record<DistrictLogAction, Tone> = {
  add: 'success',
  remove: 'danger',
  appoint: 'info',
  revoke: 'warning',
  resync: 'neutral',
  permission: 'brand',
  createPlot: 'info',
  resizePlot: 'info',
  deletePlot: 'danger',
  buyPlot: 'success',
  freezePlot: 'warning',
  unfreezePlot: 'info',
  vacatePlot: 'warning',
  suspendFriends: 'warning',
  setPlotPricing: 'brand',
  setPurchaseOpen: 'brand',
}

/**
 * 地块状态的名称与色调。平面图、列表、详情三处共用名称; 平面图上的填色与图例另在 PlotMap 的 KIND_CLASS / PLOT_LEGEND
 * (多一档"我的", 且按形状区分), 改状态时两边一起看。
 */
export const PLOT_STATUS_LABEL: Record<PlotStatus, string> = {
  owned: '已有户主',
  vacant: '空置',
  frozen: '冻结中',
}

export const PLOT_STATUS_TONE: Record<PlotStatus, Tone> = {
  owned: 'neutral',
  vacant: 'success',
  frozen: 'warning',
}

/** 不能买地的原因 (服务端 market.viewerBlock)。NOT_RESIDENT 在住户视角不会出现, 管理员看的是另一套说明。 */
export const BUY_BLOCK_TEXT: Record<PlotBuyBlock, string> = {
  NOT_RESIDENT: '只有本区住户能买本区的地块',
  ALREADY_OWNS_PLOT: '你已经有一块地了，一人最多一块',
  HAS_FROZEN_PLOT: '你原来的地块还在冻结中，请先找管理员解除冻结或收回',
  PURCHASE_CLOSED: '本区暂未开放购买',
}

export function formatCredit(amount: number): string {
  return `${formatAmount(amount)} 信用点`
}

const DAY_MS = 24 * 3_600_000

/** 冻结还剩几天 (向上取整, 到期后为 0)。只做展示, 收回由服务端按 reclaimAt 定时执行。 */
export function freezeDaysLeft(reclaimAt: number, nowMs: number): number {
  return Math.max(0, Math.ceil((reclaimAt - nowMs) / DAY_MS))
}

/**
 * 冻结剩余时间分三档: 已到期 / 不到 1 天 / 还剩 N 天。"不到 1 天"单独一档: 向上取整会让离收回只差几分钟的地块
 * 还写着"还剩 1 天", 读的人以为还有一整天。
 */
type FreezeLeft = { kind: 'expired' } | { kind: 'underDay' } | { kind: 'days'; days: number }

function freezeLeft(reclaimAt: number, nowMs: number): FreezeLeft {
  const remaining = reclaimAt - nowMs
  if (remaining <= 0) {
    return { kind: 'expired' }
  }
  return remaining < DAY_MS ? { kind: 'underDay' } : { kind: 'days', days: freezeDaysLeft(reclaimAt, nowMs) }
}

/** "冻结中，还剩 6 天" / "冻结中，还剩不到 1 天" / 到期还没被收回时的说法。 */
export function freezeLeftText(reclaimAt: number, nowMs: number): string {
  const left = freezeLeft(reclaimAt, nowMs)
  if (left.kind === 'expired') {
    return '冻结期已满，等服务器收回'
  }
  return left.kind === 'underDay' ? '冻结中，还剩不到 1 天' : `冻结中，还剩 ${String(left.days)} 天`
}

/** 列表状态格第二行的短说法 (状态标签已写"冻结中")。 */
export function freezeShortText(reclaimAt: number, nowMs: number): string {
  const left = freezeLeft(reclaimAt, nowMs)
  if (left.kind === 'expired') {
    return '已到期'
  }
  return left.kind === 'underDay' ? '还剩不到 1 天' : `还剩 ${String(left.days)} 天`
}

/** 平面图地块里那一行, 再短一点 ("剩 6 天" / "不到 1 天"): 小地块里也要放得下。 */
export function freezeMapText(reclaimAt: number, nowMs: number): string {
  const left = freezeLeft(reclaimAt, nowMs)
  if (left.kind === 'expired') {
    return '已到期'
  }
  return left.kind === 'underDay' ? '不到 1 天' : `剩 ${String(left.days)} 天`
}

const DIMENSION_LABEL: Readonly<Record<string, string>> = {
  'minecraft:overworld': '主世界',
  'minecraft:the_nether': '下界',
  'minecraft:the_end': '末地',
}

/** 未收录的维度原样显示 id, 不编一个中文名。 */
export function dimensionLabel(dimension: string): string {
  return Object.hasOwn(DIMENSION_LABEL, dimension) ? (DIMENSION_LABEL[dimension] ?? dimension) : dimension
}

export function formatDate(epochMs: number): string {
  return new Date(epochMs).toLocaleDateString('zh-CN', { year: 'numeric', month: 'numeric', day: 'numeric' })
}

/** 表格里的紧凑日期: 今年的只写"月/日", 跨年才带年份 (住户表那一行小字要省宽度)。 */
export function formatShortDate(epochMs: number, nowMs: number): string {
  const date = new Date(epochMs)
  const sameYear = date.getFullYear() === new Date(nowMs).getFullYear()
  return sameYear
    ? date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
    : formatDate(epochMs)
}

export function formatDateTime(epochMs: number): string {
  return new Date(epochMs).toLocaleString('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
}

/** "3 小时前" 这一档的粗粒度相对时间。只做展示, 不参与任何判定。 */
export function formatRelative(epochMs: number, nowMs: number): string {
  const minutes = Math.max(0, Math.floor((nowMs - epochMs) / 60_000))
  if (minutes < 1) {
    return '刚刚'
  }
  if (minutes < 60) {
    return `${String(minutes)} 分钟前`
  }
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    return `${String(hours)} 小时前`
  }
  return `${String(Math.floor(hours / 24))} 天前`
}

export function formatArea(area: number): string {
  return `${area.toLocaleString('zh-CN')} 格`
}

/**
 * 从列表数出来的个数。列表被服务端截断 (回执里对应的截断标记为真) 时, 数出来的只是下限, 写成"至少 N":
 * 照常写"N"等于把"只收到 N 项"说成"一共 N 项"。
 */
export function countText(count: number, truncated: boolean): string {
  return truncated ? `至少 ${String(count)}` : String(count)
}

/**
 * 地块列表 (district.plots 的 plots) 被截断时"其余的去哪看"那半句, 画地块的几处共用; 前半句各处按自己画的东西写。
 * 完整清单管理员在游戏里能列出来 (/district plots <自管区代号>, 仅 OP)。
 */
export const PLOTS_TRUNCATED_ELSEWHERE = '其余的请联系管理员在游戏里用 /district plots 命令查看'

/**
 * 记录类列表 (本区操作记录、地块记录, 以及它们的归档与留档) 被截断时的那句提示。记录一律新的在前, 截掉的是最旧的一段。
 * 不指到 /district 命令去: 没有能翻旧记录的子命令 (/district info 只列最近十条本区记录, 地块记录没有命令可查)。
 */
export function logTruncatedText(shown: number): string {
  return `记录太多，这里只显示了最近的 ${String(shown)} 条，更早的没有显示；要查更早的记录请联系管理员。`
}

/**
 * "我是哪几块地的朋友" (district.state 的 friendOf) 被截断时的那句提示。shown 是服务端实际下发的块数, 跨全部自管区。
 * 要说明白没列出来的不等于不是朋友: 截掉的只是这份清单, 游戏里的朋友身份 (含待生效、已暂停这些状态) 不受影响。
 * 同样不指到 /district 命令去: 没有按玩家查朋友地块的子命令。
 */
export function friendOfTruncatedText(shown: number): string {
  return `把你加成朋友的地块太多，平板上只列得出前 ${String(shown)} 块（所有自管区合计）；没列出来的那些，你的朋友身份不受影响，想知道是哪几块请问户主或联系管理员。`
}

/** 范围的长 x 宽 (方块数, 含两端)。自管区范围与地块范围都能传 (前者多一个维度字段)。 */
export function boundsSize(bounds: PlotArea): string {
  const width = bounds.maxX - bounds.minX + 1
  const depth = bounds.maxZ - bounds.minZ + 1
  return `${String(width)} × ${String(depth)}`
}

export function formatBounds(bounds: PlotArea): string {
  return `X ${String(bounds.minX)} ~ ${String(bounds.maxX)}，Z ${String(bounds.minZ)} ~ ${String(bounds.maxZ)}`
}

/** 收回之后原户主留在地里的东西怎么办还没拍板: 冻结、收回、买地几处都要照实说一句, 口径统一用这句。 */
export const RECLAIM_LEFTOVER_NOTE = '收回后地块里原户主留下的东西怎么处理还在定。'

/** 一次调用失败该给人看的那句话: 有码且收录的走文案表, 否则原样带出服务端原文 (见 lib/errorText)。 */
export function describeFailure(error: unknown): string {
  return error instanceof Error ? callErrorText(error) : String(error)
}

/** 业务拒绝的机器码; 非业务拒绝 (超时 / 桥断了) 为 null。 */
export function failureCode(error: unknown): string | null {
  return error instanceof WebUiCallError && error.business !== null ? error.business.errorCode : null
}

/** 页面内回执条的内容。seq 只当 React key 用 (同文案连发两次也要重建实例, 理由见 AdminPage)。 */
export interface DistrictToast {
  seq: number
  tone: FeedbackTone
  message: string
  title?: string | undefined
}
