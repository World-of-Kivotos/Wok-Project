/**
 * 全局数据作废信号。
 *
 * 解决两个"数据不动"的场景, 它们都不是 useMockAction 自己能看见的:
 *
 *  1. <b>写操作的涟漪</b>。领一次任务会同时改任务板与钱包, 而钱包画在外壳顶栏、任务板画在页面里, 是两个
 *     互不相识的 hook 实例。任务页调自己的 reload() 只能刷新自己那份, 顶栏的余额要等下一次整页重挂才变。
 *  2. <b>面板重开</b>。关面板只是隐藏 MC 的 Screen, 这个 SPA 原样活着 —— 玩家在游戏里挖了半小时矿再打开
 *     平板, 看到的还是半小时前那份数据, 且没有任何东西会告诉页面"你被重新打开了"。宿主为此在打开时派
 *     一个 panelOpened 事件 (见 WebUiClient.openScreen), 收到就整体作废。
 *
 * 做成"一个全局版本号 + 所有查询都跟着它重拉", 而不是按 action 精细失效: 后者要维护一张"哪个写操作会
 * 影响哪些读操作"的映射表, 而那张表一旦漏一条, 症状就是某个数字偶尔不刷新 —— 极难复现也极难归因。
 * 服务端每玩家令牌桶是 120 突发 / 30 每秒, 而整页冷启动实测 11 条, 全量重拉一次绰绰有余。
 */

import { useSyncExternalStore } from 'react'

let revision = 0
const listeners = new Set<() => void>()

/** 把所有在途查询标记为过期, 触发重拉。写操作成功后与面板重开时调用。 */
export function invalidateAll(): void {
  revision += 1
  for (const notify of listeners) {
    notify()
  }
}

function subscribe(onStoreChange: () => void): () => void {
  listeners.add(onStoreChange)
  return () => {
    listeners.delete(onStoreChange)
  }
}

function getSnapshot(): number {
  return revision
}

/** 当前作废版本号。放进查询 hook 的依赖数组即可跟随作废。 */
export function useRefreshRevision(): number {
  return useSyncExternalStore(subscribe, getSnapshot)
}
