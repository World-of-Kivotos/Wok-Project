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
 * 五个注册点: WebUiServerSubsystem (system.*) / PlayerWebUiActions (player.*) / MarketActions (market.*) /
 * MarketAdminActions (admin.*) / CaseWebUiActions (case.*)。
 */
export const SERVER_ACTIONS = [
  'admin.listItems',
  'admin.setBaseValue',
  'case.apply',
  'case.open',
  'case.state',
  'market.baseValue',
  'market.buy',
  'market.cancel',
  'market.categories',
  'market.history',
  'market.list',
  'market.mine',
  'market.place',
  'player.inventory',
  'player.wallet',
  'system.echo',
  'system.handshake',
] as const

/**
 * client.* 本地 action 名 (WebUiBridge.handleClientLocal 就地处理, 不经服务端往返, 不出现在
 * system.handshake 的 actions 字段里)。刻意与 SERVER_ACTIONS 分开导出: 握手自检若把这两份合并比对,
 * 会把"服务端 actions 不含 client.i18n / client.playCaseSound"误判成契约漂移 —— 这是设计如此, 不是缺陷。
 */
export const CLIENT_LOCAL_ACTIONS = ['client.i18n', 'client.playCaseSound'] as const

/** 参与 system.handshake 自检的服务端 action 名字面量类型。 */
export type WebUiServerActionName = (typeof SERVER_ACTIONS)[number]

/** 客户端本地 action 名字面量类型 (不参与握手自检)。 */
export type WebUiClientLocalActionName = (typeof CLIENT_LOCAL_ACTIONS)[number]

/** 服务端 + 客户端本地 action 名的并集, 供需要接受"任意合法 action 名"的调用点做字面量约束。 */
export type WebUiActionName = WebUiServerActionName | WebUiClientLocalActionName
