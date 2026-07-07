// 测试辅助脚本 (KubeJS server_scripts): 指定玩家固定 500 最大血 + 每 5 分钟回满。
// 用途: 精英怪词条真服验收期让测试员站得住桩 (冠军单击按 %maxHP 计, 500 血下 25% 单击 = 125, 扛得住多轮)。
// 部署: /data/mcsm/instances/forge-1.20.1-47.4.20/kubejs/server_scripts/test_hp.js, 改完 /reload 即生效。
// 下线: 删本文件 + /reload, 玩家重进后血量恢复默认。
//
// 实现注: 不依赖 loggedIn/loaded 事件时机 (实测 /reload 后 loaded 未对在线玩家生效), 改为 tick 周期强制:
// 每 5 秒核对名单玩家 baseValue, 漂移 (重生/换维/其它系统改动) 即重设 —— 自愈, 无需重进。

const $Attributes = Java.loadClass('net.minecraft.world.entity.ai.attributes.Attributes')

// 只对名单内玩家生效 (测试员账号; 防公服全员白嫖 500 血)。
const TEST_PLAYERS = ['Shinoyuki_Miyako']
const TARGET_HP = 500
const ENFORCE_INTERVAL_TICKS = 100  // 5 秒核对一次 baseValue
const HEAL_INTERVAL_TICKS = 6000    // 5 分钟回满一次

ServerEvents.tick(event => {
  const tick = event.server.tickCount

  // 周期强制: baseValue 漂移即重设 (覆盖 登录/重生/reload/他系统改动 全部时机)。
  if (tick % ENFORCE_INTERVAL_TICKS == 0) {
    event.server.players.forEach(p => {
      if (!TEST_PLAYERS.includes(p.username)) return
      const attr = p.getAttribute($Attributes.MAX_HEALTH)
      if (attr && attr.baseValue != TARGET_HP) {
        attr.baseValue = TARGET_HP
        p.health = p.maxHealth
        p.tell('[测试] 最大血量已固定 ' + TARGET_HP + ', 每 5 分钟自动回满')
      }
    })
  }

  // 每 5 分钟回满名单内玩家。
  if (tick % HEAL_INTERVAL_TICKS == 0) {
    event.server.players.forEach(p => {
      if (TEST_PLAYERS.includes(p.username)) {
        p.health = p.maxHealth
        p.tell('[测试] 血量已回满')
      }
    })
  }
})
