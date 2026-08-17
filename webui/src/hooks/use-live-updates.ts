import { useEffect, useState } from 'react'
import { usePanelVisible } from '@/lib/panel-visibility'
// 直取 store 而不是 @/mock 桶文件: 那条"一律从桶文件 import"的规矩是写给面板的, 而本文件是面板的下游依赖,
// 经桶文件会把整个 handlers/bridge 链拖进来, 平白制造一条 hooks -> mock 全量的依赖边。
import { nowMs } from '@/mock/store'

/**
 * "服务端不会主动告诉你" 这件事的两个后果, 集中收在这一个文件里。
 *
 * 一、轮询间隔 (W12 服务端推送已推迟)
 * 现在没有任何 S2C 推送通道: 别人向我求婚、我的入场请求最终有没有传送成功, 服务端都不会再说第二次
 * (marriage.propose 只发聊天消息, EntryGateway 的成败只走原生 TeleportResult S2C, webui 通道收不到)。
 * 唯一的办法是前端定时重拉。间隔集中在 POLL_INTERVAL_MS 而不是各页面自己写 setInterval(5000):
 * 每条 action 的服务端代价并不一样 (economy.today 打 SQLite 且跑在主线程, player.profile 打 3 次),
 * 散落之后没人能一眼看出"这一屏每分钟给主线程加了多少次查询"。推送落地后删掉的也是这一处。
 *
 * 二、tick -> 本地基准
 * 服务端手里只有 game tick, 一律发**剩余 tick** 而不是绝对时刻 (转成服务端墙钟再让 MCEF 拿 Date.now()
 * 去减, 既吃时钟偏移又在 TPS 掉帧时失真)。故所有倒计时都必须在**收到回执那一刻**折成本地到期时刻,
 * 之后纯本地推进。
 */

/** 1 game tick = 50ms (20 tick/s)。服务端全部 *RemainingTicks 字段按这个折算。 */
export const MS_PER_TICK = 50

/**
 * 把回执里的"剩余 tick"折成本地到期时刻 (epoch ms)。**必须传收到该回执的时刻**, 不能事后补算。
 *
 * 返回 0 表示"不在冷却 / 无快照" —— 服务端对这两种情况发的就是 0 tick, 折成一个过去的时刻会让
 * "已就绪"与"刚好这一刻到期"在渲染层混成一类。
 */
export function tickDeadline(remainingTicks: number, receivedAt: number): number {
  return remainingTicks <= 0 ? 0 : receivedAt + remainingTicks * MS_PER_TICK
}

/**
 * 各条需要轮询的 action 的间隔。
 *
 * **不在此表内的 action 一律不许轮询**, 尤其这三条 (契约里写死的服务端约束):
 *   economy.today    打 1 次 SQLite 且跑在服务器主线程, 契约明文"禁止定时轮询";
 *   player.profile   每次打 3 次 SQLite, 契约明文"禁止把 profile 挂上定时轮询";
 *   job.tarot.state / champion.codex / job.blueprints  静态或半静态表, 轮询只是白烧往返。
 */
export const POLL_INTERVAL_MS = {
  /**
   * mining.myStatus。mining.enter 只回"已受理", 真正的传送在之后若干 tick 才发生且成败不走 webui 通道
   * (契约明文: 面板要确认是否真进去了必须轮询本条)。取 3 秒是因为入场链路要等区块 FULL, 秒级以内的
   * 刷新只会连打几次都还没进去。本 action 零 SQLite (读 capability + 几何反查)。
   */
  miningStatus: 3_000,
  /**
   * marriage.state。求婚/应答都没有推送, 对方面板只能靠重拉发现。取 10 秒: 求婚是人对人的交互,
   * 十秒内看到通知足够, 而它每次要读关系登记表 + 扫 36 格背包算婚戒。
   */
  marriageState: 10_000,
} as const

/**
 * 按固定间隔重复调用 reload。enabled 为假时一个定时器都不挂 (不是挂了再跳过) ——
 * 面板没打开、或本页当前不需要跟踪时, 不该给服务端主线程留一条空转的心跳。
 *
 * 平板不可见时一律不挂 (usePanelVisible)。关面板只是隐藏 MC 的 Screen, 这个 SPA 原样活着, 于是
 * 玩家关掉平板出去挖矿的整段时间里, 停在矿洞页的那条 3 秒轮询还在跑 —— 桥的 -4 关屏门只挡住请求出门,
 * 挡不住这次重渲染带来的整表面重绘 (见 lib/panel-visibility)。
 *
 * reload 必须是稳定引用 (useMockAction 的 reload 是 useCallback([]) 的, 天然满足);
 * 传一个每帧重建的闭包会让定时器每帧重挂, 于是永远轮不到它触发。
 */
export function usePolling(reload: () => void, intervalMs: number, enabled = true): void {
  const visible = usePanelVisible()
  useEffect(() => {
    if (!enabled || !visible) {
      return
    }
    const timer = window.setInterval(reload, intervalMs)
    return () => {
      window.clearInterval(timer)
    }
  }, [reload, intervalMs, enabled, visible])
}

/**
 * 自走的"当前时刻"。倒计时若只在渲染那一刻算一次, 玩家看到的是一个静止的假数字 —— 冷却、新手保护、
 * 翻日剩余全是随时间走的量, 必须自己推。
 *
 * 收在这里而不是各页面自己写一个: 改动前首页 (useNowTick)、职业面板 (useLiveNow)、矿洞页与婚姻页
 * 各有一份逐字相同的实现, 四处都得记得加可见性门 —— 而漏掉一处的症状是"关了平板还在后台掉帧",
 * 没有任何检查会报它。
 *
 * 不可见时停表, 重新可见时**立刻**跳到当前时刻而不是等下一拍: 30 秒那一档若等下一拍, 玩家重开面板后
 * 最长有半分钟看到的是一个过期的倒计时, 而它与真数字长得毫无区别。
 */
export function useLiveClock(intervalMs: number, enabled = true): number {
  const visible = usePanelVisible()
  const running = enabled && visible
  const [now, setNow] = useState(nowMs)

  useEffect(() => {
    if (!running) {
      return
    }
    setNow(nowMs())
    const timer = window.setInterval(() => {
      setNow(nowMs())
    }, intervalMs)
    return () => {
      window.clearInterval(timer)
    }
  }, [intervalMs, running])

  return now
}
