package com.miningdim.job.munitions.gunsmith;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.IJobService;
import com.miningdim.job.JobId;
import com.miningdim.job.JobProgress;
import com.miningdim.job.JobServices;
import com.miningdim.job.munitions.ModMunitionsBlocks;
import com.miningdim.job.munitions.ModMunitionsItems;
import com.miningdim.job.munitions.MunitionsConfig;
import com.miningdim.job.munitions.block.GunsmithAssemblyBenchBlock;
import com.miningdim.job.munitions.block.GunsmithAssemblyBenchBlockEntity;
import com.miningdim.testutil.ConfigBaseline;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 枪匠组件平衡批次 (平衡方案 E4-E8、E11) 的回归用例。
 *
 * 期望值一律按方案原文与零件系数手算成常量, 不回调被测实现当预言机。dev GameTest 不加载 TaCZ: 成品枪用铁锄
 * 装配出的枪匠 NBT 代替, TaCZ 的 allow_attachments 标签只核对资源文件本身, 标签生效与否在测试端配件界面实测。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GunsmithComponentBalanceGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "gunsmith_component_balance";
    private static final BlockPos MAIN_REL = new BlockPos(1, 1, 1);
    private static final GunsmithBaseStats M4_BASE_STATS = new GunsmithBaseStats(6.5D, 1.5D, 48.0D, 0.16D);
    private static final GunsmithBaseStats AK47_BASE_STATS = new GunsmithBaseStats(9.0D, 1.5D, 52.0D, 0.20D);
    private static final double LEGENDARY_MID = 1.43D;

    /** 枪匠枪包 (导出给 TaCZ 的 gunpack) 里 index/guns 与 allow_attachments 标签的根目录。 */
    private static final String GUNPACK_DATA = "assets/miningdim/custom/miningdim_gunsmith/data/miningdim";

    /**
     * TaCZ 1.1.8 默认枪包里四把源枪的 allow_attachments 标签, 逐条抄成常量 (E7)。枪匠成品 id 必须与源枪一致:
     * TaCZ AllowAttachmentTagMatcher 在标签为空时一律判"不许装", 缺标签的成品连瞄具都装不上。
     */
    private static final Set<String> M4A1_ATTACHMENTS = Set.of("#tacz:scope_scope", "#tacz:scope_sight",
            "#tacz:muzzle", "#tacz:extended_mag", "#tacz:grip", "#tacz:bayonet_ar", "#tacz:stock",
            "#tacz:ammo_mod_no_he", "#tacz:ar_laser", "#tacz:pistol_laser");
    private static final Set<String> HK416D_ATTACHMENTS = Set.of("#tacz:scope_scope", "#tacz:scope_sight",
            "#tacz:muzzle", "#tacz:extended_mag", "#tacz:stock", "#tacz:grip", "#tacz:ammo_mod_no_he",
            "#tacz:ar_laser", "#tacz:pistol_laser");
    private static final Set<String> M16A1_ATTACHMENTS = Set.of("#tacz:muzzle", "#tacz:extended_mag",
            "#tacz:bayonet_ar", "#tacz:m16a1_scope", "#tacz:ammo_mod_no_he");
    private static final Set<String> M16A4_ATTACHMENTS = Set.of("#tacz:scope", "#tacz:muzzle",
            "#tacz:extended_mag", "#tacz:bayonet_ar", "#tacz:stock", "#tacz:grip", "#tacz:ammo_mod_no_he",
            "#tacz:ar_laser", "#tacz:pistol_laser", "#tacz:scope_lowsight");
    private static final Map<String, Set<String>> EXPECTED_ATTACHMENTS = Map.of(
            "m4a1_gunsmith", M4A1_ATTACHMENTS,
            "m4a1_gunsmith_burst", M4A1_ATTACHMENTS,
            "hk416d_gunsmith_burst", HK416D_ATTACHMENTS,
            "m16a1_gunsmith_burst", M16A1_ATTACHMENTS,
            "m16a4_gunsmith_burst", M16A4_ATTACHMENTS);

    private GunsmithComponentBalanceGameTests() {
    }

    /** 本批次的装配台用例会临时打开枪匠开关, 先把上一轮可能残留的值归位 (见 {@link ConfigBaseline})。 */
    @BeforeBatch(batch = BATCH)
    public static void resetConfigBaseline(ServerLevel level) {
        ConfigBaseline.resetToDefaults(MunitionsConfig.GUNSMITH_ENABLED, MunitionsConfig.ASSEMBLY_UNLOCK_LEVEL);
    }

    // ============================================================
    // E4 红东高压导气 / E5 赤雪-A: 规则表落地后的派生效果
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void redEastGasIsNeverANetDpsLossAndOnlyLegendaryHitsTheCap(GameTestHelper helper) {
        assertCapAnchors(helper);
        GunsmithPartVariant redEast = GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS;
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            double damage = redEast.damageMultiplier(quality);
            // 旧表普通档 1.20 x 0.75 = 0.90, 装上反而掉输出; 新表最低 1.36 x 0.75 = 1.02。
            helper.assertTrue(damage * redEast.fireRateMultiplier(quality) > 1.0D,
                    "red east " + quality.id() + " damage x fire rate must stay above 1.0, got "
                            + damage * redEast.fireRateMultiplier(quality));
            // 传奇枪机中值 1.43 下, 红东只有传奇档越过 2.25 总帽 (1.43 x 1.54 = 2.2022, 1.43 x 1.60 = 2.288);
            // 旧表军规档 1.43 x 1.60 就已撞帽, 再往上的品质全是白给。
            helper.assertTrue((LEGENDARY_MID * damage > 2.25D) == (quality == GunsmithPartQuality.LEGENDARY),
                    "with a mid legendary bolt only the legendary red east gas may exceed the cap, "
                            + quality.id() + " -> " + LEGENDARY_MID * damage);
        }

        // 普通枪机 + 普通红东: 9 x 1.00 x 1.36 = 12.24 (方案 E4: 旧值 10.8)。
        GunsmithGunStats common = GunsmithGunStats.from(assemble(GunsmithBlueprint.AK47, akParts(
                GunsmithPartQuality.COMMON, 1.00D, GunsmithPartVariant.BASE,
                GunsmithPartQuality.COMMON, GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS)));
        helper.assertTrue(common != null, "common red east AK must be readable");
        assertClose(helper, common.effectiveDamage(AK47_BASE_STATS), 12.24D, "common bolt + common red east damage");

        // 传奇枪机中值 + 传奇红东: 1.43 x 1.60 = 2.288, 钳到 2.25, 即 9 x 2.25 = 20.25 (方案 E4: 不变)。
        GunsmithGunStats legendary = GunsmithGunStats.from(assemble(GunsmithBlueprint.AK47, akParts(
                GunsmithPartQuality.LEGENDARY, LEGENDARY_MID, GunsmithPartVariant.BASE,
                GunsmithPartQuality.LEGENDARY, GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS)));
        helper.assertTrue(legendary != null, "legendary red east AK must be readable");
        assertClose(helper, legendary.effectiveDamage(AK47_BASE_STATS), 20.25D,
                "legendary bolt + legendary red east must stay clamped at 9 x 2.25");
        // 传奇导气核心中值 1.43 x 射程 0.75 = 1.0725: 首段射程由 52 x 0.858 放宽到 52 x 1.0725。
        assertClose(helper, legendary.effectiveRange(AK47_BASE_STATS), 52.0D * 1.0725D,
                "legendary red east effective range must use the 0.75 penalty");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chixueBoltScalesWithQualityAndMilspecNoLongerBeatsALegendaryBaseBolt(GameTestHelper helper) {
        GunsmithPartVariant chixue = GunsmithPartVariant.RED_WINTER_CHIXUE_A_BOLT;
        // 旧表五档恒 1.25: 军规中值 1.175 x 1.25 = 1.469, 高于传奇基础枪机中值 1.43, 军规档就拿满收益。
        assertClose(helper, GunsmithPartQuality.MILSPEC.midpointCoefficient()
                        * chixue.damageMultiplier(GunsmithPartQuality.MILSPEC), 1.175D * 1.15D,
                "milspec Chixue-A compound must use the 1.15 table value");
        helper.assertTrue(GunsmithPartQuality.MILSPEC.midpointCoefficient()
                        * chixue.damageMultiplier(GunsmithPartQuality.MILSPEC) < LEGENDARY_MID,
                "a mid milspec Chixue-A bolt must not out-damage a mid legendary base bolt");
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            assertClose(helper, chixue.armorIgnoreMultiplier(quality), 0.75D,
                    "Chixue-A armor penetration penalty must stay fixed at " + quality.id());
            assertClose(helper, chixue.recoilMultiplier(quality), 1.35D,
                    "Chixue-A recoil penalty must stay fixed at " + quality.id());
        }

        // 普通档: 9 x 1.00 x 1.05 = 9.45 (方案 E5: 旧值 11.25)。
        EnumMap<GunsmithPressPart, ItemStack> parts = akParts(GunsmithPartQuality.COMMON, 1.00D,
                GunsmithPartVariant.RED_WINTER_CHIXUE_A_BOLT, GunsmithPartQuality.COMMON, GunsmithPartVariant.BASE);
        GunsmithGunStats common = GunsmithGunStats.from(assemble(GunsmithBlueprint.AK47, parts));
        helper.assertTrue(common != null, "common Chixue-A AK must be readable");
        assertClose(helper, common.effectiveDamage(AK47_BASE_STATS), 9.45D, "common Chixue-A damage");
        helper.succeed();
    }

    // ============================================================
    // E6 红东导气与赤雪-A 互斥
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void exclusionTableIsSymmetricAndOnlyPairsRedEastWithChixue(GameTestHelper helper) {
        Set<String> excludedPairs = new HashSet<>();
        for (GunsmithPartVariant first : GunsmithPartVariant.values()) {
            for (GunsmithPartVariant second : GunsmithPartVariant.values()) {
                helper.assertTrue(first.excludes(second) == second.excludes(first),
                        "exclusion must be symmetric: " + first.id() + " / " + second.id());
                if (first.excludes(second)) {
                    excludedPairs.add(first.id() + "+" + second.id());
                }
            }
        }
        helper.assertTrue(excludedPairs.equals(Set.of(
                        "red_east_high_pressure_gas+red_winter_chixue_a_bolt",
                        "red_winter_chixue_a_bolt+red_east_high_pressure_gas")),
                "only red east gas and Chixue-A may exclude each other, got " + excludedPairs);
        helper.assertFalse(GunsmithPartVariant.GEHENNA_GAS.excludes(GunsmithPartVariant.AR_THREE_ROUND_BURST_BOLT),
                "Gehenna gas on a burst bolt is flagged in the tooltip, not refused at the bench");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void assemblyAndPreviewRejectRedEastWithChixue(GameTestHelper helper) {
        EnumMap<GunsmithPressPart, ItemStack> parts = akParts(GunsmithPartQuality.LEGENDARY, LEGENDARY_MID,
                GunsmithPartVariant.RED_WINTER_CHIXUE_A_BOLT,
                GunsmithPartQuality.LEGENDARY, GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS);

        GunsmithAssemblyRecipe.VariantConflict conflict =
                GunsmithAssemblyRecipe.findVariantConflict(GunsmithBlueprint.AK47, parts);
        helper.assertTrue(conflict != null, "red east gas + Chixue-A must be reported as a conflict");
        helper.assertTrue(conflict.firstPart() == GunsmithPressPart.CORE
                        && conflict.first() == GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS
                        && conflict.secondPart() == GunsmithPressPart.BOLT
                        && conflict.second() == GunsmithPartVariant.RED_WINTER_CHIXUE_A_BOLT,
                "the conflict must name both components in blueprint slot order, got " + conflict);
        assertConflictMessage(helper, conflict.message());

        GunsmithAssemblyRecipe.Preview preview =
                GunsmithAssemblyRecipe.preview(GunsmithBlueprint.AK47, parts, AK47_BASE_STATS);
        helper.assertTrue(preview.rejected() && preview.conflict() != null
                        && preview.conflict().equals(conflict),
                "the assembly preview must carry the same rejection as the bench");

        boolean threw = false;
        try {
            assemble(GunsmithBlueprint.AK47, parts);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, "assemble must refuse to write a mutually exclusive component pair");

        // 正对照: 只留红东、枪机换回基础件, 预览不拒、能装。
        EnumMap<GunsmithPressPart, ItemStack> redEastOnly = akParts(GunsmithPartQuality.LEGENDARY, LEGENDARY_MID,
                GunsmithPartVariant.BASE, GunsmithPartQuality.LEGENDARY, GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS);
        helper.assertTrue(GunsmithAssemblyRecipe.findVariantConflict(GunsmithBlueprint.AK47, redEastOnly) == null,
                "red east gas alone must not be reported as a conflict");
        helper.assertFalse(GunsmithAssemblyRecipe.preview(GunsmithBlueprint.AK47, redEastOnly, AK47_BASE_STATS)
                .rejected(), "a preview without a conflicting pair must not be rejected");
        helper.assertTrue(GunsmithGunStats.from(assemble(GunsmithBlueprint.AK47, redEastOnly)) != null,
                "red east gas alone must still assemble");
        helper.succeed();
    }

    /**
     * 装配台开工前拒绝互斥组合并告诉玩家原因; 拒绝帧不吞零件、不开动画。
     *
     * 走不带造枪函数的公开入口: dev GameTest 没有 TaCZ, 真造枪会停在"TaCZ 数据缺失"。互斥检查排在造枪之前,
     * 所以本用例收到的必须是互斥文案; 正对照换掉一件后, 同一入口收到的是 TaCZ 缺失文案, 证明文案确实来自互斥判定。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void assemblyBenchRefusesMutuallyExclusiveComponentsAndExplainsWhy(GameTestHelper helper) {
        placeBench(helper);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        be.inventory().setStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT,
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.AK47));
        akParts(GunsmithPartQuality.LEGENDARY, LEGENDARY_MID, GunsmithPartVariant.RED_WINTER_CHIXUE_A_BOLT,
                GunsmithPartQuality.LEGENDARY, GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS)
                .forEach((part, stack) -> be.inventory().setStackInSlot(
                        GunsmithAssemblyBenchBlockEntity.slotForPart(part), stack));

        List<Component> messages = new ArrayList<>();
        ServerPlayer player = recordingPlayer(helper, messages);
        boolean previousEnabled = MunitionsConfig.GUNSMITH_ENABLED.get();
        IJobService previousJob = swapJob(new FixedLevelJobService(10));
        MunitionsConfig.GUNSMITH_ENABLED.set(true);
        try {
            // 进服流程里别的系统也可能给玩家发提示, 只看开工这一下收到的。
            messages.clear();
            helper.assertFalse(be.tryStartAssembly(player), "the bench must refuse red east gas + Chixue-A");
            helper.assertFalse(be.isAnimating(), "a refused assembly must not animate");
            for (GunsmithPressPart part : GunsmithBlueprint.AK47.requiredParts()) {
                helper.assertFalse(be.inventory().getStackInSlot(
                                GunsmithAssemblyBenchBlockEntity.slotForPart(part)).isEmpty(),
                        "a refused assembly must keep " + part.id());
            }
            helper.assertTrue(be.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT).isEmpty(),
                    "a refused assembly must not create output");
            helper.assertTrue(messages.size() == 1, "the bench must explain the refusal once, got " + messages);
            assertConflictMessage(helper, messages.get(0));

            // 正对照: 枪机换成同品质基础件, 互斥解除, 同一入口改为停在 TaCZ 缺失。
            messages.clear();
            be.inventory().setStackInSlot(GunsmithAssemblyBenchBlockEntity.slotForPart(GunsmithPressPart.BOLT),
                    part(GunsmithPlatform.AK, GunsmithPressPart.BOLT, GunsmithPartQuality.LEGENDARY,
                            GunsmithPartVariant.BASE, LEGENDARY_MID));
            helper.assertFalse(be.tryStartAssembly(player), "dev GameTest has no TaCZ to build the gun");
            helper.assertTrue(messages.size() == 1
                            && messages.get(0).getContents() instanceof TranslatableContents taczMissing
                            && "message.miningdim.gunsmith_blueprint.tacz_missing".equals(taczMissing.getKey()),
                    "without the conflicting pair the bench must reach the TaCZ step, got " + messages);
        } finally {
            MunitionsConfig.GUNSMITH_ENABLED.set(previousEnabled);
            restoreJob(previousJob);
        }
        helper.succeed();
    }

    /** 互斥只约束新装配: 互斥落地前装出来的双加伤 AK 仍可读、可维修, 伤害照旧被总帽钳住。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void legacyDoubleSpecialAkStaysReadableAndCapped(GameTestHelper helper) {
        assertCapAnchors(helper);
        ItemStack gun = assemble(GunsmithBlueprint.AK47, akParts(GunsmithPartQuality.LEGENDARY, 1.50D,
                GunsmithPartVariant.BASE, GunsmithPartQuality.LEGENDARY, GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS));
        // 模拟互斥落地前的存量枪: 只把枪机型号改成赤雪-A, 品质与系数不动 (缓存里的伤害仍是枪机系数 1.50)。
        CompoundTag bolt = gun.getOrCreateTag().getCompound(GunsmithGunStats.ROOT_KEY)
                .getCompound(GunsmithGunStats.PARTS_KEY).getCompound(GunsmithPressPart.BOLT.id());
        bolt.putString("variant", GunsmithPartVariant.RED_WINTER_CHIXUE_A_BOLT.id());

        GunsmithGunStats stats = GunsmithGunStats.from(gun);
        helper.assertTrue(stats != null, "a pre-exclusion double-special AK must stay readable");
        // 1.50 x 1.60 x 1.25 = 3.00, 钳到 2.25。
        assertClose(helper, stats.damage(), 2.25D, "the double-special AK must stay clamped by the total cap");
        helper.assertTrue(GunsmithGunDurability.isManagedGun(gun),
                "the double-special AK must remain a managed (repairable) gun");
        helper.succeed();
    }

    // ============================================================
    // E7 三连发与旧模板 id 的配件标签
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyGunsmithGunIdCarriesItsSourceGunAttachmentTags(GameTestHelper helper) {
        Set<String> registeredGunIds;
        try (Stream<Path> index = Files.list(gunpackPath("index/guns"))) {
            registeredGunIds = index.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .collect(Collectors.toSet());
        } catch (IOException exception) {
            throw new IllegalStateException("gunsmith gunpack index is unreadable", exception);
        }
        helper.assertTrue(registeredGunIds.equals(EXPECTED_ATTACHMENTS.keySet()),
                "every gun the gunsmith gunpack registers needs an expected attachment list here, registered "
                        + registeredGunIds);
        for (GunsmithBlueprint blueprint : GunsmithBlueprint.values()) {
            if (blueprint.platform() == GunsmithPlatform.AR) {
                helper.assertTrue(registeredGunIds.contains(GunsmithGunFactory.burstGunId(blueprint).getPath()),
                        blueprint + " burst route must be a registered gunpack gun");
            }
        }
        helper.assertTrue(registeredGunIds.contains(GunsmithGunFactory.M4A1_ID.getPath()),
                "the legacy M4 template gun id must stay registered");

        for (Map.Entry<String, Set<String>> expected : EXPECTED_ATTACHMENTS.entrySet()) {
            Path tag = gunpackPath("tacz_tags/attachments/allow_attachments/" + expected.getKey() + ".json");
            helper.assertTrue(Files.isRegularFile(tag), expected.getKey() + " must ship an allow_attachments tag");
            JsonArray entries;
            try {
                entries = JsonParser.parseString(Files.readString(tag, StandardCharsets.UTF_8)).getAsJsonArray();
            } catch (IOException exception) {
                throw new IllegalStateException("unreadable attachment tag " + tag, exception);
            }
            List<String> actual = new ArrayList<>();
            for (JsonElement entry : entries) {
                actual.add(entry.getAsString());
            }
            helper.assertTrue(actual.size() == new HashSet<>(actual).size(),
                    expected.getKey() + " attachment tag must not repeat entries: " + actual);
            helper.assertTrue(new HashSet<>(actual).equals(expected.getValue()),
                    expected.getKey() + " must allow exactly its source gun attachments, got " + actual);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gehennaGasOnABurstGunIsShownAsIneffective(GameTestHelper helper) {
        EnumMap<GunsmithPressPart, ItemStack> burstParts = arParts(GunsmithPartQuality.LEGENDARY);
        burstParts.put(GunsmithPressPart.CORE, part(GunsmithPlatform.AR, GunsmithPressPart.CORE,
                GunsmithPartQuality.LEGENDARY, GunsmithPartVariant.GEHENNA_GAS, LEGENDARY_MID));
        burstParts.put(GunsmithPressPart.BOLT, part(GunsmithPlatform.AR, GunsmithPressPart.BOLT,
                GunsmithPartQuality.LEGENDARY, GunsmithPartVariant.AR_THREE_ROUND_BURST_BOLT, LEGENDARY_MID));
        GunsmithGunStats burst = GunsmithGunStats.from(assemble(GunsmithBlueprint.M4A1, burstParts));
        helper.assertTrue(burst != null && burst.forcesBurstFireMode(), "Gehenna + burst bolt M4 must be a burst gun");
        assertClose(helper, burst.componentFireRate(), 1.25D, "the Gehenna component fire rate stays on record");
        assertClose(helper, burst.fireRate(), 1.0D, "a forced-burst gun must report no effective fire-rate change");
        assertClose(helper, GunsmithStatMultipliers.of(burst, 1.80D).fireRate(), 1.0D,
                "a forced-burst gun must not push the dead Gehenna multiplier into the RPM cache");
        assertClose(helper, GunsmithAssemblyRecipe.preview(GunsmithBlueprint.M4A1, burstParts, M4_BASE_STATS)
                .fireRateChange(), 0.0D, "the preview fire-rate row of a forced-burst gun must read +0%");

        Component burstRate = specialGasFireRate(helper, burst);
        helper.assertTrue(burstRate.getContents() instanceof TranslatableContents translatable
                        && GunsmithGunTooltip.FIRE_RATE_BURST_INEFFECTIVE_KEY.equals(translatable.getKey())
                        && translatable.getArgs().length == 1 && "+25%".equals(String.valueOf(translatable.getArgs()[0])),
                "the burst gun fire-rate cell must read \"+25% (ineffective in burst)\", got " + burstRate);
        helper.assertTrue(TextColor.fromLegacyFormat(ChatFormatting.RED).equals(burstRate.getStyle().getColor()),
                "the ineffective fire-rate cell must be red");

        // 正对照: 同一件格赫娜导气装在普通枪机上, 射速照常 +25% 且标绿。
        EnumMap<GunsmithPressPart, ItemStack> autoParts = arParts(GunsmithPartQuality.LEGENDARY);
        autoParts.put(GunsmithPressPart.CORE, burstParts.get(GunsmithPressPart.CORE));
        GunsmithGunStats auto = GunsmithGunStats.from(assemble(GunsmithBlueprint.M4A1, autoParts));
        helper.assertTrue(auto != null && !auto.forcesBurstFireMode(), "Gehenna on a base bolt keeps the source modes");
        assertClose(helper, auto.fireRate(), 1.25D, "Gehenna on a non-burst gun must keep its +25% fire rate");
        assertClose(helper, GunsmithAssemblyRecipe.preview(GunsmithBlueprint.M4A1, autoParts, M4_BASE_STATS)
                .fireRateChange(), 25.0D, "the preview must keep Gehenna's +25% on a non-burst gun");
        Component autoRate = specialGasFireRate(helper, auto);
        helper.assertTrue("+25%".equals(autoRate.getString())
                        && TextColor.fromLegacyFormat(ChatFormatting.GREEN).equals(autoRate.getStyle().getColor()),
                "a non-burst Gehenna gun must show a plain green +25%, got " + autoRate);
        helper.succeed();
    }

    // ============================================================
    // E8 显示口径与 TaCZ 实际写入一致
    // ============================================================

    /** 触帽: 双传奇枪机 x 枪管 1.50 x 1.50 = 2.25, 显示必须与写进 TaCZ 的 1.80 一致。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void headshotDisplayAppliesTheQualityCompoundingCap(GameTestHelper helper) {
        assertCapAnchors(helper);
        EnumMap<GunsmithPressPart, ItemStack> parts = arParts(GunsmithPartQuality.COMMON);
        parts.put(GunsmithPressPart.BOLT, part(GunsmithPlatform.AR, GunsmithPressPart.BOLT,
                GunsmithPartQuality.LEGENDARY, GunsmithPartVariant.BASE, 1.50D));
        parts.put(GunsmithPressPart.BARREL, part(GunsmithPlatform.AR, GunsmithPressPart.BARREL,
                GunsmithPartQuality.LEGENDARY, GunsmithPartVariant.BASE, 1.50D));
        GunsmithGunStats stats = GunsmithGunStats.from(assemble(GunsmithBlueprint.M4A1, parts));
        helper.assertTrue(stats != null, "double legendary M4 must be readable");

        // 1.8 / 1.5 = 1.2: 爆头乘子被反解到帽上, 显示的有效爆头倍率 = 1.5 x 1.2 = 1.80, 不是 1.5 x 1.5 = 2.25。
        assertClose(helper, stats.headshot(), 1.20D, "the displayed headshot multiplier must be capped");
        helper.assertTrue(Double.compare(stats.headshot(), GunsmithStatMultipliers.of(stats, 1.80D).headshot()) == 0,
                "the displayed headshot multiplier must equal the one written to TaCZ");
        assertClose(helper, stats.effectiveHeadshot(M4_BASE_STATS), 1.80D, "the tooltip headshot must read 1.80");
        assertClose(helper, GunsmithAssemblyRecipe.preview(GunsmithBlueprint.M4A1, parts, M4_BASE_STATS).headshot(),
                1.80D, "the assembly preview headshot must read 1.80 as well");
        List<Component> tooltip = new ArrayList<>();
        GunsmithGunTooltip.append(tooltip, stats, M4_BASE_STATS);
        helper.assertTrue("1.80".equals(String.valueOf(tooltipArg(helper, tooltip,
                        "tooltip.miningdim.gunsmith_gun.damage_headshot", 3).getString())),
                "the tooltip headshot cell must print the capped 1.80");

        // 圣三一枪管的 x1.50 是组件效果, 在帽外: 1.5 x 1.2 x 1.5 = 2.70。
        parts.put(GunsmithPressPart.BARREL, part(GunsmithPlatform.AR, GunsmithPressPart.BARREL,
                GunsmithPartQuality.LEGENDARY, GunsmithPartVariant.TRINITY_PRECISION_GRADUATED_BARREL, 1.50D));
        GunsmithGunStats trinity = GunsmithGunStats.from(assemble(GunsmithBlueprint.M4A1, parts));
        helper.assertTrue(trinity != null, "double legendary Trinity M4 must be readable");
        assertClose(helper, trinity.effectiveHeadshot(M4_BASE_STATS), 2.70D,
                "a faction headshot bonus must stay outside the quality cap");
        assertClose(helper, GunsmithAssemblyRecipe.preview(GunsmithBlueprint.M4A1, parts, M4_BASE_STATS).headshot(),
                2.70D, "the preview must keep the faction headshot bonus outside the cap");
        helper.succeed();
    }

    /** 含握把散布: 全传奇中值 M4 的护木与握把都是 1.43, 实战散布 1/1.43/1.43 - 1 = -51%, 不是 -30%。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void spreadDisplayIncludesGripControl(GameTestHelper helper) {
        EnumMap<GunsmithPressPart, ItemStack> parts = arParts(GunsmithPartQuality.LEGENDARY);
        GunsmithGunStats stats = GunsmithGunStats.from(assemble(GunsmithBlueprint.M4A1, parts));
        helper.assertTrue(stats != null, "all-legendary M4 must be readable");

        double expected = 1.0D / LEGENDARY_MID / LEGENDARY_MID - 1.0D;
        assertClose(helper, stats.spreadChange(), expected, "tooltip spread must include the grip control");
        helper.assertTrue(Double.compare(stats.inaccuracyMultiplier(),
                        GunsmithStatMultipliers.of(stats, 1.80D).combinedInaccuracy()) == 0,
                "the displayed spread multiplier must equal the single value written to the TaCZ cache");
        assertClose(helper, GunsmithAssemblyRecipe.preview(GunsmithBlueprint.M4A1, parts, M4_BASE_STATS)
                .spreadChange() / 100.0D, expected, "the assembly preview spread must include the grip control");
        List<Component> tooltip = new ArrayList<>();
        GunsmithGunTooltip.append(tooltip, stats, M4_BASE_STATS);
        helper.assertTrue("-51%".equals(tooltipArg(helper, tooltip,
                        "tooltip.miningdim.gunsmith_gun.spread_handling", 0).getString()),
                "the tooltip spread cell must print -51% for an all-legendary M4");
        helper.succeed();
    }

    // ============================================================
    // E11 维修件与伤害件对齐
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void repairPartMatchesTheDamagePartOnEveryPlatform(GameTestHelper helper) {
        for (GunsmithPlatform platform : GunsmithPlatform.values()) {
            List<GunsmithPressPart> damageSource = new ArrayList<>();
            GunsmithStat.DAMAGE.coefficient(platform, part -> {
                damageSource.add(part);
                return 1.0D;
            });
            helper.assertTrue(damageSource.size() == 1
                            && damageSource.get(0) == GunsmithGunDurability.repairPart(platform),
                    platform.id() + " repair part must be its damage part " + damageSource);
            helper.assertTrue(platform.supports(GunsmithGunDurability.repairPart(platform)),
                    platform.id() + " repair part must be a slot the platform actually has");
        }
        helper.assertTrue(GunsmithGunDurability.repairPart(GunsmithPlatform.PISTOL) == GunsmithPressPart.HAMMER,
                "pistol repairs must consume the hammer (was the slide)");
        helper.assertTrue(GunsmithGunDurability.repairPart(GunsmithPlatform.SNIPER) == GunsmithPressPart.RECEIVER,
                "bolt-action repairs must consume the receiver (was the firing pin)");

        // 行为核对: 维修替换件校验按新件走, 旧件 (套筒 / 撞针) 不再被接受。
        EnumMap<GunsmithPressPart, ItemStack> pistolParts = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : GunsmithBlueprint.M1911.requiredParts()) {
            pistolParts.put(part, part(GunsmithPlatform.PISTOL, part, GunsmithPartQuality.MILSPEC,
                    GunsmithPartVariant.BASE, 1.20D));
        }
        GunsmithGunStats pistol = GunsmithGunStats.from(assemble(GunsmithBlueprint.M1911, pistolParts));
        helper.assertTrue(pistol != null, "M1911 must be readable");
        helper.assertTrue(GunsmithGunDurability.isRepairReplacement(pistol, part(GunsmithPlatform.PISTOL,
                        GunsmithPressPart.HAMMER, GunsmithPartQuality.MILSPEC, GunsmithPartVariant.BASE, 1.20D)),
                "a same-quality hammer must service an M1911");
        helper.assertFalse(GunsmithGunDurability.isRepairReplacement(pistol, part(GunsmithPlatform.PISTOL,
                        GunsmithPressPart.SLIDE, GunsmithPartQuality.MILSPEC, GunsmithPartVariant.BASE, 1.20D)),
                "a slide must no longer service an M1911");

        EnumMap<GunsmithPressPart, ItemStack> sniperParts = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : GunsmithBlueprint.KAR98K.requiredParts()) {
            sniperParts.put(part, part(GunsmithPlatform.SNIPER, part, GunsmithPartQuality.MILSPEC,
                    GunsmithPartVariant.BASE, 1.20D));
        }
        GunsmithGunStats sniper = GunsmithGunStats.from(assemble(GunsmithBlueprint.KAR98K, sniperParts));
        helper.assertTrue(sniper != null, "KAR98K must be readable");
        helper.assertTrue(GunsmithGunDurability.isRepairReplacement(sniper, part(GunsmithPlatform.SNIPER,
                        GunsmithPressPart.RECEIVER, GunsmithPartQuality.MILSPEC, GunsmithPartVariant.BASE, 1.20D)),
                "a same-quality receiver must service a KAR98K");
        helper.assertFalse(GunsmithGunDurability.isRepairReplacement(sniper, part(GunsmithPlatform.SNIPER,
                        GunsmithPressPart.FIRING_PIN, GunsmithPartQuality.MILSPEC, GunsmithPartVariant.BASE, 1.20D)),
                "a firing pin must no longer service a KAR98K");
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 帽值手算锚点: 本类的 20.25 / 1.80 / 2.25 期望值全按默认帽值算, 默认值一改这里先红。 */
    private static void assertCapAnchors(GameTestHelper helper) {
        helper.assertTrue(Math.abs(MunitionsConfig.GUNSMITH_DAMAGE_MULTIPLIER_CAP.get() - 2.25D) < 0.0000001D,
                "本类按整枪伤害总帽 2.25 手算期望值");
        helper.assertTrue(Math.abs(MunitionsConfig.GUNSMITH_HEADSHOT_DAMAGE_CAP.get() - 1.80D) < 0.0000001D,
                "本类按爆头品质复利帽 1.8 手算期望值");
    }

    private static void assertConflictMessage(GameTestHelper helper, Component message) {
        helper.assertTrue(message.getContents() instanceof TranslatableContents translatable
                        && GunsmithAssemblyRecipe.VARIANT_CONFLICT_KEY.equals(translatable.getKey())
                        && GunsmithAssemblyRecipe.VARIANT_CONFLICT_FALLBACK.equals(translatable.getFallback())
                        && translatable.getArgs().length == 2
                        && translatable.getArgs()[0] instanceof Component first
                        && first.getContents() instanceof TranslatableContents firstName
                        && GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS.labelKey().equals(firstName.getKey())
                        && translatable.getArgs()[1] instanceof Component second
                        && second.getContents() instanceof TranslatableContents secondName
                        && GunsmithPartVariant.RED_WINTER_CHIXUE_A_BOLT.labelKey().equals(secondName.getKey()),
                "the refusal must name red east gas and Chixue-A, got " + message);
    }

    private static Component specialGasFireRate(GameTestHelper helper, GunsmithGunStats stats) {
        List<Component> tooltip = new ArrayList<>();
        GunsmithGunTooltip.append(tooltip, stats, M4_BASE_STATS);
        return tooltipArg(helper, tooltip, "tooltip.miningdim.gunsmith_gun.special_gas", 0);
    }

    private static Component tooltipArg(GameTestHelper helper, List<Component> tooltip, String key, int index) {
        for (Component line : tooltip) {
            if (line.getContents() instanceof TranslatableContents translatable && key.equals(translatable.getKey())) {
                Object arg = translatable.getArgs()[index];
                if (arg instanceof Component component) {
                    return component;
                }
                helper.fail(key + " argument " + index + " is not a component: " + arg);
            }
        }
        helper.fail("tooltip has no " + key + " row: " + tooltip);
        throw new IllegalStateException("unreachable");
    }

    private static ItemStack assemble(GunsmithBlueprint blueprint, EnumMap<GunsmithPressPart, ItemStack> parts) {
        return GunsmithAssemblyRecipe.assemble(new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), blueprint), parts);
    }

    /** 六件同品质中值的 AR 基础件。 */
    private static EnumMap<GunsmithPressPart, ItemStack> arParts(GunsmithPartQuality quality) {
        EnumMap<GunsmithPressPart, ItemStack> parts = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : GunsmithBlueprint.M4A1.requiredParts()) {
            parts.put(part, part(GunsmithPlatform.AR, part, quality, GunsmithPartVariant.BASE,
                    quality.midpointCoefficient()));
        }
        return parts;
    }

    /** AK 六件: 枪机与导气按参数给, 其余四件普通中值基础件。 */
    private static EnumMap<GunsmithPressPart, ItemStack> akParts(GunsmithPartQuality boltQuality,
                                                                  double boltCoefficient,
                                                                  GunsmithPartVariant boltVariant,
                                                                  GunsmithPartQuality coreQuality,
                                                                  GunsmithPartVariant coreVariant) {
        EnumMap<GunsmithPressPart, ItemStack> parts = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : GunsmithBlueprint.AK47.requiredParts()) {
            parts.put(part, part(GunsmithPlatform.AK, part, GunsmithPartQuality.COMMON, GunsmithPartVariant.BASE,
                    GunsmithPartQuality.COMMON.midpointCoefficient()));
        }
        parts.put(GunsmithPressPart.BOLT, part(GunsmithPlatform.AK, GunsmithPressPart.BOLT, boltQuality,
                boltVariant, boltCoefficient));
        parts.put(GunsmithPressPart.CORE, part(GunsmithPlatform.AK, GunsmithPressPart.CORE, coreQuality,
                coreVariant, coreQuality.midpointCoefficient()));
        return parts;
    }

    private static ItemStack part(GunsmithPlatform platform, GunsmithPressPart part, GunsmithPartQuality quality,
                                  GunsmithPartVariant variant, double coefficient) {
        return GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(), platform, part, quality,
                variant, coefficient);
    }

    private static Path gunpackPath(String relative) {
        return ModList.get().getModFileById(MiningConstants.MODID).getFile()
                .findResource((GUNPACK_DATA + "/" + relative).split("/"));
    }

    private static void placeBench(GameTestHelper helper) {
        GunsmithAssemblyBenchBlock block = (GunsmithAssemblyBenchBlock) ModMunitionsBlocks.GUNSMITH_ASSEMBLY_BENCH.get();
        for (GunsmithAssemblyBenchBlock.Part part : GunsmithAssemblyBenchBlock.Part.values()) {
            BlockState state = block.defaultBlockState()
                    .setValue(GunsmithAssemblyBenchBlock.FACING, Direction.NORTH)
                    .setValue(GunsmithAssemblyBenchBlock.PART, part)
                    .setValue(GunsmithAssemblyBenchBlock.ACTIVE, false);
            helper.setBlock(GunsmithAssemblyBenchBlock.partPos(MAIN_REL, Direction.NORTH, part), state);
        }
    }

    private static GunsmithAssemblyBenchBlockEntity requireBench(GameTestHelper helper) {
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(MAIN_REL))
                instanceof GunsmithAssemblyBenchBlockEntity be)) {
            throw new IllegalStateException("assembly bench block entity missing");
        }
        return be;
    }

    /**
     * 与 MockGameTestPlayers 同法造一个挂了活动 channel 的玩家, 另把 displayClientMessage 记下来, 供断言
     * 装配台给玩家的拒绝原因。
     */
    private static ServerPlayer recordingPlayer(GameTestHelper helper, List<Component> messages) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "gunsmith-balance-probe")) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return true;
            }

            @Override
            public void displayClientMessage(Component message, boolean actionBar) {
                messages.add(message);
                super.displayClientMessage(message, actionBar);
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, player);
        return player;
    }

    @Nullable
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

    private static void restoreJob(@Nullable IJobService previous) {
        if (previous != null) {
            JobServices.registerJobService(previous);
        } else {
            JobServices.reset();
        }
    }

    private static void assertClose(GameTestHelper helper, double actual, double expected, String label) {
        helper.assertTrue(Math.abs(actual - expected) < 0.0000001D,
                label + " expected " + expected + " but was " + actual);
    }

    /** 定级职业门面替身, 只供装配等级门读取。 */
    private static final class FixedLevelJobService implements IJobService {
        private final int level;

        private FixedLevelJobService(int level) {
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
            throw new UnsupportedOperationException("not exercised by gunsmith component balance tests");
        }
    }
}
