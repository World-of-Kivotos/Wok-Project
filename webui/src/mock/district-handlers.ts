/**
 * 自管区二十六条 (district.* / plot.* / admin.district.* / admin.plot.*, 接线清单 K 组) 在 lib/bridge.mock 里的
 * 假实现: 开发构建没有宿主时, bridge.mock 的 resolveMock 把这 26 条转给本文件的 resolveDistrictMock, 由内存世界
 * 回答。装进游戏 (桥已注入) 或生产构建里这里的任何一行都不会作答 —— 那时走真服 DistrictWebUiActions。
 *
 * 规则以 Java 为准 (com.miningdim.district, 设计见 docs/District_Backend_Design.md); 本文件只复刻到"点下去有后果、
 * 拒绝码与真服相同"的程度 —— 码按设计文档 15.2 发服务端的码, 拒绝形状与 bridge.mock 的 businessFailure 相同
 * (身份不够 PERMISSION_DENIED、形状错误 INVALID_REQUEST + params.field)。已知与真服不同的地方:
 *   - 写 Flan 恒成功, 生效状态恒为已生效 (真服在 Flan 用不了的降级状态下会如实报失败);
 *   - 没有回执体积预算, ★ 截断标记恒为 false;
 *   - 冻结期满不自动收回 (见下)。
 *
 * 这里复刻的**已拍板的权限模型**:
 *   - 管理员 (OP): 一切; 区务长 (由 OP 任命, 本身不是 OP): 仅本区的加/移住户、查看记录、查看地块列表、
 *     开关本区**公共区域**住户/外人的权限 (区域规则除外), 以及在本区划地块、调整或删除**空置**地块;
 *     住户: 看本区信息与地块列表 (不含朋友数与生效状态), 管自己的地块, 没有地块时直接买一块空置的;
 *     外人: 只有公开摘要 (含"外人在各区能做什么"、地块块数)。谁都拿得到"我是哪几块地的朋友"这一份自己的数据。
 *   - 开关值只收 boolean: 缺省、null 一律 INVALID_REQUEST 拒绝 —— 写进 Flan 就成了"不设置", 会回退到上一层。
 *   - 地块 (K11-K16): 户主本人管自己地块的朋友和三列权限; 区务长改别人的地块一律拒绝 (户主的家由户主做主);
 *     管理员可以代改, 每条记录标 onBehalfOfOwner, 户主在地块记录里看得到。
 *   - 划地块 (K17-K19): 区务长只动本区空置地块; 有户主的地块只有管理员能调范围 (代改, 地块记录里通知户主);
 *     删除只删空置的; 冻结中的谁都不能调、不能删。范围校验见 district-geometry.ts。
 *   - 买地 (K20-K22): 直接购买, 先到先得; 单价与尺寸上下限只有管理员能设; 购买默认关。
 *   - 移出有地块的住户: 那块地原地冻结 7 天 (K4, 户主的朋友与设置原样存着), 管理员可以解除冻结或立即收回
 *     (K23 / K24); 因违反区规移出的, TA 在本区别人地块的朋友身份自动暂停 (各户主的地块记录里一条通知), 户主可恢复 (K25)。
 *   - 删除自管区只解除绑定 (K7): 学院成员名单保留, 操作记录归档 (K26)。
 *   - 权限开关的身份拦截全在这里做 (模拟服务端权威): 页面把按钮画成锁定只是提示, 绕过界面直接调也照样被拒。
 *   - 从没进过服的玩家第一次提交会被拦下 (PLAYER_NEVER_JOINED), 操作者确认后才以待生效记入名单 (住户与朋友同一流程)。
 *   - 一个人只能属于一个学院 (= 一个自管区; 解绑了的学院照样算)。
 *   - 区务长不能被区务长自己移出, 要先由管理员撤销。
 * 这些规则写在这里只为让开发构建的预览"点下去有后果"; 两边有出入时改这里, 不改 Java。
 *
 * 冻结期满的自动收回是服务端定时任务; 假世界不跑定时器, 到期之后仍显示冻结 (界面写"冻结期已满，等服务器收回",
 * 列表与平面图写"已到期"), 由管理员立即收回演示。
 *
 * 删除空置地块 (plot.delete) 不带走记录: 这块地自己的地块记录 (本任 + 历任户主的归档) 整份挪进本区的
 * deletedPlots 墓碑, 管理员仍可在 district.plots 里查 —— 区务长能删地块, 不能借此抹掉以前的记录。
 *
 * 身份: 以谁的名义看由 world.district.previewAs 决定 (null = 本机玩家), 是否管理员跟随 world.player.isOp ——
 * 与外壳顶栏的 OP 视图开关是同一个位, 于是"切成管理员"与"勾上 OP 视图"永远是同一件事, 侧栏的管理后台入口
 * 也跟着一起出现/消失。页面自己不读这两个字段 (契约守卫 F013), 只读回执里的 viewer.role。
 */

import type { WebUiActionName } from '../lib/actions'
import type { PayloadOf, ResultOf } from '../lib/bridge'
import { SERVER_FAILURE_CODE, WebUiCallError } from '../lib/bridge'
import { areaOfPlot, checkPlotArea, plotAreaOf } from './district-geometry'
import type {
  DistrictPreviewPersona,
  MockArchivedDistrictRecord,
  MockDeletedPlotRecord,
  MockDistrictPermissionDef,
  MockDistrictPermissionGroupDef,
  MockDistrictPlotRecord,
  MockDistrictRecord,
  MockDistrictResidentRecord,
  MockPlotFriendRecord,
} from './district-seed'
import {
  DISTRICT_FIXED_RULES,
  DISTRICT_PERMISSION_CATALOG,
  DISTRICT_PREVIEW_OPTIONS,
  PLOT_EDGE_GAP,
  PLOT_FREEZE_DAYS,
  PLOT_FRIEND_LIMIT,
  PLOT_LOG_NOTE,
  defaultPlotPermissions,
  pad2,
} from './district-seed'
import type {
  DeletedPlot,
  DistrictAbilities,
  DistrictLogAction,
  DistrictLogActorRole,
  DistrictLogEntry,
  DistrictPermissionAudience,
  DistrictPermissionChange,
  DistrictPermissionItem,
  DistrictPermissionValues,
  DistrictPermissionsResult,
  DistrictResidency,
  DistrictResident,
  DistrictRole,
  DistrictSummary,
  PlotActorRole,
  PlotArea,
  PlotAreaChange,
  PlotAudience,
  PlotBuyBlock,
  PlotDetailResult,
  PlotFreezeInfo,
  PlotFriend,
  PlotFriendship,
  PlotInfo,
  PlotLogAction,
  PlotLogEntry,
  PlotMarket,
  PlotPermissionChange,
  PlotPermissionGroup,
  PlotPermissionItem,
  PlotPermissionValues,
  PlotRef,
  PlotRules,
  PlotStatus,
  PlotSummary,
  RemoveReasonKind,
} from '../lib/types'
import type { MockWorld } from './store'
import { cloneResult, getWorld, mutateWorld, nowMs } from './store'

/** 自管区的二十六条 action 名 (真契约 WebUiActionName 里的那一段)。 */
export type DistrictActionName = Extract<
  WebUiActionName,
  `district.${string}` | `admin.district.${string}` | `admin.plot.${string}` | `plot.${string}`
>

/** 管理员门的拒绝文案, 与真服 WebUiPermissions.requireOp 相同。 */
const OP_REQUIRED_MESSAGE = '需要 OP 权限'

/** Minecraft 正版 ID 的字符集与长度。服务端最终会按 GameProfile 再验一遍, 这里只拦显然不合法的输入。 */
const PLAYER_NAME_PATTERN = /^[A-Za-z0-9_]{3,16}$/

const DAY_MS = 24 * 3_600_000

const REMOVE_REASON_KINDS: readonly RemoveReasonKind[] = ['inactive', 'violation', 'selfRequest', 'other']

/** 移出原因去掉首尾空白后的字符上限, 与真服 DistrictLimits.MAX_REMOVE_REASON_CHARS 相同 (界面的补充说明限 60 字, 碰不到)。 */
const REMOVE_REASON_MAX_CHARS = 200

// ============================================================
// 失败
// ============================================================

/**
 * 带机器码的业务拒绝, 与 bridge.mock 的 businessFailure 同形 (WebUiCallError + SERVER_FAILURE_CODE, params 缺省即
 * 不写这一键): 面板的 catch 分支因此与真服一致; 页面要按码分支 (PLAYER_NEVER_JOINED 走"仍要添加"的确认)。
 */
function reject(
  action: DistrictActionName,
  errorCode: string,
  message: string,
  params?: Record<string, string>,
): WebUiCallError {
  return new WebUiCallError(action, SERVER_FAILURE_CODE, message, {
    errorCode,
    retrySameOpeningId: false,
    ...(params === undefined ? {} : { params }),
  })
}

/** 形状或取值不对的机器字段 (开关值、枚举取值): 与真服 DistrictRuleException.invalidRequest 同形。 */
function invalidRequest(action: DistrictActionName, field: string, value: unknown): WebUiCallError {
  if (typeof value !== 'string') {
    return reject(action, 'INVALID_REQUEST', `字段 ${field} 缺失或类型不对`, { field })
  }
  return reject(action, 'INVALID_REQUEST', `字段 ${field} 不接受这个取值`, { field, value: value.slice(0, 64) })
}

// ============================================================
// 身份与权限
// ============================================================

interface DistrictViewer {
  playerName: string
  role: DistrictRole
}

function sameName(left: string, right: string): boolean {
  // MC 玩家名大小写不敏感 (GameProfile 查找即如此), 名单比对跟着走, 否则 "Pebble_fox" 能把同一个人加两遍。
  return left.toLowerCase() === right.toLowerCase()
}

function homeDistrictOf(world: MockWorld, playerName: string): MockDistrictRecord | undefined {
  return world.district.districts.find((district) =>
    district.residents.some((resident) => sameName(resident.playerName, playerName)),
  )
}

/** 解绑了自管区、但成员名单还在的学院 (K7)。一个人仍只能属于一个学院, 加住户时要一并查。 */
function archivedAcademyOf(world: MockWorld, playerName: string): MockArchivedDistrictRecord | undefined {
  return world.district.archived.find((archived) =>
    archived.members.some((member) => sameName(member.playerName, playerName)),
  )
}

function resolveViewer(world: MockWorld): DistrictViewer {
  const playerName = world.district.previewAs ?? world.player.name
  if (world.player.isOp) {
    return { playerName, role: 'admin' }
  }
  const wardenOf = world.district.districts.find(
    (district) => district.wardenName !== null && sameName(district.wardenName, playerName),
  )
  if (wardenOf !== undefined) {
    return { playerName, role: 'warden' }
  }
  return { playerName, role: homeDistrictOf(world, playerName) === undefined ? 'outsider' : 'resident' }
}

/** 查看者对某个自管区的访问档。none = 无权看详情 (外人, 或别的学院的人)。 */
type DistrictAccess = 'admin' | 'warden' | 'resident' | 'none'

function accessOf(viewer: DistrictViewer, district: MockDistrictRecord): DistrictAccess {
  if (viewer.role === 'admin') {
    return 'admin'
  }
  if (district.wardenName !== null && sameName(district.wardenName, viewer.playerName)) {
    return 'warden'
  }
  return district.residents.some((resident) => sameName(resident.playerName, viewer.playerName))
    ? 'resident'
    : 'none'
}

const ABILITIES_BY_ACCESS: Record<Exclude<DistrictAccess, 'none'>, DistrictAbilities> = {
  admin: {
    build: true,
    interact: true,
    manageResidents: true,
    viewRoster: true,
    editClaim: true,
    deleteDistrict: true,
    appointWarden: true,
    retrySync: true,
    managePermissions: true,
    manageRegionRules: true,
    viewPlotList: true,
    viewPlotStatus: true,
    inspectPlots: true,
    overridePlots: true,
    managePlots: true,
    managePlotMarket: true,
    manageFrozenPlots: true,
  },
  warden: {
    build: true,
    interact: true,
    manageResidents: true,
    viewRoster: true,
    editClaim: false,
    deleteDistrict: false,
    appointWarden: false,
    retrySync: false,
    managePermissions: true,
    manageRegionRules: false,
    viewPlotList: true,
    viewPlotStatus: true,
    inspectPlots: false,
    overridePlots: false,
    // 区务长在本区划地块、调整或删除空置地块 (服务端代建子领地); 定价与冻结处置仍只归管理员。
    managePlots: true,
    managePlotMarket: false,
    manageFrozenPlots: false,
  },
  resident: {
    build: true,
    interact: true,
    manageResidents: false,
    viewRoster: false,
    editClaim: false,
    deleteDistrict: false,
    appointWarden: false,
    retrySync: false,
    managePermissions: false,
    manageRegionRules: false,
    // 住户也看得到本区地块和户主 (谁是邻居); 朋友数与生效状态是户主的私事, 不给。
    viewPlotList: true,
    viewPlotStatus: false,
    inspectPlots: false,
    overridePlots: false,
    managePlots: false,
    managePlotMarket: false,
    manageFrozenPlots: false,
  },
}

function requireDistrict(action: DistrictActionName, world: MockWorld, districtId: string): MockDistrictRecord {
  const district = world.district.districts.find((candidate) => candidate.districtId === districtId)
  if (district === undefined) {
    throw reject(action, 'DISTRICT_NOT_FOUND', '没有找到这个自管区，它可能刚被管理员解除绑定了')
  }
  return district
}

function requireManager(action: DistrictActionName, viewer: DistrictViewer, district: MockDistrictRecord): void {
  const access = accessOf(viewer, district)
  if (access !== 'admin' && access !== 'warden') {
    throw reject(action, 'PERMISSION_DENIED', '只有本区区务长或管理员可以管理住户', { action, requires: 'manager' })
  }
}

function requireAdmin(action: DistrictActionName, viewer: DistrictViewer): void {
  if (viewer.role !== 'admin') {
    throw reject(action, 'PERMISSION_DENIED', OP_REQUIRED_MESSAGE, { action })
  }
}

/**
 * 开关值只收 boolean。类型只管得住编译期, 绕过界面直接调的人什么都能传; 缺省或 null 若照写进 Flan 就成了
 * "不设置", 会回退到上一层 (公共区域的住户列回落到外人列, 地块回退到自管区) —— 正是"防漏"要堵的那条路。
 */
function requireSwitchValue(action: DistrictActionName, field: string, value: unknown): boolean {
  if (typeof value !== 'boolean') {
    throw invalidRequest(action, field, null)
  }
  return value
}

// ============================================================
// 回执组装
// ============================================================

function areaOfBounds(bounds: MockDistrictRecord['bounds']): number {
  const { minX, minZ, maxX, maxZ } = bounds
  return (maxX - minX + 1) * (maxZ - minZ + 1)
}

function plotStatusOf(plot: MockDistrictPlotRecord): PlotStatus {
  if (plot.frozen !== null) {
    return 'frozen'
  }
  return plot.ownerName === null ? 'vacant' : 'owned'
}

function vacantCount(district: MockDistrictRecord): number {
  return district.plots.filter((plot) => plotStatusOf(plot) === 'vacant').length
}

function summarize(district: MockDistrictRecord, forAdmin: boolean): DistrictSummary {
  return {
    districtId: district.districtId,
    displayName: district.displayName,
    academyName: district.academyName,
    academyFullName: district.academyFullName,
    wardenName: district.wardenName,
    residentCount: district.residents.length,
    area: areaOfBounds(district.bounds),
    plotCount: district.plots.length,
    vacantPlotCount: vacantCount(district),
    syncIssues: forAdmin
      ? {
          pending: district.residents.filter((resident) => resident.syncStatus === 'pending').length,
          failed: district.residents.filter((resident) => resident.syncStatus === 'failed').length,
        }
      : null,
  }
}

function ownedPlot(district: MockDistrictRecord, playerName: string): MockDistrictPlotRecord | undefined {
  return district.plots.find((plot) => plot.ownerName !== null && sameName(plot.ownerName, playerName))
}

/** 某人被移出时冻结、还没收回的旧地块。 */
function frozenPlotOf(district: MockDistrictRecord, playerName: string): MockDistrictPlotRecord | undefined {
  return district.plots.find((plot) => plot.frozen !== null && sameName(plot.frozen.formerOwnerName, playerName))
}

function plotRefOf(plot: MockDistrictPlotRecord): PlotRef {
  return { plotId: plot.plotId, code: plot.code }
}

function plotRefOfOwner(district: MockDistrictRecord, playerName: string): PlotRef | null {
  const plot = ownedPlot(district, playerName)
  return plot === undefined ? null : plotRefOf(plot)
}

/**
 * 某人是本区几块别人地块的 (未暂停的) 朋友。冻结中的地块也算: 解除冻结时朋友名单照原样回来,
 * 违反区规移出时那里的朋友身份同样要暂停。
 */
function friendPlotsOf(district: MockDistrictRecord, playerName: string): MockDistrictPlotRecord[] {
  return district.plots.filter(
    (plot) =>
      (plot.ownerName === null || !sameName(plot.ownerName, playerName)) &&
      (plot.ownerName !== null || plot.frozen !== null) &&
      plot.friends.some((friend) => sameName(friend.playerName, playerName) && friend.suspendedAt === null),
  )
}

function toResident(
  world: MockWorld,
  district: MockDistrictRecord,
  record: MockDistrictResidentRecord,
  forAdmin: boolean,
): DistrictResident {
  const known = world.district.knownPlayers.find((player) => sameName(player.name, record.playerName))
  return {
    playerName: record.playerName,
    joinedAt: record.joinedAt,
    addedBy: record.addedBy,
    lastSeenAt: known === undefined ? null : known.lastSeenAt,
    syncStatus: record.syncStatus,
    // 失败原因是 Flan 侧的排障原文, 只给管理员; 区务长只需知道"没生效、已交给管理员"。
    syncError: forAdmin ? record.syncError : null,
    isWarden: district.wardenName !== null && sameName(district.wardenName, record.playerName),
    plot: plotRefOfOwner(district, record.playerName),
    friendOfPlotCount: friendPlotsOf(district, record.playerName).length,
  }
}

function residencyOf(world: MockWorld, viewer: DistrictViewer): DistrictResidency | null {
  const home = homeDistrictOf(world, viewer.playerName)
  const record = home?.residents.find((resident) => sameName(resident.playerName, viewer.playerName))
  if (home === undefined || record === undefined) {
    return null
  }
  return {
    districtId: home.districtId,
    districtName: home.displayName,
    academyName: home.academyName,
    academyFullName: home.academyFullName,
    grantedAt: record.joinedAt,
    grantedBy: record.addedBy,
    isWarden: home.wardenName !== null && sameName(home.wardenName, viewer.playerName),
    syncStatus: record.syncStatus,
    plot: plotRefOfOwner(home, viewer.playerName),
  }
}

/**
 * 查看者对某个自管区的能力。住户与区务长的建造/交互跟着**自己那条** Flan 写入走: 名单上有你但写入没生效
 * (待生效 / 同步失败) 时, Flan 在游戏里照样拦你, 这里就不许说你能建。管理员 (OP) 不经居民组, 不受影响;
 * 管人的能力由服务端代办, 与本人的 Flan 状态无关, 也不受影响。
 */
function abilitiesOf(
  access: Exclude<DistrictAccess, 'none'>,
  district: MockDistrictRecord,
  viewer: DistrictViewer,
): DistrictAbilities {
  const base = ABILITIES_BY_ACCESS[access]
  if (access === 'admin') {
    return base
  }
  const own = district.residents.find((resident) => sameName(resident.playerName, viewer.playerName))
  const effective = own !== undefined && own.syncStatus === 'synced'
  return effective ? base : { ...base, build: false, interact: false }
}

type LogDraft = Omit<DistrictLogEntry, 'entryId' | 'at'>

/** 本区记录里操作人的身份。管理员、区务长之外的只有住户自己买地。 */
function logActorRole(viewer: DistrictViewer): DistrictLogActorRole {
  if (viewer.role === 'admin') {
    return 'admin'
  }
  return viewer.role === 'warden' ? 'warden' : 'resident'
}

function logDraft(
  viewer: DistrictViewer,
  action: Exclude<DistrictLogAction, 'permission'>,
  targetName: string | null,
  reason: string | null,
  area: PlotAreaChange | null = null,
): LogDraft {
  return {
    actorName: viewer.playerName,
    actorRole: logActorRole(viewer),
    action,
    targetName,
    reason,
    permission: null,
    area,
  }
}

function permissionLogDraft(
  viewer: DistrictViewer,
  change: DistrictPermissionChange,
  reason: string | null,
): LogDraft {
  return {
    actorName: viewer.playerName,
    actorRole: viewer.role === 'admin' ? 'admin' : 'warden',
    action: 'permission',
    targetName: null,
    reason,
    permission: change,
    area: null,
  }
}

// ============================================================
// 权限开关 (K8-K10, 公共区域)
// ============================================================

/** 能改开关的访问档: 管理员改全部, 区务长只改本区住户/外人两列。其余一律只读。 */
function isPermissionManager(access: DistrictAccess): access is 'admin' | 'warden' {
  return access === 'admin' || access === 'warden'
}

/** 某个 scope 的项有哪几列开关。 */
function audiencesOf(group: MockDistrictPermissionGroupDef): readonly DistrictPermissionAudience[] {
  return group.scope === 'region' ? ['district'] : ['resident', 'outsider']
}

function findPermission(
  permissionId: string,
): { group: MockDistrictPermissionGroupDef; def: MockDistrictPermissionDef } | undefined {
  for (const group of DISTRICT_PERMISSION_CATALOG) {
    const def = group.items.find((item) => item.permissionId === permissionId)
    if (def !== undefined) {
      return { group, def }
    }
  }
  return undefined
}

function permissionValuesOf(district: MockDistrictRecord, permissionId: string): DistrictPermissionValues {
  const values = district.permissions[permissionId]
  if (values === undefined) {
    // 种子按条目全量建表; 走到这里说明条目加了一项而种子没跟上, 是本 mock 自己的缺陷。
    throw new Error(`mock 缺陷: ${district.districtId} 的开关表里没有 ${permissionId}`)
  }
  return values
}

/**
 * 按查看者裁剪一项 (口径见 lib/types.ts K8): 不是本区住户的人看不到住户列; Flan id 只给管理员;
 * 高风险提示只给能改那一列的人。外人列与全区列对谁都公开 —— 走进领地一试便知; 住户也要外人列:
 * 本人居住权待生效 / 同步失败时, 游戏里按的正是它。
 */
function toPermissionItem(
  def: MockDistrictPermissionDef,
  group: MockDistrictPermissionGroupDef,
  values: DistrictPermissionValues,
  access: DistrictAccess,
): DistrictPermissionItem {
  const manager = isPermissionManager(access)
  const seesResident = manager || access === 'resident'
  const mask = (source: DistrictPermissionValues): DistrictPermissionValues => ({
    resident: seesResident ? source.resident : null,
    outsider: source.outsider,
    district: source.district,
  })
  return {
    permissionId: def.permissionId,
    label: def.label,
    detail: def.detail,
    scope: group.scope,
    current: mask(values),
    defaults: mask(def.defaults),
    flanIds: access === 'admin' ? [...def.flanIds] : null,
    flanInverted: access === 'admin' ? def.flanInverted : null,
    outsiderRisk: manager ? def.outsiderRisk : null,
    districtRisk: access === 'admin' ? def.districtRisk : null,
  }
}

function permissionsView(district: MockDistrictRecord, access: DistrictAccess): DistrictPermissionsResult {
  return {
    districtId: district.districtId,
    editable: { member: isPermissionManager(access), region: access === 'admin' },
    groups: DISTRICT_PERMISSION_CATALOG.map((group) => ({
      groupId: group.groupId,
      label: group.label,
      scope: group.scope,
      items: group.items.map((def) =>
        toPermissionItem(def, group, permissionValuesOf(district, def.permissionId), access),
      ),
    })),
    fixedRules: DISTRICT_FIXED_RULES.map((rule) => ({ ...rule })),
  }
}

// ============================================================
// 地块 (K11-K16)
// ============================================================

/** 地块开关的三列。运行期也拿它核对入参: 类型只管得住编译期, 绕过界面直接调的人什么都能传。 */
const PLOT_AUDIENCES: readonly PlotAudience[] = ['friend', 'resident', 'outsider']

/** 地块里能开关的分组: 目录去掉区域规则 (区域规则全区统一, 地块里不能改)。 */
const PLOT_GROUPS: readonly MockDistrictPermissionGroupDef[] = DISTRICT_PERMISSION_CATALOG.filter(
  (group) => group.scope === 'member',
)

function plotDefaultsOf(def: MockDistrictPermissionDef): PlotPermissionValues {
  if (def.plotDefaults === null) {
    throw new Error(`mock 缺陷: ${def.permissionId} 是区域规则, 没有地块默认值`)
  }
  return def.plotDefaults
}

function plotValuesOf(plot: MockDistrictPlotRecord, permissionId: string): PlotPermissionValues {
  const values = plot.permissions[permissionId]
  if (values === undefined) {
    throw new Error(`mock 缺陷: 地块 ${plot.plotId} 的开关表里没有 ${permissionId}`)
  }
  return values
}

function toPlotItem(
  def: MockDistrictPermissionDef,
  values: PlotPermissionValues,
  forAdmin: boolean,
): PlotPermissionItem {
  return {
    permissionId: def.permissionId,
    label: def.label,
    detail: def.detail,
    current: { ...values },
    defaults: { ...plotDefaultsOf(def) },
    flanIds: forAdmin ? [...def.flanIds] : null,
    flanInverted: forAdmin ? def.flanInverted : null,
    risk: def.plotRisk,
  }
}

function plotGroupsView(plot: MockDistrictPlotRecord, forAdmin: boolean): PlotPermissionGroup[] {
  return PLOT_GROUPS.map((group) => ({
    groupId: group.groupId,
    label: group.label,
    items: group.items.map((def) => toPlotItem(def, plotValuesOf(plot, def.permissionId), forAdmin)),
  }))
}

function residentDefaultLabels(): string[] {
  return PLOT_GROUPS.flatMap((group) => group.items)
    .filter((def) => plotDefaultsOf(def).resident)
    .map((def) => def.label)
}

function priceOf(district: MockDistrictRecord, plot: MockDistrictPlotRecord): number {
  return areaOfBounds(plot.bounds) * district.unitPrice
}

/** canRestore 只给区务长和管理员: 原户主回没回来, 住户用不着知道。 */
function freezeInfoOf(
  district: MockDistrictRecord,
  plot: MockDistrictPlotRecord,
  forManager: boolean,
): PlotFreezeInfo | null {
  const frozen = plot.frozen
  if (frozen === null) {
    return null
  }
  const back = district.residents.some((resident) => sameName(resident.playerName, frozen.formerOwnerName))
  return {
    formerOwnerName: frozen.formerOwnerName,
    frozenAt: frozen.frozenAt,
    reclaimAt: frozen.frozenAt + PLOT_FREEZE_DAYS * DAY_MS,
    canRestore: forManager ? back && ownedPlot(district, frozen.formerOwnerName) === undefined : null,
  }
}

function toPlotSummary(
  district: MockDistrictRecord,
  plot: MockDistrictPlotRecord,
  access: Exclude<DistrictAccess, 'none'>,
): PlotSummary {
  const manager = access === 'admin' || access === 'warden'
  const defs = PLOT_GROUPS.flatMap((group) => group.items)
  const status = plotStatusOf(plot)
  const frozen = status === 'frozen'
  return {
    plotId: plot.plotId,
    code: plot.code,
    status,
    ownerName: plot.ownerName,
    frozen: freezeInfoOf(district, plot, manager),
    bounds: plot.bounds,
    area: areaOfBounds(plot.bounds),
    price: status === 'vacant' ? priceOf(district, plot) : null,
    // 朋友名单是户主的私事: 住户只知道这块地是谁的、其他住户在里面能做什么。
    friendCount: manager ? plot.friends.length : null,
    syncStatus: manager ? plot.syncStatus : null,
    syncError: access === 'admin' ? plot.syncError : null,
    // 冻结中谁都进不去: 不列任何一项, 也不算"按默认"。
    openToResidents: frozen
      ? []
      : defs.filter((def) => plotValuesOf(plot, def.permissionId).resident).map((def) => def.label),
    residentColumnIsDefault:
      !frozen &&
      defs.every((def) => plotValuesOf(plot, def.permissionId).resident === plotDefaultsOf(def).resident),
  }
}

function toPlotInfo(district: MockDistrictRecord, plot: MockDistrictPlotRecord, forAdmin: boolean): PlotInfo {
  return {
    plotId: plot.plotId,
    code: plot.code,
    districtId: district.districtId,
    status: plotStatusOf(plot),
    ownerName: plot.ownerName,
    frozen: freezeInfoOf(district, plot, forAdmin),
    bounds: plot.bounds,
    area: areaOfBounds(plot.bounds),
    syncStatus: plot.syncStatus,
    syncError: forAdmin ? plot.syncError : null,
  }
}

function toFriend(district: MockDistrictRecord, record: MockPlotFriendRecord): PlotFriend {
  return {
    playerName: record.playerName,
    addedAt: record.addedAt,
    addedBy: record.addedBy,
    syncStatus: record.syncStatus,
    isResident: district.residents.some((resident) => sameName(resident.playerName, record.playerName)),
    suspended: record.suspendedAt !== null,
    suspendedAt: record.suspendedAt,
  }
}

/** 某人是哪几块地的朋友 (跨全部自管区, 按种子顺序)。空置与冻结中的地块没有生效的朋友, 不会出现。 */
function friendshipsOf(world: MockWorld, playerName: string): PlotFriendship[] {
  return world.district.districts.flatMap((district) =>
    district.plots.flatMap((plot): PlotFriendship[] => {
      const ownerName = plot.ownerName
      const record = plot.friends.find((friend) => sameName(friend.playerName, playerName))
      return ownerName === null || record === undefined
        ? []
        : [
            {
              districtId: district.districtId,
              plotId: plot.plotId,
              code: plot.code,
              ownerName,
              syncStatus: record.syncStatus,
              suspended: record.suspendedAt !== null,
            },
          ]
    }),
  )
}

/** 查看者和一块地的关系。户主优先: 万一管理员本人也有地块, 在自己地块里改的记为户主本人改的。 */
type PlotRelation = 'owner' | 'admin' | 'none'

function plotRelationOf(viewer: DistrictViewer, plot: MockDistrictPlotRecord): PlotRelation {
  if (plot.ownerName !== null && sameName(plot.ownerName, viewer.playerName)) {
    return 'owner'
  }
  return viewer.role === 'admin' ? 'admin' : 'none'
}

function plotDetailView(
  district: MockDistrictRecord,
  plot: MockDistrictPlotRecord,
  relation: Exclude<PlotRelation, 'none'>,
): PlotDetailResult {
  const forAdmin = relation === 'admin'
  return {
    plot: toPlotInfo(district, plot, forAdmin),
    viewerRelation: relation,
    // 空置与冻结中 ownerName 都是 null: 没有现任户主, 朋友与三列都不能改。
    editable: plot.ownerName !== null,
    friendLimit: PLOT_FRIEND_LIMIT,
    friends: plot.friends.map((friend) => toFriend(district, friend)),
    groups: plotGroupsView(plot, forAdmin),
    // 户主只看本任的记录; 管理员另接上历任户主期间的归档 (都在收回那条之后, 仍是新的在前)。
    log: forAdmin ? [...plot.log, ...plot.archivedLog] : plot.log,
    // 假世界没有回执体积预算, 截断标记恒为 false。
    logTruncated: false,
  }
}

/** 已删除地块的墓碑 (只给管理员, 见 district.plots)。 */
function toDeletedPlot(record: MockDeletedPlotRecord): DeletedPlot {
  return {
    plotId: record.plotId,
    code: record.code,
    bounds: record.bounds,
    deletedAt: record.deletedAt,
    deletedBy: record.deletedBy,
    log: record.log,
    logTruncated: false,
  }
}

function requirePlot(action: DistrictActionName, district: MockDistrictRecord, plotId: string): MockDistrictPlotRecord {
  const plot = district.plots.find((candidate) => candidate.plotId === plotId)
  if (plot === undefined) {
    throw reject(action, 'PLOT_NOT_FOUND', '没有找到这块地，页面可能过期了，请刷新')
  }
  return plot
}

/** 看不了这块地时的拒绝原文: 区务长单独说一句, 免得以为是自己权限没开。 */
function plotDenied(
  action: DistrictActionName,
  viewer: DistrictViewer,
  district: MockDistrictRecord,
  verb: '看' | '改',
): WebUiCallError {
  return reject(
    action,
    'PERMISSION_DENIED',
    accessOf(viewer, district) === 'warden'
      ? '别人的地块由户主做主，区务长只能看地块列表，不能看或改朋友和权限'
      : `只有户主本人和管理员能${verb}这块地的朋友和权限`,
    { action, requires: 'owner' },
  )
}

/** 能改这块地的人 (户主本人或管理员), 且这块地有现任户主。返回改动记在谁名下。 */
function requirePlotEditor(
  action: DistrictActionName,
  viewer: DistrictViewer,
  district: MockDistrictRecord,
  plot: MockDistrictPlotRecord,
): Exclude<PlotRelation, 'none'> {
  const relation = plotRelationOf(viewer, plot)
  if (relation === 'none') {
    throw plotDenied(action, viewer, district, '改')
  }
  if (plot.frozen !== null) {
    throw reject(action, 'PLOT_FROZEN', `${plot.code} 冻结中，朋友和权限要等解除冻结后才能改`)
  }
  if (plot.ownerName === null) {
    throw reject(action, 'PLOT_VACANT', `${plot.code} 现在空置，没有户主，不能加朋友或改权限`)
  }
  return relation
}

type PlotLogDraft = Omit<PlotLogEntry, 'entryId' | 'at'>

/** 户主或管理员改朋友、权限: 管理员改的一律标"代改" (户主一眼要看出不是自己改的)。 */
function plotLogDraft(
  viewer: DistrictViewer,
  relation: Exclude<PlotRelation, 'none'>,
  action: PlotLogAction,
  targetName: string | null,
  reason: string | null,
  permission: PlotPermissionChange | null,
): PlotLogDraft {
  return {
    actorName: viewer.playerName,
    actorRole: relation === 'owner' ? 'owner' : 'admin',
    action,
    targetName,
    reason,
    permission,
    area: null,
    onBehalfOfOwner: relation === 'admin',
  }
}

/** 区务长或管理员以管理身份写的地块记录 (划地块、冻结、暂停朋友、收回…), 不是替户主改设置。 */
function managerPlotLogDraft(
  viewer: DistrictViewer,
  action: PlotLogAction,
  targetName: string | null,
  reason: string | null,
  { area = null, onBehalfOfOwner = false }: { area?: PlotAreaChange | null; onBehalfOfOwner?: boolean } = {},
): PlotLogDraft {
  const actorRole: PlotActorRole = viewer.role === 'admin' ? 'admin' : 'warden'
  return { actorName: viewer.playerName, actorRole, action, targetName, reason, permission: null, area, onBehalfOfOwner }
}

/**
 * 收回地块: 户主、朋友清空, 冻结解除, 三列整张写回默认 (真服同样是整块重写, 见 lib/types.ts K11)。
 * 这块地此前的记录整份归档: 下一任户主不该看到上一任的朋友名单变动, 只有管理员还看得到 (见 plotDetailView)。
 */
function vacatePlot(plot: MockDistrictPlotRecord): void {
  plot.ownerName = null
  plot.frozen = null
  plot.friends = []
  plot.permissions = defaultPlotPermissions()
  plot.archivedLog = [...plot.log, ...plot.archivedLog]
  plot.log = []
  plot.syncStatus = 'synced'
  plot.syncError = null
}

// ============================================================
// 划地块与买地 (K17-K22)
// ============================================================

function rulesOf(district: MockDistrictRecord): PlotRules {
  return { edgeGap: PLOT_EDGE_GAP, minSide: district.minSide, maxSide: district.maxSide }
}

function walletOf(world: MockWorld, playerName: string): number {
  return world.district.wallets.find((wallet) => sameName(wallet.playerName, playerName))?.credit ?? 0
}

/** 查看者现在为什么不能买地 (与 plot.buy 的拒绝同一顺序); null = 能买。 */
function buyBlockOf(district: MockDistrictRecord, viewer: DistrictViewer): PlotBuyBlock | null {
  if (viewer.role === 'admin' || !district.residents.some((resident) => sameName(resident.playerName, viewer.playerName))) {
    return 'NOT_RESIDENT'
  }
  if (ownedPlot(district, viewer.playerName) !== undefined) {
    return 'ALREADY_OWNS_PLOT'
  }
  if (frozenPlotOf(district, viewer.playerName) !== undefined) {
    return 'HAS_FROZEN_PLOT'
  }
  return district.purchaseOpen ? null : 'PURCHASE_CLOSED'
}

function marketOf(world: MockWorld, district: MockDistrictRecord, viewer: DistrictViewer): PlotMarket {
  const block = buyBlockOf(district, viewer)
  return {
    unitPrice: district.unitPrice,
    open: district.purchaseOpen,
    viewerBlock: block,
    viewerBalance: block === null ? walletOf(world, viewer.playerName) : null,
  }
}

/** 入参里的范围: 类型只管编译期, 绕过界面直接调的什么都能传。先核成四个数, 再交给几何校验。 */
function requireArea(action: DistrictActionName, value: unknown): PlotArea {
  const area = value as Partial<Record<keyof PlotArea, unknown>> | null
  const read = (key: keyof PlotArea): number => {
    const raw = area?.[key]
    if (typeof raw !== 'number' || !Number.isInteger(raw)) {
      throw reject(action, 'INVALID_AREA', '坐标必须是整数', { field: key })
    }
    return raw
  }
  return { minX: read('minX'), minZ: read('minZ'), maxX: read('maxX'), maxZ: read('maxZ') }
}

function requireValidArea(
  action: DistrictActionName,
  district: MockDistrictRecord,
  area: PlotArea,
  ignorePlotId: string | null,
): void {
  const others = district.plots
    .filter((plot) => plot.plotId !== ignorePlotId)
    .map((plot) => ({ plotId: plot.plotId, code: plot.code, area: plotAreaOf(plot.bounds) }))
  const problem = checkPlotArea(district.bounds, area, rulesOf(district), others)
  if (problem !== null) {
    throw reject(action, problem.code, problem.message)
  }
}

/** 划 / 调 / 删地块: 本区区务长或管理员。住户、别区的区务长一律拒绝。 */
function requirePlotManager(action: DistrictActionName, viewer: DistrictViewer, district: MockDistrictRecord): void {
  const access = accessOf(viewer, district)
  if (access !== 'admin' && access !== 'warden') {
    throw reject(action, 'PERMISSION_DENIED', '只有本区区务长和管理员可以划地块', { action, requires: 'manager' })
  }
}

function formatCredit(amount: number): string {
  return `${amount.toLocaleString('zh-CN')} 信用点`
}

function sideText(area: PlotArea): string {
  return `${String(area.maxX - area.minX + 1)} × ${String(area.maxZ - area.minZ + 1)}`
}

// ============================================================
// 写入与记录
// ============================================================

interface LogDrafts {
  district: readonly LogDraft[]
  plots: readonly { plotId: string; drafts: readonly PlotLogDraft[] }[]
}

interface CommittedLog {
  district: DistrictLogEntry[]
  plot: PlotLogEntry[]
}

/**
 * 改某个自管区并按顺序记下操作记录 (本区记录与若干块地的记录, 都是新的在前), 一次 mutateWorld 完成, 返回写入的记录。
 * mutate 拿到的 at 与记录上的时刻是同一个 (冻结时刻、暂停时刻都要与记录对得上)。
 *
 * 记录先在回调外编好再写进去, 而不是在回调里赋给外层变量: 后者 TS 看不见回调里的赋值, 会把外层变量
 * 一直当成初值 null, 逼出一串"不可能为 null"的死分支。
 */
function commitWithLogs(
  districtId: string,
  mutate: (district: MockDistrictRecord, at: number) => void,
  drafts: LogDrafts,
): CommittedLog {
  const at = nowMs()
  const firstSeq = getWorld().district.nextLogSeq
  const districtEntries = drafts.district.map((draft, index) => ({
    ...draft,
    entryId: `log-${String(firstSeq + index)}`,
    at,
  }))
  let seq = firstSeq + districtEntries.length
  const plotBatches = drafts.plots.map((batch) => ({
    plotId: batch.plotId,
    entries: batch.drafts.map((draft) => {
      const entry = { ...draft, entryId: `plotlog-${String(seq)}`, at }
      seq += 1
      return entry
    }),
  }))
  mutateWorld((world) => {
    const district = world.district.districts.find((candidate) => candidate.districtId === districtId)
    if (district === undefined) {
      // 调用方都已先 requireDistrict 过, 同一个同步调用里不可能消失; 真走到这里说明本文件自己写错了。
      throw new Error(`mock 缺陷: 写操作记录时找不到自管区 ${districtId}`)
    }
    mutate(district, at)
    district.log.unshift(...[...districtEntries].reverse())
    for (const batch of plotBatches) {
      const plot = district.plots.find((candidate) => candidate.plotId === batch.plotId)
      if (plot === undefined) {
        throw new Error(`mock 缺陷: 写地块记录时找不到地块 ${batch.plotId}`)
      }
      plot.log.unshift(...[...batch.entries].reverse())
    }
    world.district.nextLogSeq = seq
  })
  return { district: districtEntries, plot: plotBatches.flatMap((batch) => batch.entries) }
}

function commitWithLog(
  districtId: string,
  mutate: (district: MockDistrictRecord, at: number) => void,
  drafts: readonly LogDraft[],
): DistrictLogEntry[] {
  return commitWithLogs(districtId, mutate, { district: drafts, plots: [] }).district
}

/**
 * 改一块地并写它的记录。每次写入都把这块地的写入状态置回已生效: 真服每次都整块重写子领地 (lib/types.ts K11),
 * 上一次失败的地块下一次改动时就补齐了; 假世界里写入恒成功。
 */
function commitPlotWithLog(
  districtId: string,
  plotId: string,
  mutate: (plot: MockDistrictPlotRecord) => void,
  drafts: readonly PlotLogDraft[],
): PlotLogEntry[] {
  return commitWithLogs(
    districtId,
    (district) => {
      const plot = district.plots.find((candidate) => candidate.plotId === plotId)
      if (plot === undefined) {
        throw new Error(`mock 缺陷: 写入时找不到地块 ${plotId}`)
      }
      mutate(plot)
      plot.syncStatus = 'synced'
      plot.syncError = null
    },
    { district: [], plots: [{ plotId, drafts }] },
  ).plot
}

/** 只写一条记录时的取值。没有那一条就是本文件自己写错了, 直接抛, 不回退成假记录。 */
function onlyEntry<T>(entries: readonly T[]): T {
  const entry = entries[entries.length - 1]
  if (entry === undefined) {
    throw new Error('mock 缺陷: 写操作之后没有写出操作记录')
  }
  return entry
}

/** 多条记录里排在最前 (最先编好) 的那条, 如移出住户时的"移出"本身。 */
function firstEntry<T>(entries: readonly T[]): T {
  const entry = entries[0]
  if (entry === undefined) {
    throw new Error('mock 缺陷: 写操作之后没有写出操作记录')
  }
  return entry
}

/** 写完之后重新找回那块地 (mutateWorld 是就地改, 引用不变; 找不到就是本文件自己写错了)。 */
function plotAfterWrite(districtId: string, plotId: string): { district: MockDistrictRecord; plot: MockDistrictPlotRecord } {
  const district = getWorld().district.districts.find((candidate) => candidate.districtId === districtId)
  const plot = district?.plots.find((candidate) => candidate.plotId === plotId)
  if (district === undefined || plot === undefined) {
    throw new Error(`mock 缺陷: 写完之后找不到地块 ${plotId}`)
  }
  return { district, plot }
}

// ============================================================
// handler 表
// ============================================================

/**
 * 每条 action 一个实现。用映射类型而不是 switch: 少写一条即编译失败 (键必须齐全), 且每个实现的 payload 已按
 * 自身 action 收窄 (与真契约 lib/bridge 的 PayloadOf / ResultOf 同一张表)。
 */
type DistrictHandlerMap = {
  [A in DistrictActionName]: (payload: PayloadOf<A>) => ResultOf<A>
}

const DISTRICT_HANDLERS: DistrictHandlerMap = {
  'district.state': () => {
    const world = getWorld()
    const viewer = resolveViewer(world)
    return cloneResult({
      viewer,
      residency: residencyOf(world, viewer),
      districts: world.district.districts.map((district) => summarize(district, viewer.role === 'admin')),
      friendOf: friendshipsOf(world, viewer.playerName),
      friendOfTruncated: false,
    })
  },

  'district.detail': (payload) => {
    const action = 'district.detail'
    const world = getWorld()
    const viewer = resolveViewer(world)
    const district = requireDistrict(action, world, payload.districtId)
    const access = accessOf(viewer, district)
    if (access === 'none') {
      throw reject(action, 'PERMISSION_DENIED', '你不是这个自管区的住户，只能看公开信息', { action, requires: 'member' })
    }
    const canSeeRoster = access !== 'resident'
    return cloneResult({
      district: {
        districtId: district.districtId,
        displayName: district.displayName,
        academyName: district.academyName,
        academyFullName: district.academyFullName,
        wardenName: district.wardenName,
        bounds: district.bounds,
        area: areaOfBounds(district.bounds),
        residentCount: district.residents.length,
        rules: district.rules,
        createdAt: district.createdAt,
        plotCount: district.plots.length,
        vacantPlotCount: vacantCount(district),
      },
      abilities: abilitiesOf(access, district, viewer),
      residents: canSeeRoster
        ? district.residents.map((record) => toResident(world, district, record, access === 'admin'))
        : null,
      log: canSeeRoster ? district.log : null,
      residentsTruncated: false,
      logTruncated: false,
    })
  },

  'district.addResident': (payload) => {
    const action = 'district.addResident'
    const world = getWorld()
    const viewer = resolveViewer(world)
    const district = requireDistrict(action, world, payload.districtId)
    requireManager(action, viewer, district)

    const name = payload.playerName.trim()
    if (!PLAYER_NAME_PATTERN.test(name)) {
      throw reject(action, 'INVALID_PLAYER_NAME', '玩家 ID 只能由 3-16 位英文字母、数字或下划线组成')
    }
    const existing = district.residents.find((resident) => sameName(resident.playerName, name))
    if (existing !== undefined) {
      throw reject(action, 'ALREADY_RESIDENT', `${existing.playerName} 已经是本区住户了`)
    }
    const elsewhere = homeDistrictOf(world, name)
    if (elsewhere !== undefined) {
      throw reject(
        action,
        'RESIDENT_ELSEWHERE',
        `${name} 已经是${elsewhere.academyFullName}的成员。一个人只能属于一个学院，要转过来得先让原学院的区务长把 TA 移出`,
      )
    }
    const unbound = archivedAcademyOf(world, name)
    if (unbound !== undefined) {
      throw reject(
        action,
        'RESIDENT_ELSEWHERE',
        `${name} 已经是${unbound.academyFullName}的成员（这个学院的自管区已解除绑定，成员名单还在）。一个人只能属于一个学院`,
      )
    }
    const known = world.district.knownPlayers.find((player) => sameName(player.name, name))
    if (known === undefined && !payload.allowNeverJoined) {
      throw reject(action, 'PLAYER_NEVER_JOINED', `没有找到 ${name} 的登录记录`)
    }

    // 进过服的人以服务器记下的真名为准 (大小写), 没进过服的只能按输入原样记。
    const record: MockDistrictResidentRecord = {
      playerName: known === undefined ? name : known.name,
      joinedAt: nowMs(),
      addedBy: viewer.playerName,
      syncStatus: known === undefined ? 'pending' : 'synced',
      syncError: null,
    }
    // 真服这里还要把 TA 补进本区每一块地的"其他住户"组 (子领地只在创建时复制一次快照, 见 lib/types.ts K11)。
    // 原来有一块冻结中的地块也不会自动还给 TA: 要由管理员解除冻结 (K23)。
    const logEntry = onlyEntry(
      commitWithLog(
        district.districtId,
        (target) => {
          target.residents.push(record)
        },
        [logDraft(viewer, 'add', record.playerName, null)],
      ),
    )
    const frozenPlot = frozenPlotOf(district, record.playerName)
    return cloneResult({
      resident: toResident(getWorld(), district, record, viewer.role === 'admin'),
      logEntry,
      frozenPlot: frozenPlot === undefined ? null : plotRefOf(frozenPlot),
    })
  },

  'district.removeResident': (payload) => {
    const action = 'district.removeResident'
    const world = getWorld()
    const viewer = resolveViewer(world)
    const district = requireDistrict(action, world, payload.districtId)
    requireManager(action, viewer, district)

    if (!REMOVE_REASON_KINDS.includes(payload.reasonKind)) {
      throw invalidRequest(action, 'reasonKind', payload.reasonKind)
    }
    const reason = payload.reason.trim()
    if (reason === '') {
      throw reject(action, 'REASON_REQUIRED', '请填写移出原因，它会写进操作记录')
    }
    if (reason.length > REMOVE_REASON_MAX_CHARS) {
      throw reject(action, 'INVALID_REQUEST', `移出原因最多 ${String(REMOVE_REASON_MAX_CHARS)} 个字`, { field: 'reason' })
    }
    const target = district.residents.find((resident) => sameName(resident.playerName, payload.playerName))
    if (target === undefined) {
      throw reject(action, 'NOT_RESIDENT', `${payload.playerName} 不是本区住户`)
    }
    if (district.wardenName !== null && sameName(district.wardenName, target.playerName)) {
      throw reject(action, 'RESIDENT_IS_WARDEN', `${target.playerName} 是本区区务长，要先由管理员撤销区务长才能移出`)
    }

    // TA 的地块原地冻结 7 天 (东西不动, 除管理员外谁都不能进出和操作; 朋友和三列设置原样存着), 到期由服务端收回。
    // 本区记录一条"冻结地块", 地块记录也一条 —— 地块记录只写固定的一句, 不抄移出原因 (下一任户主也看得到地块记录)。
    // 真服这里还要把 TA 从本区每一块地的"其他住户"组里删掉 (快照, 见 lib/types.ts K11)。
    const owned = ownedPlot(district, target.playerName)
    // 只回块数: 区务长看不到别人地块的朋友名单, 回执不能把"是哪几块"漏给 TA。
    const friendPlots = friendPlotsOf(district, target.playerName)
    const suspend = payload.reasonKind === 'violation'

    const districtDrafts: LogDraft[] = [logDraft(viewer, 'remove', target.playerName, reason)]
    const plotDrafts: { plotId: string; drafts: PlotLogDraft[] }[] = []
    if (owned !== undefined) {
      districtDrafts.push(logDraft(viewer, 'freezePlot', owned.code, `原户主 ${target.playerName} 被移出本区`))
      plotDrafts.push({
        plotId: owned.plotId,
        drafts: [managerPlotLogDraft(viewer, 'freeze', target.playerName, PLOT_LOG_NOTE.freeze)],
      })
    }
    if (suspend && friendPlots.length > 0) {
      districtDrafts.push(
        logDraft(viewer, 'suspendFriends', target.playerName, `本区 ${String(friendPlots.length)} 块地的朋友身份已暂停`),
      )
      // 这条就是给户主的通知: 户主打开自己的地块就在记录里看到, 可以自己恢复。
      for (const plot of friendPlots) {
        plotDrafts.push({
          plotId: plot.plotId,
          drafts: [managerPlotLogDraft(viewer, 'suspendFriend', target.playerName, PLOT_LOG_NOTE.suspend)],
        })
      }
    }

    const committed = commitWithLogs(
      district.districtId,
      (draftDistrict, at) => {
        draftDistrict.residents = draftDistrict.residents.filter((resident) => resident !== target)
        if (owned !== undefined) {
          owned.ownerName = null
          owned.frozen = { formerOwnerName: target.playerName, frozenAt: at }
          // 子领地整块重写成"全关"; 假世界里写入恒成功。
          owned.syncStatus = 'synced'
          owned.syncError = null
        }
        if (suspend) {
          for (const plot of friendPlots) {
            for (const friend of plot.friends) {
              if (sameName(friend.playerName, target.playerName)) {
                friend.suspendedAt = at
              }
            }
          }
        }
      },
      { district: districtDrafts, plots: plotDrafts },
    )
    const logEntry = firstEntry(committed.district)
    return cloneResult({
      logEntry,
      frozenPlot: owned === undefined ? null : plotRefOf(owned),
      reclaimAt: owned === undefined ? null : logEntry.at + PLOT_FREEZE_DAYS * DAY_MS,
      suspendedFriendOfPlots: suspend ? friendPlots.length : 0,
      stillFriendOfPlots: suspend ? 0 : friendPlots.length,
    })
  },

  'admin.district.retrySync': (payload) => {
    const action = 'admin.district.retrySync'
    const world = getWorld()
    const viewer = resolveViewer(world)
    requireAdmin(action, viewer)
    const district = requireDistrict(action, world, payload.districtId)
    const target = district.residents.find((resident) => sameName(resident.playerName, payload.playerName))
    if (target === undefined) {
      throw reject(action, 'NOT_RESIDENT', `${payload.playerName} 不是本区住户`)
    }
    if (target.syncStatus !== 'failed') {
      throw reject(action, 'SYNC_NOTHING_TO_RETRY', `${target.playerName} 当前没有同步失败，不需要重试`)
    }
    // 假世界里重试一律成功 —— 真服的重试可能再次失败, 界面已按"回执说了算"处理, 不依赖这里恒成功。
    commitWithLog(
      district.districtId,
      () => {
        target.syncStatus = 'synced'
        target.syncError = null
      },
      [logDraft(viewer, 'resync', target.playerName, null)],
    )
    return cloneResult({ resident: toResident(getWorld(), district, target, true) })
  },

  'admin.district.setWarden': (payload) => {
    const action = 'admin.district.setWarden'
    const world = getWorld()
    const viewer = resolveViewer(world)
    requireAdmin(action, viewer)
    const district = requireDistrict(action, world, payload.districtId)
    const previous = district.wardenName

    if (payload.playerName === null) {
      if (previous === null) {
        throw reject(action, 'WARDEN_NOT_APPOINTED', '本区现在没有区务长')
      }
      const logEntry = onlyEntry(
        commitWithLog(
          district.districtId,
          (target) => {
            target.wardenName = null
          },
          [logDraft(viewer, 'revoke', previous, null)],
        ),
      )
      return cloneResult({ wardenName: null, logEntry })
    }

    const candidateName = payload.playerName
    const candidate = district.residents.find((resident) => sameName(resident.playerName, candidateName))
    if (candidate === undefined) {
      throw reject(action, 'NOT_RESIDENT', `${candidateName} 还不是本区住户，请先把 TA 加进来再任命`)
    }
    if (previous !== null && sameName(previous, candidate.playerName)) {
      throw reject(action, 'ALREADY_WARDEN', `${candidate.playerName} 已经是本区区务长`)
    }
    // 换人 = 先撤旧的再任命新的, 两条都记, 免得记录里看起来像同时有两个区务长。
    const drafts =
      previous === null
        ? [logDraft(viewer, 'appoint', candidate.playerName, null)]
        : [logDraft(viewer, 'revoke', previous, null), logDraft(viewer, 'appoint', candidate.playerName, null)]
    const logEntry = onlyEntry(
      commitWithLog(
        district.districtId,
        (target) => {
          target.wardenName = candidate.playerName
        },
        drafts,
      ),
    )
    return cloneResult({ wardenName: candidate.playerName, logEntry })
  },

  'admin.district.delete': (payload) => {
    const action = 'admin.district.delete'
    const world = getWorld()
    const viewer = resolveViewer(world)
    requireAdmin(action, viewer)
    const district = requireDistrict(action, world, payload.districtId)
    // 已拍板: 只解除与学院的绑定 (lib/types.ts K7)。Flan 领地与各户子领地原样留着 (假世界里就是不再管它们),
    // 学院成员名单保留, 操作记录归档进 archived (K26 管理员仍可查)。
    const archived: MockArchivedDistrictRecord = {
      districtId: district.districtId,
      displayName: district.displayName,
      academyName: district.academyName,
      academyFullName: district.academyFullName,
      unboundAt: nowMs(),
      unboundBy: viewer.playerName,
      members: district.residents.map((resident) => ({ ...resident })),
      log: [...district.log],
    }
    mutateWorld((draft) => {
      draft.district.districts = draft.district.districts.filter(
        (candidate) => candidate.districtId !== district.districtId,
      )
      draft.district.archived.unshift(archived)
    })
    return { districtId: district.districtId, keptMembers: archived.members.length, keptPlots: district.plots.length }
  },

  'admin.district.archive': () => {
    const action = 'admin.district.archive'
    const world = getWorld()
    requireAdmin(action, resolveViewer(world))
    return cloneResult({
      districts: world.district.archived.map((archived) => ({
        districtId: archived.districtId,
        displayName: archived.displayName,
        academyName: archived.academyName,
        academyFullName: archived.academyFullName,
        unboundAt: archived.unboundAt,
        unboundBy: archived.unboundBy,
        memberCount: archived.members.length,
        log: archived.log,
        logTruncated: false,
      })),
      truncated: false,
    })
  },

  'district.permissions': (payload) => {
    const action = 'district.permissions'
    const world = getWorld()
    const viewer = resolveViewer(world)
    const district = requireDistrict(action, world, payload.districtId)
    // 不拒绝外人 (见 lib/types.ts K8): 外人拿到的是外人列 + 全区列, 本来就是公开信息。住户另多拿住户列。
    return cloneResult(permissionsView(district, accessOf(viewer, district)))
  },

  'district.setPermission': (payload) => {
    const action = 'district.setPermission'
    const world = getWorld()
    const viewer = resolveViewer(world)
    const district = requireDistrict(action, world, payload.districtId)
    const access = accessOf(viewer, district)
    if (!isPermissionManager(access)) {
      throw reject(action, 'PERMISSION_DENIED', '只有本区区务长或管理员可以改本区公共区域的权限', {
        action,
        requires: 'manager',
      })
    }
    const found = findPermission(payload.permissionId)
    if (found === undefined) {
      throw reject(action, 'PERMISSION_ITEM_UNKNOWN', '没有这一项权限，页面可能过期了，请刷新')
    }
    const { group, def } = found
    if (!audiencesOf(group).includes(payload.audience)) {
      // member 项只收住户、外人两列, 区域规则只收全区一列 (与真服同一个 INVALID_REQUEST {field: audience})。
      throw invalidRequest(action, 'audience', payload.audience)
    }
    if (group.scope === 'region' && access !== 'admin') {
      throw reject(action, 'PERMISSION_DENIED', '区域规则只有管理员可以改', { action, requires: 'admin' })
    }
    const enabled = requireSwitchValue(action, 'enabled', payload.enabled)

    const from = permissionValuesOf(district, def.permissionId)[payload.audience]
    if (from === null) {
      throw new Error(`mock 缺陷: ${district.districtId} 的 ${def.permissionId} 没有"${payload.audience}"这一列`)
    }
    if (from === enabled) {
      // 本来就是这个值 (比如两个人同时点了同一格): 不写记录, 照常回当前值。
      return cloneResult({
        item: toPermissionItem(def, group, permissionValuesOf(district, def.permissionId), access),
        logEntry: null,
      })
    }
    const logEntry = onlyEntry(
      commitWithLog(
        district.districtId,
        (target) => {
          permissionValuesOf(target, def.permissionId)[payload.audience] = enabled
        },
        [
          permissionLogDraft(
            viewer,
            { permissionId: def.permissionId, label: def.label, audience: payload.audience, from, to: enabled },
            null,
          ),
        ],
      ),
    )
    return cloneResult({
      item: toPermissionItem(def, group, permissionValuesOf(district, def.permissionId), access),
      logEntry,
    })
  },

  'district.resetPermissions': (payload) => {
    const action = 'district.resetPermissions'
    const world = getWorld()
    const viewer = resolveViewer(world)
    const district = requireDistrict(action, world, payload.districtId)
    const access = accessOf(viewer, district)
    if (!isPermissionManager(access)) {
      throw reject(action, 'PERMISSION_DENIED', '只有本区区务长或管理员可以恢复本区的默认权限', {
        action,
        requires: 'manager',
      })
    }
    // 类型只管得住编译期, 绕过界面直接调的人什么都能传; 真服对 member / all 以外的取值报 INVALID_REQUEST。
    const scope: unknown = payload.scope
    if (scope !== 'member' && scope !== 'all') {
      throw invalidRequest(action, 'scope', scope)
    }
    if (payload.scope === 'all' && access !== 'admin') {
      throw reject(action, 'PERMISSION_DENIED', '区域规则只有管理员可以改，区务长只能恢复住户和外人的开关', {
        action,
        requires: 'admin',
      })
    }

    const changes: DistrictPermissionChange[] = []
    for (const group of DISTRICT_PERMISSION_CATALOG) {
      if (group.scope === 'region' && payload.scope !== 'all') {
        continue
      }
      for (const def of group.items) {
        const values = permissionValuesOf(district, def.permissionId)
        for (const audience of audiencesOf(group)) {
          const from = values[audience]
          const to = def.defaults[audience]
          if (from !== null && to !== null && from !== to) {
            changes.push({ permissionId: def.permissionId, label: def.label, audience, from, to })
          }
        }
      }
    }

    const logEntries =
      changes.length === 0
        ? []
        : commitWithLog(
            district.districtId,
            (target) => {
              for (const change of changes) {
                permissionValuesOf(target, change.permissionId)[change.audience] = change.to
              }
            },
            changes.map((change) => permissionLogDraft(viewer, change, '恢复默认')),
          )
    return cloneResult({
      permissions: permissionsView(district, access),
      logEntries,
      changedCount: logEntries.length,
      logEntriesTruncated: false,
    })
  },

  'district.plots': (payload) => {
    const action = 'district.plots'
    const world = getWorld()
    const viewer = resolveViewer(world)
    const district = requireDistrict(action, world, payload.districtId)
    const access = accessOf(viewer, district)
    if (access === 'none') {
      // 外人只看得到 district.state 摘要里的块数 (共 N 块, M 块空置), 看不到谁家在哪。
      throw reject(action, 'PERMISSION_DENIED', '只有本区住户能看本区的地块和户主', { action, requires: 'member' })
    }
    return cloneResult({
      districtId: district.districtId,
      bounds: district.bounds,
      myPlotId: ownedPlot(district, viewer.playerName)?.plotId ?? null,
      residentDefaults: residentDefaultLabels(),
      rules: rulesOf(district),
      market: marketOf(world, district, viewer),
      plots: district.plots.map((plot) => toPlotSummary(district, plot, access)),
      // 墓碑里是历任户主期间的地块记录, 口径同 plot.detail 的归档: 只给管理员。
      deletedPlots: access === 'admin' ? district.deletedPlots.map(toDeletedPlot) : null,
      plotsTruncated: false,
      deletedPlotsTruncated: false,
    })
  },

  'plot.detail': (payload) => {
    const action = 'plot.detail'
    const world = getWorld()
    const viewer = resolveViewer(world)
    const district = requireDistrict(action, world, payload.districtId)
    const plot = requirePlot(action, district, payload.plotId)
    const relation = plotRelationOf(viewer, plot)
    if (relation === 'none') {
      throw plotDenied(action, viewer, district, '看')
    }
    return cloneResult(plotDetailView(district, plot, relation))
  },

  'plot.setPermission': (payload) => {
    const action = 'plot.setPermission'
    const world = getWorld()
    const viewer = resolveViewer(world)
    const district = requireDistrict(action, world, payload.districtId)
    const plot = requirePlot(action, district, payload.plotId)
    const relation = requirePlotEditor(action, viewer, district, plot)
    if (!(PLOT_AUDIENCES as readonly string[]).includes(payload.audience)) {
      throw invalidRequest(action, 'audience', payload.audience)
    }
    const enabled = requireSwitchValue(action, 'enabled', payload.enabled)
    const found = findPermission(payload.permissionId)
    if (found === undefined) {
      throw reject(action, 'PERMISSION_ITEM_UNKNOWN', '没有这一项权限，页面可能过期了，请刷新')
    }
    const { group, def } = found
    if (group.scope === 'region') {
      throw reject(action, 'REGION_RULE_NOT_IN_PLOT', '区域规则全区统一，只有管理员能在自管区里改，地块里不能单独改')
    }

    const from = plotValuesOf(plot, def.permissionId)[payload.audience]
    if (from === enabled) {
      return cloneResult({
        item: toPlotItem(def, plotValuesOf(plot, def.permissionId), relation === 'admin'),
        logEntry: null,
      })
    }
    const logEntry = onlyEntry(
      commitPlotWithLog(
        district.districtId,
        plot.plotId,
        (target) => {
          plotValuesOf(target, def.permissionId)[payload.audience] = enabled
        },
        [
          plotLogDraft(viewer, relation, 'permission', null, null, {
            permissionId: def.permissionId,
            label: def.label,
            audience: payload.audience,
            from,
            to: enabled,
          }),
        ],
      ),
    )
    return cloneResult({
      item: toPlotItem(def, plotValuesOf(plot, def.permissionId), relation === 'admin'),
      logEntry,
    })
  },

  'plot.resetPermissions': (payload) => {
    const action = 'plot.resetPermissions'
    const world = getWorld()
    const viewer = resolveViewer(world)
    const district = requireDistrict(action, world, payload.districtId)
    const plot = requirePlot(action, district, payload.plotId)
    const relation = requirePlotEditor(action, viewer, district, plot)

    const changes: PlotPermissionChange[] = []
    for (const def of PLOT_GROUPS.flatMap((group) => group.items)) {
      const values = plotValuesOf(plot, def.permissionId)
      const defaults = plotDefaultsOf(def)
      for (const audience of PLOT_AUDIENCES) {
        if (values[audience] !== defaults[audience]) {
          changes.push({
            permissionId: def.permissionId,
            label: def.label,
            audience,
            from: values[audience],
            to: defaults[audience],
          })
        }
      }
    }

    const logEntries =
      changes.length === 0
        ? []
        : commitPlotWithLog(
            district.districtId,
            plot.plotId,
            (target) => {
              for (const change of changes) {
                plotValuesOf(target, change.permissionId)[change.audience] = change.to
              }
            },
            changes.map((change) => plotLogDraft(viewer, relation, 'permission', null, '恢复默认', change)),
          )
    return cloneResult({
      detail: plotDetailView(district, plot, relation),
      logEntries,
      changedCount: logEntries.length,
      logEntriesTruncated: false,
    })
  },

  'plot.addFriend': (payload) => {
    const action = 'plot.addFriend'
    const world = getWorld()
    const viewer = resolveViewer(world)
    const district = requireDistrict(action, world, payload.districtId)
    const plot = requirePlot(action, district, payload.plotId)
    const relation = requirePlotEditor(action, viewer, district, plot)

    const name = payload.playerName.trim()
    if (!PLAYER_NAME_PATTERN.test(name)) {
      throw reject(action, 'INVALID_PLAYER_NAME', '玩家 ID 只能由 3-16 位英文字母、数字或下划线组成')
    }
    if (plot.ownerName !== null && sameName(plot.ownerName, name)) {
      throw reject(action, 'FRIEND_IS_OWNER', '户主本人不用加成朋友：自己的地块本来就什么都能做')
    }
    const existing = plot.friends.find((friend) => sameName(friend.playerName, name))
    if (existing !== undefined) {
      throw reject(
        action,
        'ALREADY_FRIEND',
        existing.suspendedAt === null
          ? `${existing.playerName} 已经是这块地的朋友了`
          : `${existing.playerName} 已经在朋友名单上（已暂停），点 TA 旁边的“恢复”就行`,
      )
    }
    if (plot.friends.length >= PLOT_FRIEND_LIMIT) {
      throw reject(
        action,
        'FRIEND_LIMIT_REACHED',
        `朋友已满 ${String(PLOT_FRIEND_LIMIT)} 人，先移除一位再加`,
      )
    }
    const known = world.district.knownPlayers.find((player) => sameName(player.name, name))
    if (known === undefined && !payload.allowNeverJoined) {
      throw reject(action, 'PLAYER_NEVER_JOINED', `没有找到 ${name} 的登录记录`)
    }

    const record: MockPlotFriendRecord = {
      playerName: known === undefined ? name : known.name,
      addedAt: nowMs(),
      addedBy: viewer.playerName,
      syncStatus: known === undefined ? 'pending' : 'synced',
      suspendedAt: null,
    }
    const logEntry = onlyEntry(
      commitPlotWithLog(
        district.districtId,
        plot.plotId,
        (target) => {
          target.friends.push(record)
        },
        [plotLogDraft(viewer, relation, 'addFriend', record.playerName, null, null)],
      ),
    )
    return cloneResult({ friend: toFriend(district, record), logEntry })
  },

  'plot.removeFriend': (payload) => {
    const action = 'plot.removeFriend'
    const world = getWorld()
    const viewer = resolveViewer(world)
    const district = requireDistrict(action, world, payload.districtId)
    const plot = requirePlot(action, district, payload.plotId)
    const relation = requirePlotEditor(action, viewer, district, plot)

    const friend = plot.friends.find((candidate) => sameName(candidate.playerName, payload.playerName))
    if (friend === undefined) {
      throw reject(action, 'FRIEND_NOT_FOUND', `${payload.playerName} 不是这块地的朋友`)
    }
    const logEntry = onlyEntry(
      commitPlotWithLog(
        district.districtId,
        plot.plotId,
        (target) => {
          target.friends = target.friends.filter((candidate) => candidate !== friend)
        },
        [plotLogDraft(viewer, relation, 'removeFriend', friend.playerName, null, null)],
      ),
    )
    return cloneResult({ logEntry })
  },

  'plot.restoreFriend': (payload) => {
    const action = 'plot.restoreFriend'
    const world = getWorld()
    const viewer = resolveViewer(world)
    const district = requireDistrict(action, world, payload.districtId)
    const plot = requirePlot(action, district, payload.plotId)
    const relation = requirePlotEditor(action, viewer, district, plot)

    const friend = plot.friends.find((candidate) => sameName(candidate.playerName, payload.playerName))
    if (friend === undefined) {
      throw reject(action, 'FRIEND_NOT_FOUND', `${payload.playerName} 不是这块地的朋友`)
    }
    if (friend.suspendedAt === null) {
      throw reject(action, 'FRIEND_NOT_SUSPENDED', `${friend.playerName} 的朋友身份没有暂停，不需要恢复`)
    }
    const logEntry = onlyEntry(
      commitPlotWithLog(
        district.districtId,
        plot.plotId,
        () => {
          friend.suspendedAt = null
        },
        [plotLogDraft(viewer, relation, 'restoreFriend', friend.playerName, null, null)],
      ),
    )
    return cloneResult({ friend: toFriend(district, friend), logEntry })
  },

  'plot.create': (payload) => {
    const action = 'plot.create'
    const world = getWorld()
    const viewer = resolveViewer(world)
    const district = requireDistrict(action, world, payload.districtId)
    requirePlotManager(action, viewer, district)
    const area = requireArea(action, payload.area)
    requireValidArea(action, district, area, null)

    const no = district.nextPlotNo
    const plotId = `${district.districtId}-${pad2(no)}`
    const code = `${district.academyName}-${pad2(no)}`
    const change: PlotAreaChange = { from: null, to: area }
    // 真服: 在自管区领地下代建子领地, 立刻把每一格权限写成明确值, 地块组用独立组名 (lib/types.ts K11 段头)。
    const record: MockDistrictPlotRecord = {
      plotId,
      code,
      bounds: { dimension: district.bounds.dimension, ...area },
      ownerName: null,
      frozen: null,
      friends: [],
      permissions: defaultPlotPermissions(),
      log: [],
      archivedLog: [],
      syncStatus: 'synced',
      syncError: null,
    }
    const committed = commitWithLogs(
      district.districtId,
      (target) => {
        target.plots.push(record)
        target.nextPlotNo = no + 1
      },
      {
        district: [logDraft(viewer, 'createPlot', code, `${sideText(area)}，${String(areaOfPlot(area))} 格`, change)],
        plots: [{ plotId, drafts: [managerPlotLogDraft(viewer, 'create', null, null, { area: change })] }],
      },
    )
    const after = plotAfterWrite(district.districtId, plotId)
    return cloneResult({
      plot: toPlotSummary(after.district, after.plot, accessOf(viewer, after.district) === 'admin' ? 'admin' : 'warden'),
      logEntry: onlyEntry(committed.district),
    })
  },

  'plot.resize': (payload) => {
    const action = 'plot.resize'
    const world = getWorld()
    const viewer = resolveViewer(world)
    const district = requireDistrict(action, world, payload.districtId)
    requirePlotManager(action, viewer, district)
    const plot = requirePlot(action, district, payload.plotId)
    const access = accessOf(viewer, district)
    if (plot.frozen !== null) {
      throw reject(action, 'PLOT_FROZEN', `${plot.code} 冻结中，不能改范围；要先解除冻结或收回`)
    }
    const ownerName = plot.ownerName
    if (ownerName !== null && access !== 'admin') {
      throw reject(action, 'PLOT_OCCUPIED', `${plot.code} 是 ${ownerName} 的家，区务长不能改有户主的地块范围`)
    }
    const area = requireArea(action, payload.area)
    const from = plotAreaOf(plot.bounds)
    if (from.minX === area.minX && from.minZ === area.minZ && from.maxX === area.maxX && from.maxZ === area.maxZ) {
      throw reject(action, 'PLOT_AREA_UNCHANGED', '范围和原来一样，没有改动')
    }
    requireValidArea(action, district, area, plot.plotId)

    const change: PlotAreaChange = { from, to: area }
    const override = ownerName !== null
    const committed = commitWithLogs(
      district.districtId,
      () => {
        plot.bounds = { dimension: district.bounds.dimension, ...area }
        plot.syncStatus = 'synced'
        plot.syncError = null
      },
      {
        district: [
          logDraft(
            viewer,
            'resizePlot',
            plot.code,
            override ? `管理员代改，已通知户主 ${ownerName}` : `${sideText(from)} → ${sideText(area)}`,
            change,
          ),
        ],
        // 有户主时这条就是给户主的通知 (标"管理员代改")。
        plots: [
          { plotId: plot.plotId, drafts: [managerPlotLogDraft(viewer, 'resize', null, null, { area: change, onBehalfOfOwner: override })] },
        ],
      },
    )
    return cloneResult({
      plot: toPlotSummary(district, plot, access === 'admin' ? 'admin' : 'warden'),
      logEntry: onlyEntry(committed.district),
      ownerNotified: override,
    })
  },

  'plot.delete': (payload) => {
    const action = 'plot.delete'
    const world = getWorld()
    const viewer = resolveViewer(world)
    const district = requireDistrict(action, world, payload.districtId)
    requirePlotManager(action, viewer, district)
    const plot = requirePlot(action, district, payload.plotId)
    if (plot.frozen !== null) {
      throw reject(action, 'PLOT_FROZEN', `${plot.code} 冻结中，不能删；要先解除冻结或收回`)
    }
    if (plot.ownerName !== null) {
      throw reject(action, 'PLOT_OCCUPIED', `${plot.code} 是 ${plot.ownerName} 的家，有户主的地块不能删`)
    }
    const area = plotAreaOf(plot.bounds)
    const logEntry = onlyEntry(
      commitWithLog(
        district.districtId,
        (target, at) => {
          target.plots = target.plots.filter((candidate) => candidate.plotId !== plot.plotId)
          // 地块删得掉, 记录删不掉: 本任与历任户主期间的地块记录整份进墓碑, 管理员仍可查 (lib/types.ts K19)。
          target.deletedPlots.unshift({
            plotId: plot.plotId,
            code: plot.code,
            bounds: { ...plot.bounds },
            deletedAt: at,
            deletedBy: viewer.playerName,
            log: [...plot.log, ...plot.archivedLog],
          })
        },
        [logDraft(viewer, 'deletePlot', plot.code, '删掉后这片地回到公共区域', { from: area, to: null })],
      ),
    )
    return cloneResult({ logEntry })
  },

  'plot.buy': (payload) => {
    const action = 'plot.buy'
    const world = getWorld()
    const viewer = resolveViewer(world)
    const district = requireDistrict(action, world, payload.districtId)
    const plot = requirePlot(action, district, payload.plotId)
    // 买家永远是调用者本人: 契约里没有"替谁买"的参数, 区务长替别人买在结构上就不存在。
    const block = buyBlockOf(district, viewer)
    if (block === 'NOT_RESIDENT') {
      throw reject(action, block, '只有本区住户能买本区的地块')
    }
    if (block === 'ALREADY_OWNS_PLOT') {
      throw reject(action, block, '你已经有一块地了，一人最多一块')
    }
    if (block === 'HAS_FROZEN_PLOT') {
      throw reject(action, block, '你原来的地块还在冻结中，请先找管理员解除冻结或收回')
    }
    if (block === 'PURCHASE_CLOSED') {
      throw reject(action, block, '本区暂未开放购买')
    }
    if (plot.frozen !== null) {
      throw reject(action, 'PLOT_FROZEN', `${plot.code} 冻结中，不能买`)
    }
    if (plot.ownerName !== null) {
      throw reject(action, 'PLOT_OCCUPIED', `${plot.code} 已经被 ${plot.ownerName} 买下了（先到先得）`)
    }
    // 范围排在价格之前 (与真服 PlotMarketService 同序): 确认期间地块被挪走或改了形状, 价格未必变。
    const { minX, minZ, maxX, maxZ } = plot.bounds
    const seen = payload.expectedBounds
    if (seen.minX !== minX || seen.minZ !== minZ || seen.maxX !== maxX || seen.maxZ !== maxZ) {
      throw reject(
        action,
        'PLOT_CHANGED',
        `${plot.code} 的范围刚被调整过，现在是 ${sideText(plot.bounds)}（X ${String(minX)} ~ ${String(maxX)}，Z ${String(minZ)} ~ ${String(maxZ)}），请看清新的范围和价格再确认`,
        {
          plotId: plot.plotId,
          code: plot.code,
          minX: String(minX),
          minZ: String(minZ),
          maxX: String(maxX),
          maxZ: String(maxZ),
        },
      )
    }
    const price = priceOf(district, plot)
    if (payload.expectedPrice !== price) {
      throw reject(action, 'PRICE_CHANGED', `价格刚变了，现在是 ${formatCredit(price)}，请按新价格重新确认`)
    }
    const balance = walletOf(world, viewer.playerName)
    if (balance < price) {
      throw reject(action, 'INSUFFICIENT_FUNDS', `余额不足：还差 ${formatCredit(price - balance)}`)
    }
    const buyer = district.residents.find((resident) => sameName(resident.playerName, viewer.playerName))
    // 余额够才会走到这里, 余额够就一定有余额记录 (没列出的人按 0 算, 过不了上面的检查)。
    const wallet = world.district.wallets.find((candidate) => sameName(candidate.playerName, viewer.playerName))
    if (buyer === undefined || wallet === undefined) {
      throw new Error('mock 缺陷: 通过了住户与余额检查却找不到对应记录')
    }

    // 扣款与过户在同一次写入里完成 (付款去向【待拍板】: 销毁还是进钱仓与买家无关, 扣的永远是买家自己的余额)。
    const committed = commitWithLogs(
      district.districtId,
      () => {
        wallet.credit -= price
        plot.ownerName = buyer.playerName
        plot.friends = []
        // 真服: 子领地按默认值整块写好, 户主组写入买家, 组名独立 (lib/types.ts K11 段头)。
        plot.permissions = defaultPlotPermissions()
        plot.syncStatus = 'synced'
        plot.syncError = null
      },
      {
        // 买地是以住户身份做的事: 区务长自己买也记 resident (与真服同一口径, District_Backend_Design P3)。
        district: [{ ...logDraft(viewer, 'buyPlot', plot.code, formatCredit(price)), actorRole: 'resident' }],
        plots: [
          {
            plotId: plot.plotId,
            drafts: [
              {
                actorName: buyer.playerName,
                actorRole: 'owner',
                action: 'purchase',
                targetName: buyer.playerName,
                reason: formatCredit(price),
                permission: null,
                area: null,
                onBehalfOfOwner: false,
              },
            ],
          },
        ],
      },
    )
    return cloneResult({
      plot: plotRefOf(plot),
      price,
      balanceAfter: balance - price,
      logEntry: onlyEntry(committed.district),
    })
  },

  'admin.district.setPlotPricing': (payload) => {
    const action = 'admin.district.setPlotPricing'
    const world = getWorld()
    const viewer = resolveViewer(world)
    requireAdmin(action, viewer)
    const district = requireDistrict(action, world, payload.districtId)
    const { unitPrice, minSide, maxSide } = payload
    if (typeof unitPrice !== 'number' || !Number.isInteger(unitPrice) || unitPrice < 1) {
      throw reject(action, 'INVALID_PRICE', '每格单价要是正整数')
    }
    if (
      typeof minSide !== 'number' ||
      typeof maxSide !== 'number' ||
      !Number.isInteger(minSide) ||
      !Number.isInteger(maxSide) ||
      minSide < 1 ||
      minSide > maxSide
    ) {
      throw reject(action, 'INVALID_SIZE_LIMIT', '尺寸上下限要是正整数，且下限不能大于上限')
    }
    const parts = [
      unitPrice === district.unitPrice ? null : `单价 ${String(district.unitPrice)} → ${String(unitPrice)} 信用点/格`,
      minSide === district.minSide && maxSide === district.maxSide
        ? null
        : `每边 ${String(district.minSide)}~${String(district.maxSide)} → ${String(minSide)}~${String(maxSide)} 格`,
    ].filter((part): part is string => part !== null)
    const logEntry =
      parts.length === 0
        ? null
        : onlyEntry(
            commitWithLog(
              district.districtId,
              (target) => {
                target.unitPrice = unitPrice
                target.minSide = minSide
                target.maxSide = maxSide
              },
              [logDraft(viewer, 'setPlotPricing', null, parts.join('；'))],
            ),
          )
    return cloneResult({ rules: rulesOf(district), unitPrice: district.unitPrice, logEntry })
  },

  'admin.district.setPurchaseOpen': (payload) => {
    const action = 'admin.district.setPurchaseOpen'
    const world = getWorld()
    const viewer = resolveViewer(world)
    requireAdmin(action, viewer)
    const district = requireDistrict(action, world, payload.districtId)
    const open = requireSwitchValue(action, 'open', payload.open)
    if (open === district.purchaseOpen) {
      return cloneResult({ open, logEntry: null })
    }
    const logEntry = onlyEntry(
      commitWithLog(
        district.districtId,
        (target) => {
          target.purchaseOpen = open
        },
        [logDraft(viewer, 'setPurchaseOpen', null, open ? '开放购买' : '暂停购买')],
      ),
    )
    return cloneResult({ open, logEntry })
  },

  'admin.plot.unfreeze': (payload) => {
    const action = 'admin.plot.unfreeze'
    const world = getWorld()
    const viewer = resolveViewer(world)
    requireAdmin(action, viewer)
    const district = requireDistrict(action, world, payload.districtId)
    const plot = requirePlot(action, district, payload.plotId)
    const frozen = plot.frozen
    if (frozen === null) {
      throw reject(action, 'PLOT_NOT_FROZEN', `${plot.code} 没有冻结`)
    }
    const back = district.residents.find((resident) => sameName(resident.playerName, frozen.formerOwnerName))
    if (back === undefined) {
      throw reject(
        action,
        'FORMER_OWNER_NOT_RESIDENT',
        `原户主 ${frozen.formerOwnerName} 现在不是本区住户，先把 TA 加回本区才能解除冻结`,
      )
    }
    const other = ownedPlot(district, back.playerName)
    if (other !== undefined) {
      throw reject(action, 'ALREADY_OWNS_PLOT', `${back.playerName} 回来后已经有了 ${other.code}，一人最多一块`)
    }
    const committed = commitWithLogs(
      district.districtId,
      () => {
        // 朋友和三列设置从没动过, 原样还回去; 子领地整块重写成原户主的设置。
        plot.ownerName = back.playerName
        plot.frozen = null
        plot.syncStatus = 'synced'
        plot.syncError = null
      },
      {
        district: [logDraft(viewer, 'unfreezePlot', plot.code, `还给原户主 ${back.playerName}`)],
        plots: [
          { plotId: plot.plotId, drafts: [managerPlotLogDraft(viewer, 'unfreeze', back.playerName, PLOT_LOG_NOTE.unfreeze)] },
        ],
      },
    )
    return cloneResult({ plot: plotRefOf(plot), ownerName: back.playerName, logEntry: onlyEntry(committed.district) })
  },

  'admin.plot.reclaimNow': (payload) => {
    const action = 'admin.plot.reclaimNow'
    const world = getWorld()
    const viewer = resolveViewer(world)
    requireAdmin(action, viewer)
    const district = requireDistrict(action, world, payload.districtId)
    const plot = requirePlot(action, district, payload.plotId)
    const frozen = plot.frozen
    if (frozen === null) {
      throw reject(action, 'PLOT_NOT_FROZEN', `${plot.code} 没有冻结；只有冻结中的地块能立即收回`)
    }
    const committed = commitWithLogs(
      district.districtId,
      () => {
        vacatePlot(plot)
      },
      {
        district: [logDraft(viewer, 'vacatePlot', plot.code, `管理员立即收回（原户主 ${frozen.formerOwnerName}）`)],
        // 收回之后才写进 log (vacatePlot 已把之前的记录挪进归档), 于是下一任户主能看到的第一条就是它。
        plots: [
          {
            plotId: plot.plotId,
            drafts: [managerPlotLogDraft(viewer, 'vacate', frozen.formerOwnerName, PLOT_LOG_NOTE.reclaimNow)],
          },
        ],
      },
    )
    return cloneResult({ logEntry: onlyEntry(committed.district) })
  },
}

/**
 * lib/bridge.mock 的 resolveMock 对这 26 条 action 的转发口。
 *
 * 这里的 as 与 bridge.mock.resolveMock 里那处同源: 联合类型的函数不能用联合类型的实参调用 (TS 不做逐分支配对),
 * 而每个实现的实参类型已由 DistrictHandlerMap 在定义点保证, 转换不引入运行期风险。
 */
export function resolveDistrictMock(action: DistrictActionName, payload: unknown): unknown {
  const handler = DISTRICT_HANDLERS[action] as (input: unknown) => unknown
  return handler(payload)
}

// ============================================================
// 预览身份切换 (仅假数据模式; 真服身份永远是发请求的那个人)
// ============================================================

/**
 * 当前预览身份的签名 (是否 OP + 以谁的名义看)。页面拿它判断"身份换了没有", 换了才全量作废查询 ——
 * 不能拿 world.revision 当信号: 别的面板写背包镜像、开箱扣费也会推高它, 那样本页就会跟着每次无关的写操作
 * 整页重拉, 而且一旦某条查询的 mock 实现自己改世界就会自激成死循环 (见 useMockWorld.ts 文件头)。
 *
 * 返回字符串而不是对象: 可直接当 useSyncExternalStore 的快照, 同身份内按值恒等, 不会反复重渲染。
 * 放在 mock 这一侧而不是页面里算: 页面不许读 world.player (契约守卫 F013)。真服里这两个字段从不变化。
 */
export function districtPreviewIdentity(world: MockWorld): string {
  return `${world.player.isOp ? 'op' : 'player'}|${world.district.previewAs ?? ''}`
}

/**
 * 以某个预览身份查看自管区页。
 *
 * 选"管理员"= 以本机玩家身份并打开 OP 视图; 其余各项 = 关掉 OP 视图并以对应玩家的名义查看。
 * 刻意与外壳顶栏的 OP 视图开关共用 world.player.isOp: 两个开关若各记各的, 就会出现"顶栏写着 OP、
 * 自管区页却按住户画"的自相矛盾, 而那种画面在真服上不可能出现。
 */
export function setDistrictPreviewPersona(persona: DistrictPreviewPersona): void {
  const option = DISTRICT_PREVIEW_OPTIONS.find((candidate) => candidate.persona === persona)
  if (option === undefined) {
    throw new Error(`未知的预览身份: ${persona}`)
  }
  mutateWorld((draft) => {
    draft.player.isOp = persona === 'admin'
    draft.district.previewAs = option.playerName
  })
}
