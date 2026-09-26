package com.miningdim.champion;

import com.miningdim.champion.integration.ChampionPromoter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 世界 BOSS (ChampionStarAffix spec 第十章; 2026-09-26 服主拍板: 10★ 只由世界 BOSS 产生, 世界 BOSS 暂用指令刷,
 * 出现时提示全服玩家)。对外的唯一入口: 判定 {@link #isWorldBoss} + 落地 {@link #spawn}。
 *
 * <p><b>标记</b>: 世界 BOSS 是一只普通盖章的冠军, 外加冠军 capability 上的 {@code world_boss} 字段
 * ({@link MiningChampionData#isWorldBoss}), 随实体 NBT 持久 —— 区块卸载重载、服务端重启后身份不丢。它不带矿区实例标记,
 * 也不限维度; 成就模块的击杀过滤据此在任意维度接受它, 而普通 {@code /mchampion summon} 召唤的精英没有这个标记, 仍被排除。
 *
 * <p><b>落地</b>: 与 {@code /mchampion summon} 共用唯一盖章入口 {@link ChampionPromoter#applyChampion} (capability + 血量 +
 * 6★+ 血池), 不调原版 finalizeSpawn (与命令召唤同口径: 不随机出幼年/装备/首领血量倍率, 冠军血量由盖章全权接管)。落地前
 * {@code setPersistenceRequired}, 世界 BOSS 永不自然消失; 全部状态提交后才全服公告 ({@link WorldBossBroadcast#announceSpawn})。
 * 击倒公告见 {@code integration.WorldBossHandler}。
 */
public final class WorldBoss {

    /** 世界 BOSS 的最低星级 (spec 第二章: 8-10★ 为世界 BOSS 档)。上限即 {@link StarRank#MAX_STAR}。 */
    public static final int MIN_STAR = 8;

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/worldboss");

    private WorldBoss() {
    }

    /** 该实体是否世界 BOSS (有冠军 capability 且带世界 BOSS 标记); 非 Mob / null 返回 false。 */
    public static boolean isWorldBoss(LivingEntity entity) {
        return MiningChampions.get(entity).map(MiningChampionData::isWorldBoss).orElse(false);
    }

    /**
     * 把一只尚未入世的 Mob 作为世界 BOSS 落到 level 的 pos 处: 定位 -> 常驻 -> 入世 -> 盖章 -> 打世界 BOSS 标记 -> 全服公告。
     * 世界拒收 (入世事件被取消、UUID 冲突) 时丢弃实体、不盖章不公告, 返回 false。
     *
     * @param level   落地维度 (命令源所在维度, 不要求执行者是玩家)
     * @param mob     刚由实体类型创建、尚未入世的 Mob
     * @param pos     落点 (命令源位置)
     * @param yRot    朝向 (命令源的水平朝向)
     * @param star    星级, 须在 [{@value #MIN_STAR}, {@value StarRank#MAX_STAR}]
     * @param affixes 词条 -> 品质 (拷入)
     * @return 是否已落地并公告
     */
    public static boolean spawn(ServerLevel level, Mob mob, Vec3 pos, float yRot, int star,
                                Map<AffixDef, AffixQuality> affixes) {
        if (star < MIN_STAR || star > StarRank.MAX_STAR) {
            throw new IllegalArgumentException("world boss star out of [" + MIN_STAR + "," + StarRank.MAX_STAR
                    + "]: " + star);
        }
        MiningChampionData data = MiningChampions.get(mob).orElse(null);
        if (data == null) {
            mob.discard();
            return false; // 没挂上冠军 capability (不该发生: 每只 Mob 构造时就挂): 无处打标记, 不落地。
        }
        mob.moveTo(pos.x, pos.y, pos.z, yRot, 0.0F);
        mob.setPersistenceRequired();
        if (!level.addFreshEntity(mob)) {
            mob.discard();
            LOGGER.warn("world boss {} star{} was refused by {} at {}, not announced",
                    mob.getType().getDescriptionId(), star, level.dimension().location(), mob.blockPosition());
            return false;
        }
        ChampionPromoter.applyChampion(mob, star, affixes);
        data.markWorldBoss();
        LOGGER.info("world boss {} star{} spawned in {} at {} affixes={}", mob.getType().getDescriptionId(), star,
                level.dimension().location(), mob.blockPosition(), data.affixes());
        WorldBossBroadcast.announceSpawn(level.getServer(), mob, data);
        return true;
    }
}
