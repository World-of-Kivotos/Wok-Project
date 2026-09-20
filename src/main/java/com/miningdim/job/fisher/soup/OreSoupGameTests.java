package com.miningdim.job.fisher.soup;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.ChefEffectInstance;
import com.miningdim.job.chef.ChefEffectType;
import com.miningdim.job.chef.ChefQuality;
import com.miningdim.job.chef.ChefQualityNbt;
import com.miningdim.job.fisher.ore.OreFishType;
import com.miningdim.job.fisher.ore.OreFishingItems;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class OreSoupGameTests {
    private static final String STATE = "MiningOreFishSoup";
    private static final String EXPIRES_AT = "expiresAt";

    private OreSoupGameTests() {
    }

    @GameTest(template = "empty", batch = "ore_fish_soup")
    public static void finishUsingReturnsBowlAndAmplifiesTimer(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        OreFishSoupItem soup = (OreFishSoupItem) OreFishingItems.SOUPS.get(OreFishType.IRON).get();

        // 封顶之下: 增香 x2 把 300 秒拉到 600 秒。原先只测了恰好等于上限的一档, 期望值与上限同值,
        // 删掉 durationTicks 里的 Math.min 照样绿 —— 必须一档在封顶之下、一档超过封顶才拦得住。
        ItemStack amplified = new ItemStack(soup);
        ChefQualityNbt.stamp(amplified, ChefQuality.RADIANT,
                List.of(new ChefEffectInstance(ChefEffectType.AMPLIFY, 200)));
        long beforeAmplified = player.serverLevel().getGameTime();
        ItemStack result = soup.finishUsingItem(amplified, player.level(), player);
        helper.assertTrue(result.is(Items.BOWL) && OreSoupEffects.activeType(player) == OreFishType.IRON,
                "真实食用必须返还碗并写入铁羹状态");
        helper.assertTrue(player.getPersistentData().getCompound(STATE).getLong(EXPIRES_AT) - beforeAmplified
                        == 12_000L,
                "增香 x2 必须把 300 秒羹时长拉长到 600 秒");

        // 封顶之上: 增香 x9 算出来是 2700 秒, 必须被削到 1500 秒。
        ItemStack overCap = new ItemStack(soup);
        ChefQualityNbt.stamp(overCap, ChefQuality.RADIANT,
                List.of(new ChefEffectInstance(ChefEffectType.AMPLIFY, 900)));
        long beforeOverCap = player.serverLevel().getGameTime();
        soup.finishUsingItem(overCap, player.level(), player);
        helper.assertTrue(player.getPersistentData().getCompound(STATE).getLong(EXPIRES_AT) - beforeOverCap
                        == 30_000L,
                "增香 x9 算出的 2700 秒必须被封顶到 1500 秒");
        helper.succeed();
    }

    /**
     * 铁羹的耐饥分支单独覆盖。暗金羹那条用例走的是同一个 {@code type == IRON || type == DARK_GOLD} 判断,
     * 只删掉 IRON 那一半时暗金用例照样绿, 铁羹的唯一效果就没人守。
     */
    @GameTest(template = "empty", batch = "ore_fish_soup")
    public static void ironSoupReducesExhaustionButNotMiningSpeed(GameTestHelper helper) {
        ServerPlayer player = miningPlayer(helper);
        ServerPlayer control = miningPlayer(helper);
        OreSoupEffects.applyConsumedSoup(player, OreFishType.IRON, new ItemStack(Items.BOWL));
        for (ServerPlayer each : List.of(player, control)) {
            each.getFoodData().setFoodLevel(20);
            each.getFoodData().setSaturation(0.0F);
            each.getFoodData().setExhaustion(0.0F);
        }
        player.causeFoodExhaustion(4.0F);
        control.causeFoodExhaustion(4.0F);
        helper.assertTrue(Math.abs(player.getFoodData().getExhaustionLevel() - 3.0F) < 0.001F
                        && Math.abs(control.getFoodData().getExhaustionLevel() - 4.0F) < 0.001F,
                "铁羹在矿洞内必须通过真实 causeFoodExhaustion 缩减 25% 疲劳, 实得 "
                        + player.getFoodData().getExhaustionLevel());
        helper.assertTrue(OreSoupEffects.miningSpeedBonusPercent(player) == 0,
                "铁羹只管耐饥, 不得附带挖速加成");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "ore_fish_soup")
    public static void spoiledSoupCannotReplaceActiveSoup(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        OreSoupEffects.applyConsumedSoup(player, OreFishType.GOLD, new ItemStack(Items.BOWL));
        OreFishSoupItem soup = (OreFishSoupItem) OreFishingItems.SOUPS.get(OreFishType.DIAMOND).get();
        ItemStack spoiled = new ItemStack(soup);
        ChefQualityNbt.stamp(spoiled, ChefQuality.LOW,
                List.of(new ChefEffectInstance(ChefEffectType.SPOILED, 0)));
        soup.finishUsingItem(spoiled, player.level(), player);
        helper.assertTrue(OreSoupEffects.activeType(player) == OreFishType.GOLD,
                "厨师失败品不得覆盖已经生效的金羹");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "ore_fish_soup")
    public static void replacementPersistsOutsideAndActivatesInMining(GameTestHelper helper) {
        ServerPlayer overworldPlayer = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        OreSoupEffects.applyConsumedSoup(overworldPlayer, OreFishType.IRON, new ItemStack(Items.BOWL));
        OreSoupEffects.applyConsumedSoup(overworldPlayer, OreFishType.DIAMOND, new ItemStack(Items.BOWL));
        helper.assertTrue(OreSoupEffects.activeType(overworldPlayer) == OreFishType.DIAMOND
                        && OreSoupEffects.miningSpeedBonusPercent(overworldPlayer) == 0,
                "矿洞外换羹必须保留倒计时但没有挖速收益");
        ServerPlayer miningPlayer = miningPlayer(helper);
        miningPlayer.getPersistentData().put(STATE, overworldPlayer.getPersistentData().getCompound(STATE).copy());
        helper.assertTrue(OreSoupEffects.activeType(miningPlayer) == OreFishType.DIAMOND
                        && OreSoupEffects.miningSpeedBonusPercent(miningPlayer) == 15,
                "带着未到期钻石羹进入矿洞后必须立即获得 15% 挖速");
        OreSoupEffects.onPlayerTick(miningPlayer);
        helper.assertTrue(((OreSoupPlayerStateAccess) miningPlayer).miningdim$getOreSoupState() == OreFishType.DIAMOND.ordinal() + 1,
                "进入矿洞后必须同步钻羹状态供客户端预测挖速");
        OreSoupEffects.applyConsumedSoup(miningPlayer, OreFishType.GOLD, new ItemStack(Items.BOWL));
        helper.assertTrue(miningPlayer.hasEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION),
                "金羹在矿洞内应立即授予夜视");
        OreSoupEffects.applyConsumedSoup(miningPlayer, OreFishType.IRON, new ItemStack(Items.BOWL));
        helper.assertTrue(!miningPlayer.hasEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION),
                "换成铁羹必须清除上一碗金羹自有的夜视");
        miningPlayer.getPersistentData().getCompound(STATE).putLong(EXPIRES_AT, miningPlayer.serverLevel().getGameTime());
        OreSoupEffects.onPlayerTick(miningPlayer);
        helper.assertTrue(OreSoupEffects.activeType(miningPlayer) == null
                        && ((OreSoupPlayerStateAccess) miningPlayer).miningdim$getOreSoupState() == 0,
                "到期必须同时清除服务端和客户端的汤状态");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "ore_fish_soup")
    public static void darkGoldChangesMiningSpeedAndExhaustion(GameTestHelper helper) {
        ServerPlayer player = miningPlayer(helper);
        ServerPlayer control = miningPlayer(helper);
        OreSoupEffects.applyConsumedSoup(player, OreFishType.DARK_GOLD, new ItemStack(Items.BOWL));
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(0.0F);
        control.getFoodData().setFoodLevel(20);
        control.getFoodData().setSaturation(0.0F);
        player.getFoodData().setExhaustion(0.0F);
        control.getFoodData().setExhaustion(0.0F);
        player.causeFoodExhaustion(4.0F);
        control.causeFoodExhaustion(4.0F);
        var speed = new net.minecraftforge.event.entity.player.PlayerEvent.BreakSpeed(player,
                Blocks.STONE.defaultBlockState(), 10.0F, BlockPos.ZERO);
        var controlSpeed = new net.minecraftforge.event.entity.player.PlayerEvent.BreakSpeed(control,
                Blocks.STONE.defaultBlockState(), 10.0F, BlockPos.ZERO);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(speed);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(controlSpeed);
        helper.assertTrue(Math.abs(speed.getNewSpeed() - controlSpeed.getNewSpeed() - 2.0F) < 0.001F
                        && Math.abs(player.getFoodData().getExhaustionLevel() - 3.0F) < 0.001F
                        && Math.abs(control.getFoodData().getExhaustionLevel() - 4.0F) < 0.001F,
                "暗金羹在矿洞内必须加 20% 挖速并通过真实 causeFoodExhaustion 缩减 25% 疲劳");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "ore_fish_soup")
    public static void emeraldMineBlockReducesPostUnbreakingDamage(GameTestHelper helper) {
        ServerPlayer player = miningPlayer(helper);
        ServerPlayer control = miningPlayer(helper);
        OreSoupEffects.applyConsumedSoup(player, OreFishType.EMERALD, new ItemStack(Items.BOWL));
        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        ItemStack controlPickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        EnchantmentHelper.setEnchantments(Map.of(Enchantments.UNBREAKING, 3), pickaxe);
        EnchantmentHelper.setEnchantments(Map.of(Enchantments.UNBREAKING, 3), controlPickaxe);
        player.getRandom().setSeed(0x0E5EEDL);
        control.getRandom().setSeed(0x0E5EEDL);
        for (int i = 0; i < 100; i++) {
            pickaxe.mineBlock(player.serverLevel(), Blocks.STONE.defaultBlockState(), BlockPos.ZERO, player);
            controlPickaxe.mineBlock(control.serverLevel(), Blocks.STONE.defaultBlockState(), BlockPos.ZERO, control);
        }
        helper.assertTrue(pickaxe.getDamageValue() > 0 && pickaxe.getDamageValue() < controlPickaxe.getDamageValue(),
                "同 seed 的 Unbreaking III 工具经真实 ItemStack.mineBlock 后，翠羹必须少于无羹对照的磨损");
        helper.succeed();
    }

    /**
     * 死亡与"末地回主世界"都会重建 ServerPlayer 并走 PlayerEvent.Clone, 但只有前者该清汤。
     * Forge 的 restoreFrom 只搬 PlayerPersisted 子标签, 汤状态挂在根节点上, 不自己接 Clone 就会两种情况一起丢。
     */
    @GameTest(template = "empty", batch = "ore_fish_soup")
    public static void nonDeathCloneKeepsSoupWhileDeathClearsIt(GameTestHelper helper) {
        ServerPlayer original = miningPlayer(helper);
        OreSoupEffects.applyConsumedSoup(original, OreFishType.DIAMOND, new ItemStack(Items.BOWL));
        long expiresAt = original.getPersistentData().getCompound(STATE).getLong(EXPIRES_AT);

        // 直接调 PlayerEvent.Clone 的处理方法, 不往全局总线发事件: 职业框架等子系统也监听 Clone,
        // 拿 mock 玩家去走它们的 capability 读写会直接崩掉服务端 tick 循环。
        ServerPlayer returned = miningPlayer(helper);
        OreSoupEffects.carryAcrossRespawn(original, returned, false);
        helper.assertTrue(OreSoupEffects.activeType(returned) == OreFishType.DIAMOND
                        && returned.getPersistentData().getCompound(STATE).getLong(EXPIRES_AT) == expiresAt,
                "非死亡重建(末地出口回主世界)必须原样带走汤状态与到期时刻, 倒计时不得重置");

        ServerPlayer respawned = miningPlayer(helper);
        OreSoupEffects.carryAcrossRespawn(original, respawned, true);
        helper.assertTrue(OreSoupEffects.activeType(respawned) == null,
                "死亡重生必须清除汤状态");
        helper.succeed();
    }

    private static ServerPlayer miningPlayer(GameTestHelper helper) {
        ServerLevel mining = helper.getLevel().getServer().getLevel(MiningConstants.MINING_LEVEL);
        if (mining == null) {
            throw new IllegalStateException("Mining dimension is unavailable to ore soup GameTest");
        }
        ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        player.teleportTo(mining, 0.5D, 0.0D, 0.5D, 0.0F, 0.0F);
        return player;
    }
}
