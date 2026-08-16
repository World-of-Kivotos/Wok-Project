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

import { useRefreshRevision } from '@/lib/refresh'
import { useCallback, useEffect, useState, useSyncExternalStore } from 'react'
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
 * loading 态的 data 是 <b>T | null</b> 而不是恒 null: 重拉 (全局作废 / reload) 时要保住上一份数据,
 * 否则整页控件会闪一次骨架屏再长回来, 数字没变也闪。首次加载时它自然是 null, 调用方原有的
 * "status !== 'ready' 就画骨架" 写法不受影响 —— 想做"旧数据 + 刷新中"的调用方才需要读这个 data。
 */
export type MockActionState<T> =
  | { status: 'loading'; data: T | null; error: null }
  | { status: 'ready'; data: T; error: null }
  | { status: 'error'; data: null; error: Error }

export type MockActionQuery<T> = MockActionState<T> & { reload: () => void }

function toError(value: unknown): Error {
  return value instanceof Error ? value : new Error(String(value))
}

/**
 * 发起一次 action 调用并跟随其生命周期。
 *
 * 依赖取 payload 的内容签名而不是对象引用: 调用方每次渲染都会新建一个 payload 字面量, 用引用会让 effect
 * 每帧重跑 (与 lib/i18n.ts 的 useItemNames 同一套理由)。签名走 JSON 是可逆的, effect 里再解回来即可。
 */
export function useMockAction<A extends MockActionName>(
  action: A,
  payload: MockPayloadOf<A>,
): MockActionQuery<MockResultOf<A>> {
  const signature = JSON.stringify(payload)
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<MockActionState<MockResultOf<A>>>({
    status: 'loading',
    data: null,
    error: null,
  })

  // 全局作废版本号: 别处的写操作 (或面板重开) 会推高它, 本查询随之重拉。
  const revision = useRefreshRevision()

  const reload = useCallback(() => {
    setAttempt((previous) => previous + 1)
  }, [])

  useEffect(() => {
    let cancelled = false
    // 重拉时<b>保住上一份数据</b>, 只把状态压回 loading。原来这里连 data 一起清成 null, 于是每次作废
    // (领个奖、重开面板) 整页控件都会闪一次骨架屏再长回来 —— 数字明明没变也闪。首次加载 data 本来就是
    // null, 不受影响。
    setState((previous) => ({ status: 'loading', data: previous.data, error: null }))
    const parsed = JSON.parse(signature) as MockPayloadOf<A>
    callMock(action, parsed)
      .then((data) => {
        if (!cancelled) {
          setState({ status: 'ready', data, error: null })
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          // 保留原始错误对象 (WebUiCallError 带 action/code/business 三个字段), 不要在这层压成一句文案。
          setState({ status: 'error', data: null, error: toError(error) })
        }
      })
    return () => {
      cancelled = true
    }
  }, [action, signature, attempt, revision])

  return { ...state, reload }
}
