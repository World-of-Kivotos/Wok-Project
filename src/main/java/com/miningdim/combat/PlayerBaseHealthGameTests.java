package com.miningdim.combat;

import com.miningdim.config.MiningServerConfig;
import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 玩家基础最大生命 80 落地 ({@link PlayerBaseHealthHandler}) 的 GameTest。
 *
 * mock 玩家经 {@link MockGameTestPlayers} 走真实 PlayerList.placeNewPlayer, 会在全局总线上触发一次登录事件, 因此每个
 * mock 玩家一出生就是登录迁移后的 80/80。Clone / 重生只直接调 handler 方法, 不往全局总线发事件 (职业框架等子系统
 * 也监听 Clone, 拿 mock 玩家走它们的 capability 读写会崩 tick 循环, 见 OreSoupGameTests 的同一说明); 换维走真实
 * teleportTo (与 OreFishGameTests 同法)。"刚被 restoreFrom 新建出来的玩家" 用 {@link #resetToVanillaEntity} 模拟:
 * base 回原版 20、血量 20 —— 1.20.1 的 restoreFrom 不搬属性, 新实体就是这个状态。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PlayerBaseHealthGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "player_base_health";
    private static final double EPS = 1e-6D;
    private static final double VANILLA_BASE = 20.0D;
    private static final double SERVER_BASE = 80.0D;

    /**
     * 金酒每层最大生命比例, 取 miningdim-brewer.toml ginMaxHealthPctPerLayer 的默认值 0.10 (BrewEffectGameTests 已锁)。
     * 战斗框架不能反向引用酿酒师模块 (wok-job-brewer 依赖 wok-combat-core), 所以这里按 GinMaxHealthManager 的锚定口径
     * (getBaseValue × 每层% × 层数, transient ADDITION) 复算; 真实 GinMaxHealthManager 的
     * BrewBuffStoreGameTests#ginFullLayersRaisesMaxHealthByFiftyPercentThenDeathClears 用的 mock 玩家同样经登录迁移到 80。
     */
    private static final double GIN_PCT_PER_LAYER = 0.10D;
    private static final UUID GIN_PROBE_UUID = UUID.fromString("5e0b2c7a-8d41-4f7e-9a36-1c2d3e4f5a6b");
    private static final UUID FOREIGN_BONUS_UUID = UUID.fromString("6f1c3d8b-9e52-4a8f-8b47-2d3e4f5a6b7c");

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void configDefaultsToEighty(GameTestHelper helper) {
        helper.assertTrue(Math.abs(MiningServerConfig.PLAYER_BASE_MAX_HEALTH.getDefault() - SERVER_BASE) < EPS,
                "[player] baseMaxHealth 默认值必须是 80, 实得 " + MiningServerConfig.PLAYER_BASE_MAX_HEALTH.getDefault());
        helper.assertTrue(Math.abs(PlayerBaseHealthHandler.configuredBaseMaxHealth() - SERVER_BASE) < EPS,
                "GameTest 服读到的 baseMaxHealth 必须是默认 80, 实得 " + PlayerBaseHealthHandler.configuredBaseMaxHealth());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void loginMigratesVanillaPlayerAndTopsUpOnlyOnce(GameTestHelper helper) {
        PlayerBaseHealthHandler handler = new PlayerBaseHealthHandler();
        // 真实登录 (placeNewPlayer 在全局总线上发 PlayerLoggedInEvent): 原版 20/20 -> 80/80, 且写的是基础值不是修饰。
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        assertBaseAndHealth(helper, player, SERVER_BASE, 80.0F, "真实登录迁移");
        helper.assertTrue(Math.abs(maxHealth(player).getValue() - maxHealth(player).getBaseValue()) < EPS,
                "登录迁移只改基础值, 不得挂任何修饰, value 应等于 base, 实得 " + maxHealth(player).getValue());

        // 非满血迁移: 15/20 -> 75/80 (差值 60 加到当前血量, 不是直接回满)。
        resetToVanillaEntity(player);
        player.setHealth(15.0F);
        handler.onLoggedIn(new PlayerEvent.PlayerLoggedInEvent(player));
        assertBaseAndHealth(helper, player, SERVER_BASE, 75.0F, "15/20 首次迁移");

        // base 被外部改回 20 后再登录: base 抬回 80, 但同一水位不再补血, 否则"改低-抬回"能刷出回血。
        maxHealth(player).setBaseValue(VANILLA_BASE);
        player.setHealth(10.0F);
        handler.onLoggedIn(new PlayerEvent.PlayerLoggedInEvent(player));
        assertBaseAndHealth(helper, player, SERVER_BASE, 10.0F, "同水位二次抬回");

        // 配置调高到 100 的情形: 只补超出已补偿水位 80 的那 20。
        PlayerBaseHealthHandler.raiseAndTopUp(player, 100.0D);
        assertBaseAndHealth(helper, player, 100.0D, 30.0F, "水位 80 -> 100");

        // 停在死亡界面下线 (血量 0) 的玩家登录: 只抬 base 不补血, 不得原地复活。
        resetToVanillaEntity(player);
        player.setHealth(0.0F);
        handler.onLoggedIn(new PlayerEvent.PlayerLoggedInEvent(player));
        assertBaseAndHealth(helper, player, SERVER_BASE, 0.0F, "死亡界面登录");
        player.setHealth(player.getMaxHealth());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void deathRespawnRebuildsAtEightyFull(GameTestHelper helper) {
        PlayerBaseHealthHandler handler = new PlayerBaseHealthHandler();
        ServerPlayer original = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        original.setHealth(30.0F);

        // 死亡重生: restoreFrom 新建的实体是 base 20、血量 20; Clone(wasDeath) 抬回 80 并回满。
        ServerPlayer respawned = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        resetToVanillaEntity(respawned);
        handler.onClone(new PlayerEvent.Clone(respawned, original, true));
        assertBaseAndHealth(helper, respawned, SERVER_BASE, 80.0F, "死亡 Clone");

        // 随后的 PlayerRespawnEvent (非末地出口) 同样回满, base 不变。
        respawned.setHealth(50.0F);
        handler.onRespawn(new PlayerEvent.PlayerRespawnEvent(respawned, false));
        assertBaseAndHealth(helper, respawned, SERVER_BASE, 80.0F, "死亡重生事件");

        // 只收到重生事件 (Clone 被别的监听吞掉的兜底): 单靠重生事件也得到 80/80。
        ServerPlayer respawnOnly = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        resetToVanillaEntity(respawnOnly);
        handler.onRespawn(new PlayerEvent.PlayerRespawnEvent(respawnOnly, false));
        assertBaseAndHealth(helper, respawnOnly, SERVER_BASE, 80.0F, "仅重生事件");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void endExitCloneKeepsOriginalHealth(GameTestHelper helper) {
        PlayerBaseHealthHandler handler = new PlayerBaseHealthHandler();
        ServerPlayer original = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        original.setHealth(50.0F);

        // 末地出口: restoreFrom(keepEverything) 对新实体 setHealth(旧血量), 被 base 20 的上限钳成 20。
        ServerPlayer returned = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        resetToVanillaEntity(returned);
        returned.setHealth(original.getHealth());
        helper.assertTrue(Math.abs(returned.getHealth() - 20.0F) < EPS,
                "前提: 原版 restoreFrom 在 base 20 的新实体上把 50 血钳成 20, 实得 " + returned.getHealth());

        handler.onClone(new PlayerEvent.Clone(returned, original, false));
        assertBaseAndHealth(helper, returned, SERVER_BASE, 50.0F, "末地出口 Clone");
        handler.onRespawn(new PlayerEvent.PlayerRespawnEvent(returned, true));
        assertBaseAndHealth(helper, returned, SERVER_BASE, 50.0F, "末地出口重生事件");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dimensionChangeKeepsBaseAndHealth(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel();
        ServerLevel mining = overworld.getServer().getLevel(MiningConstants.MINING_LEVEL);
        if (mining == null) {
            throw new IllegalStateException("Mining dimension is unavailable to player base health GameTest");
        }
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        player.setHealth(64.0F);

        // 真实换维 (全局总线上发 PlayerChangedDimensionEvent): 同一实体, base 与血量都不变。
        player.teleportTo(mining, 0.5D, 80.0D, 0.5D, 0.0F, 0.0F);
        helper.assertTrue(player.level() == mining, "前提: 玩家已换到矿山维度");
        assertBaseAndHealth(helper, player, SERVER_BASE, 64.0F, "进矿山维度");
        player.teleportTo(overworld, x, y, z, 0.0F, 0.0F);
        assertBaseAndHealth(helper, player, SERVER_BASE, 64.0F, "回主世界");

        // 换维兜底: base 被外部改回 20 时抬回 80; 同水位已补偿过, 血量不动。
        maxHealth(player).setBaseValue(VANILLA_BASE);
        player.setHealth(20.0F);
        new PlayerBaseHealthHandler().onChangedDimension(
                new PlayerEvent.PlayerChangedDimensionEvent(player, Level.OVERWORLD, MiningConstants.MINING_LEVEL));
        assertBaseAndHealth(helper, player, SERVER_BASE, 20.0F, "换维兜底");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void higherBaseIsNeverLowered(GameTestHelper helper) {
        PlayerBaseHealthHandler handler = new PlayerBaseHealthHandler();
        // 生产服 KubeJS test_hp.js 给测试员设 500: 各路径都只升不降, 两边不会每 5 秒互相覆盖。
        ServerPlayer tester = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        maxHealth(tester).setBaseValue(500.0D);
        tester.setHealth(500.0F);
        handler.onLoggedIn(new PlayerEvent.PlayerLoggedInEvent(tester));
        handler.onChangedDimension(
                new PlayerEvent.PlayerChangedDimensionEvent(tester, Level.OVERWORLD, MiningConstants.MINING_LEVEL));
        handler.onRespawn(new PlayerEvent.PlayerRespawnEvent(tester, true));
        tester.tickCount = PlayerBaseHealthHandler.CORRECTION_INTERVAL_TICKS * 3;
        handler.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, tester));
        assertBaseAndHealth(helper, tester, 500.0D, 500.0F, "base 500 的测试员");
        helper.assertTrue(!PlayerBaseHealthHandler.raiseBase(tester, SERVER_BASE),
                "base 已高于目标时 raiseBase 必须报告未改动");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ginFiveLayersOnEightyBaseReachesOneTwenty(GameTestHelper helper) {
        PlayerBaseHealthHandler handler = new PlayerBaseHealthHandler();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        AttributeInstance inst = maxHealth(player);

        // 金酒以 getBaseValue 为锚: 80 × 10% × 5 = 40 -> 最大生命 120 (若 80 是用修饰补的, 锚是 20, 满层只到 90)。
        double ginDelta = inst.getBaseValue() * GIN_PCT_PER_LAYER * 5;
        inst.addTransientModifier(new AttributeModifier(GIN_PROBE_UUID, "test.gin_probe", ginDelta,
                AttributeModifier.Operation.ADDITION));
        helper.assertTrue(Math.abs(player.getMaxHealth() - 120.0F) < EPS,
                "金酒满 5 层后最大生命必须是 120, 实得 " + player.getMaxHealth());

        // 周期校正与重登不碰已达标的 base, 金酒修饰原样保留。
        player.tickCount = PlayerBaseHealthHandler.CORRECTION_INTERVAL_TICKS;
        handler.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
        handler.onLoggedIn(new PlayerEvent.PlayerLoggedInEvent(player));
        helper.assertTrue(Math.abs(inst.getBaseValue() - SERVER_BASE) < EPS && Math.abs(player.getMaxHealth() - 120.0F) < EPS,
                "校正后 base 仍 80、最大生命仍 120, 实得 base=" + inst.getBaseValue() + " max=" + player.getMaxHealth());
        inst.removeModifier(GIN_PROBE_UUID);

        // 判定只看 base 不看总值: base 20 + 外来 +100 修饰 (总值 120 > 80) 仍要把 base 抬到 80, 否则金酒锚在 20。
        inst.setBaseValue(VANILLA_BASE);
        inst.addTransientModifier(new AttributeModifier(FOREIGN_BONUS_UUID, "test.foreign_bonus", 100.0D,
                AttributeModifier.Operation.ADDITION));
        handler.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
        helper.assertTrue(Math.abs(inst.getBaseValue() - SERVER_BASE) < EPS && Math.abs(inst.getValue() - 180.0D) < EPS,
                "总值已超 80 但 base 为 20 时仍须抬 base, 实得 base=" + inst.getBaseValue() + " value=" + inst.getValue());
        inst.removeModifier(FOREIGN_BONUS_UUID);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void periodicCorrectionRunsEveryHundredTicks(GameTestHelper helper) {
        PlayerBaseHealthHandler handler = new PlayerBaseHealthHandler();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        resetToVanillaEntity(player);

        // 非整百 tick 与 START 相位都不校正。
        player.tickCount = PlayerBaseHealthHandler.CORRECTION_INTERVAL_TICKS * 2 + 1;
        handler.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
        player.tickCount = PlayerBaseHealthHandler.CORRECTION_INTERVAL_TICKS * 2;
        handler.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.START, player));
        assertBaseAndHealth(helper, player, VANILLA_BASE, 20.0F, "非校正 tick");

        // 整百 tick 的 END 相位校正: 首次抬到 80 这个水位, 补差值到 80/80。
        handler.onPlayerTick(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
        assertBaseAndHealth(helper, player, SERVER_BASE, 80.0F, "第 200 tick 校正");
        helper.succeed();
    }

    private static AttributeInstance maxHealth(Player player) {
        AttributeInstance inst = player.getAttribute(Attributes.MAX_HEALTH);
        if (inst == null) {
            throw new IllegalStateException("mock player is missing its MAX_HEALTH attribute");
        }
        return inst;
    }

    /** 模拟 restoreFrom 刚新建、从未被本 handler 补偿过的玩家实体: base 20、血量 20、无补偿水位。 */
    private static void resetToVanillaEntity(ServerPlayer player) {
        maxHealth(player).setBaseValue(VANILLA_BASE);
        player.setHealth((float) VANILLA_BASE);
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        persisted.remove(PlayerBaseHealthHandler.COMPENSATED_BASE_KEY);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
    }

    private static void assertBaseAndHealth(GameTestHelper helper, Player player, double base, float health, String stage) {
        double actualBase = maxHealth(player).getBaseValue();
        helper.assertTrue(Math.abs(actualBase - base) < EPS,
                stage + ": base 应为 " + base + ", 实得 " + actualBase);
        helper.assertTrue(Math.abs(player.getHealth() - health) < 1e-4F,
                stage + ": 血量应为 " + health + ", 实得 " + player.getHealth());
    }
}
