package com.miningdim.market;

import com.google.gson.JsonObject;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.EconomyLedger;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.market.store.MarketDaoSqlite;
import com.miningdim.market.store.MarketDb;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * V0 基准价 admin curate GameTest (commit 2): 分层解析覆盖优先级 + 覆盖改变挂单偏离费 + OP 门控。
 * 与 {@link MarketGameTests} 同款内存 SQLite + 经济门面 swap/restore 范式。
 *
 * 强断言:
 *  A. 覆盖优先级: 无覆盖钻石走预设 500 / 金锭 120 / 鹅卵石空; admin 覆盖钻石 100 后 resolve 取 100 (覆盖 > 预设);
 *     且覆盖改变挂单费 —— 钻石 V0 改 100 后按 100 挂 = 平价 -> 费落 20% 地板 (而非预设 500 下的偏离费)。
 *  B. OP 门控: 非 OP 调 admin.setBaseValue / admin.listItems 被拒 (isOp=false); op() 后 setBaseValue 放行并写入覆盖。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MarketAdminGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "market_admin";

    // ============================================================
    // A. 覆盖优先级 (覆盖 > 预设 > 空) + 覆盖改变挂单偏离费
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void baseValueOverridePrecedenceAndFee(GameTestHelper helper) {
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.openInMemory();
        try {
            MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());

            // 无覆盖: 走代码预设 (钻 500 / 金锭 120) / 无锚 (鹅卵石 -> 空, 挂单走平率)。
            helper.assertTrue(engine.resolveBaseValue("minecraft:diamond").getAsLong() == 500L,
                    "diamond resolves to the code preset V0=500 before any override");
            helper.assertTrue(engine.resolveBaseValue("minecraft:gold_ingot").getAsLong() == 120L,
                    "gold ingot resolves to the code preset V0=120");
            helper.assertTrue(engine.resolveBaseValue("minecraft:cobblestone").isEmpty(),
                    "an unanchored item (cobblestone) resolves to empty (flat-rate fee)");

            // admin 覆盖钻石 V0 = 100, 覆盖优先于预设。
            UUID adminUuid = UUID.randomUUID();
            engine.setBaseValueOverride("minecraft:diamond", 100L, adminUuid);
            helper.assertTrue(engine.resolveBaseValue("minecraft:diamond").getAsLong() == 100L,
                    "admin override (100) takes precedence over the preset (500)");
            Long listedOverride = engine.baseValueOverrides().get("minecraft:diamond");
            helper.assertTrue(listedOverride != null && listedOverride == 100L,
                    "the override is recorded in base_values and surfaced via baseValueOverrides()");

            // 覆盖改变挂单费: 钻石 V0 现为 100, 按 100 挂 = 平价(VR==V0) -> 费 = round(0.20*100*4) = 80 (20% 地板),
            // 而非预设 500 下 VR=100 的偏离费 (更高)。
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            seller.getInventory().clearContent();
            seller.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 4));
            EconomyServices.economyService().grant(seller, Currency.CREDIT, 10_000L);
            long fundedBefore = ledger.balance(seller.getUUID(), Currency.CREDIT);

            MarketEngine.PlaceResult placed = engine.place(seller, 0, 4, 100L, "CREDIT");
            long expectedParityFee = Math.round(0.20D * 100L * 4); // 80 (平价 20% 地板)
            helper.assertTrue(placed.listFee() == expectedParityFee,
                    "with the override making VR==V0, the listing fee is the flat 20% parity floor (80), got " + placed.listFee());
            helper.assertTrue(ledger.balance(seller.getUUID(), Currency.CREDIT) == fundedBefore - expectedParityFee,
                    "seller is charged exactly the parity listing fee under the override");
            // 反证: 同样 VR=100 但用预设 V0=500 (无锚物品另算), 偏离费严格 > 平价费 (证明覆盖确实生效降了费)。
            long deviationUnderPreset = MarketFee.deviationFee(500L, 100L, 4);
            helper.assertTrue(deviationUnderPreset > expectedParityFee,
                    "sanity: under the preset V0=500 the same listing would cost the higher deviation fee, proving the override lowered it");

            helper.succeed();
        } finally {
            MarketDb.close(dao);
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // B. OP 门控: 非 OP 拒 / op() 后放行
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void adminActionsRequireOp(GameTestHelper helper) {
        MarketDaoSqlite dao = MarketDb.openInMemory();
        MarketEngine prevMarket = swapMarket(new MarketEngine(dao, helper.getLevel().getServer()));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            // 非 OP: 两个 admin 动作都被 isOp 门拒 (requireOp 抛, 早于触碰引擎/payload)。
            helper.assertTrue(!helper.getLevel().getServer().getPlayerList().isOp(player.getGameProfile()),
                    "a fresh mock player is not OP");
            JsonObject setPayload = new JsonObject();
            setPayload.addProperty("itemId", "minecraft:diamond");
            setPayload.addProperty("v0", 777L);
            assertThrows(helper, () -> MarketAdminActions.SET_BASE_VALUE.handle(player, setPayload),
                    "admin.setBaseValue rejects a non-OP sender (OP gate)");
            assertThrows(helper, () -> MarketAdminActions.LIST_ITEMS.handle(player, new JsonObject()),
                    "admin.listItems rejects a non-OP sender (OP gate)");

            // op() 后: setBaseValue 放行并经引擎写入覆盖。
            helper.getLevel().getServer().getPlayerList().op(player.getGameProfile());
            helper.assertTrue(helper.getLevel().getServer().getPlayerList().isOp(player.getGameProfile()),
                    "player is OP after op()");
            MarketAdminActions.SET_BASE_VALUE.handle(player, setPayload);
            helper.assertTrue(
                    MarketServices.marketEngine().resolveBaseValue("minecraft:diamond").getAsLong() == 777L,
                    "an OP sender can set the base value override (diamond -> 777)");

            helper.succeed();
        } finally {
            // 清理 ops 列表 (避免污染后续测试) + 还原门面 + 关库。
            helper.getLevel().getServer().getPlayerList().deop(player.getGameProfile());
            restoreMarket(prevMarket);
            MarketDb.close(dao);
        }
    }

    // ============================================================
    // 门面 swap/restore + 工具
    // ============================================================

    private static IEconomyService swapEconomy(IEconomyService fake) {
        IEconomyService prev = EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
        EconomyServices.registerEconomyService(fake);
        return prev;
    }

    private static void restoreEconomy(IEconomyService prev) {
        if (prev != null) {
            EconomyServices.registerEconomyService(prev);
        } else {
            EconomyServices.reset();
        }
    }

    private static MarketEngine swapMarket(MarketEngine fake) {
        MarketEngine prev = MarketServices.isRegistered() ? MarketServices.marketEngine() : null;
        MarketServices.registerMarketEngine(fake);
        return prev;
    }

    private static void restoreMarket(MarketEngine prev) {
        if (prev != null) {
            MarketServices.registerMarketEngine(prev);
        } else {
            MarketServices.reset();
        }
    }

    private static void assertThrows(GameTestHelper helper, Runnable r, String msg) {
        boolean threw = false;
        try {
            r.run();
        } catch (RuntimeException e) {
            threw = true;
        }
        helper.assertTrue(threw, msg);
    }

    private static Function<UUID, PlayerAbuseState> newStateResolver() {
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        return id -> states.computeIfAbsent(id, k -> new PlayerAbuseState());
    }
}
