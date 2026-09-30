/**
 * mock 面板层的公开面 —— 面板一律从这里 import, 不要深入到具体文件。
 *
 * 目录分工:
 *   planned.ts       前端假定契约 (系统商店两条, 服务端在 WOK-ChestShop 跨仓)
 *   store.ts         可变内存世界单例 + 订阅机制
 *   seed.ts          初始数据 (含边界值; 数值为演示用, 非平衡真源)
 *   district-seed.ts / district-handlers.ts   自管区的种子与内存实现 (含仅假数据模式可用的预览身份切换);
 *                    自管区已接线, 这两个文件是 lib/bridge.mock 里那 26 条的假后端, 类型住在 lib/types.ts
 *   handlers.ts      callMock: 与 lib/bridge 的 call 同签名的统一调用口
 *   useMockWorld.ts  React 侧的 useMockWorld / useMockAction
 *
 * 与 lib/bridge.mock.ts 的关系 (一句话): 那是**真契约 action** 的假后端, 挂在 call() 后面;
 * 本目录是**后端还没有的那些面板**的假世界。callMock 按 action 名把两类分流, 真的那类原样转调 call(),
 * 因此两边不会各自维护一份余额/背包/挂单。详见 handlers.ts 文件头。
 */

export { callMock, primeRealDomainMirror, recordMirrorError, refreshInventoryMirror } from './handlers'
export type { MockActionName, MockPayloadOf, MockResultOf } from './handlers'

export { getWorld, mutateWorld, nowMs, resetWorld, subscribeWorld } from './store'
export type {
  MockJobState,
  MockPlayerIdentity,
  MockRealDomainMirror,
  MockWalletOverlay,
  MockWorld,
} from './store'

export { mockActionKey, useMockAction, useMockWorld } from './useMockWorld'
export type { MockActionQuery, MockActionState } from './useMockWorld'

export { PLANNED_ACTIONS } from './planned'
export type * from './planned'

export { DISTRICT_PREVIEW_ADD_HINTS, DISTRICT_PREVIEW_OPTIONS } from './district-seed'
export type { DistrictPreviewOption, DistrictPreviewPersona } from './district-seed'
export { districtPreviewIdentity, setDistrictPreviewPersona } from './district-handlers'
