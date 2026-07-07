// 测试辅助脚本 (KubeJS server_scripts): 指定玩家固定 500 最大血 + 每 5 分钟回满。
// 用途: 精英怪词条真服验收期让测试员站得住桩 (冠军单击按 %maxHP 计, 500 血下 25% 单击 = 125, 扛得住多轮)。
// 部署: /data/mcsm/instances/forge-1.20.1-47.4.20/kubejs/server_scripts/test_hp.js, 改完 /reload 即生效。
// 下线: 删本文件 + /reload (血量属性是 baseValue 直改, 删脚本后新登录/重生回默认; 已在线玩家重进即恢复)。

const $Attributes = Java.loadClass('net.minecraft.world.entity.ai.attributes.Attributes')

// 只对名单内玩家生效 (测试员账号; 防公服全员白嫖 500 血)。
const TEST_PLAYERS = ['Shinoyuki_Miyako']
const TARGET_HP = 500
const HEAL_INTERVAL_TICKS = 6000 // 5 分钟 = 6000 tick

function applyTestHp(player) {
  if (!TEST_PLAYERS.includes(player.username)) return
  const attr = player.getAttribute($Attributes.MAX_HEALTH)
  if (attr) {
    attr.baseValue = TARGET_HP
    player.health = player.maxHealth
    player.tell('[测试] 最大血量已固定 ' + TARGET_HP + ', 每 5 分钟自动回满')
  }
}

// 登录 / 死亡重生都重挂 (重生是新实体, 属性回默认须重设)。
PlayerEvents.loggedIn(event => applyTestHp(event.player))
PlayerEvents.respawned(event => applyTestHp(event.player))

// /reload 后对已在线玩家立即生效 (不用重进)。
ServerEvents.loaded(event => {
  event.server.players.forEach(p => applyTestHp(p))
})

// 每 5 分钟回满名单内玩家。
ServerEvents.tick(event => {
  if (event.server.tickCount % HEAL_INTERVAL_TICKS != 0) return
  event.server.players.forEach(p => {
    if (TEST_PLAYERS.includes(p.username)) {
      p.health = p.maxHealth
      p.tell('[测试] 血量已回满')
    }
  })
})
