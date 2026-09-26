package com.miningdim.achievement.trigger;

import com.miningdim.achievement.AchievementConfig;
import com.miningdim.achievement.AchievementServices;
import com.miningdim.achievement.AchievementStoreException;
import com.miningdim.core.Difficulty;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.MobInstanceTag;
import com.miningdim.ore.OreType;
import com.miningdim.trap.TrapDisguise;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 矿区的事件钩子 (Achievement_System_DesignSpec 6.1、6.3): 行程与有效撤离、计数挖掘、踩中伪装矿石陷阱。原版与 Forge 事件在这里
 * 翻译成统计项与触发器, 行程状态本身在 {@link MiningTrips}。
 *
 * <ul>
 *   <li>行程: 从其他维度进入矿区开一段 (记下所在区域的难度, 加 mining_entries、触发 enter_mining); 以任何方式离开矿区都结算
 *       (/mining leave、面板、逃生技能、自动重置撤离都是一次维度切换); 下线作废; 在矿区里上线从头计时 (不补 mining_entries,
 *       那只算从其他维度进入; 但补触发 enter_mining, 见 {@link #onLoggedIn}); 死后在矿区外重生视为这一趟结束。</li>
 *   <li>死亡: {@link LivingDeathEvent} 以 LOWEST、不收已取消的事件标记, 被职业技能、纳米反应堆这类在更高优先级取消掉的
 *       "死亡"不作废行程。</li>
 *   <li>命中: {@link LivingHurtEvent} 以 HIGHEST、收已取消的事件记录"被怪物或陷阱打过" —— 被别的系统 (免疫、减伤) 取消掉的
 *       一下也是真的挨了打。怪物指带实例标记的怪; 陷阱指非玩家造成的爆炸、岩浆与落石, 与陷阱系统使用的伤害类型一致。</li>
 *   <li>计数挖掘: {@link BlockEvent.BreakEvent} 以 LOWEST、不收已取消的事件判定 (6.3 的全部条件)。连锁挖掘、隧道技能走
 *       destroyBlock, 不发 BreakEvent, 天然不计。</li>
 *   <li>陷阱: 同一事件另挂一个 LOWEST、收已取消事件的监听。陷阱系统在 HIGHEST 取消玩家的破坏并自行清掉方块, 所以
 *       "已取消 + 伪装矿石 + 该位置已成空气"就是玩家自己挖到了陷阱。</li>
 * </ul>
 *
 * <p>性能 (第十章): 方块破坏、受伤、死亡都先判维度与实体类型再做别的; 不做 tick 轮询; 数据库只在有效撤离结算时写一次
 * 每日计数。
 */
public final class MiningTripHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/achievement/hooks");

    /** 流体生成位置表的上限。超出后丢掉最早记下的位置: 旧位置多半早被别的方式清掉了 (爆炸、destroyBlock 都不发 BreakEvent)。 */
    private static final int FLUID_GENERATED_CAPACITY = 4096;

    /**
     * 矿区维度里由流体生成的方块位置 (BlockPos.asLong)。{@link BlockEvent.FluidPlaceBlockEvent} 记入, 被挖掉时消费。
     * 只登记矿区这一个维度, 所以不按维度分表; 有上限的插入序集合, 只在主线程读写。
     */
    private static final Set<Long> FLUID_GENERATED = Collections.newSetFromMap(new LinkedHashMap<Long, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > FLUID_GENERATED_CAPACITY;
        }
    });

    // ---- 行程的开与关 ----

    @SubscribeEvent
    public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return;
        }
        boolean toMining = event.getTo().equals(MiningConstants.MINING_LEVEL);
        boolean fromMining = event.getFrom().equals(MiningConstants.MINING_LEVEL);
        if (toMining && !fromMining) {
            enter(player);
        } else if (fromMining && !toMining) {
            leave(player);
        }
    }

    /**
     * 上线时人若在矿区里 (断线重连回到原实例), 重开一段行程, 从此刻计时。重连不走维度切换, 必须在这里补; 计时不接续掉线前的
     * 进度, 否则进矿区后下线、几小时后上线走出来就能秒过停留门槛。若入口子系统随后把人送回回退点, 那次传送会结算一段停留约为
     * 0 的行程, 自然不算撤离。
     *
     * <p>同时补触发一次 enter_mining (不加 mining_entries): 本模块上线前就留在矿区里的玩家从没走过维度切换, 不补的话
     * mining/first_entry 下面的子成就 (计数挖掘、撤离、陷阱、地牢宝箱) 会先于它到手。first_entry 是一次性条件,
     * 已获得时原版直接忽略这次触发。
     */
    @SubscribeEvent
    public void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)
                && inMining(player)) {
            InstanceState region = regionOf(player);
            if (region != null) {
                MiningTrips.open(player.getUUID(), region.difficulty(), overworldTick(player));
                AchievementTriggers.ENTER_MINING.trigger(player, region.difficulty());
            }
        }
    }

    /** 下线: 丢弃在途行程。下线不是撤离。 */
    @SubscribeEvent
    public void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        MiningTrips.discard(event.getEntity().getUUID());
    }

    /**
     * 死后重生到矿区以外: 这一趟以死亡告终, 直接丢弃。重生不发维度切换事件, 不在这里摘掉的话, 已判死的行程要等下次进入矿区才被覆盖。
     */
    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !inMining(player)) {
            MiningTrips.discard(player.getUUID());
        }
    }

    // ---- 行程中的死亡与命中 ----

    /** 玩家死亡作废行程。LOWEST 且不收已取消的事件: 被更高优先级救下来的"死亡"不算 (6.3 第 2 条)。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MiningTrips.markDied(player.getUUID());
        }
    }

    /** 被怪物或陷阱命中, 供"死里逃生"判断撤离前多久被打过。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onPlayerHurt(LivingHurtEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && inMining(player) && isThreat(event.getSource())) {
            MiningTrips.recordThreatHit(player.getUUID(), overworldTick(player));
        }
    }

    // ---- 方块 ----

    /** 计数挖掘 (6.3)。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.dimension().equals(MiningConstants.MINING_LEVEL)) {
            return;
        }
        BlockPos pos = event.getPos();
        // 流体生成的位置不论这次破坏算不算数都要消费掉: 事件没被取消, 方块这就没了。
        if (FLUID_GENERATED.remove(pos.asLong())) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return;
        }
        BlockState state = event.getState();
        if (state.getDestroySpeed(level, pos) <= 0.0F || isPlaceWhitelisted(state.getBlock())) {
            return;
        }
        InstanceState region = MiningServices.instanceManager().regionAt(pos.getX(), pos.getZ());
        if (region == null) {
            return;
        }
        AchievementStats.award(player, AchievementStats.MINING_BLOCKS_MINED, 1);
        MiningTrips.recordCountedBreak(player.getUUID(), overworldTick(player),
                AchievementConfig.HARD_ACTIVE_GAP_CAP_TICKS.get());
        OreType ore = OreType.fromBlock(state.getBlock());
        if (ore != null) {
            AchievementTriggers.MINE_ORE.trigger(player, ore, region.difficulty());
        }
    }

    /** 玩家自己挖到伪装矿石陷阱 (6.1 mining_traps_sprung)。 */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onTrapSprung(BlockEvent.BreakEvent event) {
        if (!event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)
                || !level.dimension().equals(MiningConstants.MINING_LEVEL)) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return;
        }
        if (TrapDisguise.isDisguiseOre(event.getState()) && level.getBlockState(event.getPos()).isAir()) {
            AchievementStats.award(player, AchievementStats.MINING_TRAPS_SPRUNG, 1);
        }
    }

    /** 记下矿区里由流体生成的方块位置 (刷石机的产物不算计数挖掘)。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onFluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension().equals(MiningConstants.MINING_LEVEL)) {
            FLUID_GENERATED.add(event.getPos().asLong());
        }
    }

    // ---- 结算 ----

    private static void enter(ServerPlayer player) {
        InstanceState region = regionOf(player);
        if (region == null) {
            return; // 落在缓冲带等区域以外 (管理员传送): 不算进入某个实例。
        }
        MiningTrips.open(player.getUUID(), region.difficulty(), overworldTick(player));
        AchievementStats.award(player, AchievementStats.MINING_ENTRIES, 1);
        AchievementTriggers.ENTER_MINING.trigger(player, region.difficulty());
    }

    private static void leave(ServerPlayer player) {
        MiningTrips.Extraction extraction = MiningTrips.finish(player.getUUID(), overworldTick(player),
                AchievementConfig.EXTRACTION_MIN_DWELL_TICKS.get(), AchievementConfig.MIN_TRIP_BLOCKS.get(),
                AchievementConfig.HARD_ACTIVE_PER_BLOCK_CAP_TICKS.get());
        if (extraction != null) {
            awardExtraction(player, extraction);
        }
    }

    /**
     * 一次有效撤离: 按每日上限加撤离次数 (全部撤离与困难撤离各计各的), 困难行程另加作业时长, 最后触发 mining_extraction。
     * 每日计数写库失败时只记错误日志、不加计数类统计 (宁可少发), 触发器照常触发 —— 维度切换在传送流程末尾, 异常不能冒出去
     * 打断传送。
     */
    private static void awardExtraction(ServerPlayer player, MiningTrips.Extraction extraction) {
        boolean hard = extraction.difficulty() == Difficulty.HARD;
        try {
            DailyCounterRepository counters = AchievementServices.dailyCounters();
            long today = DailyCounterRepository.today();
            if (counters.incrementIfBelow(player.getUUID(), DailyCounterRepository.EXTRACTION_KEY, today,
                    AchievementConfig.DAILY_EXTRACTION_CAP.get())) {
                AchievementStats.award(player, AchievementStats.MINING_EXTRACTIONS, 1);
            }
            if (hard && counters.incrementIfBelow(player.getUUID(), DailyCounterRepository.HARD_EXTRACTION_KEY, today,
                    AchievementConfig.DAILY_HARD_EXTRACTION_CAP.get())) {
                AchievementStats.award(player, AchievementStats.MINING_EXTRACTIONS_HARD, 1);
            }
        } catch (AchievementStoreException failure) {
            LOGGER.error("[miningdim] extraction of {} not counted: daily counter unavailable",
                    player.getGameProfile().getName(), failure);
        }
        if (hard && extraction.hardActiveTicks() > 0L) {
            AchievementStats.award(player, AchievementStats.MINING_HARD_ACTIVE_TICKS,
                    (int) Math.min(extraction.hardActiveTicks(), Integer.MAX_VALUE));
        }
        double healthRatio = player.getHealth() / player.getMaxHealth();
        AchievementTriggers.MINING_EXTRACTION.trigger(player, extraction.difficulty(), healthRatio,
                extraction.ticksSinceThreatHit());
    }

    // ---- 判据 ----

    /** 伤害来源是否"怪物或陷阱": 带实例标记的怪 (含其射出的弹射物), 或非玩家造成的爆炸、岩浆、落石。 */
    static boolean isThreat(DamageSource source) {
        Entity cause = source.getEntity();
        if (cause instanceof Mob mob) {
            return MobInstanceTag.isTagged(mob);
        }
        if (cause instanceof Player) {
            return false;
        }
        return source.is(DamageTypes.EXPLOSION) || source.is(DamageTypes.LAVA) || source.is(DamageTypes.FALLING_BLOCK);
    }

    /** 方块是否在矿区的放置白名单里 (放了再挖不算)。白名单实时读, 与规则模块一致, /reload 改了即时生效。 */
    private static boolean isPlaceWhitelisted(Block block) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
        for (String entry : MiningServices.config().placeWhitelist()) {
            if (id != null && id.equals(ResourceLocation.tryParse(entry))) {
                return true;
            }
        }
        return false;
    }

    private static boolean inMining(Entity entity) {
        return entity.level().dimension().equals(MiningConstants.MINING_LEVEL);
    }

    private static InstanceState regionOf(ServerPlayer player) {
        return MiningServices.instanceManager().regionAt(player.getBlockX(), player.getBlockZ());
    }

    private static long overworldTick(ServerPlayer player) {
        return player.server.overworld().getGameTime();
    }

    /** 停服时清空行程与流体生成位置 (进程内瞬时状态, 不跨存档)。 */
    public static void reset() {
        MiningTrips.reset();
        FLUID_GENERATED.clear();
    }
}
