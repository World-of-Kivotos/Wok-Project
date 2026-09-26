package com.miningdim.achievement.trigger;

import com.miningdim.achievement.AchievementIds;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.PlayerTrigger;

import java.util.List;

/**
 * 本模块全部自定义触发器 (Achievement_System_DesignSpec 6.2 的 P1 各行) 的唯一实例与注册入口。
 *
 * 钩子一律经这里的静态实例触发, 例如 {@code AchievementTriggers.MINE_ORE.trigger(player, ore, difficulty)}。
 * 原版只给玩家尚未完成的条件挂监听, 完成即摘除, 各 trigger 方法里只做轻量比较, 不查库 (第十章)。
 *
 * <p>触发器一律注册, 不因可选模组缺失而跳过 (gun_kill 也一样); 依赖 TaCZ 的成就在进度 JSON 上加
 * {@code forge:mod_loaded} 加载条件。{@code married} 与 {@code open_shared_backpack} 没有条件字段, 直接复用
 * 原版的 {@link PlayerTrigger} 形态 (只带玩家谓词)。
 */
public final class AchievementTriggers {

    public static final EnterMiningTrigger ENTER_MINING = new EnterMiningTrigger();
    public static final MiningExtractionTrigger MINING_EXTRACTION = new MiningExtractionTrigger();
    public static final MineOreTrigger MINE_ORE = new MineOreTrigger();
    public static final StatAtLeastTrigger STAT_AT_LEAST = new StatAtLeastTrigger();
    public static final ChampionKillTrigger CHAMPION_KILL = new ChampionKillTrigger();
    public static final GunKillTrigger GUN_KILL = new GunKillTrigger();
    public static final AchievementCountTrigger ACHIEVEMENT_COUNT = new AchievementCountTrigger();
    /** 与伴侣完成婚礼 (P1 由登录与自动保存时读婚姻指针补查)。 */
    public static final PlayerTrigger MARRIED = new PlayerTrigger(AchievementIds.id("married"));
    /** 第一次打开和伴侣的共享背包。 */
    public static final PlayerTrigger OPEN_SHARED_BACKPACK =
            new PlayerTrigger(AchievementIds.id("open_shared_backpack"));

    private static final List<CriterionTrigger<?>> ALL = List.of(ENTER_MINING, MINING_EXTRACTION, MINE_ORE,
            STAT_AT_LEAST, CHAMPION_KILL, GUN_KILL, ACHIEVEMENT_COUNT, MARRIED, OPEN_SHARED_BACKPACK);

    private AchievementTriggers() {
    }

    /**
     * 登记进原版触发器表。原版的表是普通 HashMap、重复 id 直接抛, 所以只能在 FMLCommonSetup 的 enqueueWork 里
     * (主线程、每个进程一次、早于任何数据包加载) 调用。
     */
    public static void register() {
        ALL.forEach(CriteriaTriggers::register);
    }

    /** 全部触发器。 */
    public static List<CriterionTrigger<?>> all() {
        return ALL;
    }
}
