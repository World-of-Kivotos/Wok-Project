package com.miningdim.champion.integration;

import com.miningdim.champion.ChampionEffectRegistries;
import com.miningdim.champion.aggregate.PlayerDotAccumulator;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 冠军 DoT (燃烧/寒霜冻伤) 每秒聚合施加 (Champions 集成层; ChampionStarAffix spec 红线 4 DoT ≤15% maxHP/s + 9.5
 * 聚合器 + 9A.3 #8 per-player 跨多怪聚合)。
 *
 * 受击点 ({@link ChampionAttackHandler}) 只【刷层 + 记本秒名义伤害】进 per-player {@link PlayerDotAccumulator};
 * 真正扣血在本 tick handler: 每 server tick 对在册的每个 DoT 累加器, 到 1s flush 边界时经
 * {@code DotAggregator} 把该玩家本秒多源 DoT (燃烧 + 寒霜冻伤 + …) 合计夹到 ≤15% maxHP/s (超额按贡献比例衰减),
 * 然后把衰减后的合计伤害一次性施加到玩家。多源统一封顶在此单点完成 (红线 4: 非各源独立扣血)。
 *
 * 为何直接扣血而非 {@code player.hurt}: 本合计已是【红线 4 钳后的权威 DoT 量】(精确 ≤15% maxHP/s)。若再走
 * {@code player.hurt} 会被易伤放大 (撕裂/塔罗) 二次放大破 15% 封顶, 且受击 i-frame (invulnerableTime) 会吞掉每秒
 * DoT。故 DoT 作为服务端权威持续伤害直接扣 health (与 spec "%maxHP/s 持续伤害" 口径一致), 不经入伤事件链二次
 * 修正。死亡由原版 health≤0 自然触发。
 *
 * compileOnly 隔离: 本类不 import 任何 top.theillusivec4.champions.* (累加器/聚合器是纯逻辑), 但归 integration
 * 包 (仅 Champions 加载时由 bootstrap 挂 forgeBus, 与攻击 handler 同生命周期; dev 下 DOT 表恒空, 本 tick no-op)。
 */
public final class ChampionDotTickHandler {

    /** DoT 期间燃烧火粒子节流: 每多少 tick 在中招玩家身上播一轮火 (cosmetic 显示层, 与扣血结算分离)。 */
    private static final int DOT_PARTICLE_INTERVAL_TICKS = 3;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!ChampionEffectRegistries.hasAnyDot()) {
            return; // 无在册 DoT 累加器: 跳过 (dev / 无 DoT 战斗时 no-op)。
        }
        MinecraftServer server = event.getServer();
        long nowTick = server.overworld().getGameTime();
        boolean emitParticles = server.getTickCount() % DOT_PARTICLE_INTERVAL_TICKS == 0;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!ChampionEffectRegistries.hasDot(player.getUUID())) {
                continue;
            }
            // 持续燃烧火粒子 (cosmetic): DoT 在册期间在玩家身上播火, 让"纯 DoT"也看得见在烧 (不设真火、不二次伤害)。
            if (emitParticles && player.level() instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(ParticleTypes.FLAME,
                        player.getX(), player.getY() + player.getBbHeight() * 0.5D, player.getZ(),
                        3, player.getBbWidth() * 0.35D, player.getBbHeight() * 0.4D, player.getBbWidth() * 0.35D, 0.01D);
            }
            PlayerDotAccumulator acc = ChampionEffectRegistries.dotFor(player.getUUID());
            if (!acc.shouldFlush(nowTick)) {
                continue; // 未到 1s flush 边界。
            }
            double maxHp = player.getMaxHealth();
            if (maxHp <= 0.0D) {
                continue;
            }
            PlayerDotAccumulator.FlushResult result = acc.flush(maxHp, nowTick);
            double total = result.total();
            if (total <= 0.0D) {
                continue;
            }
            // 红线 4 钳后权威 DoT 量直接扣 health (不经入伤事件链二次放大): health = max(health - total, 0)。
            float newHealth = (float) Math.max(0.0D, player.getHealth() - total);
            player.setHealth(newHealth);
        }
    }
}
