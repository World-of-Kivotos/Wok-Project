package com.miningdim.job.agent.integration;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import top.theillusivec4.champions.api.IChampion;
import top.theillusivec4.champions.common.capability.ChampionCapability;

/**
 * 特勤集成层对"本工程盖章精英"的探测 + 盖章 NBT 读取门面 (SpecialAgent_Job_DesignSpec 五章探测 + 六章封印
 * 前置 + 七章奖励前置)。所有触 Champions capability/IChampion 的读取收敛于此, 供探测/封印/奖励/悬赏接线复用,
 * 不在各 handler 各写一套 capability 探测 (单一探测来源, 范式对齐 {@code ChampionRewardHandler.isOurChampion})。
 *
 * 盖章 NBT 契约 (与 {@code ChampionPromoter} 盖章侧严格同口径): 升格器在 {@code IChampion.getServer().setData}
 * 写入子标签 {@value #DATA_KEY}, 内含整型 {@value #NBT_STAR} (初始星级) 与浮点 {@value #NBT_EFFECTIVE_HP}
 * (总有效血)。本类按同名键读取。
 *
 * 为何在此重声明键字面量而非 import champion 包常量: {@code ChampionPromoter.DATA_KEY/NBT_STAR/NBT_EFFECTIVE_HP}
 * 当前是 {@code com.miningdim.champion.integration} 包私有 (package-private), 特勤集成层在
 * {@code com.miningdim.job.agent.integration} 跨包不可见。按硬约束"只在 com.miningdim.job.agent 新建、不改已落地
 * champion 子系统", 本类不提升那三个常量的可见性 (越界改 champion 包), 而以同值字面量复刻这份稳定的 namespaced
 * NBT 契约 (key 是出生即盖章、运营期不变的协议常量, 复刻一份是受约束下的最小代价)。把它们提升为 public 或加
 * getter 是后续可做的去重项 (留待用户定夺, 见交付报告)。
 *
 * compileOnly 隔离: 本类 import top.theillusivec4.champions.* —— 仅在 {@code ModList.isLoaded("champions")}
 * 守卫下由 {@link AgentIntegrationBootstrap} 触达, dev (Champions 未加载) 永不被类加载。
 */
final class AgentChampionData {

    private AgentChampionData() {
    }

    /** 自定义四池/星级数据在 IChampion.getServer().getData 的子标签键 (与 ChampionPromoter.DATA_KEY 同值)。 */
    static final String DATA_KEY = "miningdim_champion";

    /** NBT 字段: 本工程盖章的初始星级 (与 ChampionPromoter.NBT_STAR 同值)。 */
    static final String NBT_STAR = "star";

    /** NBT 字段: 该精英总有效血 (贡献池盖章门槛分母; 与 ChampionPromoter.NBT_EFFECTIVE_HP 同值)。 */
    static final String NBT_EFFECTIVE_HP = "effective_hp";

    /**
     * 取某实体的 IChampion (经 Champions capability); 非冠军 / 无服务端镜像返回 null。
     *
     * @param entity 受检实体 (普通怪无 capability 返回 null)
     * @return IChampion (服务端镜像非空才返回) 或 null
     */
    static IChampion championOf(LivingEntity entity) {
        if (entity == null) {
            return null;
        }
        IChampion champion = ChampionCapability.getCapability(entity).orElse(null);
        if (champion == null || champion.getServer() == null) {
            return null;
        }
        return champion;
    }

    /**
     * 是否本工程盖章的精英 (有 miningdim_champion/star NBT)。外来 mod 的冠军有 capability 但无本工程盖章 NBT,
     * 须排除 (范式对齐 {@code ChampionRewardHandler.isOurChampion})。
     *
     * @param champion 非 null 的 IChampion (服务端镜像非空)
     * @return 是否本工程盖章精英
     */
    static boolean isOurChampion(IChampion champion) {
        CompoundTag data = champion.getServer().getData(DATA_KEY);
        return data != null && data.contains(NBT_STAR);
    }

    /**
     * 读本工程盖章的初始星级 (出生即盖章, 即使被封印削弱也不变, 与贡献池池大小同口径)。非本工程精英 / 缺字段返 0。
     *
     * @param champion 非 null 的 IChampion
     * @return 初始星级 (1-10); 非本工程精英返 0
     */
    static int starOf(IChampion champion) {
        CompoundTag data = champion.getServer().getData(DATA_KEY);
        if (data == null || !data.contains(NBT_STAR)) {
            return 0;
        }
        return data.getInt(NBT_STAR);
    }

    /**
     * 读本工程盖章的总有效血 (贡献池门槛分母; 6★+=血池 maxHp, 1-5★=星表基础有效血)。缺字段返 0。
     *
     * @param champion 非 null 的 IChampion
     * @return 总有效血; 缺字段返 0
     */
    static double effectiveHpOf(IChampion champion) {
        CompoundTag data = champion.getServer().getData(DATA_KEY);
        if (data == null || !data.contains(NBT_EFFECTIVE_HP)) {
            return 0.0D;
        }
        return data.getDouble(NBT_EFFECTIVE_HP);
    }
}
