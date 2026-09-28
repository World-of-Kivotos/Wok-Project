package com.miningdim.job.tarot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.tarot.craft.TarotCraftBlockEntity;
import com.miningdim.job.tarot.craft.TarotCraftMenu;
import com.miningdim.job.tarot.pack.TarotPackSavedData;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * feat/tarot-ui 配套 GameTest: 面板新增的只读字段与两个 action、卡牌说明缓存、闪耀朝向、合成台原因预判。
 * 断言都落在具体数值/状态上 (删对应实现必挂)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class TarotUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "tarot";

    // ============================================================
    // job.tarot.state: 剩余冷却 + 逐品质收集
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void stateReportsRemainingCooldownsAndCollectedByQuality(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        UUID self = player.getUUID();
        // 背包里放一张别人名下的 2 号 SR (判重不看绑定), 账本里记一张 5 号 R (放进箱子的牌)。
        player.getInventory().add(TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), 2, TarotQuality.SR, true,
                UUID.randomUUID()));
        TarotPackSavedData.get(helper.getLevel().getServer().overworld()).markCollected(self, 5, TarotQuality.R);
        // 占用一次冷却: 7 号牌普通级 400 tick, GCD 30 tick (只读查询必须如实反映, 且查询本身不再占用)。
        helper.assertTrue(TarotRuntime.cooldown().tryUse(player, 7, 400, 30, false), "测试前置: 首次占用必须成功");

        JsonObject state = handle(helper, "job.tarot.state", player, new JsonObject());

        int gcd = state.get("gcdRemainingTicks").getAsInt();
        helper.assertTrue(gcd > 0 && gcd <= 30, "GCD 剩余应在 (0,30], 实得 " + gcd);
        JsonArray deck = state.getAsJsonArray("deck");
        JsonObject seven = deck.get(7).getAsJsonObject();
        int remaining = seven.get("cooldownRemainingTicks").getAsInt();
        helper.assertTrue(remaining > 380 && remaining <= 400, "7 号牌剩余冷却应接近 400, 实得 " + remaining);
        helper.assertTrue(seven.get("shinyCooldownRemainingTicks").getAsInt() == 0, "7 号牌闪耀级未占用, 应为 0");
        helper.assertTrue(deck.get(8).getAsJsonObject().get("cooldownRemainingTicks").getAsInt() == 0,
                "未打过的牌剩余冷却为 0");

        JsonArray two = deck.get(2).getAsJsonObject().getAsJsonArray("collectedByQuality");
        JsonArray five = deck.get(5).getAsJsonObject().getAsJsonArray("collectedByQuality");
        helper.assertTrue(two.size() == 5 && five.size() == 5, "collectedByQuality 恒 5 项");
        helper.assertTrue(two.get(TarotQuality.SR.ordinal()).getAsBoolean()
                        && !two.get(TarotQuality.R.ordinal()).getAsBoolean(),
                "背包里的 2 号 SR (不论绑定) 只挡 SR 档, 实得 " + two);
        helper.assertTrue(five.get(TarotQuality.R.ordinal()).getAsBoolean()
                        && !five.get(TarotQuality.SSR.ordinal()).getAsBoolean(),
                "账本里的 5 号 R 只挡 R 档, 实得 " + five);

        // 只读查询不占用: 再查一次, GCD 不会被重置回满值之外的数, 7 号牌也不会变长。
        JsonObject again = handle(helper, "job.tarot.state", player, new JsonObject());
        helper.assertTrue(again.getAsJsonArray("deck").get(7).getAsJsonObject()
                        .get("cooldownRemainingTicks").getAsInt() <= remaining,
                "查询不能延长冷却");
        player.getInventory().clearContent();
        helper.succeed();
    }

    // ============================================================
    // job.tarot.cardEffects
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cardEffectsSendsParsableComponentLinesForEveryTier(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonObject effects = handle(helper, "job.tarot.cardEffects", player, cardIdPayload(TarotArcana.DEATH.cardId()));
        helper.assertTrue(effects.get("loaded").getAsBoolean(), "GameTest 环境牌效表已加载");
        JsonArray upright = effects.getAsJsonArray("upright");
        JsonArray reversed = effects.getAsJsonArray("reversed");
        helper.assertTrue(upright.size() == 4 && reversed.size() == 4, "正/逆位各 4 档 (R/SR/SSR/UR)");
        for (JsonArray tiers : new JsonArray[]{upright, reversed}) {
            for (JsonElement tier : tiers) {
                helper.assertTrue(tier.getAsJsonArray().size() > 0, "死神每档都有牌效说明");
                assertComponentLines(helper, tier.getAsJsonArray());
            }
        }
        JsonArray shiny = effects.getAsJsonArray("shiny");
        helper.assertTrue(shiny.size() > 0, "死神闪耀有签名技说明");
        assertComponentLines(helper, shiny);

        WebUiBusinessException rejected = rejection(helper, "job.tarot.cardEffects", player, cardIdPayload(22));
        helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(rejected.errorCode()),
                "cardId 越界应回 INVALID_REQUEST, 实得 " + rejected.errorCode());
        helper.succeed();
    }

    private static void assertComponentLines(GameTestHelper helper, JsonArray lines) {
        for (JsonElement line : lines) {
            Component component = Component.Serializer.fromJson(line.getAsString());
            helper.assertTrue(component != null && !component.getString().isEmpty(),
                    "每行都必须是可反序列化的 Component JSON, 实得 " + line);
        }
    }

    // ============================================================
    // job.tarot.exchange
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void exchangeSpendsShardsAndGrantsBoundSsr(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        int cost = TarotConfig.SHARD_EXCHANGE_COST.get();

        WebUiBusinessException poor = rejection(helper, "job.tarot.exchange", player, exchangePayload(9, false));
        helper.assertTrue(WebUiErrorCodes.INSUFFICIENT_FUNDS.equals(poor.errorCode())
                        && "tarot_shard".equals(poor.params().get("resource"))
                        && Integer.toString(cost).equals(poor.params().get("required")),
                "碎片不足应回 INSUFFICIENT_FUNDS 并指明缺碎片, 实得 " + poor.errorCode() + " " + poor.params());
        helper.assertTrue(countCards(player, 9) == 0, "被拒时不发牌");

        player.getInventory().add(new ItemStack(TarotRegistry.TAROT_SHARD.get(), cost + 3));
        JsonObject result = handle(helper, "job.tarot.exchange", player, exchangePayload(9, false));
        helper.assertTrue(result.get("shardsSpent").getAsInt() == cost && result.get("shardsLeft").getAsInt() == 3,
                "扣 " + cost + " 枚、剩 3 枚, 实得 " + result);
        ItemStack granted = findCard(player, 9);
        helper.assertTrue(!granted.isEmpty()
                        && TarotCardItem.quality(granted) == TarotQuality.SSR
                        && !TarotCardItem.upright(granted)
                        && player.getUUID().equals(TarotCardItem.owner(granted)),
                "兑换出绑定本人的逆位 9 号 SSR");
        player.getInventory().clearContent();
        helper.succeed();
    }

    // ============================================================
    // 卡牌物品: 闪耀朝向 + 牌效说明缓存随数据刷新
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shinyCardsAreCreatedUpright(GameTestHelper helper) {
        ItemStack shiny = TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), 0, TarotQuality.SHINY, false,
                UUID.randomUUID());
        helper.assertTrue(TarotCardItem.upright(shiny), "闪耀不分正逆位, 一律按正位生成");
        ItemStack reversedUr = TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), 0, TarotQuality.UR, false,
                UUID.randomUUID());
        helper.assertFalse(TarotCardItem.upright(reversedUr), "其它品质照常保留逆位");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void staleEffectTooltipIsRewrittenOnInventoryTick(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ItemStack card = TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), 3, TarotQuality.R, true,
                player.getUUID());
        int fresh = card.getOrCreateTag().getInt("EffectTooltipVersion");
        helper.assertTrue(fresh != 0, "新建的牌带当前牌效版本号");
        // 模拟"数据包改过数值后的旧牌": 版本号对不上当前牌效。
        card.getOrCreateTag().putInt("EffectTooltipVersion", fresh + 1);
        card.inventoryTick(helper.getLevel(), player, 0, false);
        helper.assertTrue(card.getOrCreateTag().getInt("EffectTooltipVersion") == fresh,
                "版本号过期的说明必须在背包 tick 时按当前牌效重写 (原先版本号写死 1, 改数值后旧牌永远显示旧数值)");
        helper.succeed();
    }

    // ============================================================
    // 合成台: 客户端预判的拒绝原因与服务端判据一致
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void craftPreviewExplainsWhyCraftingIsRefused(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        UUID self = player.getUUID();
        BlockPos rel = new BlockPos(1, 2, 1);
        helper.setBlock(rel, TarotRegistry.CRAFT_TABLE.get());
        BlockPos abs = helper.absolutePos(rel);
        if (!(helper.getLevel().getBlockEntity(abs) instanceof TarotCraftBlockEntity table)) {
            helper.fail("合成台方块实体未生成");
            return;
        }
        TarotCraftMenu menu = new TarotCraftMenu(0, player.getInventory(), abs);

        assertBlocker(helper, menu, self, 1, TarotCraftMenu.Blocker.INSERT_CARDS);
        table.inventory().setStackInSlot(0, card(TarotQuality.R, self));
        assertBlocker(helper, menu, self, 1, TarotCraftMenu.Blocker.NEED_TWO);
        table.inventory().setStackInSlot(1, card(TarotQuality.SR, self));
        assertBlocker(helper, menu, self, 1, TarotCraftMenu.Blocker.MISMATCH);
        table.inventory().setStackInSlot(1, card(TarotQuality.R, UUID.randomUUID()));
        assertBlocker(helper, menu, self, 1, TarotCraftMenu.Blocker.NOT_OWNER);
        table.inventory().setStackInSlot(1, card(TarotQuality.R, self));
        assertBlocker(helper, menu, self, 1, TarotCraftMenu.Blocker.NONE);

        table.inventory().setStackInSlot(0, card(TarotQuality.UR, self));
        table.inventory().setStackInSlot(1, card(TarotQuality.UR, self));
        assertBlocker(helper, menu, self, 9, TarotCraftMenu.Blocker.NEED_L10);
        assertBlocker(helper, menu, self, 10, TarotCraftMenu.Blocker.NONE);

        table.inventory().setStackInSlot(0, card(TarotQuality.SHINY, self));
        table.inventory().setStackInSlot(1, card(TarotQuality.SHINY, self));
        assertBlocker(helper, menu, self, 10, TarotCraftMenu.Blocker.SHINY_TOP);

        table.inventory().setStackInSlot(0, ItemStack.EMPTY);
        table.inventory().setStackInSlot(1, ItemStack.EMPTY);
        helper.succeed();
    }

    private static void assertBlocker(GameTestHelper helper, TarotCraftMenu menu, UUID viewer, int level,
                                      TarotCraftMenu.Blocker expected) {
        TarotCraftMenu.Blocker actual = menu.previewBlocker(viewer, level);
        helper.assertTrue(actual == expected, "期望 " + expected + ", 实得 " + actual);
    }

    // ============================================================
    // helpers
    // ============================================================

    private static ItemStack card(TarotQuality quality, UUID owner) {
        return TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), 4, quality, true, owner);
    }

    private static JsonObject cardIdPayload(int cardId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("cardId", cardId);
        return payload;
    }

    private static JsonObject exchangePayload(int cardId, boolean upright) {
        JsonObject payload = cardIdPayload(cardId);
        payload.addProperty("upright", upright);
        return payload;
    }

    private static int countCards(ServerPlayer player, int cardId) {
        return findCard(player, cardId).isEmpty() ? 0 : 1;
    }

    private static ItemStack findCard(ServerPlayer player, int cardId) {
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.getItem() instanceof TarotCardItem
                    && TarotCardItem.hasReadableCardIdentity(stack) && TarotCardItem.cardId(stack) == cardId) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static JsonObject handle(GameTestHelper helper, String action, ServerPlayer sender, JsonObject payload) {
        return JsonParser.parseString(handler(helper, action).handle(sender, payload)).getAsJsonObject();
    }

    private static WebUiBusinessException rejection(GameTestHelper helper, String action,
                                                    ServerPlayer sender, JsonObject payload) {
        try {
            handler(helper, action).handle(sender, payload);
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        helper.fail("该请求本应被业务拒绝, 实际却成功返回了: " + action + " " + payload);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static WebUiAction handler(GameTestHelper helper, String action) {
        WebUiAction handler = WebUiServerDispatcher.resolve(action);
        if (handler == null) {
            helper.fail("action " + action + " 未注册进派发器 (TarotSystem.register 须调用 TarotWebUiActions.registerAll)");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return handler;
    }
}
