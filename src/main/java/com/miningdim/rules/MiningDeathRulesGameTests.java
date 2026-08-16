package com.miningdim.rules;

import com.miningdim.core.Difficulty;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;

/** HARD 强制掉落、低难度保留、维度门与消失诅咒语义的事件总线集成测试。 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MiningDeathRulesGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "rules_death";
    private static final int INVENTORY_SLOT = 9;

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hardDeathAddsInventoryItemToDrops(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        moveToInstance(helper, player, Difficulty.HARD);
        player.getInventory().setItem(INVENTORY_SLOT, new ItemStack(Items.DIAMOND, 3));
        LivingDropsEvent event = postDropsEvent(player);

        helper.assertTrue(droppedCount(event, Items.DIAMOND) == 3,
                "HARD 死亡必须把背包中的 3 颗钻石原量加入掉落, 实得 " + droppedCount(event, Items.DIAMOND));
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hardDeathClearsTheDroppedInventorySlot(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        moveToInstance(helper, player, Difficulty.HARD);
        player.getInventory().setItem(INVENTORY_SLOT, new ItemStack(Items.EMERALD, 2));
        LivingDropsEvent event = postDropsEvent(player);

        helper.assertTrue(droppedCount(event, Items.EMERALD) == 2,
                "测试前提: HARD 路径必须实际生成 2 颗绿宝石掉落");
        helper.assertTrue(player.getInventory().getItem(INVENTORY_SLOT).isEmpty(),
                "HARD 掉落后原背包槽必须清空, 否则 keepInventory 会复制整组物品");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void easyAndMediumDeathsLeaveInventoryUntouched(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        moveToInstance(helper, player, Difficulty.EASY);
        player.getInventory().setItem(INVENTORY_SLOT, new ItemStack(Items.IRON_INGOT, 4));
        LivingDropsEvent easyEvent = postDropsEvent(player);
        helper.assertTrue(droppedCount(easyEvent, Items.IRON_INGOT) == 0,
                "EASY 死亡不得把背包铁锭加入强制掉落");
        helper.assertTrue(player.getInventory().getItem(INVENTORY_SLOT).is(Items.IRON_INGOT)
                        && player.getInventory().getItem(INVENTORY_SLOT).getCount() == 4,
                "EASY 死亡必须原样保留槽位 9 的 4 块铁锭");

        moveToInstance(helper, player, Difficulty.MEDIUM);
        player.getInventory().setItem(INVENTORY_SLOT, new ItemStack(Items.GOLD_INGOT, 5));
        LivingDropsEvent mediumEvent = postDropsEvent(player);
        helper.assertTrue(droppedCount(mediumEvent, Items.GOLD_INGOT) == 0,
                "MEDIUM 死亡不得把背包金锭加入强制掉落");
        helper.assertTrue(player.getInventory().getItem(INVENTORY_SLOT).is(Items.GOLD_INGOT)
                        && player.getInventory().getItem(INVENTORY_SLOT).getCount() == 5,
                "MEDIUM 死亡必须原样保留槽位 9 的 5 块金锭");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void overworldCoordinatesInsideHardRegionDoNotTriggerDrops(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        InstanceState hard = requireInstance(helper, Difficulty.HARD);
        RegionBox box = hard.regionBox();
        int x = box.originX() + 1;
        int z = box.originZ() + 1;
        player.setNoGravity(true);
        player.teleportTo(x + 0.5D, player.getY(), z + 0.5D);

        helper.assertTrue(!player.level().dimension().equals(MiningConstants.MINING_LEVEL),
                "前提校验: GameTest 玩家必须仍在主世界");
        InstanceState geometricHit = MiningServices.instanceManager().regionAt(x, z);
        helper.assertTrue(geometricHit == hard && geometricHit.difficulty() == Difficulty.HARD,
                "前提校验: 主世界使用的 X/Z 必须命中运行期 HARD RegionBox, 否则删维度门后也不会触发掉落");

        player.getInventory().setItem(INVENTORY_SLOT, new ItemStack(Items.NETHERITE_INGOT, 2));
        LivingDropsEvent event = postDropsEvent(player);
        helper.assertTrue(droppedCount(event, Items.NETHERITE_INGOT) == 0,
                "主世界即使复用 HARD 区域的 X/Z 也不得触发强制掉落");
        helper.assertTrue(player.getInventory().getItem(INVENTORY_SLOT).is(Items.NETHERITE_INGOT)
                        && player.getInventory().getItem(INVENTORY_SLOT).getCount() == 2,
                "维度门必须让主世界背包保持原样");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hardDeathDestroysVanishingCursedItems(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        moveToInstance(helper, player, Difficulty.HARD);
        ItemStack cursed = new ItemStack(Items.DIAMOND_SWORD);
        cursed.enchant(Enchantments.VANISHING_CURSE, 1);
        helper.assertTrue(EnchantmentHelper.hasVanishingCurse(cursed),
                "测试前提: 钻石剑必须实际带有消失诅咒");
        player.getInventory().setItem(INVENTORY_SLOT, cursed);

        LivingDropsEvent event = postDropsEvent(player);

        helper.assertTrue(droppedCount(event, Items.DIAMOND_SWORD) == 0,
                "HARD 死亡必须销毁消失诅咒物品, 不得把钻石剑加入掉落");
        helper.assertTrue(player.getInventory().getItem(INVENTORY_SLOT).isEmpty(),
                "消失诅咒物品被销毁后也必须从原背包槽移除");
        helper.succeed();
    }

    private static LivingDropsEvent postDropsEvent(ServerPlayer player) {
        LivingDropsEvent event = new LivingDropsEvent(
                player, player.level().damageSources().generic(), new ArrayList<>(), 0, false);
        MinecraftForge.EVENT_BUS.post(event);
        return event;
    }

    private static InstanceState moveToInstance(
            GameTestHelper helper, ServerPlayer player, Difficulty difficulty) {
        InstanceState instance = requireInstance(helper, difficulty);
        ServerLevel miningLevel = requireMiningLevel(helper);
        RegionBox box = instance.regionBox();
        int x = box.originX() + 1;
        int z = box.originZ() + 1;
        player.setNoGravity(true);
        player.teleportTo(miningLevel, x + 0.5D, box.originY() + 2.0D, z + 0.5D, 0.0F, 0.0F);

        helper.assertTrue(player.level().dimension().equals(MiningConstants.MINING_LEVEL),
                "前提校验: 玩家必须已进入矿洞维度");
        helper.assertTrue(MiningServices.instanceManager().regionAt(x, z) == instance,
                "前提校验: 玩家坐标必须命中运行期 " + difficulty.configName() + " RegionBox");
        return instance;
    }

    private static InstanceState requireInstance(GameTestHelper helper, Difficulty difficulty) {
        InstanceState[] found = new InstanceState[1];
        MiningServices.instanceManager().forEach(instance -> {
            if (found[0] == null && instance.difficulty() == difficulty) {
                found[0] = instance;
            }
        });
        if (found[0] == null) {
            helper.fail("前提校验: 未找到运行期 " + difficulty.configName() + " 常驻区域");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return found[0];
    }

    private static ServerLevel requireMiningLevel(GameTestHelper helper) {
        ServerLevel miningLevel = helper.getLevel().getServer().getLevel(MiningConstants.MINING_LEVEL);
        if (miningLevel == null) {
            helper.fail("前提校验: 矿洞维度 " + MiningConstants.MINING_LEVEL.location() + " 必须已加载");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return miningLevel;
    }

    private static int droppedCount(LivingDropsEvent event, Item item) {
        int count = 0;
        for (ItemEntity drop : event.getDrops()) {
            if (drop.getItem().is(item)) {
                count += drop.getItem().getCount();
            }
        }
        return count;
    }
}
