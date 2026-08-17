/**
 * Web UI action 名清单, 供 bridge 握手自检比对 (架构文档 10.6): 页面启动时调一次 system.handshake,
 * 拿服务端回执的 actions 数组与本文件的 SERVER_ACTIONS 逐项比对 —— 缺失项即"页面比服务端新"
 * (或服务端少装了子系统), 多出项即"页面是旧缓存调了已删除的 action"。
 *
 * 真源: WebUiServerDispatcher.registeredActions() 遍历各子系统 register() 期注册的 action 名, 按
 * Collections.sort 字典序返回。SERVER_ACTIONS 在此手工按同一字典序排列, 使自检代码可以直接逐项 diff
 * 而不必自行归一化排序。新增/删除服务端 action 时必须同步改这里, 否则握手自检会把真实契约漂移误判掉
 * (或反过来把契约漂移错当噪音吞掉)。
 */

/**
 * 全部服务端 action 名 (system.handshake 回执 actions 字段的镜像, 不含 client.* 本地 action)。
 *
 * 二十三个注册点 (每个 *WebUiActions 类的 registerAll 各占一个):
 *   system.*            WebUiServerSubsystem
 *   player.*            PlayerWebUiActions
 *   hub.*               HubWebUiActions
 *   market.*            MarketActions
 *   admin.setBaseValue / admin.listItems   MarketAdminActions
 *   case.*              CaseWebUiActions
 *   job.progress        JobWebUiActions
 *   job.miner.*         MinerWebUiActions
 *   job.farmer.*        FarmerWebUiActions
 *   job.chef.*          ChefWebUiActions
 *   job.brewer.*        BrewerWebUiActions
 *   job.agent.*         AgentWebUiActions
 *   job.munitions.state / job.blueprints   MunitionsWebUiActions
 *   job.engineer.state  EngineerWebUiActions
 *   job.tarot.*         TarotWebUiActions
 *   admin.job.setLevel  JobAdminWebUiActions
 *   economy.*           EconomyWebUiActions
 *   admin.economy.*     EconomyAdminWebUiActions
 *   marriage.*          MarriageWebUiActions
 *   mining.*            MiningWebUiActions
 *   quest.*             QuestWebUiActions
 *   admin.mining.reset  MiningAdminWebUiActions
 *   champion.*          ChampionWebUiActions
 */
export const SERVER_ACTIONS = [
  'admin.economy.balance',
  'admin.economy.set',
  'admin.job.setLevel',
  'admin.listItems',
  'admin.mining.reset',
  'admin.setBaseValue',
  'case.apply',
  'case.open',
  'case.state',
  'champion.codex',
  'champion.inspect',
  'economy.priceTable',
  'economy.status',
  'economy.today',
  'hub.panels',
  'job.agent.scan',
  'job.agent.seal',
  'job.agent.state',
  'job.blueprints',
  'job.brewer.state',
  'job.chef.state',
  'job.engineer.state',
  'job.farmer.sell',
  'job.farmer.state',
  'job.miner.scan',
  'job.miner.state',
  'job.munitions.state',
  'job.progress',
  'job.tarot.buyPack',
  'job.tarot.state',
  'market.baseValue',
  'market.buy',
  'market.cancel',
  'market.categories',
  'market.feePreview',
  'market.history',
  'market.list',
  'market.mine',
  'market.p2pCap',
  'market.pendingPayout',
  'market.place',
  'market.tradable',
  'marriage.buyRing',
  'marriage.divorce',
  'marriage.propose',
  'marriage.respond',
  'marriage.sharedInv',
  'marriage.state',
  'marriage.wed',
  'mining.enter',
  'mining.leave',
  'mining.myStatus',
  'mining.overview',
  'player.inventory',
  'player.isOp',
  'player.itemDetail',
  'player.prefs.get',
  'player.prefs.set',
  'player.profile',
  'player.roster',
  'player.wallet',
  'quest.board',
  'quest.claim',
  'quest.refresh',
  'quest.turnIn',
  'system.batch',
  'system.echo',
  'system.handshake',
  'system.serverStatus',
] as const

/**
 * client.* 本地 action 名 (WebUiBridge.handleClientLocal 就地处理, 不经服务端往返, 不出现在
 * system.handshake 的 actions 字段里)。刻意与 SERVER_ACTIONS 分开导出: 握手自检若把这两份合并比对,
 * 会把"服务端 actions 不含 client.i18n / client.playCaseSound"误判成契约漂移 —— 这是设计如此, 不是缺陷。
 */
export const CLIENT_LOCAL_ACTIONS = [
  'client.i18n',
  'client.playCaseSound',
  'client.closePanel',
  'client.textFocus',
  'client.display.get',
  'client.display.set',
] as const

/** 参与 system.handshake 自检的服务端 action 名字面量类型。 */
export type WebUiServerActionName = (typeof SERVER_ACTIONS)[number]

/** 客户端本地 action 名字面量类型 (不参与握手自检)。 */
export type WebUiClientLocalActionName = (typeof CLIENT_LOCAL_ACTIONS)[number]

/** 服务端 + 客户端本地 action 名的并集, 供需要接受"任意合法 action 名"的调用点做字面量约束。 */
export type WebUiActionName = WebUiServerActionName | WebUiClientLocalActionName
