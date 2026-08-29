package com.miningdim.job.munitions.block;

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
import com.miningdim.job.munitions.ModMunitionsBlocks;
import com.miningdim.job.munitions.ModMunitionsItems;
import com.miningdim.job.munitions.MunitionsConfig;
import com.miningdim.job.munitions.gunsmith.GunsmithPartItem;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPartVariant;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.testutil.ConfigBaseline;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GunsmithPressGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "gunsmith_press";
    private static final BlockPos PRESS_REL = new BlockPos(1, 1, 1);

    private GunsmithPressGameTests() {
    }

    /**
     * 跨轮基线归位: 本批次会临时改下列配置项做探针, 先抹掉上一轮可能残留的值 (见 {@link ConfigBaseline})。
     *
     * 绝不能在这里把工费/等级门置 0 当"隔离基线": ForgeConfigSpec 的写入是落盘的, runGameTestServer 复用
     * run/world 存档, 探针值会留在 run/world/serverconfig/miningdim-munitions.toml 里跨轮存活 —— 而
     * pressWorkFeeCredits 的下界就是 0, SPEC.correct() 不会纠回, 结果是第二轮起冲压工费 sink 全局失效且
     * 毫无告警。要真开工的用例一律自带够级别、够余额的玩家上下文 ({@link #withGunsmithContext}), 而不是
     * 把生产配置改没。
     */
    @BeforeBatch(batch = BATCH)
    public static void resetConfigBaseline(ServerLevel level) {
        ConfigBaseline.resetToDefaults(
                MunitionsConfig.QUALITY_UNLOCK_COMMON,
                MunitionsConfig.PRESS_WORK_FEE_CREDITS);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pistolPlatformOffersFivePartsAndStartsHammerProduction(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.PISTOL.index()),
                "press must accept the pistol platform");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.BARREL,
                "switching to pistol must normalize an AR-only selection to the first pistol part");
        helper.assertTrue(GunsmithPlatform.PISTOL.supportedParts().size() == 5,
                "pistol press selection must contain exactly five parts");
        helper.assertFalse(GunsmithPlatform.PISTOL.supports(GunsmithPressPart.CORE),
                "pistol press selection must exclude the rifle gas system");

        int hammerRow = compactRow(GunsmithPlatform.PISTOL, GunsmithPressPart.HAMMER);
        helper.assertTrue(press.trySelectPart(hammerRow), "press must select the pistol hammer by compact row");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.HAMMER,
                "compact pistol row must resolve to the hammer");
        helper.assertFalse(press.trySelectPart(99), "press must reject an unknown compact part row");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.HAMMER,
                "rejected selection must preserve the current part");

        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                new ItemStack(Items.IRON_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                new ItemStack(Items.COPPER_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                new ItemStack(Items.SLIME_BLOCK, 64));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        withGunsmithContext(10, 100000L, player, ledger -> {
            helper.assertTrue(press.tryStartPreview(player), "complete hammer materials must start the press");
            helper.assertTrue(press.isPressing(), "pistol hammer production must put the press into its active state");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 62,
                    "common hammer production must consume two generic gun parts");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 60,
                    "common hammer production must consume four alloy units");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 64,
                    "common hammer production must not consume polymer");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_OUTPUT).isEmpty(),
                    "press output must remain empty until production finishes");
        });
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bullpupPlatformOffersReceiverAndStartsCommonReceiverProduction(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.BULLPUP.index()),
                "press must accept the bullpup platform");
        helper.assertTrue(GunsmithPlatform.BULLPUP.supportedParts().size() == 5,
                "bullpup press selection must contain exactly five parts");
        helper.assertTrue(GunsmithPlatform.BULLPUP.supportedParts().containsAll(java.util.List.of(
                        GunsmithPressPart.CORE, GunsmithPressPart.BARREL, GunsmithPressPart.HANDGUARD,
                        GunsmithPressPart.GRIP, GunsmithPressPart.RECEIVER)),
                "bullpup must support core, barrel, handguard, grip, and receiver");
        helper.assertFalse(GunsmithPlatform.BULLPUP.supports(GunsmithPressPart.BOLT),
                "bullpup must reject bolt");
        helper.assertFalse(GunsmithPlatform.BULLPUP.supports(GunsmithPressPart.STOCK),
                "bullpup must reject stock");
        assertIllegalCombination(helper, GunsmithPlatform.BULLPUP, GunsmithPressPart.BOLT);
        assertIllegalCombination(helper, GunsmithPlatform.BULLPUP, GunsmithPressPart.STOCK);

        int receiverRow = compactRow(GunsmithPlatform.BULLPUP, GunsmithPressPart.RECEIVER);
        helper.assertTrue(press.trySelectPart(receiverRow), "press must select the bullpup receiver by compact row");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.RECEIVER,
                "compact bullpup row must resolve to receiver");
        helper.assertTrue(press.selectedQuality() == GunsmithPartQuality.COMMON,
                "receiver production must start at common quality");

        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                new ItemStack(Items.IRON_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                new ItemStack(Items.COPPER_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                new ItemStack(Items.SLIME_BLOCK, 64));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        withGunsmithContext(10, 100000L, player, ledger -> {
            helper.assertTrue(press.tryStartPreview(player), "complete receiver materials must start the press");
            helper.assertTrue(press.isPressing(), "bullpup receiver production must put the press into its active state");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 58,
                    "common receiver production must consume six generic gun parts");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 58,
                    "common receiver production must consume six alloy units");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 59,
                    "common receiver production must consume five polymer units");
        });
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void marksmanPlatformOffersOrderedSixPartsAndStartsHandguardProduction(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.MARKSMAN.index()),
                "press must accept the marksman platform");
        helper.assertTrue(GunsmithPlatform.MARKSMAN.supportedParts().size() == 6,
                "marksman press selection must contain exactly six parts");
        helper.assertTrue(new java.util.ArrayList<>(GunsmithPlatform.MARKSMAN.supportedParts()).equals(java.util.List.of(
                        GunsmithPressPart.HANDGUARD, GunsmithPressPart.CORE, GunsmithPressPart.STOCK,
                        GunsmithPressPart.BOLT, GunsmithPressPart.BARREL, GunsmithPressPart.GRIP)),
                "marksman parts must retain the configured compact-row order");
        helper.assertTrue(GunsmithPlatform.MARKSMAN.supports(GunsmithPressPart.GRIP),
                "marksman must expose a grip slot");

        helper.assertTrue(press.trySelectPart(0), "first marksman compact row must select handguard");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.HANDGUARD,
                "first marksman compact row must resolve to handguard");

        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                new ItemStack(Items.IRON_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                new ItemStack(Items.COPPER_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                new ItemStack(Items.SLIME_BLOCK, 64));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        withGunsmithContext(10, 100000L, player, ledger -> {
            helper.assertTrue(press.tryStartPreview(player), "complete handguard materials must start the press");
            helper.assertTrue(press.isPressing(), "marksman handguard production must put the press into its active state");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 61,
                    "common handguard production must consume three generic gun parts");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 62,
                    "common handguard production must consume two alloy units");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 60,
                    "common handguard production must consume four polymer units");
        });
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sniperPlatformOffersOrderedFivePartsAndStartsFiringPinProduction(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.SNIPER.index()),
                "press must accept the sniper platform");
        helper.assertTrue(GunsmithPlatform.SNIPER.supportedParts().size() == 5,
                "sniper press selection must contain exactly five parts");
        helper.assertTrue(new java.util.ArrayList<>(GunsmithPlatform.SNIPER.supportedParts()).equals(java.util.List.of(
                        GunsmithPressPart.RECEIVER, GunsmithPressPart.STOCK, GunsmithPressPart.BARREL,
                        GunsmithPressPart.HANDGUARD, GunsmithPressPart.FIRING_PIN)),
                "sniper parts must retain the configured compact-row order");
        helper.assertFalse(GunsmithPlatform.SNIPER.supports(GunsmithPressPart.CORE),
                "sniper must not expose a core slot");
        helper.assertFalse(GunsmithPlatform.SNIPER.supports(GunsmithPressPart.GRIP),
                "sniper must not expose a grip slot");
        helper.assertFalse(GunsmithPlatform.SNIPER.supports(GunsmithPressPart.BOLT),
                "sniper must not expose a bolt slot");
        assertIllegalCombination(helper, GunsmithPlatform.SNIPER, GunsmithPressPart.CORE);
        assertIllegalCombination(helper, GunsmithPlatform.SNIPER, GunsmithPressPart.GRIP);
        assertIllegalCombination(helper, GunsmithPlatform.SNIPER, GunsmithPressPart.BOLT);

        helper.assertTrue(press.selectedPart() == GunsmithPressPart.RECEIVER,
                "first sniper compact row must resolve to receiver");
        helper.assertTrue(press.trySelectPart(compactRow(GunsmithPlatform.SNIPER,
                        GunsmithPressPart.FIRING_PIN)),
                "press must select the sniper firing pin by compact row");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.FIRING_PIN,
                "sniper firing-pin compact row must select firing pin");
        helper.assertTrue(press.selectedQuality() == GunsmithPartQuality.COMMON,
                "firing-pin production must start at common quality");

        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                new ItemStack(Items.IRON_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                new ItemStack(Items.COPPER_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                new ItemStack(Items.SLIME_BLOCK, 64));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        withGunsmithContext(10, 100000L, player, ledger -> {
            helper.assertTrue(press.tryStartPreview(player), "complete firing-pin materials must start the press");
            helper.assertTrue(press.isPressing(), "sniper firing-pin production must put the press into its active state");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 60,
                    "common firing-pin production must consume four generic gun parts");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 59,
                    "common firing-pin production must consume five alloy units");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 64,
                    "common firing-pin production must not consume polymer");
        });
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void machineGunPlatformOffersOrderedFivePartsAndStartsBipodProduction(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.MACHINE_GUN.index()),
                "press must accept the machine gun platform");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.HANDGUARD,
                "switching to machine gun must normalize to the first configured part");
        helper.assertTrue(new java.util.ArrayList<>(GunsmithPlatform.MACHINE_GUN.supportedParts()).equals(java.util.List.of(
                        GunsmithPressPart.HANDGUARD, GunsmithPressPart.BOLT, GunsmithPressPart.BARREL,
                        GunsmithPressPart.STOCK, GunsmithPressPart.BIPOD)),
                "machine gun parts must retain the configured compact-row order");
        helper.assertFalse(GunsmithPlatform.MACHINE_GUN.supports(GunsmithPressPart.CORE),
                "machine gun must not expose a core slot");
        helper.assertFalse(GunsmithPlatform.MACHINE_GUN.supports(GunsmithPressPart.GRIP),
                "machine gun must not expose a grip slot");
        helper.assertFalse(GunsmithPlatform.MACHINE_GUN.supports(GunsmithPressPart.RECEIVER),
                "machine gun must not expose a receiver slot");
        assertIllegalCombination(helper, GunsmithPlatform.MACHINE_GUN, GunsmithPressPart.CORE);
        assertIllegalCombination(helper, GunsmithPlatform.MACHINE_GUN, GunsmithPressPart.GRIP);
        assertIllegalCombination(helper, GunsmithPlatform.MACHINE_GUN, GunsmithPressPart.RECEIVER);

        helper.assertTrue(press.trySelectPart(compactRow(GunsmithPlatform.MACHINE_GUN, GunsmithPressPart.BIPOD)),
                "press must select the machine gun bipod by compact row");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.BIPOD,
                "machine gun bipod compact row must resolve to bipod");
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                new ItemStack(Items.IRON_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                new ItemStack(Items.COPPER_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                new ItemStack(Items.SLIME_BLOCK, 64));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        withGunsmithContext(10, 100000L, player, ledger -> {
            helper.assertTrue(press.tryStartPreview(player), "complete bipod materials must start the press");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 61,
                    "common bipod production must consume three generic gun parts");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 60,
                    "common bipod production must consume four alloy units");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 63,
                    "common bipod production must consume one polymer unit");
        });
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shotgunPlatformOffersOrderedFourPartsAndStartsBoltProduction(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.SHOTGUN.index()),
                "press must accept the shotgun platform");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.STOCK,
                "switching to shotgun must normalize to the first configured part");
        helper.assertTrue(new java.util.ArrayList<>(GunsmithPlatform.SHOTGUN.supportedParts()).equals(java.util.List.of(
                        GunsmithPressPart.STOCK, GunsmithPressPart.BARREL,
                        GunsmithPressPart.BOLT, GunsmithPressPart.HANDGUARD)),
                "shotgun parts must retain stock, barrel, bolt, handguard order");
        helper.assertFalse(GunsmithPlatform.SHOTGUN.supports(GunsmithPressPart.CORE),
                "shotgun must not expose a gas-system slot");
        helper.assertFalse(GunsmithPlatform.SHOTGUN.supports(GunsmithPressPart.GRIP),
                "shotgun must not expose a grip slot");
        helper.assertFalse(GunsmithPlatform.SHOTGUN.supports(GunsmithPressPart.RECEIVER),
                "shotgun must not expose a separate receiver slot");
        assertIllegalCombination(helper, GunsmithPlatform.SHOTGUN, GunsmithPressPart.CORE);
        assertIllegalCombination(helper, GunsmithPlatform.SHOTGUN, GunsmithPressPart.GRIP);
        assertIllegalCombination(helper, GunsmithPlatform.SHOTGUN, GunsmithPressPart.RECEIVER);

        helper.assertTrue(press.trySelectPart(compactRow(GunsmithPlatform.SHOTGUN, GunsmithPressPart.BOLT)),
                "press must select the shotgun bolt by compact row");
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                new ItemStack(Items.IRON_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                new ItemStack(Items.COPPER_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                new ItemStack(Items.SLIME_BLOCK, 64));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        withGunsmithContext(10, 100000L, player, ledger -> {
            helper.assertTrue(press.tryStartPreview(player), "complete bolt materials must start the shotgun press");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 58,
                    "common shotgun bolt production must consume six generic gun parts");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 59,
                    "common shotgun bolt production must consume five alloy units");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 64,
                    "common shotgun bolt production must consume no polymer");
        });
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void smgPlatformOffersOrderedFivePartsAndStartsReceiverProduction(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.SMG.index()),
                "press must accept the SMG platform");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.BARREL,
                "switching to SMG must normalize to the first configured part");
        helper.assertTrue(new java.util.ArrayList<>(GunsmithPlatform.SMG.supportedParts()).equals(java.util.List.of(
                        GunsmithPressPart.BARREL, GunsmithPressPart.STOCK, GunsmithPressPart.RECEIVER,
                        GunsmithPressPart.HANDGUARD, GunsmithPressPart.GRIP)),
                "SMG parts must retain barrel, stock, receiver, handguard, grip order");
        helper.assertFalse(GunsmithPlatform.SMG.supports(GunsmithPressPart.CORE),
                "SMG must not expose a gas-system slot");
        helper.assertFalse(GunsmithPlatform.SMG.supports(GunsmithPressPart.BOLT),
                "SMG must not expose a separate bolt slot");
        assertIllegalCombination(helper, GunsmithPlatform.SMG, GunsmithPressPart.CORE);
        assertIllegalCombination(helper, GunsmithPlatform.SMG, GunsmithPressPart.BOLT);

        helper.assertTrue(press.trySelectPart(compactRow(GunsmithPlatform.SMG, GunsmithPressPart.RECEIVER)),
                "press must select the SMG receiver by compact row");
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                new ItemStack(Items.IRON_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                new ItemStack(Items.COPPER_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                new ItemStack(Items.SLIME_BLOCK, 64));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        withGunsmithContext(10, 100000L, player, ledger -> {
            helper.assertTrue(press.tryStartPreview(player), "complete receiver materials must start the SMG press");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 58,
                    "common SMG receiver production must consume six generic gun parts");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 58,
                    "common SMG receiver production must consume six alloy units");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 59,
                    "common SMG receiver production must consume five polymer units");
        });
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void platformPartModelDataCoversFiveQualitiesWithoutChangingRifleCodes(GameTestHelper helper) {
        Map<GunsmithPressPart, Integer> pistolBases = new EnumMap<>(GunsmithPressPart.class);
        pistolBases.put(GunsmithPressPart.BARREL, 211);
        pistolBases.put(GunsmithPressPart.GRIP, 241);
        pistolBases.put(GunsmithPressPart.SLIDE, 261);
        pistolBases.put(GunsmithPressPart.TRIGGER, 271);
        pistolBases.put(GunsmithPressPart.HAMMER, 281);

        for (Map.Entry<GunsmithPressPart, Integer> entry : pistolBases.entrySet()) {
            for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
                ItemStack stack = GunsmithPartItem.createStack(
                        ModMunitionsItems.GUNSMITH_PART.get(), GunsmithPlatform.PISTOL, entry.getKey(), quality);
                helper.assertTrue(stack.getOrCreateTag().getInt("CustomModelData")
                                == entry.getValue() + quality.index(),
                        entry.getKey() + " " + quality + " must use its reserved pistol model code");
                helper.assertTrue(GunsmithPartItem.matches(stack, GunsmithPlatform.PISTOL, entry.getKey()),
                        "generated pistol part must decode to its platform and part");
            }
        }

        Map<GunsmithPressPart, Integer> bullpupBases = new EnumMap<>(GunsmithPressPart.class);
        bullpupBases.put(GunsmithPressPart.CORE, 301);
        bullpupBases.put(GunsmithPressPart.BARREL, 311);
        bullpupBases.put(GunsmithPressPart.HANDGUARD, 331);
        bullpupBases.put(GunsmithPressPart.GRIP, 341);
        bullpupBases.put(GunsmithPressPart.RECEIVER, 391);
        for (Map.Entry<GunsmithPressPart, Integer> entry : bullpupBases.entrySet()) {
            for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
                assertModelData(helper, GunsmithPlatform.BULLPUP, entry.getKey(), quality,
                        entry.getValue() + quality.index());
            }
        }

        Map<GunsmithPressPart, Integer> marksmanBases = new EnumMap<>(GunsmithPressPart.class);
        marksmanBases.put(GunsmithPressPart.HANDGUARD, 431);
        marksmanBases.put(GunsmithPressPart.CORE, 401);
        marksmanBases.put(GunsmithPressPart.STOCK, 451);
        marksmanBases.put(GunsmithPressPart.BOLT, 421);
        marksmanBases.put(GunsmithPressPart.BARREL, 411);
        marksmanBases.put(GunsmithPressPart.GRIP, 441);
        for (Map.Entry<GunsmithPressPart, Integer> entry : marksmanBases.entrySet()) {
            for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
                assertModelData(helper, GunsmithPlatform.MARKSMAN, entry.getKey(), quality,
                        entry.getValue() + quality.index());
            }
        }

        Map<GunsmithPressPart, Integer> sniperBases = new EnumMap<>(GunsmithPressPart.class);
        sniperBases.put(GunsmithPressPart.BARREL, 511);
        sniperBases.put(GunsmithPressPart.HANDGUARD, 531);
        sniperBases.put(GunsmithPressPart.STOCK, 551);
        sniperBases.put(GunsmithPressPart.RECEIVER, 591);
        sniperBases.put(GunsmithPressPart.FIRING_PIN, 10501);
        for (Map.Entry<GunsmithPressPart, Integer> entry : sniperBases.entrySet()) {
            for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
                assertModelData(helper, GunsmithPlatform.SNIPER, entry.getKey(), quality,
                        entry.getValue() + quality.index());
            }
        }

        Map<GunsmithPressPart, Integer> machineGunBases = new EnumMap<>(GunsmithPressPart.class);
        machineGunBases.put(GunsmithPressPart.BARREL, 611);
        machineGunBases.put(GunsmithPressPart.BOLT, 621);
        machineGunBases.put(GunsmithPressPart.HANDGUARD, 631);
        machineGunBases.put(GunsmithPressPart.STOCK, 651);
        machineGunBases.put(GunsmithPressPart.BIPOD, 701);
        for (Map.Entry<GunsmithPressPart, Integer> entry : machineGunBases.entrySet()) {
            for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
                assertModelData(helper, GunsmithPlatform.MACHINE_GUN, entry.getKey(), quality,
                        entry.getValue() + quality.index());
            }
        }

        Map<GunsmithPressPart, Integer> shotgunBases = new EnumMap<>(GunsmithPressPart.class);
        shotgunBases.put(GunsmithPressPart.BARREL, 711);
        shotgunBases.put(GunsmithPressPart.BOLT, 721);
        shotgunBases.put(GunsmithPressPart.HANDGUARD, 731);
        shotgunBases.put(GunsmithPressPart.STOCK, 751);
        for (Map.Entry<GunsmithPressPart, Integer> entry : shotgunBases.entrySet()) {
            for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
                assertModelData(helper, GunsmithPlatform.SHOTGUN, entry.getKey(), quality,
                        entry.getValue() + quality.index());
            }
        }

        Map<GunsmithPressPart, Integer> smgBases = new EnumMap<>(GunsmithPressPart.class);
        smgBases.put(GunsmithPressPart.BARREL, 811);
        smgBases.put(GunsmithPressPart.HANDGUARD, 831);
        smgBases.put(GunsmithPressPart.GRIP, 841);
        smgBases.put(GunsmithPressPart.STOCK, 851);
        smgBases.put(GunsmithPressPart.RECEIVER, 891);
        for (Map.Entry<GunsmithPressPart, Integer> entry : smgBases.entrySet()) {
            for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
                assertModelData(helper, GunsmithPlatform.SMG, entry.getKey(), quality,
                        entry.getValue() + quality.index());
            }
        }

        helper.assertTrue(GunsmithPlatform.byIndex(0) == GunsmithPlatform.AR,
                "AR platform index must remain zero");
        helper.assertTrue(GunsmithPlatform.byIndex(1) == GunsmithPlatform.AK,
                "AK platform index must remain one");
        helper.assertTrue(GunsmithPlatform.byIndex(2) == GunsmithPlatform.PISTOL,
                "pistol platform index must remain two");
        helper.assertTrue(GunsmithPlatform.byIndex(3) == GunsmithPlatform.BULLPUP,
                "bullpup platform index must remain three");
        helper.assertTrue(GunsmithPlatform.byIndex(4) == GunsmithPlatform.MARKSMAN,
                "marksman platform index must be four");
        helper.assertTrue(GunsmithPlatform.SNIPER.index() == 5,
                "sniper platform index must be five");
        helper.assertTrue(GunsmithPlatform.byIndex(5) == GunsmithPlatform.SNIPER,
                "sniper platform must decode from index five");
        helper.assertTrue(GunsmithPlatform.MACHINE_GUN.index() == 6,
                "machine gun platform index must be six");
        helper.assertTrue(GunsmithPlatform.byIndex(6) == GunsmithPlatform.MACHINE_GUN,
                "machine gun platform must decode from index six");
        helper.assertTrue(GunsmithPlatform.SHOTGUN.index() == 7,
                "shotgun platform index must be seven");
        helper.assertTrue(GunsmithPlatform.byIndex(7) == GunsmithPlatform.SHOTGUN,
                "shotgun platform must decode from index seven");
        helper.assertTrue(GunsmithPlatform.SMG.index() == 8,
                "SMG platform index must be eight");
        helper.assertTrue(GunsmithPlatform.byIndex(8) == GunsmithPlatform.SMG,
                "SMG platform must decode from index eight");
        helper.assertTrue(GunsmithPlatform.AR.supportedParts().size() == 6,
                "AR platform must retain its original six component slots");
        helper.assertFalse(GunsmithPlatform.AR.supports(GunsmithPressPart.RECEIVER),
                "AR platform must not expose a separate receiver slot");
        assertModelData(helper, GunsmithPlatform.AR, GunsmithPressPart.CORE, GunsmithPartQuality.COMMON, 1);
        assertModelData(helper, GunsmithPlatform.AR, GunsmithPressPart.STOCK, GunsmithPartQuality.LEGENDARY, 55);
        assertModelData(helper, GunsmithPlatform.AK, GunsmithPressPart.CORE, GunsmithPartQuality.COMMON, 101);
        assertModelData(helper, GunsmithPlatform.AK, GunsmithPressPart.STOCK, GunsmithPartQuality.LEGENDARY, 155);
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            ItemStack gehenna = GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(),
                    GunsmithPlatform.AR, GunsmithPressPart.CORE, quality, GunsmithPartVariant.GEHENNA_GAS);
            helper.assertTrue(gehenna.getOrCreateTag().getInt("CustomModelData") == 10001 + quality.index(),
                    "gehenna gas quality must use its reserved model code");
            ItemStack redEast = GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(),
                    GunsmithPlatform.AK, GunsmithPressPart.CORE, quality,
                    GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS);
            helper.assertTrue(redEast.getOrCreateTag().getInt("CustomModelData") == 10101 + quality.index(),
                    "red east gas quality must use its reserved model code");
            ItemStack mkAxA = GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(),
                    GunsmithPlatform.AR, GunsmithPressPart.BOLT, quality,
                    GunsmithPartVariant.MK_AX_A_BOLT);
            helper.assertTrue(mkAxA.getOrCreateTag().getInt("CustomModelData") == 10201 + quality.index(),
                    "MK-AX-A bolt quality must use its reserved model code");
            ItemStack threeRoundBurst = GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(),
                    GunsmithPlatform.AR, GunsmithPressPart.BOLT, quality,
                    GunsmithPartVariant.AR_THREE_ROUND_BURST_BOLT);
            helper.assertTrue(threeRoundBurst.getOrCreateTag().getInt("CustomModelData")
                            == 10401 + quality.index(),
                    "three-round-burst bolt quality must use its reserved model code");
            ItemStack trinitySniperBarrel = GunsmithPartItem.createStack(
                    ModMunitionsItems.GUNSMITH_PART.get(), GunsmithPlatform.SNIPER,
                    GunsmithPressPart.BARREL, quality,
                    GunsmithPartVariant.TRINITY_PRECISION_GRADUATED_SNIPER_BARREL);
            helper.assertTrue(trinitySniperBarrel.getOrCreateTag().getInt("CustomModelData")
                            == 10601 + quality.index(),
                    "Trinity sniper barrel quality must use its reserved model code");
        }
        ItemStack legacyMkAxA = GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(),
                GunsmithPlatform.AR, GunsmithPressPart.BOLT, GunsmithPartQuality.COMMON,
                GunsmithPartVariant.MK_AX_A_BOLT);
        legacyMkAxA.getOrCreateTag().putString("GunsmithPart", "receiver");
        legacyMkAxA.getOrCreateTag().putString("GunsmithVariant", "mk_ax_a_receiver");
        GunsmithPartItem.PartData migrated = GunsmithPartItem.requirePartData(legacyMkAxA);
        helper.assertTrue(migrated.part() == GunsmithPressPart.BOLT
                        && migrated.variant() == GunsmithPartVariant.MK_AX_A_BOLT,
                "legacy AR receiver item must migrate into the existing bolt slot");
        // 归一只作用于返回的 PartData: requirePartData 会被 tooltip 等客户端只读路径调用, 在只读路径上写 tag
        // 会让客户端副本单方面偏离服务端。故存量 NBT 必须逐字保持原样。
        helper.assertTrue("receiver".equals(legacyMkAxA.getOrCreateTag().getString("GunsmithPart"))
                        && "mk_ax_a_receiver".equals(legacyMkAxA.getOrCreateTag().getString("GunsmithVariant")),
                "reading a legacy MK-AX-A item must not rewrite its stored part/variant ids");
        assertIllegalCombination(helper, GunsmithPlatform.PISTOL, GunsmithPressPart.CORE);
        assertIllegalCombination(helper, GunsmithPlatform.AR, GunsmithPressPart.SLIDE);
        assertIllegalCombination(helper, GunsmithPlatform.MACHINE_GUN, GunsmithPressPart.SLIDE);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pressOffersPlatformSpecificGasForArAndAkCore(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);
        helper.assertTrue(press.trySelectVariant(GunsmithPartVariant.GEHENNA_GAS.index()),
                "AR/M4 core must accept the Gehenna gas variant");
        helper.assertTrue(press.selectedVariant() == GunsmithPartVariant.GEHENNA_GAS,
                "press must retain selected Gehenna gas");
        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.AK.index()),
                "press must select AK platform");
        helper.assertTrue(press.selectedVariant() == GunsmithPartVariant.BASE,
                "switching from AR to AK must clear the AR-only Gehenna gas");
        helper.assertTrue(press.trySelectVariant(GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS.index()),
                "AK core must accept red east gas");
        helper.assertTrue(press.selectedVariant() == GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS,
                "press must retain selected red east gas");
        helper.assertTrue(press.trySelectPart(compactRow(GunsmithPlatform.AK, GunsmithPressPart.BARREL)),
                "press must select AK barrel");
        helper.assertTrue(press.selectedVariant() == GunsmithPartVariant.BASE,
                "changing away from AK core must normalize the component variant");
        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.AR.index()),
                "press must switch back to AR platform");
        helper.assertTrue(press.trySelectPart(compactRow(GunsmithPlatform.AR, GunsmithPressPart.BOLT)),
                "press must select the existing AR bolt slot");
        helper.assertTrue(press.trySelectVariant(GunsmithPartVariant.MK_AX_A_BOLT.index()),
                "AR bolt slot must accept MK-AX-A");
        helper.assertTrue(press.selectedVariant() == GunsmithPartVariant.MK_AX_A_BOLT,
                "press must retain selected MK-AX-A bolt");
        helper.assertTrue(press.trySelectVariant(GunsmithPartVariant.AR_THREE_ROUND_BURST_BOLT.index()),
                "the same AR bolt slot must accept the three-round-burst bolt");
        helper.assertTrue(press.selectedVariant() == GunsmithPartVariant.AR_THREE_ROUND_BURST_BOLT,
                "press must retain the selected three-round-burst bolt");
        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.SNIPER.index()),
                "press must select the bolt-action rifle platform");
        helper.assertTrue(press.trySelectPart(compactRow(GunsmithPlatform.SNIPER, GunsmithPressPart.BARREL)),
                "press must select the sniper barrel slot");
        helper.assertTrue(press.trySelectVariant(
                        GunsmithPartVariant.TRINITY_PRECISION_GRADUATED_SNIPER_BARREL.index()),
                "sniper barrel slot must accept the Trinity precision-graduated variant");
        helper.assertTrue(press.selectedVariant()
                        == GunsmithPartVariant.TRINITY_PRECISION_GRADUATED_SNIPER_BARREL,
                "press must retain the selected Trinity sniper barrel");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pressRejectsWrongMaterialsAndGatesEachSlotByType(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        // 三槽只认对应材料, 且互不串 (审查 PRESS-MAT-01)。
        helper.assertFalse(press.inventory().isItemValid(
                        GunsmithPressBlockEntity.SLOT_GUN_PARTS, new ItemStack(Items.COBBLESTONE)),
                "gun-parts slot must reject cobblestone");
        helper.assertFalse(press.inventory().isItemValid(
                        GunsmithPressBlockEntity.SLOT_ALLOY, new ItemStack(Items.IRON_INGOT)),
                "alloy slot must reject iron (its material is copper)");
        helper.assertFalse(press.inventory().isItemValid(
                        GunsmithPressBlockEntity.SLOT_POLYMER, new ItemStack(Items.COPPER_INGOT)),
                "polymer slot must reject copper (its material is slime)");
        helper.assertTrue(press.inventory().isItemValid(
                        GunsmithPressBlockEntity.SLOT_ALLOY, new ItemStack(Items.COPPER_INGOT)),
                "alloy slot must accept copper");
        helper.assertTrue(press.inventory().isItemValid(
                        GunsmithPressBlockEntity.SLOT_POLYMER, new ItemStack(Items.SLIME_BLOCK)),
                "polymer slot must accept slime block");

        int receiverRow = compactRow(GunsmithPlatform.BULLPUP, GunsmithPressPart.RECEIVER);
        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.BULLPUP.index()),
                "press must accept the bullpup platform");
        helper.assertTrue(press.trySelectPart(receiverRow), "press must select the bullpup receiver");

        // 圆石冒充三槽 (RECEIVER 需 6/6/5) 严禁开工, 且不得消耗。
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                new ItemStack(Items.COBBLESTONE, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                new ItemStack(Items.COBBLESTONE, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                new ItemStack(Items.COBBLESTONE, 64));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        withGunsmithContext(10, 100000L, player, ledger -> {
            helper.assertFalse(press.tryStartPreview(player),
                    "cobblestone stuffed into every slot must not start a real gun-part press");
            helper.assertFalse(press.isPressing(), "rejected press must stay idle");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 64,
                    "rejected press must not consume the fake gun-parts material");

            // 换成正确材料 -> 正常开工并按 6/6/5 消耗。
            press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                    new ItemStack(Items.IRON_INGOT, 64));
            press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                    new ItemStack(Items.COPPER_INGOT, 64));
            press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                    new ItemStack(Items.SLIME_BLOCK, 64));
            helper.assertTrue(press.tryStartPreview(player),
                    "correct iron/copper/slime materials must start the receiver press");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 58,
                    "receiver press must consume six iron gun parts");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 58,
                    "receiver press must consume six copper alloy");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 59,
                    "receiver press must consume five slime polymer");
        });
        helper.succeed();
    }

    // ============================================================
    // 存量组件 NBT: 已发布的 "basic" / "gehenna_high_speed_gas" 型号别名是世界里 100% 存量组件唯一依赖的
    // 兼容钩子; 读取存量件一律不得回写 NBT (requirePartData 会被客户端渲染线程的 tooltip 调用)。
    // fixture 全部手写, 不经 createStack —— 用当前写入器造 fixture 就是拿被测实现当预言机, 存量形态零覆盖。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void legacyPartVariantAliasesReadAsBaseWithoutRewritingStoredNbt(GameTestHelper helper) {
        // 形态一: 型号字段问世之前冲压出的组件, 连 GunsmithVariant 都没有。
        ItemStack preVariant = legacyPartStack("ar", "core", "common", 1.00D, 1);
        helper.assertTrue(GunsmithPartItem.requirePartData(preVariant).variant() == GunsmithPartVariant.BASE,
                "a stored component without a variant field must read as the base component");
        helper.assertFalse(preVariant.getOrCreateTag().contains("GunsmithVariant"),
                "reading a pre-variant component must not write a variant field into it");

        // 形态二: 主线写出的普通组件, 型号 id 是已发布的 "basic", 并带当时的数据版本字段。
        ItemStack basicAlias = legacyPartStack("ar", "core", "common", 1.00D, 1);
        basicAlias.getOrCreateTag().putString("GunsmithVariant", "basic");
        basicAlias.getOrCreateTag().putInt("GunsmithPartDataVersion", 2);
        helper.assertTrue(GunsmithPartItem.requirePartData(basicAlias).variant() == GunsmithPartVariant.BASE,
                "the published \"basic\" variant id must still resolve to the base component");
        helper.assertTrue("basic".equals(basicAlias.getOrCreateTag().getString("GunsmithVariant")),
                "reading a \"basic\" component must not rewrite its stored variant id");
        helper.assertTrue(basicAlias.getOrCreateTag().getInt("GunsmithPartDataVersion") == 2,
                "reading a legacy component must leave its obsolete data-version field untouched");

        // 形态三: 主线写出的格赫娜导气核心, 型号 id 是已发布的 "gehenna_high_speed_gas"。
        ItemStack gehennaAlias = legacyPartStack("ar", "core", "legendary", 1.43D, 10005);
        gehennaAlias.getOrCreateTag().putString("GunsmithVariant", "gehenna_high_speed_gas");
        gehennaAlias.getOrCreateTag().putInt("GunsmithPartDataVersion", 2);
        GunsmithPartItem.PartData gehennaData = GunsmithPartItem.requirePartData(gehennaAlias);
        helper.assertTrue(gehennaData.variant() == GunsmithPartVariant.GEHENNA_GAS,
                "the published \"gehenna_high_speed_gas\" id must still resolve to the Gehenna gas component");
        helper.assertTrue(gehennaData.quality() == GunsmithPartQuality.LEGENDARY,
                "a legacy Gehenna component must keep its stored legendary quality");
        helper.assertTrue(Math.abs(gehennaData.coefficient() - 1.43D) < 0.0000001D,
                "a legacy Gehenna component must keep its stored coefficient");
        helper.assertTrue("gehenna_high_speed_gas".equals(
                        gehennaAlias.getOrCreateTag().getString("GunsmithVariant")),
                "reading a legacy Gehenna component must not rewrite its stored variant id");

        // 当前写入器: 型号写稳定 id, 不再写数据版本字段, 模型码 = 10000 + 品质序号 + 1 = 10005。
        ItemStack current = GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(),
                GunsmithPlatform.AR, GunsmithPressPart.CORE, GunsmithPartQuality.LEGENDARY,
                GunsmithPartVariant.GEHENNA_GAS);
        CompoundTag currentTag = current.getOrCreateTag();
        helper.assertTrue(currentTag.contains("GunsmithVariant", Tag.TAG_STRING)
                        && "gehenna_gas".equals(currentTag.getString("GunsmithVariant")),
                "new components must persist the current stable variant id");
        helper.assertFalse(currentTag.contains("GunsmithPartDataVersion"),
                "new components must not resurrect the obsolete part data version field");
        helper.assertTrue(currentTag.getInt("CustomModelData") == 10005,
                "legendary Gehenna gas must use model code 10005");
        helper.succeed();
    }

    // ============================================================
    // F048 冲压等级门: 品质档按 config 曲线解锁, 且服务端在开工帧重校 (不能靠别人选好的高品质开工)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pressQualitySelectionRejectsLockedTierAndServerRechecksAtStart(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        GunsmithPartQuality before = press.selectedQuality();
        helper.assertTrue(MunitionsConfig.QUALITY_UNLOCK_LEGENDARY.get() == 10,
                "本用例按传奇解锁等级 10 手算; 配置默认值一旦调整必须同步改本用例");

        IJobService lowJob = swapJob(new FixedLevelJobService(1));
        try {
            helper.assertFalse(press.trySelectQuality(GunsmithPartQuality.LEGENDARY.index(), player),
                    "L1 player must not select the LEGENDARY quality tier (F048 unlock L10)");
            helper.assertTrue(press.selectedQuality() == before,
                    "a rejected quality selection must leave the prior tier unchanged (no half-applied state)");
        } finally {
            restoreJob(lowJob);
        }

        IJobService highJob = swapJob(new FixedLevelJobService(10));
        try {
            helper.assertTrue(press.trySelectQuality(GunsmithPartQuality.LEGENDARY.index(), player),
                    "L10 player must select the LEGENDARY quality tier");
            helper.assertTrue(press.selectedQuality() == GunsmithPartQuality.LEGENDARY,
                    "accepted selection must persist LEGENDARY");
        } finally {
            restoreJob(highJob);
        }

        // 服务端权威重校: 高等级玩家已选好 LEGENDARY 后, 换回低等级玩家开工必须仍被挡, 且拒绝帧零消耗。
        stockPressMaterials(press);
        IJobService reDowngraded = swapJob(new FixedLevelJobService(1));
        try {
            helper.assertFalse(press.tryStartPreview(player),
                    "the server must re-check the quality gate at start time even though LEGENDARY is already selected");
            helper.assertFalse(press.isPressing(), "a level-gate rejection must not start the press");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 64,
                    "a rejected start must not consume gun parts (zero-cost rejection frame)");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 64,
                    "a rejected start must not consume alloy");
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 64,
                    "a rejected start must not consume polymer");
        } finally {
            restoreJob(reDowngraded);
        }
        helper.succeed();
    }

    // ============================================================
    // F048 + 审查 29 冲压工费 sink: 实扣 = 基数 x 品质材料倍率 x 稀有度加价倍率 (向上取整);
    // 余额差 1 CP 时全额作废且料槽零消耗 (先查后扣)。期望值全部按配置默认值手算成常量, 不回调被测公式。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pressWorkFeeScalesWithQualityAndRarityAndForfeitsWhenUnaffordable(GameTestHelper helper) {
        helper.assertTrue(MunitionsConfig.PRESS_WORK_FEE_CREDITS.get() == 200,
                "本用例的期望工费按基数 200 手算; 基数改了必须同步改本用例的 1400/800/200/500");
        helper.assertTrue(Math.abs(MunitionsConfig.PRESS_RARITY_FEE_MULTIPLIER_SPECIAL.get() - 2.5D) < 0.0000001D,
                "本用例的势力组件工费按 SPECIAL 倍率 2.5 手算; 倍率改了必须同步改 500");

        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        withGunsmithContext(10, 0L, player, ledger -> {
            // -- 场景 A: 余额恰好够付 PRECISION 基础组件工费 200 x 7 x 1.0 = 1400 -> 精确扣款到 0。
            GunsmithPressBlockEntity affordable = freshPress(helper);
            helper.assertTrue(affordable.trySelectQuality(GunsmithPartQuality.PRECISION.index(), player),
                    "L10 player selects PRECISION quality");
            stockPressMaterials(affordable);
            ledger.credit(player.getUUID(), Currency.CREDIT, 1400L);
            helper.assertTrue(affordable.tryStartPreview(player),
                    "sufficient balance must start the press and charge the PRECISION work fee");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 0L,
                    "PRECISION base component must destroy exactly 1400 CP, balance left "
                            + ledger.balance(player.getUUID(), Currency.CREDIT));

            // -- 场景 B: 余额比 MILSPEC 工费 (200 x 4 x 1.0 = 800) 差 1 CP -> 全额作废, 料槽/开工态/余额分文不动。
            // 场景 A 的台还在 isPressing() (同一 tick 未推进), 必须换一台真正空闲的 BE, 否则 tryStartPreview 会被
            // isPressing() 挡在工费门之前, 本条断言就测不到要测的东西。
            GunsmithPressBlockEntity unaffordable = freshPress(helper);
            helper.assertTrue(unaffordable.trySelectQuality(GunsmithPartQuality.MILSPEC.index(), player),
                    "L10 player selects MILSPEC quality");
            stockPressMaterials(unaffordable);
            ledger.credit(player.getUUID(), Currency.CREDIT, 799L);
            helper.assertFalse(unaffordable.tryStartPreview(player),
                    "a balance one credit short of the MILSPEC work fee must reject the press run");
            helper.assertFalse(unaffordable.isPressing(), "an unaffordable start must not enter the pressing state");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 799L,
                    "a failed fee charge must leave the balance untouched, got "
                            + ledger.balance(player.getUUID(), Currency.CREDIT));
            helper.assertTrue(unaffordable.inventory()
                            .getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 64,
                    "a failed fee charge must not consume gun parts");
            helper.assertTrue(unaffordable.inventory()
                            .getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 64,
                    "a failed fee charge must not consume alloy");
            helper.assertTrue(unaffordable.inventory()
                            .getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 64,
                    "a failed fee charge must not consume polymer");

            // -- 场景 C: 同品质 (COMMON) 下势力组件必须比基础组件贵。基础 200 x 1 x 1.0 = 200;
            // 格赫娜导气核心 200 x 1 x 2.5 = 500。留 499 时必须被挡, 补到 500 才放行且扣光。
            GunsmithPressBlockEntity special = freshPress(helper);
            helper.assertTrue(special.trySelectVariant(GunsmithPartVariant.GEHENNA_GAS.index()),
                    "AR core must accept the Gehenna gas variant");
            stockPressMaterials(special);
            // 场景 B 结束时账上还留着未被扣掉的 799 CP, 先清空再充到 499, 使本场景的起点是精确的"差 1 CP"。
            helper.assertTrue(ledger.tryDebit(player.getUUID(), Currency.CREDIT, 799L),
                    "scenario C setup must be able to drain the balance scenario B left untouched");
            ledger.credit(player.getUUID(), Currency.CREDIT, 499L);
            helper.assertFalse(special.tryStartPreview(player),
                    "499 CP must not cover the 500 CP special-rarity press fee");
            helper.assertTrue(special.inventory()
                            .getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 64,
                    "a rarity-priced fee rejection must not consume gun parts");
            ledger.credit(player.getUUID(), Currency.CREDIT, 1L);
            helper.assertTrue(special.tryStartPreview(player),
                    "exactly 500 CP must cover the special-rarity press fee");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 0L,
                    "the Gehenna press run must destroy exactly 500 CP, balance left "
                            + ledger.balance(player.getUUID(), Currency.CREDIT));
        });
        helper.succeed();
    }

    /**
     * 审查 29 的定价口径: 同槽 (AR/CORE) 同品质 (COMMON) 下, 势力组件的<b>信用点工费</b>严格高于基础组件,
     * 而<b>料量完全相同</b>。
     *
     * 两半都必须锁死。工费那半是审查 29 的正题 (势力组件与基础组件同价即定价缺陷); 料量那半是实现里一个
     * 刻意的取舍 (见 {@code GunsmithPressBlockEntity.requiredGunParts} 的注释): 料槽是单槽 64 上限, 传奇品质的
     * x10 倍率已经把枪管合金推到 70 顶穿上限, 再乘稀有度倍率会让高稀有度组件直接冲不出来, 变成隐性封禁而
     * 不是定价。没有这条断言, 后来人"顺手补齐"料量加价就会悄无声息地把高稀有度组件封死。
     *
     * 期望值全部按配置默认值手算: 基础 200 x 1 x 1.0 = 200 CP; 格赫娜 (SPECIAL) 200 x 1 x 2.5 = 500 CP;
     * AR 核心 COMMON 档的料量恒为 4 零件 / 6 合金 / 0 板材。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void specialRarityRaisesTheFeeWhileLeavingMaterialCostUntouched(GameTestHelper helper) {
        helper.assertTrue(MunitionsConfig.PRESS_WORK_FEE_CREDITS.get() == 200,
                "本用例按冲压工费基数 200 手算 200/500 两档");
        helper.assertTrue(Math.abs(MunitionsConfig.PRESS_RARITY_FEE_MULTIPLIER_STANDARD.get() - 1.0D) < 0.0000001D,
                "本用例按 STANDARD 稀有度倍率 1.0 手算 200 CP");
        helper.assertTrue(Math.abs(MunitionsConfig.PRESS_RARITY_FEE_MULTIPLIER_SPECIAL.get() - 2.5D) < 0.0000001D,
                "本用例按 SPECIAL 稀有度倍率 2.5 手算 500 CP");

        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        withGunsmithContext(10, 0L, player, ledger -> {
            // -- 基础组件: 200 CP 恰好够, 料吃 4/6/0。
            GunsmithPressBlockEntity standard = freshPress(helper);
            stockPressMaterials(standard);
            ledger.credit(player.getUUID(), Currency.CREDIT, 200L);
            helper.assertTrue(standard.tryStartPreview(player),
                    "200 CP 必须够付基础组件的冲压工费");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 0L,
                    "基础组件必须恰好销毁 200 CP, 余额实得 "
                            + ledger.balance(player.getUUID(), Currency.CREDIT));
            assertCoreMaterialDraw(helper, standard, "基础组件");

            // -- 势力组件: 同槽同品质, 拿着与基础组件完全一样的 200 CP 必须被挡 (工费严格更高)。
            GunsmithPressBlockEntity special = freshPress(helper);
            helper.assertTrue(special.trySelectVariant(GunsmithPartVariant.GEHENNA_GAS.index()),
                    "AR 核心槽必须接受格赫娜导气型号");
            stockPressMaterials(special);
            ledger.credit(player.getUUID(), Currency.CREDIT, 200L);
            helper.assertFalse(special.tryStartPreview(player),
                    "势力组件的工费必须严格高于基础组件: 够付基础档的 200 CP 不得放行 SPECIAL 档");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 200L,
                    "被工费挡下时余额必须分文不动, 实得 "
                            + ledger.balance(player.getUUID(), Currency.CREDIT));

            // -- 补到 500 CP: 开工, 而料量必须与基础组件逐个相等。
            ledger.credit(player.getUUID(), Currency.CREDIT, 300L);
            helper.assertTrue(special.tryStartPreview(player),
                    "500 CP 必须够付 SPECIAL 档的冲压工费");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 0L,
                    "势力组件必须恰好销毁 500 CP, 余额实得 "
                            + ledger.balance(player.getUUID(), Currency.CREDIT));
            assertCoreMaterialDraw(helper, special, "势力组件");
        });
        helper.succeed();
    }

    /**
     * 稀有度等级门 (审查 29): 选中态拿不到玩家, 服务端在开工那一帧做唯一权威判定, 拒绝帧必须零消耗零扣费。
     *
     * 用 L5 玩家: 普通品质档的解锁等级是 1, 早就过了, 唯一挡得住他的只有 SPECIAL 稀有度的 6 级门 ——
     * 于是本条测的确实是稀有度门, 而不是被品质门顺手挡住。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pressRarityGateRejectsLockedVariantWithoutConsumingMaterialsOrCredits(GameTestHelper helper) {
        helper.assertTrue(MunitionsConfig.QUALITY_UNLOCK_COMMON.get() == 1,
                "本用例靠普通品质档 1 级解锁来隔离出稀有度门; 该默认值改了必须同步改本用例");
        helper.assertTrue(MunitionsConfig.RARITY_UNLOCK_SPECIAL.get() == 6,
                "本用例按 SPECIAL 稀有度解锁等级 6 手算 (L5 拒 / L6 过)");

        GunsmithPressBlockEntity press = freshPress(helper);
        helper.assertTrue(press.trySelectVariant(GunsmithPartVariant.GEHENNA_GAS.index()),
                "AR 核心槽必须接受格赫娜导气型号");
        stockPressMaterials(press);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        withGunsmithContext(5, 100000L, player, ledger -> {
            helper.assertFalse(press.tryStartPreview(player),
                    "L5 玩家不得冲压 SPECIAL 稀有度的势力组件 (解锁 6 级)");
            helper.assertFalse(press.isPressing(), "被稀有度门挡下的请求不得让冲压机进入开工态");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 100000L,
                    "被稀有度门挡下时不得扣工费, 余额实得 "
                            + ledger.balance(player.getUUID(), Currency.CREDIT));
            helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 64
                            && press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 64
                            && press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 64,
                    "被稀有度门挡下时三个料槽必须一件不少");
        });

        // 正对照: 同一台机器、同一套选中态、同一批料, 只把玩家等级抬到 6 就必须放行 ——
        // 上面的拒绝因此确实来自稀有度门, 而不是材料/输出槽/忙碌这些别的判据。
        withGunsmithContext(6, 100000L, player, ledger -> {
            helper.assertTrue(press.tryStartPreview(player),
                    "正对照: L6 玩家必须能冲压 SPECIAL 稀有度组件");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 99500L,
                    "L6 放行后必须销毁 500 CP 的 SPECIAL 档工费, 余额实得 "
                            + ledger.balance(player.getUUID(), Currency.CREDIT));
        });
        helper.succeed();
    }

    /** AR 核心 COMMON 档的料量恒为 4 零件 / 6 合金 / 0 板材 (手算常量, 不回调 requiredGunParts 当预言机)。 */
    private static void assertCoreMaterialDraw(GameTestHelper helper, GunsmithPressBlockEntity press,
                                               String label) {
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 60,
                label + " 的 AR 核心必须只吃 4 件通用零件, 实得吃了 "
                        + (64 - press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount()));
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 58,
                label + " 的 AR 核心必须只吃 6 件合金, 实得吃了 "
                        + (64 - press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount()));
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 64,
                label + " 的 AR 核心不得消耗板材");
    }

    // ============================================================
    // 冲压端到端: 选中态存读往返 + 走完整条产线, 断言输出槽里真的躺着选中的势力组件 (而不是停在 isPressing())
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 20)
    public static void pressPersistsAndProducesSelectedGehennaVariant(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        helper.assertTrue(press.trySelectVariant(GunsmithPartVariant.GEHENNA_GAS.index()),
                "AR core press selection must accept the Gehenna gas variant");
        CompoundTag saved = new CompoundTag();
        press.saveAdditional(saved);
        helper.assertTrue("gehenna_gas".equals(saved.getString("SelectedVariant")),
                "press save data must persist the selected special variant");
        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.AK.index()),
                "press must allow switching away from AR before the load round trip");
        helper.assertTrue(press.selectedVariant() == GunsmithPartVariant.BASE,
                "switching to an incompatible platform must normalize the variant to base");
        press.load(saved);
        helper.assertTrue(press.selectedPlatform() == GunsmithPlatform.AR,
                "press load must restore the selected AR platform");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.CORE,
                "press load must restore the selected core slot");
        helper.assertTrue(press.selectedVariant() == GunsmithPartVariant.GEHENNA_GAS,
                "press load must restore the selected Gehenna variant");

        stockPressMaterials(press);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        withGunsmithContext(10, 100000L, player, ledger ->
                helper.assertTrue(press.tryStartPreview(player),
                        "complete materials must start Gehenna component production"));

        // COMMON 档要跑满 600 tick, GameTest 等不起: 存盘后把结束时刻改成当前 tick 再读回, 让下一帧 serverTick
        // 走完整条 finishPressRun 产出链路 (不绕过 createRolledStack)。
        CompoundTag inProgress = new CompoundTag();
        press.saveAdditional(inProgress);
        inProgress.putLong("ActiveUntilTick", Math.max(1L, helper.getLevel().getGameTime()));
        press.load(inProgress);
        helper.runAfterDelay(2, () -> {
            ItemStack output = press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_OUTPUT);
            helper.assertFalse(output.isEmpty(), "completed press run must create an output component");
            GunsmithPartItem.PartData data = GunsmithPartItem.requirePartData(output);
            helper.assertTrue(data.platform() == GunsmithPlatform.AR,
                    "pressed Gehenna component must retain the AR platform");
            helper.assertTrue(data.part() == GunsmithPressPart.CORE,
                    "pressed Gehenna component must retain the core slot");
            helper.assertTrue(data.quality() == GunsmithPartQuality.COMMON,
                    "pressed component must retain the selected common quality");
            helper.assertTrue(data.variant() == GunsmithPartVariant.GEHENNA_GAS,
                    "pressed output must retain the selected Gehenna variant, got " + data.variant().id());
            helper.assertTrue(output.getOrCreateTag().getInt("CustomModelData") == 10001,
                    "pressed common Gehenna gas must carry model code 10001, got "
                            + output.getOrCreateTag().getInt("CustomModelData"));
            helper.assertTrue(data.coefficient() >= 0.96D && data.coefficient() <= 1.04D,
                    "a rolled common component must land inside the common coefficient band, got "
                            + data.coefficient());
            helper.assertTrue(press.inventory()
                            .getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 60,
                    "common core production must consume four generic gun parts");
            helper.assertTrue(press.inventory()
                            .getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 58,
                    "common core production must consume six alloy units");
            helper.assertTrue(press.inventory()
                            .getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 64,
                    "common core production must not consume polymer");
            helper.succeed();
        });
    }

    /**
     * 手写一件存量组件的 NBT (不带型号字段)。fixture 与被测实现异源是硬要求: 用 {@code createStack} 造再
     * 改字段, 等于拿 PR 自己的写入器当真相来源, 线上真实存在的旧字段与旧型号 id 一条都覆盖不到。
     */
    private static ItemStack legacyPartStack(String platformId, String partId, String qualityId,
                                             double coefficient, int customModelData) {
        ItemStack stack = new ItemStack(ModMunitionsItems.GUNSMITH_PART.get());
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString("GunsmithPlatform", platformId);
        tag.putString("GunsmithPart", partId);
        tag.putString("GunsmithQuality", qualityId);
        tag.putDouble("GunsmithCoefficient", coefficient);
        tag.putInt("CustomModelData", customModelData);
        return stack;
    }

    /**
     * 在同一坐标上换出一台真正空闲的冲压机。同类型方块重新 setBlock 不会销毁旧 BE (vanilla 只在 Block
     * 类型变化时才重建), 故先落 AIR 再落回冲压机。
     */
    private static GunsmithPressBlockEntity freshPress(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, Blocks.AIR.defaultBlockState());
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        return requirePress(helper);
    }

    private static void stockPressMaterials(GunsmithPressBlockEntity press) {
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                new ItemStack(Items.IRON_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                new ItemStack(Items.COPPER_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                new ItemStack(Items.SLIME_BLOCK, 64));
    }

    /**
     * 装/卸「定级职业门面 + 内存账本经济」替身, 跑完即还原。
     *
     * 冲压开工要过 F048 等级门与信用点工费 sink, 而批次基线把这两项配置留在生产默认值, 所以每个真开工的
     * 用例都得自带够级别、够余额的玩家上下文。集中在这里装卸, 免得各用例各写一遍 finally 时漏还原, 污染
     * 同批次后续用例。
     */
    private static void withGunsmithContext(int munitionsLevel, long credits, ServerPlayer player,
                                            Consumer<EconomyLedger> scenario) {
        IJobService previousJob = swapJob(new FixedLevelJobService(munitionsLevel));
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService previousEconomy = swapEconomy(freshEconomy(ledger));
        try {
            if (credits > 0L) {
                ledger.credit(player.getUUID(), Currency.CREDIT, credits);
            }
            scenario.accept(ledger);
        } finally {
            restoreJob(previousJob);
            restoreEconomy(previousEconomy);
        }
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

    /** 真 EconomyService (内存账本 + AbuseGuard + 惰性玩家态解析器); tryCharge 走真 sink 语义。 */
    private static IEconomyService freshEconomy(EconomyLedger ledger) {
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        Function<UUID, PlayerAbuseState> resolver = id -> states.computeIfAbsent(id, key -> new PlayerAbuseState());
        return new EconomyService(ledger, new AbuseGuard(), resolver);
    }

    /** 定级职业门面替身 (level/grantXp 不计数, 仅供枪匠等级门读取)。 */
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
            throw new UnsupportedOperationException("not exercised by gunsmith press tests");
        }
    }

    private static GunsmithPressBlockEntity requirePress(GameTestHelper helper) {
        if (helper.getLevel().getBlockEntity(helper.absolutePos(PRESS_REL))
                instanceof GunsmithPressBlockEntity press) {
            return press;
        }
        throw new IllegalStateException("gunsmith press block entity missing");
    }

    private static int compactRow(GunsmithPlatform platform, GunsmithPressPart wanted) {
        int row = 0;
        for (GunsmithPressPart part : platform.supportedParts()) {
            if (part == wanted) {
                return row;
            }
            row++;
        }
        throw new IllegalArgumentException("part is not supported by platform: " + wanted);
    }

    private static void assertModelData(GameTestHelper helper, GunsmithPlatform platform,
                                        GunsmithPressPart part, GunsmithPartQuality quality, int expected) {
        ItemStack stack = GunsmithPartItem.createStack(
                ModMunitionsItems.GUNSMITH_PART.get(), platform, part, quality);
        helper.assertTrue(stack.getOrCreateTag().getInt("CustomModelData") == expected,
                platform + " " + part + " " + quality + " model code must remain " + expected);
    }

    private static void assertIllegalCombination(GameTestHelper helper, GunsmithPlatform platform,
                                                 GunsmithPressPart part) {
        boolean threw = false;
        try {
            GunsmithPartItem.createStack(
                    ModMunitionsItems.GUNSMITH_PART.get(), platform, part, GunsmithPartQuality.COMMON);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, platform + " must reject unsupported part " + part);
    }
}
