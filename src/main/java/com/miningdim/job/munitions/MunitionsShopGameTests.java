package com.miningdim.job.munitions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyLedger;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.job.IJobService;
import com.miningdim.job.JobId;
import com.miningdim.job.JobProgress;
import com.miningdim.job.JobServices;
import com.miningdim.job.munitions.gunsmith.GunsmithAssemblyRecipe;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprint;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprintItem;
import com.miningdim.testutil.ConfigBaseline;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 军火商系统采购 (Munitions_Job_DesignSpec 6.4, job.munitions.shop / job.munitions.buy) 的 GameTest。
 *
 * 覆盖面按交付要求逐条: 等级不足拒绝、余额不足拒绝、台数上限拒绝、背包满处理、成功扣费发货、重复请求不重复扣费、
 * 图纸解锁等级规则、第三方枪包缺失时图纸不可买; 另加发货失败退款、枪匠总开关、已持有图纸、经济未就绪与目录只读。
 *
 * 期望值一律取独立来源而不是回调被测实现: 等级门写死设计文档推出来的数 (中级 L1 / 高级 L5 / ... / 图纸 L5 与狙击 L6),
 * 图纸弹药对照取 {@link TaczRecipeFilterGameTests#BLUEPRINT_AMMO} (那张表逐张抄自枪数据文件), 默认价写死 6.4 列出的锚价。
 *
 * 全局门面 (职业/经济) 与枪包探测是进程级静态, 每条用例经 {@link #withShop} 装上替身并在 finally 放回;
 * 用例体全同步跑完, 批内并行不会读到别的用例装的替身。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MunitionsShopGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "munitions_shop";

    private static final String SHOP_ACTION = "job.munitions.shop";
    private static final String BUY_ACTION = "job.munitions.buy";

    private static final String MEDIUM = "munitions_bench_medium";
    private static final String HIGH = "munitions_bench_high";
    private static final String RADIANT = "munitions_bench_radiant";
    private static final String PRESS = "gunsmith_press";
    private static final String ASSEMBLY = "gunsmith_assembly_bench";

    /** 下行回执上限 (S2CWebUiResponse 的 writeUtf)。目录体积必须留足余量给图纸枚举继续长。 */
    private static final int DOWNLINK_LIMIT = 32767;

    /** 狙击口径 (.30-06 / 7.92x57 / .303) 的三张图纸: 口径 L6 高过装配门 L5, 图纸门跟着抬到 L6。 */
    private static final Set<GunsmithBlueprint> SNIPER_BLUEPRINTS =
            EnumSet.of(GunsmithBlueprint.KAR98K, GunsmithBlueprint.SMLE_III, GunsmithBlueprint.M700);

    private MunitionsShopGameTests() {
    }

    /**
     * 批前钩子: 绑定默认配置 (dev 下 SERVER spec 未经 Forge 加载), 并把本批依赖的键归位到默认值 ——
     * 别的批次拿这些键做探针时若还原没落盘, 残值会跨轮存活 (见 {@link ConfigBaseline})。
     */
    @BeforeBatch(batch = BATCH)
    public static void beforeShopBatch(ServerLevel level) {
        MunitionsConfig.ensureLoadedForTest();
        ConfigBaseline.resetToDefaults(
                MunitionsConfig.GUNSMITH_ENABLED,
                MunitionsConfig.ASSEMBLY_UNLOCK_LEVEL,
                MunitionsConfig.REPAIR_UNLOCK_LEVEL,
                MunitionsConfig.QUALITY_UNLOCK_COMMON,
                MunitionsConfig.QUALITY_UNLOCK_IMPROVED,
                MunitionsConfig.QUALITY_UNLOCK_MILSPEC,
                MunitionsConfig.QUALITY_UNLOCK_PRECISION,
                MunitionsConfig.QUALITY_UNLOCK_LEGENDARY,
                MunitionsConfig.TABLE_COUNT_L1,
                MunitionsConfig.TABLE_COUNT_L4,
                MunitionsConfig.TABLE_COUNT_L10);
    }

    // ============================================================
    // 1. 等级门
    // ============================================================

    /**
     * 军火台采购等级 = 低一档台的有效等级上限 + 1。旧注册名是全档台 (上限 L10), 与闪耀台同在 L10 才卖 ——
     * 按它方块上声明的 unlockLevel=1 卖, L1 就能买到一台陪玩家升满级的台子, 后五档形同虚设。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchGatesFollowTheEffectiveLevelCeilingOfTheTierBelow(GameTestHelper helper) {
        assertGate(helper, MEDIUM, 1);
        assertGate(helper, HIGH, 5);
        assertGate(helper, "munitions_bench_superior", 7);
        assertGate(helper, "munitions_bench_transcendent", 9);
        assertGate(helper, RADIANT, 10);
        assertGate(helper, "munitions_bench", 10);
        // 冲压机: 最低一档品质 (普通) 的解锁等级; 装配台: 装配 L5 与维修 L4 取低 —— 维修先开放, 修枪铺得有台子。
        assertGate(helper, PRESS, 1);
        assertGate(helper, ASSEMBLY, 4);

        List<MunitionsShop.Entry> entries = MunitionsShop.entries();
        helper.assertTrue(entries.size() == 8 + GunsmithBlueprint.values().length,
                "目录 = 六档军火台 + 冲压机 + 装配台 + 全部图纸, 实得 " + entries.size());

        withShop(helper, 4, 0L, true, (player, ledger) -> {
            JsonObject shop = shop(helper, player);
            JsonObject medium = row(helper, shop, MEDIUM);
            JsonObject high = row(helper, shop, HIGH);
            helper.assertTrue(medium.get("unlocked").getAsBoolean() && medium.get("maxEffectiveLevel").getAsInt() == 4,
                    "L4 已解锁中级台 (上限 L4), 实得 " + medium);
            helper.assertFalse(high.get("unlocked").getAsBoolean(), "L4 还不到高级台的 L5");
            helper.assertTrue(WebUiErrorCodes.PURCHASE_LEVEL_LOCKED.equals(high.get("reasonCode").getAsString())
                            && high.get("requiredLevel").getAsInt() == 5,
                    "高级台的锁定原因必须是等级门且标出 L5, 实得 " + high);
        });
        helper.succeed();
    }

    /** 等级不足: 拒绝码带上要求与现状, 一分不扣、一件不发; 升到门槛等级后同一件立刻能买。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void purchaseBelowTheLevelGateIsRejectedWithoutCharging(GameTestHelper helper) {
        long funds = 1_000_000L;
        withShop(helper, 4, funds, true, (player, ledger) -> {
            WebUiBusinessException locked = rejection(helper, player, HIGH, UUID.randomUUID());
            helper.assertTrue(WebUiErrorCodes.PURCHASE_LEVEL_LOCKED.equals(locked.errorCode()),
                    "L4 买高级台应回 PURCHASE_LEVEL_LOCKED, 实得 " + locked.errorCode());
            helper.assertTrue("5".equals(locked.params().get("requiredLevel"))
                            && "4".equals(locked.params().get("currentLevel"))
                            && "munitions".equals(locked.params().get("job"))
                            && HIGH.equals(locked.params().get("entryId")),
                    "拒绝必须指名要几级、现在几级, 实得 " + locked.params());
            assertBalance(helper, ledger, player, funds, "等级不足被拒时");
            helper.assertTrue(count(player, ModMunitionsItems.MUNITIONS_BENCH_HIGH_ITEM.get()) == 0,
                    "等级不足不许发货");
        });
        withShop(helper, 5, funds, true, (player, ledger) -> {
            JsonObject receipt = buy(helper, player, HIGH, UUID.randomUUID());
            helper.assertTrue(HIGH.equals(receipt.get("entryId").getAsString()),
                    "L5 应能买到高级台, 实得 " + receipt);
            helper.assertTrue(count(player, ModMunitionsItems.MUNITIONS_BENCH_HIGH_ITEM.get()) == 1,
                    "L5 买到的高级台必须真进背包");
        });
        helper.succeed();
    }

    /**
     * 图纸门 = max(装配解锁等级, 图纸弹药口径的解锁等级)。弹药对照拿枪数据文件抄出来的独立表核, 不拿生产映射自证;
     * 期望的门槛写死: 狙击三张 L6, 其余 L5 (手枪/SMG 与步枪、霰弹的口径门都低于装配门)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void blueprintGateIsTheHigherOfAssemblyAndAmmoCaliberUnlock(GameTestHelper helper) {
        for (GunsmithBlueprint blueprint : GunsmithBlueprint.values()) {
            MunitionsCaliber caliber = blueprint.ammoCaliber();
            String ammo = caliber.ammoNamespace() + ":" + caliber.defaultAmmoPath();
            helper.assertTrue(ammo.equals(TaczRecipeFilterGameTests.BLUEPRINT_AMMO.get(blueprint)),
                    blueprint + " 的口径映射 " + ammo + " 与枪数据文件的弹药 "
                            + TaczRecipeFilterGameTests.BLUEPRINT_AMMO.get(blueprint) + " 不一致");
            int expected = SNIPER_BLUEPRINTS.contains(blueprint) ? 6 : 5;
            assertGate(helper, MunitionsShop.BLUEPRINT_ENTRY_PREFIX + blueprint.templateId(), expected);
        }

        withShop(helper, 5, 0L, true, (player, ledger) -> {
            JsonObject shop = shop(helper, player);
            JsonObject m4 = row(helper, shop, blueprintEntry(GunsmithBlueprint.M4A1));
            JsonObject m700 = row(helper, shop, blueprintEntry(GunsmithBlueprint.M700));
            helper.assertTrue(m4.get("unlocked").getAsBoolean() && "rifle_556".equals(m4.get("caliberId").getAsString()),
                    "L5 已能买 M4A1 图纸 (5.56 口径 L3 < 装配 L5), 实得 " + m4);
            helper.assertFalse(m700.get("unlocked").getAsBoolean(),
                    "L5 还买不了 M700 图纸: .30-06 口径要 L6, 造不出弹的图纸不该卖");
            helper.assertTrue(WebUiErrorCodes.PURCHASE_LEVEL_LOCKED.equals(m700.get("reasonCode").getAsString()),
                    "M700 的锁定原因是等级门, 实得 " + m700.get("reasonCode"));
        });
        withShop(helper, 4, 0L, true, (player, ledger) -> {
            for (JsonElement element : shop(helper, player).getAsJsonArray("entries")) {
                JsonObject row = element.getAsJsonObject();
                if ("blueprint".equals(row.get("kind").getAsString())) {
                    helper.assertFalse(row.get("unlocked").getAsBoolean(),
                            "L4 连装配都没解锁, 任何图纸都不该标已解锁: " + row.get("entryId"));
                }
            }
        });
        withShop(helper, 6, 0L, true, (player, ledger) -> helper.assertTrue(
                row(helper, shop(helper, player), blueprintEntry(GunsmithBlueprint.M700)).get("unlocked").getAsBoolean(),
                "L6 解锁 M700 图纸"));
        helper.succeed();
    }

    // ============================================================
    // 2. 余额 / 台数 / 背包
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void purchaseWithoutEnoughCreditsIsRejectedWithoutCharging(GameTestHelper helper) {
        long price = entry(helper, MEDIUM).price();
        helper.assertTrue(price > 1L, "前置条件: 中级台售价 > 1 (差 1 信用点才有意义), 实为 " + price);
        withShop(helper, 1, price - 1L, true, (player, ledger) -> {
            JsonObject row = row(helper, shop(helper, player), MEDIUM);
            helper.assertFalse(row.get("affordable").getAsBoolean(), "差 1 信用点的行 affordable 必须是 false");
            helper.assertTrue(WebUiErrorCodes.INSUFFICIENT_FUNDS.equals(row.get("reasonCode").getAsString()),
                    "目录行的原因必须与下单被拒同码, 实得 " + row.get("reasonCode"));

            WebUiBusinessException poor = rejection(helper, player, MEDIUM, UUID.randomUUID());
            helper.assertTrue(WebUiErrorCodes.INSUFFICIENT_FUNDS.equals(poor.errorCode()),
                    "余额不足应回 INSUFFICIENT_FUNDS, 实得 " + poor.errorCode());
            helper.assertTrue(Long.toString(price).equals(poor.params().get("totalPrice"))
                            && Long.toString(price - 1L).equals(poor.params().get("balance"))
                            && "CREDIT".equals(poor.params().get("currency")),
                    "拒绝必须指名要多少、有多少, 实得 " + poor.params());
            assertBalance(helper, ledger, player, price - 1L, "余额不足被拒时");
            helper.assertTrue(count(player, ModMunitionsItems.MUNITIONS_BENCH_MEDIUM_ITEM.get()) == 0,
                    "余额不足不许发货");
        });
        helper.succeed();
    }

    /**
     * 拥有数 = 已放置 (SavedData, 与放置门控同一份) + 背包里未放置的台。L1 上限 1 台: 放了一台不许再买,
     * 背包里揣着一台也不许再买, 买到一台之后第二单同样被拒 —— 花钱绕不过台数上限。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchPurchaseCannotExceedTheOwnedTableCap(GameTestHelper helper) {
        helper.assertTrue(MunitionsLevels.tableCount(1) == 1, "前置条件: L1 台数上限 1");
        long price = entry(helper, MEDIUM).price();
        long funds = price * 3L + 7L;
        withShop(helper, 1, funds, true, (player, ledger) -> {
            MunitionsSavedData savedData = MunitionsSavedData.get(player.server.overworld());
            savedData.increment(player.getUUID());
            try {
                WebUiBusinessException placedFull = rejection(helper, player, MEDIUM, UUID.randomUUID());
                helper.assertTrue(WebUiErrorCodes.PURCHASE_CAP_REACHED.equals(placedFull.errorCode()),
                        "已放置 1 台的 L1 玩家再买应回 PURCHASE_CAP_REACHED, 实得 " + placedFull.errorCode());
                helper.assertTrue("1".equals(placedFull.params().get("cap"))
                                && "1".equals(placedFull.params().get("placed"))
                                && "0".equals(placedFull.params().get("held")),
                        "拒绝必须带上限与已放置/背包两栏, 实得 " + placedFull.params());
            } finally {
                savedData.decrement(player.getUUID());
            }

            player.getInventory().add(new ItemStack(ModMunitionsItems.MUNITIONS_BENCH_HIGH_ITEM.get()));
            WebUiBusinessException heldFull = rejection(helper, player, MEDIUM, UUID.randomUUID());
            helper.assertTrue(WebUiErrorCodes.PURCHASE_CAP_REACHED.equals(heldFull.errorCode())
                            && "1".equals(heldFull.params().get("held")),
                    "背包里揣着一台未放置的台 (任一档) 也算拥有, 实得 " + heldFull.errorCode() + " " + heldFull.params());
            player.getInventory().clearContent();

            buy(helper, player, MEDIUM, UUID.randomUUID());
            WebUiBusinessException secondOrder = rejection(helper, player, MEDIUM, UUID.randomUUID());
            helper.assertTrue(WebUiErrorCodes.PURCHASE_CAP_REACHED.equals(secondOrder.errorCode()),
                    "刚买到的那一台同样占额度, 第二单必须被拒, 实得 " + secondOrder.errorCode());
            assertBalance(helper, ledger, player, funds - price, "三次被拒 + 一次成交后");
            helper.assertTrue(count(player, ModMunitionsItems.MUNITIONS_BENCH_MEDIUM_ITEM.get()) == 1,
                    "只成交了一次, 背包里只能有一台中级台");
        });
        helper.succeed();
    }

    /** 背包满: 拒绝 (不掉在脚下), 一分不扣; 腾出一格后同一件照常成交。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fullInventoryRejectsThePurchaseInsteadOfDroppingIt(GameTestHelper helper) {
        long funds = 1_000_000L;
        withShop(helper, 10, funds, true, (player, ledger) -> {
            for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
                player.getInventory().items.set(slot, new ItemStack(Items.STONE, 64));
            }
            JsonObject row = row(helper, shop(helper, player), PRESS);
            helper.assertTrue(WebUiErrorCodes.INVENTORY_FULL.equals(row.get("reasonCode").getAsString()),
                    "背包满时目录行就该标出 INVENTORY_FULL, 实得 " + row.get("reasonCode"));

            WebUiBusinessException full = rejection(helper, player, PRESS, UUID.randomUUID());
            helper.assertTrue(WebUiErrorCodes.INVENTORY_FULL.equals(full.errorCode())
                            && PRESS.equals(full.params().get("entryId")),
                    "背包满应回 INVENTORY_FULL, 实得 " + full.errorCode() + " " + full.params());
            assertBalance(helper, ledger, player, funds, "背包满被拒时");
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                            player.getBoundingBox().inflate(4.0D),
                            entity -> entity.getItem().is(ModMunitionsItems.GUNSMITH_PRESS_ITEM.get())).isEmpty(),
                    "背包满选的是拒绝, 脚下不许冒出一台冲压机");

            player.getInventory().items.set(17, ItemStack.EMPTY);
            buy(helper, player, PRESS, UUID.randomUUID());
            helper.assertTrue(player.getInventory().items.get(17).is(ModMunitionsItems.GUNSMITH_PRESS_ITEM.get()),
                    "腾出的那一格必须收到冲压机");
            assertBalance(helper, ledger, player, funds - entry(helper, PRESS).price(), "腾格后成交");
        });
        helper.succeed();
    }

    // ============================================================
    // 3. 成交 / 防重入 / 回滚
    // ============================================================

    /** 成功路径: 回执字段、实扣额、余额、发货四样逐一对上; 图纸发的是带正确 NBT 的那一张。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void successfulPurchaseChargesOnceAndDeliversTheGoods(GameTestHelper helper) {
        long funds = 2_000_000L;
        withShop(helper, 10, funds, true, (player, ledger) -> {
            long radiantPrice = entry(helper, RADIANT).price();
            UUID purchaseId = UUID.randomUUID();
            JsonObject receipt = buy(helper, player, RADIANT, purchaseId);
            helper.assertTrue(purchaseId.toString().equals(receipt.get("purchaseId").getAsString())
                            && "bench".equals(receipt.get("kind").getAsString())
                            && "miningdim:munitions_bench_radiant".equals(receipt.get("itemId").getAsString())
                            && receipt.get("count").getAsInt() == 1
                            && "CREDIT".equals(receipt.get("currency").getAsString())
                            && !receipt.get("replayed").getAsBoolean(),
                    "回执形状不对: " + receipt);
            helper.assertTrue(receipt.get("price").getAsLong() == radiantPrice
                            && receipt.get("balanceAfter").getAsLong() == funds - radiantPrice,
                    "回执必须报实扣额与扣后余额, 实得 " + receipt);
            assertBalance(helper, ledger, player, funds - radiantPrice, "买闪耀台后");
            helper.assertTrue(count(player, ModMunitionsItems.MUNITIONS_BENCH_RADIANT_ITEM.get()) == 1,
                    "闪耀台必须真进背包");

            long blueprintPrice = entry(helper, blueprintEntry(GunsmithBlueprint.AK47)).price();
            JsonObject blueprintReceipt = buy(helper, player, blueprintEntry(GunsmithBlueprint.AK47), UUID.randomUUID());
            helper.assertTrue("ak47".equals(blueprintReceipt.get("blueprintId").getAsString())
                            && "tacz.gun.ak47.name".equals(blueprintReceipt.get("gunNameKey").getAsString()),
                    "图纸回执必须带图纸 id 与枪名键, 实得 " + blueprintReceipt);
            ItemStack blueprint = firstOf(player, ModMunitionsItems.GUNSMITH_BLUEPRINT.get());
            helper.assertTrue(GunsmithAssemblyRecipe.isBlueprint(blueprint)
                            && GunsmithAssemblyRecipe.blueprint(blueprint) == GunsmithBlueprint.AK47,
                    "发出的必须是一张能上装配台的 AK47 图纸 (NBT 完整), 实得 " + blueprint.getTag());
            assertBalance(helper, ledger, player, funds - radiantPrice - blueprintPrice, "再买 AK47 图纸后");
        });
        helper.succeed();
    }

    /**
     * 防重入: 同一 purchaseId 第二次到达只回放第一次的回执, 不扣费不发货; 拿同一个 id 去买别的条目被拒;
     * 格式不对的 id 在任何扣费之前被拒。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void repeatedPurchaseIdIsReplayedWithoutChargingTwice(GameTestHelper helper) {
        long funds = 1_000_000L;
        withShop(helper, 10, funds, true, (player, ledger) -> {
            long price = entry(helper, PRESS).price();
            UUID purchaseId = UUID.randomUUID();
            JsonObject first = buy(helper, player, PRESS, purchaseId);
            JsonObject second = buy(helper, player, PRESS, purchaseId);
            helper.assertFalse(first.get("replayed").getAsBoolean(), "第一次是真成交");
            helper.assertTrue(second.get("replayed").getAsBoolean()
                            && second.get("price").getAsLong() == first.get("price").getAsLong()
                            && second.get("balanceAfter").getAsLong() == first.get("balanceAfter").getAsLong(),
                    "重复到达必须回放第一次的回执, 实得 " + second);
            assertBalance(helper, ledger, player, funds - price, "同一 purchaseId 发两次后");
            helper.assertTrue(count(player, ModMunitionsItems.GUNSMITH_PRESS_ITEM.get()) == 1,
                    "同一 purchaseId 只能发一台冲压机, 实得 " + count(player, ModMunitionsItems.GUNSMITH_PRESS_ITEM.get()));

            WebUiBusinessException reused = rejection(helper, player, ASSEMBLY, purchaseId);
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(reused.errorCode())
                            && "purchaseId".equals(reused.params().get("field")),
                    "拿同一个 purchaseId 买别的条目必须被拒, 实得 " + reused.errorCode() + " " + reused.params());

            JsonObject malformed = new JsonObject();
            malformed.addProperty("entryId", ASSEMBLY);
            malformed.addProperty("purchaseId", "1-1-1-1-1");
            WebUiBusinessException badId = rejection(helper, player, malformed);
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(badId.errorCode())
                            && "purchaseId".equals(badId.params().get("field")),
                    "非规范 UUID 必须被拒, 实得 " + badId.errorCode() + " " + badId.params());

            JsonObject unknown = new JsonObject();
            unknown.addProperty("entryId", "munitions_bench_legendary");
            unknown.addProperty("purchaseId", UUID.randomUUID().toString());
            WebUiBusinessException badEntry = rejection(helper, player, unknown);
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(badEntry.errorCode())
                            && "entryId".equals(badEntry.params().get("field")),
                    "未知条目必须被拒, 实得 " + badEntry.errorCode() + " " + badEntry.params());

            assertBalance(helper, ledger, player, funds - price, "三次非法请求之后");
            helper.assertTrue(count(player, ModMunitionsItems.GUNSMITH_ASSEMBLY_BENCH_ITEM.get()) == 0,
                    "被拒的请求不许发装配台");
        });
        helper.succeed();
    }

    /**
     * 先扣费后发货的回滚: 发货失败 (返回 false 或抛异常) 必须原额退回, 且不记回执 —— 同一 purchaseId 在发货恢复后
     * 重试要能真正成交一次, 而不是回放一个从未发出的货。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void failedDeliveryAfterChargingIsRefundedInFull(GameTestHelper helper) {
        long funds = 1_000_000L;
        withShop(helper, 10, funds, true, (player, ledger) -> {
            long price = entry(helper, ASSEMBLY).price();
            helper.assertTrue(price > 0L, "前置条件: 装配台售价 > 0 (免费条目无从验证退款)");
            UUID purchaseId = UUID.randomUUID();

            MunitionsShop.overrideDeliveryForTest((target, goods) -> false);
            WebUiBusinessException refused = rejection(helper, player, ASSEMBLY, purchaseId);
            helper.assertTrue(WebUiErrorCodes.INVENTORY_FULL.equals(refused.errorCode()),
                    "发货失败按背包放不下回报, 实得 " + refused.errorCode());
            assertBalance(helper, ledger, player, funds, "发货失败退款后");

            MunitionsShop.overrideDeliveryForTest((target, goods) -> {
                throw new IllegalStateException("probe: delivery exploded");
            });
            boolean thrown = false;
            try {
                handler(helper, BUY_ACTION).handle(player, buyPayload(ASSEMBLY, purchaseId));
            } catch (IllegalStateException expected) {
                thrown = true;
            }
            helper.assertTrue(thrown, "发货抛出的异常必须原样冒泡 (由派发器兜底成失败回执)");
            assertBalance(helper, ledger, player, funds, "发货抛异常退款后");
            helper.assertTrue(count(player, ModMunitionsItems.GUNSMITH_ASSEMBLY_BENCH_ITEM.get()) == 0,
                    "两次发货都失败, 背包里不许有装配台");

            MunitionsShop.resetDeliveryForTest();
            JsonObject receipt = buy(helper, player, ASSEMBLY, purchaseId);
            helper.assertFalse(receipt.get("replayed").getAsBoolean(),
                    "失败的那两次不记回执, 同一个 id 重试必须是一次真成交");
            assertBalance(helper, ledger, player, funds - price, "恢复发货后成交");
            helper.assertTrue(count(player, ModMunitionsItems.GUNSMITH_ASSEMBLY_BENCH_ITEM.get()) == 1,
                    "恢复后恰好发出一台装配台");
        });
        helper.succeed();
    }

    // ============================================================
    // 4. 不可售 / 已持有 / 经济未就绪
    // ============================================================

    /** 图纸所绑的枪不在 TaCZ 索引里 (缺第三方枪包): 该图纸标不可用, 下单被拒且一分不扣; 同批的 tacz 图纸不受影响。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void blueprintIsNotSoldWhenItsGunPackIsMissing(GameTestHelper helper) {
        long funds = 5_000_000L;
        withShop(helper, 10, funds, true, (player, ledger) -> {
            MunitionsShop.overrideGunPackProbeForTest(gunId -> "tacz".equals(gunId.getNamespace()));
            JsonObject shop = shop(helper, player);
            JsonObject ksg = row(helper, shop, blueprintEntry(GunsmithBlueprint.KSG));
            helper.assertFalse(ksg.get("available").getAsBoolean() || ksg.get("purchasable").getAsBoolean(),
                    "hare 枪包缺失时 KSG 图纸不可用, 实得 " + ksg);
            helper.assertTrue("gun_pack_missing".equals(ksg.get("unavailableReason").getAsString())
                            && WebUiErrorCodes.SHOP_ITEM_UNAVAILABLE.equals(ksg.get("reasonCode").getAsString()),
                    "不可用的原因必须写明是缺枪包, 实得 " + ksg);
            JsonObject m870 = row(helper, shop, blueprintEntry(GunsmithBlueprint.M870));
            helper.assertTrue(m870.get("available").getAsBoolean() && m870.get("purchasable").getAsBoolean(),
                    "默认枪包的 M870 不受影响, 实得 " + m870);

            WebUiBusinessException missing = rejection(helper, player, blueprintEntry(GunsmithBlueprint.KSG),
                    UUID.randomUUID());
            helper.assertTrue(WebUiErrorCodes.SHOP_ITEM_UNAVAILABLE.equals(missing.errorCode())
                            && "gun_pack_missing".equals(missing.params().get("reason")),
                    "缺枪包的图纸下单应回 SHOP_ITEM_UNAVAILABLE/gun_pack_missing, 实得 "
                            + missing.errorCode() + " " + missing.params());
            assertBalance(helper, ledger, player, funds, "缺枪包被拒时");
            helper.assertTrue(count(player, ModMunitionsItems.GUNSMITH_BLUEPRINT.get()) == 0, "缺枪包不许发图纸");

            // 默认探测走 TaCZ 枪械索引: dev 不加载 TaCZ, 于是每张图纸都该判缺包 (而不是误判成可买)。
            MunitionsShop.resetGunPackProbeForTest();
            if (!MunitionsAmmoFactory.isTaczLoaded()) {
                for (JsonElement element : shop(helper, player).getAsJsonArray("entries")) {
                    JsonObject row = element.getAsJsonObject();
                    if ("blueprint".equals(row.get("kind").getAsString())) {
                        helper.assertTrue("gun_pack_missing".equals(row.get("unavailableReason").getAsString()),
                                "TaCZ 未加载时图纸 " + row.get("entryId") + " 必须判缺包");
                    }
                }
            }
        });
        helper.succeed();
    }

    /** 枪匠链总开关关着: 冲压机/装配台/图纸不卖 (买到也用不了), 军火台不受影响。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gunsmithSwitchOffWithdrawsGunsmithEntriesButKeepsBenches(GameTestHelper helper) {
        long funds = 1_000_000L;
        withShop(helper, 10, funds, false, (player, ledger) -> {
            JsonObject shop = shop(helper, player);
            helper.assertFalse(shop.get("gunsmithEnabled").getAsBoolean(), "前置条件: 目录如实报出开关关着");
            for (JsonElement element : shop.getAsJsonArray("entries")) {
                JsonObject row = element.getAsJsonObject();
                boolean bench = "bench".equals(row.get("kind").getAsString());
                if (bench) {
                    helper.assertTrue(row.get("available").getAsBoolean(),
                            "枪匠开关不管军火台: " + row.get("entryId"));
                } else {
                    helper.assertTrue("gunsmith_disabled".equals(row.get("unavailableReason").getAsString()),
                            "开关关着时 " + row.get("entryId") + " 必须标 gunsmith_disabled, 实得 " + row);
                }
            }
            WebUiBusinessException closed = rejection(helper, player, PRESS, UUID.randomUUID());
            helper.assertTrue(WebUiErrorCodes.SHOP_ITEM_UNAVAILABLE.equals(closed.errorCode())
                            && "gunsmith_disabled".equals(closed.params().get("reason")),
                    "开关关着买冲压机应被拒, 实得 " + closed.errorCode() + " " + closed.params());
            assertBalance(helper, ledger, player, funds, "开关关着被拒时");
        });
        helper.succeed();
    }

    /** 装配不消耗图纸: 背包里已有同一张 (含旧 M4 装配模板) 时不再卖, 别的图纸照常。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void heldBlueprintIsNotSoldAgain(GameTestHelper helper) {
        long funds = 1_000_000L;
        withShop(helper, 10, funds, true, (player, ledger) -> {
            player.getInventory().add(GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(),
                    GunsmithBlueprint.UZI));
            JsonObject uzi = row(helper, shop(helper, player), blueprintEntry(GunsmithBlueprint.UZI));
            helper.assertTrue(uzi.get("owned").getAsBoolean()
                            && WebUiErrorCodes.ALREADY_OWNED.equals(uzi.get("reasonCode").getAsString()),
                    "背包里有 UZI 图纸时目录行必须标已持有, 实得 " + uzi);
            WebUiBusinessException again = rejection(helper, player, blueprintEntry(GunsmithBlueprint.UZI),
                    UUID.randomUUID());
            helper.assertTrue(WebUiErrorCodes.ALREADY_OWNED.equals(again.errorCode()),
                    "重复买同一张图纸应回 ALREADY_OWNED, 实得 " + again.errorCode());

            player.getInventory().add(new ItemStack(ModMunitionsItems.M4_ASSEMBLY_TEMPLATE.get()));
            WebUiBusinessException legacy = rejection(helper, player, blueprintEntry(GunsmithBlueprint.M4A1),
                    UUID.randomUUID());
            helper.assertTrue(WebUiErrorCodes.ALREADY_OWNED.equals(legacy.errorCode()),
                    "旧 M4 装配模板等价于 M4A1 图纸, 也算已持有, 实得 " + legacy.errorCode());
            assertBalance(helper, ledger, player, funds, "两次重复购买被拒时");

            buy(helper, player, blueprintEntry(GunsmithBlueprint.MPX), UUID.randomUUID());
            helper.assertTrue(count(player, ModMunitionsItems.GUNSMITH_BLUEPRINT.get()) == 2,
                    "没持有的 MPX 照常能买, 背包里应有 UZI + MPX 两张");
        });
        helper.succeed();
    }

    /** 经济未注册: 目录余额报 null (不是 0), 收费条目下单回 ECONOMY_OFFLINE 且不发货。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void paidEntriesAreRefusedWhileTheEconomyIsOffline(GameTestHelper helper) {
        withShop(helper, 10, 0L, true, (player, ledger) -> {
            IEconomyService online = EconomyServices.economyService();
            EconomyServices.reset();
            try {
                JsonObject shop = shop(helper, player);
                helper.assertTrue(shop.get("balance").isJsonNull() && !shop.get("economyOnline").getAsBoolean(),
                        "经济未就绪时余额是未知, 必须发 null, 实得 " + shop.get("balance"));
                WebUiBusinessException offline = rejection(helper, player, MEDIUM, UUID.randomUUID());
                helper.assertTrue(WebUiErrorCodes.ECONOMY_OFFLINE.equals(offline.errorCode()),
                        "经济未就绪应回 ECONOMY_OFFLINE, 实得 " + offline.errorCode());
                helper.assertTrue(count(player, ModMunitionsItems.MUNITIONS_BENCH_MEDIUM_ITEM.get()) == 0,
                        "经济未就绪不许发货");
            } finally {
                EconomyServices.registerEconomyService(online);
            }
        });
        helper.succeed();
    }

    // ============================================================
    // 5. 目录只读 / 体积 / 默认价
    // ============================================================

    /** 目录是只读的 (进得了 system.batch): 连调两次不动钱包、背包与放置计数; 回执体积留足下行余量。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shopCatalogIsReadOnlyAndFitsTheDownlink(GameTestHelper helper) {
        long funds = 123_456L;
        withShop(helper, 10, funds, true, (player, ledger) -> {
            int placedBefore = MunitionsSavedData.get(player.server.overworld()).benchCount(player.getUUID());
            String raw = handler(helper, SHOP_ACTION).handle(player, new JsonObject());
            handler(helper, SHOP_ACTION).handle(player, new JsonObject());
            assertBalance(helper, ledger, player, funds, "连调两次目录后");
            helper.assertTrue(player.getInventory().isEmpty(), "目录不许往背包里放任何东西");
            helper.assertTrue(MunitionsSavedData.get(player.server.overworld()).benchCount(player.getUUID())
                    == placedBefore, "目录不许动放置计数");
            helper.assertTrue(raw.length() <= DOWNLINK_LIMIT / 2,
                    "目录回执 " + raw.length() + " 字符, 已超下行上限的一半; 图纸再加几张就会撑爆, 该分页了");

            JsonObject shop = JsonParser.parseString(raw).getAsJsonObject();
            helper.assertTrue(shop.get("entryCount").getAsInt() == MunitionsShop.entries().size()
                            && shop.get("balance").getAsLong() == funds
                            && shop.get("level").getAsInt() == 10
                            && shop.get("benchCap").getAsInt() == MunitionsLevels.tableCount(10),
                    "目录顶层字段不对: level/balance/benchCap/entryCount");
            JsonObject press = row(helper, shop, PRESS);
            for (String key : new String[]{"gunNameKey", "blueprintId", "gunId", "caliberId", "maxEffectiveLevel",
                    "unavailableReason", "reasonCode"}) {
                helper.assertTrue(press.has(key) && press.get(key).isJsonNull(),
                        "非图纸行的 " + key + " 必须是显式 null (键不能整个消失), 实得 " + press);
            }
        });
        helper.succeed();
    }

    /**
     * 默认价逐项对 6.4 列出的出处: 军火台按 6.2 每台日净 x 3 天回本 (暂定), 冲压机/装配台按工费倍数 (暂定),
     * 图纸按 8.3 单把枪价区间中值、以弹药口径归档。比的是 spec 默认值 (getDefault), 不受 run 目录里 toml 改动影响。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void defaultPricesMatchTheDocumentedAnchors(GameTestHelper helper) {
        assertDefault(helper, MunitionsConfig.SHOP_BENCH_MEDIUM_PRICE, 15_000, "中级军火台");
        assertDefault(helper, MunitionsConfig.SHOP_BENCH_HIGH_PRICE, 55_000, "高级军火台");
        assertDefault(helper, MunitionsConfig.SHOP_BENCH_SUPERIOR_PRICE, 70_000, "极品军火台");
        assertDefault(helper, MunitionsConfig.SHOP_BENCH_TRANSCENDENT_PRICE, 80_000, "超凡军火台");
        assertDefault(helper, MunitionsConfig.SHOP_BENCH_RADIANT_PRICE, 90_000, "闪耀军火台");
        assertDefault(helper, MunitionsConfig.SHOP_BENCH_LEGACY_PRICE, 90_000, "旧全档军火台");
        assertDefault(helper, MunitionsConfig.SHOP_GUNSMITH_PRESS_PRICE, 20_000, "枪匠冲压机");
        assertDefault(helper, MunitionsConfig.SHOP_GUNSMITH_ASSEMBLY_BENCH_PRICE, 50_000, "枪匠装配台");
        for (GunsmithBlueprint blueprint : GunsmithBlueprint.values()) {
            int expected = switch (blueprint.ammoCaliber().category()) {
                case PISTOL -> 35_000;
                case RIFLE -> 115_000;
                case SHOTGUN -> 160_000;
                case SNIPER -> 325_000;
                case EXPLOSIVE -> throw new IllegalStateException("没有吃爆炸弹的图纸, 需要先给它定价档: " + blueprint);
            };
            assertDefault(helper, MunitionsConfig.shopBlueprintPrice(blueprint), expected, blueprint + " 图纸");
        }
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    @FunctionalInterface
    private interface ShopScenario {
        void run(ServerPlayer player, EconomyLedger ledger);
    }

    /**
     * 装/卸一名干净玩家的采购上下文: 定级职业门面 + 内存账本经济 + 枪匠开关 + "所有枪包都在"的探测, 跑完全部放回。
     * 枪匠开关只在与现值不同时才写 (每写一次都会落盘一次 serverconfig)。
     */
    private static void withShop(GameTestHelper helper, int level, long credits, boolean gunsmithEnabled,
                                 ShopScenario scenario) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        IJobService previousJob = swapJob(new FixedLevelJobService(level));
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService previousEconomy = swapEconomy(freshEconomy(ledger));
        boolean previousGunsmith = MunitionsConfig.GUNSMITH_ENABLED.get();
        if (previousGunsmith != gunsmithEnabled) {
            MunitionsConfig.GUNSMITH_ENABLED.set(gunsmithEnabled);
        }
        MunitionsShop.overrideGunPackProbeForTest(gunId -> true);
        try {
            if (credits > 0L) {
                ledger.credit(player.getUUID(), Currency.CREDIT, credits);
            }
            scenario.run(player, ledger);
        } finally {
            MunitionsShop.resetGunPackProbeForTest();
            MunitionsShop.resetDeliveryForTest();
            if (previousGunsmith != gunsmithEnabled) {
                MunitionsConfig.GUNSMITH_ENABLED.set(previousGunsmith);
            }
            restoreEconomy(previousEconomy);
            restoreJob(previousJob);
        }
    }

    private static JsonObject shop(GameTestHelper helper, ServerPlayer player) {
        return JsonParser.parseString(handler(helper, SHOP_ACTION).handle(player, new JsonObject())).getAsJsonObject();
    }

    private static JsonObject buy(GameTestHelper helper, ServerPlayer player, String entryId, UUID purchaseId) {
        return JsonParser.parseString(handler(helper, BUY_ACTION).handle(player, buyPayload(entryId, purchaseId)))
                .getAsJsonObject();
    }

    private static WebUiBusinessException rejection(GameTestHelper helper, ServerPlayer player, String entryId,
                                                    UUID purchaseId) {
        return rejection(helper, player, buyPayload(entryId, purchaseId));
    }

    private static WebUiBusinessException rejection(GameTestHelper helper, ServerPlayer player, JsonObject payload) {
        try {
            handler(helper, BUY_ACTION).handle(player, payload);
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        helper.fail("该请求本应被业务拒绝, 实际却成交了: " + payload);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static JsonObject buyPayload(String entryId, UUID purchaseId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("entryId", entryId);
        payload.addProperty("purchaseId", purchaseId.toString());
        return payload;
    }

    /** 刻意不在测试侧补注册: 生产侧 MunitionsSystem.register 漏调 registerAll 时本文件必须先红。 */
    private static WebUiAction handler(GameTestHelper helper, String action) {
        WebUiAction handler = WebUiServerDispatcher.resolve(action);
        if (handler == null) {
            helper.fail("action " + action + " 未注册: MunitionsSystem.register 没有调用 MunitionsWebUiActions.registerAll");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return handler;
    }

    private static JsonObject row(GameTestHelper helper, JsonObject shop, String entryId) {
        JsonArray entries = shop.getAsJsonArray("entries");
        for (JsonElement element : entries) {
            JsonObject row = element.getAsJsonObject();
            if (entryId.equals(row.get("entryId").getAsString())) {
                return row;
            }
        }
        helper.fail("目录里没有 " + entryId);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static MunitionsShop.Entry entry(GameTestHelper helper, String entryId) {
        return MunitionsShop.find(entryId).orElseGet(() -> {
            helper.fail("MunitionsShop 没有条目 " + entryId);
            throw new IllegalStateException("unreachable: helper.fail already threw");
        });
    }

    private static String blueprintEntry(GunsmithBlueprint blueprint) {
        return MunitionsShop.BLUEPRINT_ENTRY_PREFIX + blueprint.templateId();
    }

    private static void assertGate(GameTestHelper helper, String entryId, int expected) {
        int actual = entry(helper, entryId).requiredLevel();
        helper.assertTrue(actual == expected, entryId + " 的采购等级应为 L" + expected + ", 实为 L" + actual);
    }

    private static void assertDefault(GameTestHelper helper, ForgeConfigSpec.IntValue value, int expected,
                                      String label) {
        helper.assertTrue(value.getDefault() == expected,
                label + " 的默认售价应为 " + expected + ", 实为 " + value.getDefault());
    }

    private static void assertBalance(GameTestHelper helper, EconomyLedger ledger, ServerPlayer player,
                                      long expected, String when) {
        long actual = ledger.balance(player.getUUID(), Currency.CREDIT);
        helper.assertTrue(actual == expected, when + "余额应为 " + expected + ", 实为 " + actual);
    }

    /** 主背包 + 副手里某物品的总数。 */
    private static int count(ServerPlayer player, Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static ItemStack firstOf(ServerPlayer player, Item item) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) {
                return stack;
            }
        }
        throw new IllegalStateException("背包里没有 " + item);
    }

    private static IJobService swapJob(IJobService fake) {
        IJobService previous;
        try {
            previous = JobServices.jobService();
        } catch (IllegalStateException notRegistered) {
            previous = null;
        }
        JobServices.registerJobService(fake);
        return previous;
    }

    private static void restoreJob(IJobService previous) {
        if (previous != null) {
            JobServices.registerJobService(previous);
        } else {
            JobServices.reset();
        }
    }

    private static IEconomyService swapEconomy(IEconomyService fake) {
        IEconomyService previous = EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
        EconomyServices.registerEconomyService(fake);
        return previous;
    }

    private static void restoreEconomy(IEconomyService previous) {
        if (previous != null) {
            EconomyServices.registerEconomyService(previous);
        } else {
            EconomyServices.reset();
        }
    }

    /** 真 EconomyService (内存账本 + AbuseGuard); tryCharge/grant 走真实的扣费与入账。 */
    private static IEconomyService freshEconomy(EconomyLedger ledger) {
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        Function<UUID, PlayerAbuseState> resolver = id -> states.computeIfAbsent(id, key -> new PlayerAbuseState());
        return new EconomyService(ledger, new AbuseGuard(), resolver);
    }

    /** 定级职业门面替身: level 恒为给定值 (采购只读等级, 不入账经验)。 */
    private static final class FixedLevelJobService implements IJobService {
        private final int level;

        FixedLevelJobService(int level) {
            this.level = level;
        }

        @Override
        public int level(Player player, JobId job) {
            return level;
        }

        @Override
        public long totalXp(Player player, JobId job) {
            return 0L;
        }

        @Override
        public long grantXp(Player player, JobId job, long rawXp) {
            return rawXp;
        }

        @Override
        public JobProgress progress(Player player, JobId job) {
            throw new UnsupportedOperationException("not exercised by munitions shop tests");
        }
    }
}
