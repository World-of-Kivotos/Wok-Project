package com.miningdim.achievement.trigger;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.Stats;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成就用的自定义统计项 (Achievement_System_DesignSpec 6.1)。
 *
 * 经 {@code DeferredRegister<ResourceLocation>(Registries.CUSTOM_STAT)} 注册, 数值随原版统计文件按玩家保存
 * ({@code world/stats/<uuid>.json}), 原版"统计信息"界面里也看得到。计数类成就不自己另存计数, 一律用
 * {@code stat_at_least} 读这里的值。
 *
 * <p>格式化器必须在任何代码以默认格式器取用这些统计项之前绑定: {@link net.minecraft.stats.StatType} 只在第一次取用时
 * 创建 Stat 对象并记下格式器, 之后再传别的格式器也不会生效。{@link #bindFormatters} 因此放在 FMLCommonSetup
 * (两端都会跑, 早于任何存档加载与统计同步) 里执行。
 */
public final class AchievementStats {

    private static final DeferredRegister<ResourceLocation> CUSTOM_STATS =
            DeferredRegister.create(Registries.CUSTOM_STAT, MiningConstants.MODID);

    /** 注册顺序即原版统计界面里的显示顺序; 值为该项的格式器。 */
    private static final Map<RegistryObject<ResourceLocation>, StatFormatter> FORMATTERS = new LinkedHashMap<>();

    /** 从其他维度进入矿区。只供统计界面展示, 没有成就读它 (进入免费、离开瞬间, 可以无限刷)。 */
    public static final RegistryObject<ResourceLocation> MINING_ENTRIES = stat("mining_entries", StatFormatter.DEFAULT);
    /** 有效撤离 (6.3), 每人每天 (UTC) 最多计 dailyExtractionCap 次。 */
    public static final RegistryObject<ResourceLocation> MINING_EXTRACTIONS =
            stat("mining_extractions", StatFormatter.DEFAULT);
    /** 困难难度的有效撤离, 每人每天最多计 dailyHardExtractionCap 次 (与上一项分开计)。 */
    public static final RegistryObject<ResourceLocation> MINING_EXTRACTIONS_HARD =
            stat("mining_extractions_hard", StatFormatter.DEFAULT);
    /** 计数挖掘 (6.3)。 */
    public static final RegistryObject<ResourceLocation> MINING_BLOCKS_MINED =
            stat("mining_blocks_mined", StatFormatter.DEFAULT);
    /** 玩家自己挖到伪装矿石陷阱。 */
    public static final RegistryObject<ResourceLocation> MINING_TRAPS_SPRUNG =
            stat("mining_traps_sprung", StatFormatter.DEFAULT);
    /** 困难矿区的有效作业时长 (tick), 每次有效的困难撤离结算时累加; 统计界面按时间显示。 */
    public static final RegistryObject<ResourceLocation> MINING_HARD_ACTIVE_TICKS =
            stat("mining_hard_active_ticks", StatFormatter.TIME);
    /** 作为在线有效贡献者击倒精英怪 (挂机冻结的贡献者不加)。 */
    public static final RegistryObject<ResourceLocation> CHAMPION_KILLS = stat("champion_kills", StatFormatter.DEFAULT);
    /** 在矿区用枪击杀满足 6.4 过滤的怪物 (需 TaCZ)。 */
    public static final RegistryObject<ResourceLocation> GUN_KILLS = stat("gun_kills", StatFormatter.DEFAULT);
    /** 同上且爆头 (需 TaCZ)。 */
    public static final RegistryObject<ResourceLocation> GUN_HEADSHOT_KILLS =
            stat("gun_headshot_kills", StatFormatter.DEFAULT);
    /** grantDaily 的 faucet 入账提交后, 加上衰减后的实际入账额 (所有 faucet 键; 市场收入等 grant 不算)。 */
    public static final RegistryObject<ResourceLocation> CREDITS_EARNED =
            stat("credits_earned", StatFormatter.DEFAULT);
    /** 领取一份任务奖励, 每人每天 (UTC) 最多计 dailyQuestCap 份。 */
    public static final RegistryObject<ResourceLocation> QUESTS_COMPLETED =
            stat("quests_completed", StatFormatter.DEFAULT);
    /** 领完当天全部每日任务, 每个日常周期戳最多计 1 次。 */
    public static final RegistryObject<ResourceLocation> QUEST_DAILY_CLEARS =
            stat("quest_daily_clears", StatFormatter.DEFAULT);

    private AchievementStats() {
    }

    private static RegistryObject<ResourceLocation> stat(String name, StatFormatter formatter) {
        RegistryObject<ResourceLocation> entry =
                CUSTOM_STATS.register(name, () -> new ResourceLocation(MiningConstants.MODID, name));
        FORMATTERS.put(entry, formatter);
        return entry;
    }

    /** mod 构造期挂上 DeferredRegister。 */
    public static void register(IEventBus modBus) {
        CUSTOM_STATS.register(modBus);
    }

    /** 以各自的格式器创建 Stat 对象 (FMLCommonSetup 的 enqueueWork 里调用, 见类注释)。 */
    public static void bindFormatters() {
        FORMATTERS.forEach((entry, formatter) -> Stats.CUSTOM.get(entry.get(), formatter));
    }

    /** 全部统计项, 按注册顺序。 */
    public static List<RegistryObject<ResourceLocation>> all() {
        return Collections.unmodifiableList(new ArrayList<>(FORMATTERS.keySet()));
    }

    /**
     * 给玩家的统计项加 amount (原版封顶在 int 上限), 随后重新核对他尚未完成的 stat_at_least 条件。
     * 统计项的所有递增都应经过这里, 否则阈值成就要等到下一次别的递增才会被发现达成。
     */
    public static void award(ServerPlayer player, RegistryObject<ResourceLocation> stat, int amount) {
        player.awardStat(stat.get(), amount);
        AchievementTriggers.STAT_AT_LEAST.trigger(player);
    }
}
