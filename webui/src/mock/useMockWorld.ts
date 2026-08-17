/**
 * React 侧的两个入口: 订阅世界本身, 或订阅一次 action 调用。
 *
 * 分成两个 hook 而不是一个, 因为两者的更新时机不同:
 *   - useMockWorld  读的是内存世界, 任何 mutateWorld 都要立刻反映 (hub 首页的余额、背包格靠它跨面板联动);
 *   - useMockAction 读的是一次调用的回执, 只在入参变化或显式 reload 时重新发起。
 *
 * useMockAction 刻意**不**订阅世界版本自动重查: 有些 action 自身就会改世界 (job.miner.scan 会写冷却,
 * job.tarot.buyPack 会改牌组), 一旦"世界变了就重查", 这类 action 会把自己拽进无限循环。需要跨面板联动的
 * 数据请走 useMockWorld, 需要重查的地方请显式调 reload —— 这条边界是刻意划的, 别为了省事把它抹掉。
 */

import { useCachedQuery } from '@/lib/query-cache'
import type { QueryState } from '@/lib/query-cache'
import { useSyncExternalStore } from 'react'
import type { MockActionName, MockPayloadOf, MockResultOf } from './handlers'
import { callMock } from './handlers'
import type { MockWorld } from './store'
import { getWorld, subscribeWorld } from './store'

/**
 * 订阅整个 mock 世界。
 * mutateWorld 每次都会换掉根对象引用, 故 getWorld 可以直接当 getSnapshot 用 —— 同一 revision 内引用恒定,
 * useSyncExternalStore 不会因此反复重渲染。
 */
export function useMockWorld(): MockWorld {
  return useSyncExternalStore(subscribeWorld, getWorld, getWorld)
}

/**
 * loading 态的 data 是 <b>T | null</b> 而不是恒 null: 重查时要保住上一份数据, 否则整页控件会闪一次骨架屏
 * 再长回来, 数字没变也闪。首次加载时它自然是 null, 调用方原有的 "status !== 'ready' 就画骨架" 写法不受影响。
 *
 * refreshing 是加出来的第四个字段 (不改动原有三态, 存量调用方一行不用动): 后台重查期间 status 保持 ready
 * 而 refreshing 为真, 想画一个不打断阅读的刷新指示就读它。
 */
export type MockActionState<T> = QueryState<T>

export type MockActionQuery<T> = MockActionState<T> & { reload: () => void }

/**
 * 发起一次 action 调用并跟随其生命周期。
 *
 * 缓存键 = action 名 + payload 的内容签名。取内容而不是对象引用: 调用方每次渲染都会新建一个 payload 字面量,
 * 用引用会让 effect 每帧重跑 (与 lib/i18n.ts 的 useItemNames 同一套理由)。
 *
 * 真正的缓存与在途合并住在 lib/query-cache (见那里的文件注释): 命中新鲜缓存时本 hook 首帧即 ready,
 * 一个请求都不发 —— 这是"切页面不闪"与"别反复打服务端"两件事的同一个实现。
 */
export function useMockAction<A extends MockActionName>(
  action: A,
  payload: MockPayloadOf<A>,
): MockActionQuery<MockResultOf<A>> {
  const signature = JSON.stringify(payload)
  return useCachedQuery<MockResultOf<A>>(
    `${action}|${signature}`,
    // payload 经签名往返一遍而不是直接闭包住入参对象: 与缓存键同源, 杜绝"键相同但实际发出的入参不同"。
    () => callMock(action, JSON.parse(signature) as MockPayloadOf<A>),
  )
}

/** 缓存键的唯一合成处。预取方 (侧栏悬停) 必须经它取键, 不许自己拼 —— 拼错的症状是预取永不命中。 */
export function mockActionKey<A extends MockActionName>(action: A, payload: MockPayloadOf<A>): string {
  return `${action}|${JSON.stringify(payload)}`
}
