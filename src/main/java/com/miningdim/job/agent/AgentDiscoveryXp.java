package com.miningdim.job.agent;

import com.miningdim.champion.MiningChampionData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * "首次扫描发现精英"经验 (SpecialAgent_Job_DesignSpec 8.1: 星级 x 8, 每只仅首次, 奖励"找", 防重复扫刷)。
 *
 * 发放口径:
 *  - 不受入职标志 (activeAgent) 约束 —— 与击杀经验同一条 F016 理由: 经验是新号升到 L3 去封印、进而入职的唯一
 *    通路, 共用那道门就是"没入职 -> 没经验 -> 进不了职"的死锁 (见 {@code AgentRewardHandler}); 经验不产货币且走
 *    职业框架每日软上限, 不构成福利泄漏;
 *  - 支援召唤词条召出的召唤物 ({@link MiningChampionData#isSummonedByAffix}) 不给 —— 与击杀结算同一道经济闸
 *    (ChampionStarAffix 7.4 支援 [红队]), 否则一只带支援的精英每 14 秒就给附近干员刷一批"新发现";
 *  - 去重键 = (玩家, 精英实体): 同一只精英对同一名玩家一辈子只给一次, 不同玩家各自首次各给一次 (每人都真的
 *    "找"了一次)。
 *
 * 去重记录存哪 (持久化选择): 写在<b>精英实体自己</b>的 Forge 持久数据 ({@code Entity.getPersistentData()}) 里, 一个
 * 发现者 UUID 列表。理由:
 *  1. 生命期天然正确: 精英死亡 / 被清除, 记录随实体一起消失, 不需要任何死亡监听去清全局表, 也不存在 despawn 不发
 *     死亡事件导致的泄漏 (血池注册表为这类泄漏专门补过离场监听);
 *  2. 跨区块卸载与服务端重启都保留 —— 若只存进程内存, 重启后同一只精英可以被同一个人再"首次发现"一次, 长寿的
 *     世界 BOSS 会变成每次重启一笔免费经验;
 *  3. 不碰自研冠军 capability ({@link MiningChampionData}) 的存储格式 —— 那是精英模块的权威数据, 封印持久化等
 *     改动正在那边进行, 特勤侧的簿记不该挤进去;
 *  4. 有界: 每只精英最多记 {@value #MAX_DISCOVERERS_PER_CHAMPION} 名发现者, 满了之后不再给新人发 (宁可少发不重发;
 *     一只精英被 64 个不同的人扫过已远超任何世界 BOSS 战的规模)。
 */
public final class AgentDiscoveryXp {

    /** 每星经验 (8.1 表: 1★=8 … 10★=80)。 */
    public static final int XP_PER_STAR = 8;

    /** 每只精英最多记录的发现者数 (有界内存 / NBT 体积上限, 见类注释第 4 条)。 */
    public static final int MAX_DISCOVERERS_PER_CHAMPION = 64;

    /** 精英持久数据里的发现者列表键 (modid 前缀防与其它 mod 的持久数据撞键)。 */
    static final String NBT_DISCOVERED_BY = "miningdim_agent_discovered_by";

    private AgentDiscoveryXp() {
    }

    /**
     * 登记一次发现并返回应发的原始经验。已发现过 / 召唤物 / 非精英 / 名额已满时返 0 且不写任何记录。
     *
     * @param champion 被扫到的精英实体 (写它的持久数据)
     * @param data     该实体的自研冠军数据
     * @param playerId 扫描玩家
     * @return 应入账的原始经验 (星级 x {@value #XP_PER_STAR}); 不发时 0
     */
    public static long claim(LivingEntity champion, MiningChampionData data, UUID playerId) {
        if (data == null || !data.isChampion() || data.isSummonedByAffix()) {
            return 0L;
        }
        CompoundTag persistent = champion.getPersistentData();
        ListTag discoverers = persistent.getList(NBT_DISCOVERED_BY, Tag.TAG_INT_ARRAY);
        for (Tag entry : discoverers) {
            if (playerId.equals(NbtUtils.loadUUID(entry))) {
                return 0L; // 这名玩家早已发现过这只精英。
            }
        }
        if (discoverers.size() >= MAX_DISCOVERERS_PER_CHAMPION) {
            return 0L; // 名额已满: 不再登记, 也不发 (宁可少发不重发)。
        }
        discoverers.add(NbtUtils.createUUID(playerId));
        persistent.put(NBT_DISCOVERED_BY, discoverers); // getList 对缺键返回的是游离新表, 必须显式写回。
        return (long) data.star() * XP_PER_STAR;
    }

    /** 该玩家是否已登记为这只精英的发现者 (GameTest 用)。 */
    static boolean hasDiscovered(LivingEntity champion, UUID playerId) {
        ListTag discoverers = champion.getPersistentData().getList(NBT_DISCOVERED_BY, Tag.TAG_INT_ARRAY);
        for (Tag entry : discoverers) {
            if (playerId.equals(NbtUtils.loadUUID(entry))) {
                return true;
            }
        }
        return false;
    }
}
