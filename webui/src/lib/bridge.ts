/**
 * WebUI 桥的业务门面: 全前端只经本文件与 Java 通信。
 *
 * 分层 (三层各自只干一件事, 别在下层加策略):
 *   src/bridge/types.ts  —— 宿主注入的三个全局的类型声明
 *   src/bridge/query.ts  —— 裸传输: 一次 cefQuery 往返, 无契约无兜底
 *   本文件               —— 契约层: 按 action 定型 payload/result、把失败信封翻成人话、握手自检、事件订阅
 *
 * 桥接契约 (真源 com.miningdim.client.webui.WebUiBridge):
 *   入站  window.miningdimQuery({request:JSON.stringify({action,payload}), onSuccess, onFailure})
 *   下行  页面预置 window.miningdimOnEvent(name, dataJsonString)
 *   授权  宿主按"整串 URL 精确匹配"放行 cefQuery, 因此 UI 严禁进 iframe, 运行期严禁改 location
 */

import { installWebUiEventBridge, subscribeWebUiEvent } from '../bridge/events'
import { BRIDGE_UNAVAILABLE_CODE, WebUiQueryError, webUiQuery } from '../bridge/query'
import type { WebUiActionName } from './actions'
import { SERVER_ACTIONS } from './actions'
import { enqueueBatched, installBatchTransport, isBatchableAction } from './batch'
import type {
  EconomyPriceTablePayload,
  EconomyPriceTableResult,
  EconomyStatusPayload,
  EconomyStatusResult,
  EconomyTodayPayload,
  EconomyTodayResult,
  AgentStatePayload,
  AgentStateResult,
  AgentScanPayload,
  AgentScanResult,
  AgentSealPayload,
  AgentSealResult,
  MunitionsStatePayload,
  MunitionsStateResult,
  BlueprintsPayload,
  BlueprintsResult,
  EngineerStatePayload,
  EngineerStateResult,
  TarotStatePayload,
  TarotStateResult,
  TarotBuyPackPayload,
  TarotBuyPackResult,
  MarriageStatePayload,
  MarriageStateResult,
  MarriageBuyRingPayload,
  MarriageBuyRingResult,
  MarriageProposePayload,
  MarriageProposeResult,
  MarriageRespondPayload,
  MarriageRespondResult,
  MarriageWedPayload,
  MarriageWedResult,
  MarriageDivorcePayload,
  MarriageDivorceResult,
  MarriageSharedInvPayload,
  MarriageSharedInvResult,
  MiningOverviewPayload,
  MiningOverviewResult,
  MiningMyStatusPayload,
  MiningMyStatusResult,
  MiningEnterPayload,
  MiningEnterResult,
  MiningLeavePayload,
  MiningLeaveResult,
  QuestBoardPayload,
  QuestBoardResult,
  QuestClaimPayload,
  QuestClaimResult,
  QuestRefreshPayload,
  QuestRefreshResult,
  QuestTurnInPayload,
  QuestTurnInResult,
  ChampionCodexPayload,
  ChampionCodexResult,
  ChampionInspectPayload,
  ChampionInspectResult,
  AdminEconomyBalancePayload,
  AdminEconomyBalanceResult,
  AdminEconomySetPayload,
  AdminEconomySetResult,
  AdminJobSetLevelPayload,
  AdminJobSetLevelResult,
  AdminMiningResetPayload,
  AdminMiningResetResult,
  PlayerRosterPayload,
  PlayerRosterResult,
  AdminListItemsPayload,
  AdminListItemsResult,
  AdminSetBaseValuePayload,
  AdminSetBaseValueResult,
  BrewerStatePayload,
  BrewerStateResult,
  CaseApplyPayload,
  CaseApplyResult,
  CaseOpenPayload,
  CaseOpenResult,
  CaseStatePayload,
  CaseStateResult,
  ChefStatePayload,
  ChefStateResult,
  ClientI18nPayload,
  ClientI18nResult,
  ClientClosePanelPayload,
  ClientClosePanelResult,
  ClientPlayCaseSoundPayload,
  ClientPlayCaseSoundResult,
  ClientDisplayGetPayload,
  ClientDisplayResult,
  ClientDisplaySetPayload,
  ClientTextFocusPayload,
  ClientTextFocusResult,
  FarmerSellPayload,
  FarmerSellResult,
  FarmerStatePayload,
  FarmerStateResult,
  HubPanelsPayload,
  HubPanelsResult,
  JobProgressPayload,
  JobProgressResult,
  MarketBaseValuePayload,
  MarketBaseValueResult,
  MarketBuyPayload,
  MarketBuyResult,
  MarketCancelPayload,
  MarketCancelResult,
  MarketCategoriesPayload,
  MarketCategoriesResult,
  MarketFeePreviewPayload,
  MarketFeePreviewResult,
  MarketHistoryPayload,
  MarketHistoryResult,
  MarketListPayload,
  MarketListResult,
  MarketMinePayload,
  MarketMineResult,
  MarketP2pCapPayload,
  MarketP2pCapResult,
  MarketPendingPayoutPayload,
  MarketPendingPayoutResult,
  MarketPlacePayload,
  MarketPlaceResult,
  MarketTradablePayload,
  MarketTradableResult,
  MinerScanPayload,
  MinerScanResult,
  MinerStatePayload,
  MinerStateResult,
  PlayerInventoryPayload,
  PlayerInventoryResult,
  PlayerIsOpPayload,
  PlayerIsOpResult,
  PlayerItemDetailPayload,
  PlayerItemDetailResult,
  PlayerPrefsGetPayload,
  PlayerPrefsGetResult,
  PlayerPrefsSetPayload,
  PlayerPrefsSetResult,
  PlayerProfilePayload,
  PlayerProfileResult,
  PlayerWalletPayload,
  PlayerWalletResult,
  SystemBatchPayload,
  SystemBatchResult,
  SystemEchoPayload,
  SystemEchoResult,
  SystemHandshakePayload,
  SystemHandshakeResult,
  SystemServerStatusPayload,
  SystemServerStatusResult,
} from './types'

/**
 * action 名 -> {payload, result} 的映射表。字段形状本身住在 types.ts (逐条标了 Java 落点),
 * 这里只做名字与形状的配对, 好让 call() 一个泛型函数覆盖全部 action 而不必逐个写重载。
 */
type WebUiContractMap = {
  'system.batch': { payload: SystemBatchPayload; result: SystemBatchResult }
  'system.echo': { payload: SystemEchoPayload; result: SystemEchoResult }
  'system.handshake': { payload: SystemHandshakePayload; result: SystemHandshakeResult }
  'system.serverStatus': { payload: SystemServerStatusPayload; result: SystemServerStatusResult }
  'player.inventory': { payload: PlayerInventoryPayload; result: PlayerInventoryResult }
  'player.isOp': { payload: PlayerIsOpPayload; result: PlayerIsOpResult }
  'player.itemDetail': { payload: PlayerItemDetailPayload; result: PlayerItemDetailResult }
  'player.prefs.get': { payload: PlayerPrefsGetPayload; result: PlayerPrefsGetResult }
  'player.prefs.set': { payload: PlayerPrefsSetPayload; result: PlayerPrefsSetResult }
  'player.profile': { payload: PlayerProfilePayload; result: PlayerProfileResult }
  'player.wallet': { payload: PlayerWalletPayload; result: PlayerWalletResult }
  'hub.panels': { payload: HubPanelsPayload; result: HubPanelsResult }
  'job.progress': { payload: JobProgressPayload; result: JobProgressResult }
  'job.miner.state': { payload: MinerStatePayload; result: MinerStateResult }
  'job.miner.scan': { payload: MinerScanPayload; result: MinerScanResult }
  'job.farmer.state': { payload: FarmerStatePayload; result: FarmerStateResult }
  'job.farmer.sell': { payload: FarmerSellPayload; result: FarmerSellResult }
  'job.chef.state': { payload: ChefStatePayload; result: ChefStateResult }
  'job.brewer.state': { payload: BrewerStatePayload; result: BrewerStateResult }
  'market.list': { payload: MarketListPayload; result: MarketListResult }
  'market.place': { payload: MarketPlacePayload; result: MarketPlaceResult }
  'market.buy': { payload: MarketBuyPayload; result: MarketBuyResult }
  'market.cancel': { payload: MarketCancelPayload; result: MarketCancelResult }
  'market.mine': { payload: MarketMinePayload; result: MarketMineResult }
  'market.history': { payload: MarketHistoryPayload; result: MarketHistoryResult }
  'market.baseValue': { payload: MarketBaseValuePayload; result: MarketBaseValueResult }
  'market.categories': { payload: MarketCategoriesPayload; result: MarketCategoriesResult }
  'market.feePreview': { payload: MarketFeePreviewPayload; result: MarketFeePreviewResult }
  'market.p2pCap': { payload: MarketP2pCapPayload; result: MarketP2pCapResult }
  'market.pendingPayout': { payload: MarketPendingPayoutPayload; result: MarketPendingPayoutResult }
  'market.tradable': { payload: MarketTradablePayload; result: MarketTradableResult }
  'admin.setBaseValue': { payload: AdminSetBaseValuePayload; result: AdminSetBaseValueResult }
  'admin.listItems': { payload: AdminListItemsPayload; result: AdminListItemsResult }
  'case.state': { payload: CaseStatePayload; result: CaseStateResult }
  'case.open': { payload: CaseOpenPayload; result: CaseOpenResult }
  'case.apply': { payload: CaseApplyPayload; result: CaseApplyResult }
  'client.i18n': { payload: ClientI18nPayload; result: ClientI18nResult }
  'client.playCaseSound': { payload: ClientPlayCaseSoundPayload; result: ClientPlayCaseSoundResult }
  'client.closePanel': { payload: ClientClosePanelPayload; result: ClientClosePanelResult }
  'client.textFocus': { payload: ClientTextFocusPayload; result: ClientTextFocusResult }
  'client.display.get': { payload: ClientDisplayGetPayload; result: ClientDisplayResult }
  'client.display.set': { payload: ClientDisplaySetPayload; result: ClientDisplayResult }
  'economy.priceTable': { payload: EconomyPriceTablePayload; result: EconomyPriceTableResult }
  'economy.status': { payload: EconomyStatusPayload; result: EconomyStatusResult }
  'economy.today': { payload: EconomyTodayPayload; result: EconomyTodayResult }
  'job.agent.state': { payload: AgentStatePayload; result: AgentStateResult }
  'job.agent.scan': { payload: AgentScanPayload; result: AgentScanResult }
  'job.agent.seal': { payload: AgentSealPayload; result: AgentSealResult }
  'job.munitions.state': { payload: MunitionsStatePayload; result: MunitionsStateResult }
  'job.blueprints': { payload: BlueprintsPayload; result: BlueprintsResult }
  'job.engineer.state': { payload: EngineerStatePayload; result: EngineerStateResult }
  'job.tarot.state': { payload: TarotStatePayload; result: TarotStateResult }
  'job.tarot.buyPack': { payload: TarotBuyPackPayload; result: TarotBuyPackResult }
  'marriage.state': { payload: MarriageStatePayload; result: MarriageStateResult }
  'marriage.buyRing': { payload: MarriageBuyRingPayload; result: MarriageBuyRingResult }
  'marriage.propose': { payload: MarriageProposePayload; result: MarriageProposeResult }
  'marriage.respond': { payload: MarriageRespondPayload; result: MarriageRespondResult }
  'marriage.wed': { payload: MarriageWedPayload; result: MarriageWedResult }
  'marriage.divorce': { payload: MarriageDivorcePayload; result: MarriageDivorceResult }
  'marriage.sharedInv': { payload: MarriageSharedInvPayload; result: MarriageSharedInvResult }
  'mining.overview': { payload: MiningOverviewPayload; result: MiningOverviewResult }
  'mining.myStatus': { payload: MiningMyStatusPayload; result: MiningMyStatusResult }
  'mining.enter': { payload: MiningEnterPayload; result: MiningEnterResult }
  'mining.leave': { payload: MiningLeavePayload; result: MiningLeaveResult }
  'quest.board': { payload: QuestBoardPayload; result: QuestBoardResult }
  'quest.claim': { payload: QuestClaimPayload; result: QuestClaimResult }
  'quest.refresh': { payload: QuestRefreshPayload; result: QuestRefreshResult }
  'quest.turnIn': { payload: QuestTurnInPayload; result: QuestTurnInResult }
  'champion.codex': { payload: ChampionCodexPayload; result: ChampionCodexResult }
  'champion.inspect': { payload: ChampionInspectPayload; result: ChampionInspectResult }
  'admin.economy.balance': { payload: AdminEconomyBalancePayload; result: AdminEconomyBalanceResult }
  'admin.economy.set': { payload: AdminEconomySetPayload; result: AdminEconomySetResult }
  'admin.job.setLevel': { payload: AdminJobSetLevelPayload; result: AdminJobSetLevelResult }
  'admin.mining.reset': { payload: AdminMiningResetPayload; result: AdminMiningResetResult }
  'player.roster': { payload: PlayerRosterPayload; result: PlayerRosterResult }
}

/**
 * 编译期双向核对: 契约表少一个 actions.ts 里的 action 名, 或多出一个不存在的名字, 本类型即坍成 never,
 * 下面那行赋值随之报错。手工维护的两张表最容易悄悄脱节, 这道锁把脱节提到编译期。
 */
type AssertContractCoverage = [Exclude<WebUiActionName, keyof WebUiContractMap>] extends [never]
  ? [Exclude<keyof WebUiContractMap, WebUiActionName>] extends [never]
    ? true
    : never
  : never

export const CONTRACT_COVERS_ALL_ACTIONS: AssertContractCoverage = true

export type WebUiContract = WebUiContractMap
export type PayloadOf<A extends WebUiActionName> = WebUiContractMap[A]['payload']
export type ResultOf<A extends WebUiActionName> = WebUiContractMap[A]['result']

/**
 * 服务端业务拒绝 (WebUiBusinessException) 附带的稳定机器码。通用异常没有这层 ——
 * 那种情况下 business 为 null, 只剩一句 Java 异常原文 —— 那是给排障看的, 措辞随实现走。带码的一档
 * 由 lib/errorText.ts 翻成玩家文案 (A10 已落地); 没码的一档只能原样带出, 因为它本就不是可预期的业务拒绝。
 */
export type WebUiBusinessError = {
  /**
   * 如 CASE_DISABLED / INSUFFICIENT_FUNDS / RATE_LIMITED / ASSET_NOT_OWNED / INVALID_REQUEST。
   * 全集与各自的抛出点见 Java 侧 com.miningdim.webui.server.WebUiErrorCodes。
   */
  errorCode: string
  /** 开箱专用: true 表示可以拿同一个 openingId 原样重试, 不会重复扣费。 */
  retrySameOpeningId: boolean
  /**
   * 错误码文案的占位符实参 (Java 侧 WebUiBusinessException 第四参)。如 SLOT_OUT_OF_RANGE 带
   * {slot, size}、prefs.set 的 INVALID_REQUEST 带 {field, value}。值一律是字符串 (服务端把数字也
   * 字符串化了), 只用于填 errorCode 对应的中文文案, 不参与计算 —— 要拿数字就自己 Number() 并自担解析失败。
   *
   * 键缺席是正常形态: 服务端 params 为空时整键不写 (businessErrorJson), 不是发空对象。
   */
  params?: Record<string, string>
}

/**
 * 一次 action 调用的失败。message 已经是可直接展示的那一句, 不是整坨 JSON。
 *
 * code 取值 (宿主侧定义):
 *    0   服务端回了失败信封 (业务错误, 细节在 business / message)
 *   -1   请求信封非法或客户端本地动作失败
 *   -2   30 秒超时
 *   -3   页面未授权 (URL 不匹配 / 非顶层帧) —— 出现即说明页面被塞进了 iframe 或改过 location
 * -100   桥未注入 (见 BRIDGE_UNAVAILABLE_CODE)
 * -101   响应不是合法 JSON (见 BRIDGE_MALFORMED_CODE)
 * -102   宿主既未回成功也未回失败, 由前端看门狗强行了结 (见 BRIDGE_ABANDONED_CODE)
 */
export class WebUiCallError extends Error {
  readonly action: string
  readonly code: number
  readonly business: WebUiBusinessError | null

  constructor(action: string, code: number, message: string, business: WebUiBusinessError | null) {
    super(message)
    this.name = 'WebUiCallError'
    this.action = action
    this.code = code
    this.business = business
  }
}

/** 服务端失败信封的专用失败码 (WebUiBridge.onResponse 用 0 占位, 细节全在 JSON 里)。 */
export const SERVER_FAILURE_CODE = 0

/** params 的形状校验: 必须是"值全为字符串"的普通对象 (数组与 null 都不算)。 */
function isStringRecord(value: unknown): value is Record<string, string> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return false
  }
  return Object.values(value).every((entry) => typeof entry === 'string')
}

/**
 * 把服务端失败信封 {"error":...,"errorCode"?:...,"retrySameOpeningId"?:...,"params"?:...} 解成结构化错误。
 *
 * 存在的理由: onFailure 第二参在 code=0 时是一整串 JSON, 直接扔给玩家看就是
 * "{"error":"信用点不足"}" 这种东西。解析失败或形状不符时原样带回 —— 那属于契约破裂,
 * 此时任何加工都只会掩盖现场。
 */
function parseServerFailure(action: string, rawJson: string): WebUiCallError {
  let parsed: unknown
  try {
    parsed = JSON.parse(rawJson)
  } catch {
    return new WebUiCallError(action, SERVER_FAILURE_CODE, rawJson, null)
  }
  if (typeof parsed !== 'object' || parsed === null || !('error' in parsed)) {
    return new WebUiCallError(action, SERVER_FAILURE_CODE, rawJson, null)
  }
  const message = parsed.error
  if (typeof message !== 'string') {
    return new WebUiCallError(action, SERVER_FAILURE_CODE, rawJson, null)
  }
  if (!('errorCode' in parsed) || typeof parsed.errorCode !== 'string') {
    // 通用异常路径 (WebUiServerDispatcher.errorJson) 只有 error 一个键, 没有 errorCode, 这是正常形态。
    return new WebUiCallError(action, SERVER_FAILURE_CODE, message, null)
  }
  /*
   * businessErrorJson 三个键是一起写出的 (error + errorCode + retrySameOpeningId), 只要 errorCode 在,
   * retrySameOpeningId 就必须是布尔。这里刻意不把缺席/错型补成 false: 那个值直接决定 case.open 失败后
   * 能不能拿同一个 openingId 重试, 猜错的代价是要么重复扣费、要么钱被扣了却不敢重试。补默认值会让契约破裂
   * 长成一条看起来完全合法的业务错误, 与本文件其它形状校验同纪律 —— 形状不符就原样带回, 不加工。
   */
  if (!('retrySameOpeningId' in parsed) || typeof parsed.retrySameOpeningId !== 'boolean') {
    return new WebUiCallError(action, SERVER_FAILURE_CODE, rawJson, null)
  }
  if (!('params' in parsed)) {
    /*
     * params 缺席是正常形态而非契约破裂: 服务端只在有占位符实参时才写这一键 (空 Map 不写), 存量
     * case.* 的拒绝全都没有它。这里刻意不补成 {} —— 与上面同纪律, 文案层据"有没有 params"决定用带参
     * 还是不带参的那句话, 补一个空对象等于把"服务端没给"伪装成"服务端给了但是空的"。
     */
    return new WebUiCallError(action, SERVER_FAILURE_CODE, message, {
      errorCode: parsed.errorCode,
      retrySameOpeningId: parsed.retrySameOpeningId,
    })
  }
  // 键在却不是全字符串值的对象 = 契约破裂, 按本文件既有纪律原样带回 rawJson, 不把半截 params 交给文案层填坑。
  if (!isStringRecord(parsed.params)) {
    return new WebUiCallError(action, SERVER_FAILURE_CODE, rawJson, null)
  }
  return new WebUiCallError(action, SERVER_FAILURE_CODE, message, {
    errorCode: parsed.errorCode,
    retrySameOpeningId: parsed.retrySameOpeningId,
    params: parsed.params,
  })
}

/** 非 0 失败码的 message 是宿主写的纯文本 (不是 JSON), 原样保留。 */
function toCallError(action: string, error: WebUiQueryError): WebUiCallError {
  if (error.code === SERVER_FAILURE_CODE) {
    return parseServerFailure(action, error.message)
  }
  return new WebUiCallError(action, error.code, error.message, null)
}

/**
 * 契约里的 payload 全是普通对象字面量类型; 这层只是把泛型指代擦回 query.ts 的入参形状,
 * 无任何运行期行为 (泛型 PayloadOf<A> 在未实例化时不被判定为 Record 的子类型)。
 */
function asPayloadRecord(payload: unknown): Record<string, unknown> {
  return payload as Record<string, unknown>
}

function bridgeInjected(): boolean {
  return typeof window.miningdimQuery === 'function'
}

/**
 * 当前是否在假数据模式。
 *
 * 只在 dev server 下允许落 mock: 生产构建里桥缺失是真故障, 此时回假余额/假挂单比直接报错危险得多 ——
 * 玩家会照着假数字下单。设计侧脱离游戏预览走 pnpm dev, 正好落在这个口子里。
 */
export function isMockActive(): boolean {
  return import.meta.env.DEV && !bridgeInjected()
}

let mockWarned = false

function warnMockOnce(): void {
  if (mockWarned) {
    return
  }
  mockWarned = true
  console.warn(
    '[webui-bridge] 未检测到 MCEF 宿主注入, 本会话全部 action 走 bridge.mock 假数据; 任何数值都不代表服务端真实状态。',
  )
}

/**
 * 调用一个 action。payload 与返回值由 contracts.ts 的契约表定型, 传错字段编译期即报错。
 *
 * 失败一律以 WebUiCallError 抛出, 不做任何默认值兜底 —— 余额/库存回假值比报错危险得多。
 * 调用方只在最外层 (页面级错误边界 / 提交按钮的一次性 catch) 收口, 不要在数据函数里 try/catch。
 *
 * 只读 action 会被<b>透明合并</b>进一次 system.batch 往返 (见 lib/batch.ts): 调用方无从分辨自己是被合并了
 * 还是单发的, 成功与失败两条路径都同形。合并只在宿主已注入时生效 —— 假数据模式下 bridge.mock 没有
 * system.batch, 且那条路上本来就没有往返成本可省。
 */
export async function call<A extends WebUiActionName>(
  action: A,
  payload: PayloadOf<A>,
): Promise<ResultOf<A>> {
  if (bridgeInjected() && isBatchableAction(action)) {
    return (await enqueueBatched(action, asPayloadRecord(payload))) as ResultOf<A>
  }
  return callDirect(action, payload)
}

/**
 * 不经批量合并的单发通道。批量调度器自己要用它 (发那条 system.batch、以及降级/溢出时逐条重发),
 * 若它调回 {@link call} 就成了无限自套。
 */
async function callDirect<A extends WebUiActionName>(
  action: A,
  payload: PayloadOf<A>,
): Promise<ResultOf<A>> {
  if (!bridgeInjected()) {
    if (!import.meta.env.DEV) {
      throw new WebUiCallError(
        action,
        BRIDGE_UNAVAILABLE_CODE,
        `WebUI 桥未注入: 页面不在 MCEF 宿主内 (action=${action})`,
        null,
      )
    }
    warnMockOnce()
    // 动态导入: import.meta.env.DEV 在生产构建里是常量 false, 整个分支连同 mock 模块一起被摇掉。
    const { mockCall } = await import('./bridge.mock')
    return mockCall(action, payload)
  }
  try {
    return await webUiQuery<ResultOf<A>>(action, asPayloadRecord(payload))
  } catch (queryError) {
    // 只翻译桥层错误 (把失败信封解成人话) 后原样重抛; 其它异常直接冒泡。这是转换, 不是吞异常。
    if (queryError instanceof WebUiQueryError) {
      throw toCallError(action, queryError)
    }
    throw queryError
  }
}

/**
 * 单发通道的类型擦除视图。批量调度器按运行期字符串收发, 拿不到 A 这个泛型参数 —— 与
 * mock/handlers.ts 里那处 callErased 同源同理由: 集中成一个具名常量, 让"本文件一共有几处断言"一眼可数。
 */
const callDirectErased = callDirect as unknown as (
  action: string,
  payload: Record<string, unknown>,
) => Promise<unknown>

/*
 * 模块求值时把单发通道与失败信封翻译交给批量调度器。
 *
 * 方向是 bridge -> batch 的单向注入, 不让 batch 反过来 import 本文件: ESM 的循环 import 不报错, 只会让先
 * 求值的一侧拿到 undefined, 症状是"call 偶尔不是函数"这类极难归因的故障。
 */
installBatchTransport(callDirectErased, parseServerFailure)

export type WebUiEventHandler = (data: unknown) => void

/**
 * 订阅服务端下行事件, 返回退订函数。
 *
 * data 是 unknown 而非具体类型: 服务端 sendWebUiEvent 至今零业务调用方, 现在给事件定字段名
 * 等于凭空发明契约; 首个真实发送方落地时再收窄 (决策 J2 把成交/求婚/击杀结算划给推送)。
 *
 * 红线: 任何功能都不能依赖本通道到达才能工作 —— 进度类数据一律轮询。这里接住它, 只是为了
 * 首个生产发送方上线时事件不会被静默丢弃。
 */
export function on(eventName: string, handler: WebUiEventHandler): () => void {
  // installWebUiEventBridge 内部按引用计数装卸全局入口, 与 App 的挂载期安装叠加不会互相摘掉。
  const uninstall = installWebUiEventBridge()
  const unsubscribe = subscribeWebUiEvent(eventName, handler)
  return () => {
    unsubscribe()
    uninstall()
  }
}

/**
 * 握手自检结果。missingOnServer 非空即为不兼容: 页面会调服务端根本没注册的 action,
 * 表现是功能逐个静默失效 (架构文档 10.6 要解的正是这个)。
 *
 * unknownToClient 不算不兼容, 只说明服务端跑在更新的构建上 (或挂了 GameTest 的临时 action),
 * 前端少用几个 action 不影响已有功能。
 */
export type HandshakeReport = {
  modVersion: string
  /** 服务端已注册的全部 action, 字典序。 */
  serverActions: readonly string[]
  /** 前端声明要用、服务端却没有的。 */
  missingOnServer: readonly string[]
  /** 服务端有、前端没声明的。 */
  unknownToClient: readonly string[]
  compatible: boolean
}

/**
 * 启动自检: 拿服务端注册表与前端声明的 action 清单对账, 差异结构化返回 (不抛)。
 *
 * 不抛的理由: 契约漂移是需要展示给运维/玩家看的诊断信息, 不是一次调用失败。由调用方决定
 * 是整页拦截还是只挂条警告。真连不上桥时 call 自身会抛, 那才是错误。
 */
export async function handshake(): Promise<HandshakeReport> {
  const result = await call('system.handshake', {})
  /*
   * 契约表只在编译期成立, 宿主实际回什么不受类型系统约束; 而本函数恰恰是那个"专门用来发现契约不对劲"
   * 的入口 —— 它自己被喂了畸形回执却抛一句无从追溯的 TypeError, 是最坏的一种失败。故就地把形状验明,
   * 报出到底收到了什么。这是全库唯一做运行期形状校验的 action, 因为只有它的职责就是校验契约本身。
   */
  if (!Array.isArray(result.actions) || result.actions.some((name) => typeof name !== 'string')) {
    throw new Error(`system.handshake 回执的 actions 不是字符串数组: ${JSON.stringify(result)}`)
  }
  if (typeof result.modVersion !== 'string') {
    throw new Error(`system.handshake 回执缺少 modVersion 字符串: ${JSON.stringify(result)}`)
  }
  const registered = new Set(result.actions)
  const declared = new Set<string>(SERVER_ACTIONS)
  const missingOnServer = SERVER_ACTIONS.filter((action) => !registered.has(action))
  const unknownToClient = result.actions.filter((action) => !declared.has(action))
  return {
    modVersion: result.modVersion,
    serverActions: result.actions,
    missingOnServer,
    unknownToClient,
    compatible: missingOnServer.length === 0,
  }
}
