package com.miningdim.champion.integration;

import com.miningdim.champion.ChampionAffixParticles;
import com.miningdim.champion.integration.affix.MiningAffix;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import top.theillusivec4.champions.api.IAffix;
import top.theillusivec4.champions.api.IChampion;
import top.theillusivec4.champions.common.capability.ChampionCapability;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 精英怪词条环境指示粒子 (Champions 集成层; 显示层视觉反馈)。我方词条是纯标记 + 设置 hasSub:false, 原版 Champions
 * 的 {@code onClientUpdate} 客户端自绘粒子链对其不触发, 故服务端每 {@value #EMIT_INTERVAL_TICKS} tick 扫玩家附近
 * (&lt;= {@value #VIEW_RANGE} 格) 精英怪, 对其每个我方词条经 {@link ChampionAffixParticles} 取签名粒子,
 * {@code sendParticles} 在冠军身上随机偏移播几颗 —— vanilla 服务端粒子机制自动同步附近客户端, 故纯服务端、零客户端代码。
 * 与 {@link ChampionBossBarHandler} 同范式 (按玩家 AABB 扫 + capability 检出冠军)。
 *
 * compileOnly 隔离: 本类 import top.theillusivec4.champions.* (读 IChampion 词条池), 仅 Champions 加载时由
 * {@link ChampionIntegrationBootstrap} 挂 forgeBus, dev (Champions 未加载) 永不注册。粒子主题映射纯逻辑下沉
 * {@link ChampionAffixParticles} (GameTest 验)。
 */
public final class ChampionParticleHandler {

    /** 播粒子节流: 每多少 tick 播一轮 (0.25s; 够顺滑且省扫描/网络)。 */
    private static final int EMIT_INTERVAL_TICKS = 5;

    /** 播粒子的玩家可见距离 (格); 与 BOSS 血条同量级。 */
    private static final double VIEW_RANGE = 48.0D;

    /** 每词条每轮播的颗数 (多词条冠军身上多种粒子交织, 显示其全部词条)。 */
    private static final int PARTICLES_PER_AFFIX = 2;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (event.getServer().getTickCount() % EMIT_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            List<ServerPlayer> players = level.players();
            if (players.isEmpty()) {
                continue;
            }
            Set<UUID> emitted = new HashSet<>();
            for (ServerPlayer player : players) {
                AABB box = player.getBoundingBox().inflate(VIEW_RANGE);
                for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
                    if (!emitted.add(entity.getUUID())) {
                        continue; // 多玩家同时看同一冠军: 本轮只播一次 (避免重复刷)。
                    }
                    emitAffixParticles(level, entity);
                }
            }
        }
    }

    /** 实体若是精英怪则对其每个我方词条在身上随机偏移播签名粒子; 非冠军/无我方词条不播。 */
    private static void emitAffixParticles(ServerLevel level, LivingEntity entity) {
        IChampion champion = ChampionCapability.getCapability(entity).resolve().orElse(null);
        if (champion == null || champion.getServer() == null) {
            return; // 非冠军。
        }
        RandomSource rng = entity.getRandom();
        double width = entity.getBbWidth();
        double height = entity.getBbHeight();
        for (IAffix affix : champion.getServer().getAffixes()) {
            if (!(affix instanceof MiningAffix mining)) {
                continue; // 仅我方词条 (持 AffixDef); 第三方词条不碰 (其自带 onClientUpdate)。
            }
            ParticleOptions particle = ChampionAffixParticles.ambientParticle(mining.def());
            for (int i = 0; i < PARTICLES_PER_AFFIX; i++) {
                double px = entity.getX() + (rng.nextDouble() - 0.5D) * width * 1.4D;
                double py = entity.getY() + rng.nextDouble() * height;
                double pz = entity.getZ() + (rng.nextDouble() - 0.5D) * width * 1.4D;
                level.sendParticles(particle, px, py, pz, 1, 0.0D, 0.02D, 0.0D, 0.01D);
            }
        }
    }
}
