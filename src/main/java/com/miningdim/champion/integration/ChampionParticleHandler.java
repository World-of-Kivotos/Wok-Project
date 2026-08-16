package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.ChampionAffixParticles;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 精英怪词条环境指示粒子 (自研冠军显示层; 视觉反馈)。我方词条是纯数据标记 (无客户端自绘粒子链), 故服务端每
 * {@value #EMIT_INTERVAL_TICKS} tick 对 {@value ChampionProximityScanner#VIEW_RANGE} 格快照内的精英怪, 经
 * {@link ChampionAffixParticles} 取签名粒子, {@code sendParticles} 在冠军身上随机偏移播几颗 —— vanilla 服务端粒子
 * 机制自动同步附近客户端, 故纯服务端、零客户端代码。与 {@link ChampionBossBarHandler} 同范式
 * (共享近场快照 + 自研 capability 检出冠军)。
 *
 * 数据源: 经 {@link MiningChampions#get} 读自研 {@link MiningChampionData} 的词条集 ({@link AffixDef}), 不触任何
 * top.theillusivec4.champions.* (故 dev 亦可加载)。粒子主题映射纯逻辑下沉 {@link ChampionAffixParticles} (GameTest 验)。
 */
public final class ChampionParticleHandler {

    /** 播粒子节流: 每多少 tick 播一轮 (0.25s; 够顺滑且省扫描/网络)。 */
    private static final int EMIT_INTERVAL_TICKS = 5;

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
        for (ChampionProximityScanner.Sighting sighting : ChampionProximityScanner.sightings(event.getServer())) {
            if (!sighting.entity().isAlive()) {
                continue; // 快照按 tick 复用, 同 tick 更早的 handler 可能已致死: 存活性逐条重查。
            }
            emitAffixParticles(sighting.level(), sighting.entity());
        }
    }

    /** 实体若是精英怪则对其每个我方词条在身上随机偏移播签名粒子; 非冠军/无我方词条不播。 */
    private static void emitAffixParticles(ServerLevel level, LivingEntity entity) {
        MiningChampionData champ = MiningChampions.get(entity).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 非冠军。
        }
        RandomSource rng = entity.getRandom();
        double width = entity.getBbWidth();
        double height = entity.getBbHeight();
        for (AffixDef def : champ.affixes().keySet()) {
            ParticleOptions particle = ChampionAffixParticles.ambientParticle(def);
            for (int i = 0; i < PARTICLES_PER_AFFIX; i++) {
                double px = entity.getX() + (rng.nextDouble() - 0.5D) * width * 1.4D;
                double py = entity.getY() + rng.nextDouble() * height;
                double pz = entity.getZ() + (rng.nextDouble() - 0.5D) * width * 1.4D;
                level.sendParticles(particle, px, py, pz, 1, 0.0D, 0.02D, 0.0D, 0.01D);
            }
        }
    }
}
