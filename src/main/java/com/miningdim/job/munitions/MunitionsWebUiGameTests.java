package com.miningdim.job.munitions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.block.GunsmithAssemblyBenchBlock;
import com.miningdim.job.munitions.block.GunsmithAssemblyBenchBlockEntity;
import com.miningdim.job.munitions.block.GunsmithPressBlockEntity;
import com.miningdim.job.munitions.block.MunitionsBenchBlockEntity;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprint;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprintItem;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * W4b 军械组的 job.munitions.state / job.blueprints GameTest。
 *
 * 三条主线:
 *  1. 只读镜像必须真的<b>只读</b> ({@link #munitionsStateNeverSettlesProduction}): 面板刷新是玩家每隔几秒就
 *     触发一次的读操作, 一旦它顺手调 settleForOwner, "开着平板" 就变成产能加速器。本条备好一台随时能出货的台,
 *     先证明调 action 后一发没产, 再手动结算一次证明这台确实随时能出货 —— 没有后半段, 前半段等于什么都没测。
 *  2. 归属过滤 ({@link #munitionsStateHidesBenchesOwnedBySomeoneElse}): 军火台是三台里唯一有归属字段的,
 *     别人的产线不许出现在你的面板上。
 *  3. 实时读 config ({@link #munitionsStateReadsConfigLive}): 运营改完 toml, 下一次调用就得跟着变。
 *
 * 就近取台的世界搭建 (下同): GameTest 的 empty 模板只有 1x1x1, 各用例的方块都落在模板框之外且不随用例结束清理,
 * 故本文件的断言一律建立在 "我自己刚放的那一台离我最近" 与 "军火台按归属过滤" 之上, 绝不断言
 * "附近没有任何冲压机/装配台" —— 那种断言会被隔壁 GunsmithPressGameTests 留下的方块随机打红。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MunitionsWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui_w4b_munitions";

    /**
     * 会临时改写全局 config 的用例单独成批。Forge GameTest 批与批之间串行、<b>批内并行</b>,
     * 与读同一份 config 的用例同批就是一个必然偶发的竞态: 读的那条会在探针值还没被 finally 放回时
     * 取到探针值 (已实测撞出过一次: 低级板经验期望 15 实得 56 = 15 + 探针偏移 41)。
     *
     * 另一个坑与批次无关但同源: Forge 的 serverconfig 是 autosave 且<b>异步落盘</b>, 而 GameTest 服务器跑完
     * 立刻退出 —— 探针写的值可能已经落进 run/world/serverconfig/*.toml, 而 finally 恢复的那次写还没刷出去
     * 进程就结束了。于是之后每次运行都从被污染的值起步, 表现为某条断言硬编码期望值的用例"无缘无故"开始挂
     * (实测: 低级板经验期望 15 恒得 56 = 15 + 探针偏移 41)。真遇到时把那个 toml 里对应键改回默认即可;
     * run/ 已在 .gitignore 内, 不会污染仓库。
     */
    private static final String BATCH_CONFIG = "webui_w4b_munitions_config";


    private static final String STATE_ACTION = "job.munitions.state";
    private static final String BLUEPRINTS_ACTION = "job.blueprints";

    private static final String STATION_BENCH = "munitions_bench";
    private static final String STATION_PRESS = "gunsmith_press";
    private static final String STATION_ASSEMBLY = "gunsmith_assembly_bench";

    /** 三台机器的落点 (相对模板)。彼此隔开: 军火台的 updateShape 会把贴在它连接方向上的异类方块判成断裂。 */
    private static final BlockPos BENCH_REL = new BlockPos(0, 1, 0);
    private static final BlockPos PRESS_REL = new BlockPos(3, 1, 0);
    private static final BlockPos ASSEMBLY_MAIN_REL = new BlockPos(0, 1, 3);

    /** 搜索半径的契约值 (写死在测试里: 它是回执里 searchRadiusBlocks 的对外承诺, 跟着实现一起改就等于没测)。 */
    private static final int SEARCH_RADIUS_BLOCKS = 64;

    /** 装配台一次动画的 tick 数 (契约值; 前端拿它当进度条满格)。 */
    private static final int ASSEMBLY_DURATION_TICKS = 160;

    private MunitionsWebUiGameTests() {
    }

    /** 批前钩子: 绑定 MunitionsConfig 默认值 (dev 环境下其 SERVER spec 未经 Forge 加载, 不绑定则 get() 抛)。 */
    @BeforeBatch(batch = BATCH)
    public static void beforeMunitionsWebUiBatch(ServerLevel level) {
        MunitionsConfig.ensureLoadedForTest();
    }

    // ============================================================
    // 1. 三台机器的镜像形状与真值
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void munitionsStateMirrorsTheThreeNearbyStations(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MunitionsBenchBlockEntity bench = placeBench(helper, player.getUUID());
        GunsmithPressBlockEntity press = placePress(helper);
        GunsmithAssemblyBenchBlockEntity assembly = placeAssembly(helper);
        standAt(helper, player, BENCH_REL);

        // 选一个 L1 就解锁的口径: 选中口径必须原样出现在镜像里 (发 -1 哨兵的实现会把它错成 pistol)。
        helper.assertTrue(bench.trySelectCaliber(MunitionsCaliber.PISTOL, player),
                "前提: L1 玩家选手枪弹应被接受");
        // 图纸放进装配台的图纸槽: 镜像必须解出是哪一张图纸, 而不是只说"有东西"。
        assembly.inventory().setStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT,
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.AK47));

        JsonObject state = state(helper, player);
        helper.assertTrue(state.get("level").getAsInt() == 1, "新号军火商 1 级");
        helper.assertTrue(state.get("searchRadiusBlocks").getAsInt() == SEARCH_RADIUS_BLOCKS,
                "搜索半径的对外承诺是 " + SEARCH_RADIUS_BLOCKS + " 格, 实得 " + state.get("searchRadiusBlocks"));
        helper.assertTrue(state.get("benchCap").getAsInt() == MunitionsConfig.TABLE_COUNT_L1.get(),
                "L1 台数上限取 config 实时值 " + MunitionsConfig.TABLE_COUNT_L1.get());

        JsonArray stations = state.getAsJsonArray("stations");
        helper.assertTrue(stations.size() == 3, "三台机器恒 3 行, 实得 " + stations.size());
        helper.assertTrue(STATION_BENCH.equals(stations.get(0).getAsJsonObject().get("stationId").getAsString())
                        && STATION_PRESS.equals(stations.get(1).getAsJsonObject().get("stationId").getAsString())
                        && STATION_ASSEMBLY.equals(stations.get(2).getAsJsonObject().get("stationId").getAsString()),
                "三行顺序恒为 军火台/冲压机/装配台 (前端按下标画卡片)");

        JsonObject benchRow = station(helper, state, STATION_BENCH);
        assertPos(helper, benchRow, helper.absolutePos(BENCH_REL), "军火台");
        helper.assertTrue(ModMunitionsBlocks.MUNITIONS_BENCH.get().getDescriptionId()
                        .equals(benchRow.get("nameKey").getAsString()),
                "军火台发的是方块翻译键而不是中文, 实得 " + benchRow.get("nameKey"));
        JsonObject benchDetail = benchRow.getAsJsonObject("detail");
        helper.assertTrue("pistol".equals(benchDetail.get("caliberId").getAsString()),
                "选中口径必须镜像成 pistol, 实得 " + benchDetail.get("caliberId"));
        helper.assertTrue(benchDetail.get("bufferedRounds").getAsInt() == 0
                        && benchDetail.get("bufferCap").getAsInt() == MunitionsConfig.BUFFER_L1.get(),
                "空台缓冲 0 / 上限取 L1 config " + MunitionsConfig.BUFFER_L1.get()
                        + ", 实得 " + benchDetail.get("bufferCap"));
        helper.assertFalse(benchDetail.get("locked").getAsBoolean(), "新台未上锁");
        helper.assertFalse(benchDetail.get("refineUnlocked").getAsBoolean(), "L1 未解锁提炼 (L6 才解锁)");
        helper.assertTrue(benchDetail.get("effectiveLevel").getAsInt() == 1, "全档台在 L1 玩家手里按 1 级算产能");
        helper.assertFalse(benchRow.get("running").getAsBoolean(), "没开工的台 running=false");
        helper.assertTrue(benchRow.get("outputItemId").isJsonNull() && benchRow.get("outputCount").getAsInt() == 0,
                "输出槽空时发 null 而不是空串");

        JsonObject pressRow = station(helper, state, STATION_PRESS);
        assertPos(helper, pressRow, helper.absolutePos(PRESS_REL), "冲压机");
        helper.assertTrue(pressRow.get("requiredTicks").getAsInt() == GunsmithPartQuality.COMMON.requiredTicks(),
                "冲压机所需 tick = 当前选中品质的 requiredTicks ("
                        + GunsmithPartQuality.COMMON.requiredTicks() + "), 实得 " + pressRow.get("requiredTicks"));
        helper.assertTrue(pressRow.get("progressTicks").getAsInt() == 0 && !pressRow.get("running").getAsBoolean(),
                "没开工的冲压机进度 0 且 running=false");
        JsonObject pressDetail = pressRow.getAsJsonObject("detail");
        helper.assertTrue(GunsmithPlatform.AR.id().equals(pressDetail.get("platformId").getAsString())
                        && GunsmithPressPart.CORE.id().equals(pressDetail.get("partId").getAsString())
                        && GunsmithPartQuality.COMMON.id().equals(pressDetail.get("qualityId").getAsString()),
                "冲压机默认选择是 ar/core/common, 实得 " + pressDetail);
        helper.assertTrue(press.selectedPlatform() == GunsmithPlatform.AR,
                "前提校验: 冲压机 BE 的默认平台确实是 AR (镜像值与 BE 同源)");

        JsonObject assemblyRow = station(helper, state, STATION_ASSEMBLY);
        assertPos(helper, assemblyRow, helper.absolutePos(ASSEMBLY_MAIN_REL), "装配台");
        helper.assertTrue(assemblyRow.get("requiredTicks").getAsInt() == ASSEMBLY_DURATION_TICKS,
                "装配一次恒 " + ASSEMBLY_DURATION_TICKS + " tick, 实得 " + assemblyRow.get("requiredTicks"));
        // 装配台没有 ContainerData 也没有已进行 tick 的只读方法: 进度必须发 null 而不是编一个 0 出来。
        helper.assertTrue(assemblyRow.get("progressTicks").isJsonNull(),
                "装配台进度不可知, 必须发 null (发 0 会画出一条永远空着的进度条)");
        helper.assertTrue("ak47".equals(assemblyRow.getAsJsonObject("detail").get("blueprintId").getAsString()),
                "图纸槽里的 AK47 图纸必须被解出来, 实得 " + assemblyRow.getAsJsonObject("detail").get("blueprintId"));
        helper.succeed();
    }

    /** 台位没扫到时 pos 是真 null (Gson 必须 serializeNulls, 否则前端拿到的是 undefined)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void munitionsStateHidesBenchesOwnedBySomeoneElse(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MunitionsBenchBlockEntity bench = placeBench(helper, UUID.randomUUID());
        standAt(helper, player, BENCH_REL);

        JsonObject benchRow = station(helper, state(helper, player), STATION_BENCH);
        helper.assertTrue(benchRow.has("pos") && benchRow.get("pos").isJsonNull(),
                "别人的军火台不进我的面板: pos 必须是显式 null (键不能整个消失), 实得 " + benchRow.get("pos"));
        helper.assertTrue(benchRow.get("progressTicks").isJsonNull()
                        && benchRow.get("outputItemId").isJsonNull()
                        && benchRow.getAsJsonObject("detail").size() == 0,
                "未扫到的那一行除了 stationId/nameKey 之外不许有任何遥测数值, 实得 " + benchRow);

        // 同一台改挂到自己名下就必须出现 —— 证明上面的 null 来自归属判定, 而不是"根本没扫到方块"。
        bench.setOwner(player.getUUID());
        assertPos(helper, station(helper, state(helper, player), STATION_BENCH),
                helper.absolutePos(BENCH_REL), "改归属后的军火台");
        helper.succeed();
    }

    /**
     * benchesPlaced 走的是 {@link MunitionsSavedData} 的<b>全局</b>计数, 不是就近扫到几台 —— 只有它能回答
     * "我到底造了几台" (人不在基地时三行的 pos 全是 null, 那时前端唯一还能显示的就是这个数)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void munitionsStateReportsGloballyPlacedBenchCount(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MunitionsSavedData savedData = MunitionsSavedData.get(player.server.overworld());

        helper.assertTrue(state(helper, player).get("benchesPlaced").getAsInt() == 0,
                "新号一台没造");
        try {
            savedData.increment(player.getUUID());
            savedData.increment(player.getUUID());
            helper.assertTrue(state(helper, player).get("benchesPlaced").getAsInt() == 2,
                    "放置计数 +2 之后回执必须变成 2 (读的是 SavedData 而不是就近扫描的结果), 实得 "
                            + state(helper, player).get("benchesPlaced"));
        } finally {
            savedData.decrement(player.getUUID());
            savedData.decrement(player.getUUID());
        }
        helper.assertTrue(state(helper, player).get("benchesPlaced").getAsInt() == 0,
                "计数回落之后回执也必须跟着回落");
        helper.succeed();
    }

    // ============================================================
    // 2. 只读: 面板刷新不许推进产线
    // ============================================================

    /**
     * 备好一台"下一次结算就出货"的军火台, 连调两次 job.munitions.state, 要求一发不产、一件料不少;
     * 随后手动 settleForOwner 一次并要求真的产出 —— 后半段证明这台确实随时能出货, 前半段才有意义。
     *
     * 实现里加一句 onAccess/settleForOwner 本条即挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH_CONFIG)
    public static void munitionsStateNeverSettlesProduction(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MunitionsBenchBlockEntity bench = placeBench(helper, player.getUUID());
        standAt(helper, player, BENCH_REL);
        helper.assertTrue(bench.trySelectCaliber(MunitionsCaliber.PISTOL, player), "前提: L1 选手枪弹被接受");
        stockOneBatch(bench);
        backdateSettleTick(bench, helper, 10_000_000L);

        int originalWorkFee = MunitionsConfig.WORK_FEE_PER_TEN_ROUNDS.get();
        try {
            // 工费清零: 本条测的是"读操作有没有推进产线", 不该被 mock 玩家有没有钱这件事左右。
            MunitionsConfig.WORK_FEE_PER_TEN_ROUNDS.set(0);

            state(helper, player);
            JsonObject benchDetail = station(helper, state(helper, player), STATION_BENCH)
                    .getAsJsonObject("detail");
            helper.assertTrue(bench.bufferedRounds() == 0 && benchDetail.get("bufferedRounds").getAsInt() == 0,
                    "两次面板刷新之后缓冲仍必须是 0 发, 实得 " + bench.bufferedRounds());
            helper.assertTrue(primerCount(bench) == MunitionsConfig.RECIPE_PRIMER_COST.get(),
                    "料一件都不许扣, 实得底火 " + primerCount(bench));

            // 正对照: 同一台真结算一次就该出货 —— 上面的 "0 发" 因此是"没被推进", 不是"本来就产不出"。
            bench.settleForOwner(player);
            int expectedRounds = MunitionsProduction.roundsPerBatch(MunitionsCaliber.PISTOL, 1);
            helper.assertTrue(bench.bufferedRounds() == expectedRounds,
                    "正对照: 手动结算一次应产 " + expectedRounds + " 发, 实得 " + bench.bufferedRounds());
            helper.assertTrue(primerCount(bench) == 0, "正对照: 结算后整批料被扣光, 实得底火 " + primerCount(bench));
            helper.assertTrue(station(helper, state(helper, player), STATION_BENCH)
                            .getAsJsonObject("detail").get("bufferedRounds").getAsInt() == expectedRounds,
                    "结算之后镜像必须跟着变成 " + expectedRounds + " 发");
        } finally {
            MunitionsConfig.WORK_FEE_PER_TEN_ROUNDS.set(originalWorkFee);
        }
        helper.succeed();
    }

    // ============================================================
    // 3. 实时读 config
    // ============================================================

    /**
     * 运营改一次 toml, 下一次调用就必须跟着变。三个探针打在三条不同的取数路径上:
     * benchCap 走等级查表, bufferCap 走军火台 ContainerData, gunsmithEnabled 走布尔开关 ——
     * 只缓存其中一条的实现也会被抓出来。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH_CONFIG)
    public static void munitionsStateReadsConfigLive(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        placeBench(helper, player.getUUID());
        standAt(helper, player, BENCH_REL);

        int originalTableCount = MunitionsConfig.TABLE_COUNT_L1.get();
        int originalBuffer = MunitionsConfig.BUFFER_L1.get();
        boolean originalGunsmith = MunitionsConfig.GUNSMITH_ENABLED.get();
        int probeTableCount = originalTableCount + 5;
        int probeBuffer = originalBuffer + 137;
        try {
            JsonObject before = state(helper, player);
            helper.assertTrue(before.get("benchCap").getAsInt() == originalTableCount
                            && bufferCap(helper, before) == originalBuffer
                            && before.get("gunsmithEnabled").getAsBoolean() == originalGunsmith,
                    "前置校验: 改动前回执就该是 config 当前值");

            MunitionsConfig.TABLE_COUNT_L1.set(probeTableCount);
            MunitionsConfig.BUFFER_L1.set(probeBuffer);
            MunitionsConfig.GUNSMITH_ENABLED.set(!originalGunsmith);

            JsonObject after = state(helper, player);
            helper.assertTrue(after.get("benchCap").getAsInt() == probeTableCount,
                    "台数上限必须跟着 config 变成 " + probeTableCount + ", 实得 " + after.get("benchCap"));
            helper.assertTrue(bufferCap(helper, after) == probeBuffer,
                    "缓冲上限必须跟着 config 变成 " + probeBuffer + ", 实得 " + bufferCap(helper, after));
            helper.assertTrue(after.get("gunsmithEnabled").getAsBoolean() == !originalGunsmith,
                    "枪匠总开关必须跟着 config 翻转");
            helper.assertTrue(blueprints(helper, player).get("gunsmithEnabled").getAsBoolean() == !originalGunsmith,
                    "图纸页发的是同一个开关, 也必须跟着翻转");
        } finally {
            MunitionsConfig.TABLE_COUNT_L1.set(originalTableCount);
            MunitionsConfig.BUFFER_L1.set(originalBuffer);
            MunitionsConfig.GUNSMITH_ENABLED.set(originalGunsmith);
        }

        JsonObject restored = state(helper, player);
        helper.assertTrue(restored.get("benchCap").getAsInt() == originalTableCount
                        && bufferCap(helper, restored) == originalBuffer,
                "改回去之后回执也必须跟着回去 (证明两次变化都来自实时读, 不是一次性初始化)");
        helper.succeed();
    }

    // ============================================================
    // 4. 图纸静态表
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void blueprintsDumpMatchesTheEnumTable(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonObject result = blueprints(helper, player);

        JsonArray rows = result.getAsJsonArray("blueprints");
        helper.assertTrue(rows.size() == GunsmithBlueprint.values().length && rows.size() == 9,
                "图纸恒 9 款 (GunsmithBlueprint 全枚举), 实得 " + rows.size());
        helper.assertTrue(result.get("blueprintCount").getAsInt() == rows.size(), "blueprintCount 必须与数组等长");
        helper.assertTrue("miningdim:gunsmith_part".equals(result.get("partItemId").getAsString()),
                "195 种零件共用一个注册名, 顶层只发这一份, 实得 " + result.get("partItemId"));

        for (int i = 0; i < GunsmithBlueprint.values().length; i++) {
            GunsmithBlueprint blueprint = GunsmithBlueprint.values()[i];
            JsonObject row = rows.get(i).getAsJsonObject();
            helper.assertTrue(blueprint.templateId().equals(row.get("blueprintId").getAsString()),
                    "第 " + i + " 行必须是 " + blueprint.templateId() + ", 实得 " + row.get("blueprintId"));
            helper.assertTrue(("tacz:" + blueprint.templateId()).equals(row.get("gunId").getAsString()),
                    blueprint.templateId() + " 的枪 id 必须是 tacz 命名空间的, 实得 " + row.get("gunId"));
            helper.assertTrue(("tacz.gun." + blueprint.templateId() + ".name")
                            .equals(row.get("gunNameKey").getAsString()),
                    blueprint.templateId() + " 发的是 TACZ 枪名翻译键而不是中文");
            helper.assertTrue(blueprint.platform().id().equals(row.get("platformId").getAsString()),
                    blueprint.templateId() + " 的平台必须是 " + blueprint.platform().id());

            JsonArray parts = row.getAsJsonArray("requiredParts");
            helper.assertTrue(parts.size() == blueprint.requiredParts().size(),
                    blueprint.templateId() + " 的部位数必须是 " + blueprint.requiredParts().size()
                            + ", 实得 " + parts.size());
            for (JsonElement element : parts) {
                JsonObject part = element.getAsJsonObject();
                GunsmithPressPart resolved = GunsmithPressPart.byId(part.get("partId").getAsString());
                helper.assertTrue(blueprint.requiredParts().contains(resolved),
                        blueprint.templateId() + " 不需要 " + resolved.id() + ", 却出现在配方里");
                helper.assertTrue(resolved.labelKey().equals(part.get("labelKey").getAsString()),
                        resolved.id() + " 的 labelKey 必须与零件 tooltip 同一批键");
                helper.assertTrue(part.get("count").getAsInt() == 1,
                        "装配台每个部位槽恒 1 件 (getSlotLimit=1), 实得 " + part.get("count"));
            }
        }

        // 手枪平台的部位与步枪完全不同: 拿它锁死"配方来自枚举而不是照 AR 抄了一份"。
        JsonObject m1911 = blueprintRow(helper, rows, GunsmithBlueprint.M1911.templateId());
        helper.assertTrue(m1911.getAsJsonArray("requiredParts").size() == 5
                        && hasPart(m1911, GunsmithPressPart.SLIDE)
                        && hasPart(m1911, GunsmithPressPart.HAMMER)
                        && !hasPart(m1911, GunsmithPressPart.CORE),
                "M1911 是 5 件手枪部位 (含套筒/击锤, 无导气系统), 实得 " + m1911.getAsJsonArray("requiredParts"));
        helper.succeed();
    }

    /**
     * 静态表不分页, 靠的是它编译期定长; 这条断言就是那个"定长"的守卫: 枚举涨到撑破下行上限之前先红。
     * 余量取下行上限的一半 —— 撞到收口 (RESPONSE_TOO_LARGE) 时前端拿到的是一整页错误, 不是半张表。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void blueprintsDumpKeepsHalfTheDownstreamBudgetFree(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        WebUiAction handler = require(helper, BLUEPRINTS_ACTION);
        int length = handler.handle(player, new JsonObject()).length();
        int budget = FriendlyByteBuf.MAX_STRING_LENGTH / 2;
        helper.assertTrue(length < budget,
                "图纸整表必须留下一半以上的下行预算 (上限 " + FriendlyByteBuf.MAX_STRING_LENGTH
                        + " 字符), 实得 " + length + " 字符; 超了就该精简字段而不是加分页");
        helper.succeed();
    }

    // ============================================================
    // F053: NearbyStations.around 从"扫满整个方框"改成"按环扫 + 提前收敛", 结果必须与旧实现逐字相同
    // ============================================================

    /**
     * 三段覆盖三条判据:
     *  (a) 约 60 格外、仍在军火台 64 格半径内的台必须被扫到 (按环扫不能漏最外层);
     *  (b) 无归属机台 (冲压机) 的公用可见半径只有 16 格, 我们自己刻意放在 20 格外的这台绝不能被选中
     *      (不断言 pos 恒为 null: GameTest 批内并行且冲压机没有归属字段, 世界里同时跑着的其它测试实例
     *      可能凑巧也有一台落在玩家 16 格公用半径内, 那不是本条要测的东西——公用半径的真距离截断才是);
     *  (c) 再放一台自己的近台 (脚下, 距离 0): 两台自己的军火台同时存在时必须取最近那台, 不是先放的远台。
     *
     * 删掉 NearbyStations.around 里按环提前收敛的那段 (或改错判定半径), (a)/(c) 会在军火台仍应被扫到的位置
     * 突然扫不到 (真距离在 64/0 格内, 却因为换了扫描顺序/半径漏判), 断言必挂; 把 (b) 的公用半径真距离截断
     * (PUBLIC_STATION_RADIUS_SQR 判定) 删掉或改错, 我们自己那台 20 格外的冲压机就会被选中, 断言同样必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void munitionsStateRingScanFindsSameStationsAsFullScan(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        BlockPos homeRel = new BlockPos(0, 1, 0);
        BlockPos farBenchRel = new BlockPos(60, 1, 0);
        BlockPos farPressRel = new BlockPos(20, 1, 0);

        placeBench(helper, player.getUUID(), farBenchRel);
        standAt(helper, player, homeRel);

        // (a)
        JsonObject stateA = state(helper, player);
        assertPos(helper, station(helper, stateA, STATION_BENCH), helper.absolutePos(farBenchRel),
                "60 格外仍在 64 格半径内的军火台必须被扫到");

        // (b)
        placePress(helper, farPressRel);
        JsonObject stateB = state(helper, player);
        JsonObject pressRow = station(helper, stateB, STATION_PRESS);
        // 断言不能硬要求 pos=null: GameTest 批内并行 (类头注释已记录), 冲压机没有归属字段 (F053 设计如此),
        // 世界里同时跑着的其它测试实例可能恰好也有一台落在玩家 16 格公用半径内, 那不是本条要测的东西。
        // 真正要测的不变量是"我们自己刻意放的这台 (20 格外) 绝不能是被选中的那台"——若 pos 命中它, 说明
        // 公用半径的真距离截断失效, 是 F053 的真回归; pos 命中别的机台或干脆是 null, 都不构成失败。
        if (!pressRow.get("pos").isJsonNull()) {
            JsonObject pos = pressRow.getAsJsonObject("pos");
            BlockPos farPressAbs = helper.absolutePos(farPressRel);
            boolean matchesOurFarPress = pos.get("x").getAsInt() == farPressAbs.getX()
                    && pos.get("y").getAsInt() == farPressAbs.getY()
                    && pos.get("z").getAsInt() == farPressAbs.getZ();
            helper.assertFalse(matchesOurFarPress,
                    "冲压机公用可见半径只有 16 格, 20 格外我们自己放的这台绝不能被选中, 实得 " + pos);
        }

        // (c)
        placeBench(helper, player.getUUID(), homeRel);
        JsonObject stateC = state(helper, player);
        assertPos(helper, station(helper, stateC, STATION_BENCH), helper.absolutePos(homeRel),
                "两台自己的军火台同时存在时必须取最近那台, 而不是先放的远台");

        helper.succeed();
    }

    // ============================================================
    // 5. 注册名
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void munitionsActionsAreRegisteredUnderTheContractNames(GameTestHelper helper) {
        ensureRegistered();
        helper.assertTrue(WebUiServerDispatcher.resolve(STATE_ACTION) != null
                        && WebUiServerDispatcher.resolve(BLUEPRINTS_ACTION) != null,
                "两条 action 必须由 MunitionsWebUiActions.registerAll 注册进派发器");
        helper.assertTrue(WebUiServerDispatcher.resolve("munitions.state") == null
                        && WebUiServerDispatcher.resolve("job.munitions.blueprints") == null,
                "军火商页只有 job.munitions.state 与 job.blueprints 两条, 不得另注册别名");
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 注册守卫。<b>刻意不在测试侧补注册</b>: 补上了, "MunitionsSystem.register 忘了调 MunitionsWebUiActions.registerAll"
     * 这一类装配缺陷就永远测不出来 —— 把生产侧那一行删掉, 本文件全绿, 而真服上前端调 job.munitions.* action 只会拿到
     * 派发器的 "unknown Web UI action" 失败回执, 整个面板全黑。
     *
     * 没注册就是 MunitionsSystem.register 的接线掉了, 直接炸。
     */
    private static void ensureRegistered() {
        if (WebUiServerDispatcher.resolve(STATE_ACTION) == null) {
            throw new IllegalStateException(
                    "job.munitions.* action 未注册: MunitionsSystem.register 没有调用 MunitionsWebUiActions.registerAll");
        }
    }

    private static WebUiAction require(GameTestHelper helper, String action) {
        ensureRegistered();
        WebUiAction handler = WebUiServerDispatcher.resolve(action);
        if (handler == null) {
            helper.fail("action " + action + " 未注册进派发器");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return handler;
    }

    private static JsonObject state(GameTestHelper helper, ServerPlayer sender) {
        return JsonParser.parseString(require(helper, STATE_ACTION).handle(sender, new JsonObject()))
                .getAsJsonObject();
    }

    private static JsonObject blueprints(GameTestHelper helper, ServerPlayer sender) {
        return JsonParser.parseString(require(helper, BLUEPRINTS_ACTION).handle(sender, new JsonObject()))
                .getAsJsonObject();
    }

    private static JsonObject station(GameTestHelper helper, JsonObject state, String stationId) {
        for (JsonElement element : state.getAsJsonArray("stations")) {
            JsonObject row = element.getAsJsonObject();
            if (stationId.equals(row.get("stationId").getAsString())) {
                return row;
            }
        }
        helper.fail("回执里没有 " + stationId + " 这一行");
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static JsonObject blueprintRow(GameTestHelper helper, JsonArray rows, String blueprintId) {
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            if (blueprintId.equals(row.get("blueprintId").getAsString())) {
                return row;
            }
        }
        helper.fail("图纸表里没有 " + blueprintId);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static boolean hasPart(JsonObject blueprintRow, GunsmithPressPart part) {
        for (JsonElement element : blueprintRow.getAsJsonArray("requiredParts")) {
            if (part.id().equals(element.getAsJsonObject().get("partId").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static void assertPos(GameTestHelper helper, JsonObject stationRow, BlockPos expected, String label) {
        helper.assertFalse(stationRow.get("pos").isJsonNull(), label + " 应当被扫到, 实得 pos=null");
        JsonObject pos = stationRow.getAsJsonObject("pos");
        helper.assertTrue(pos.get("x").getAsInt() == expected.getX()
                        && pos.get("y").getAsInt() == expected.getY()
                        && pos.get("z").getAsInt() == expected.getZ(),
                label + " 的坐标必须是 " + expected + ", 实得 " + pos);
    }

    private static int bufferCap(GameTestHelper helper, JsonObject state) {
        return station(helper, state, STATION_BENCH).getAsJsonObject("detail").get("bufferCap").getAsInt();
    }

    private static int primerCount(MunitionsBenchBlockEntity bench) {
        return bench.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER).getCount();
    }

    // ---- 世界搭建 ----

    /** 在 BENCH_REL 放一台全档军火台并指定主人。 */
    private static MunitionsBenchBlockEntity placeBench(GameTestHelper helper, UUID owner) {
        return placeBench(helper, owner, BENCH_REL);
    }

    /** 在任意相对坐标放一台全档军火台并指定主人 (F053 环扫测试用非固定坐标)。 */
    private static MunitionsBenchBlockEntity placeBench(GameTestHelper helper, UUID owner, BlockPos rel) {
        helper.setBlock(rel, ModMunitionsBlocks.MUNITIONS_BENCH.get());
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(rel))
                instanceof MunitionsBenchBlockEntity bench)) {
            throw new IllegalStateException("军火台 BE 没有出现在 " + rel);
        }
        bench.setOwner(owner);
        return bench;
    }

    private static GunsmithPressBlockEntity placePress(GameTestHelper helper) {
        return placePress(helper, PRESS_REL);
    }

    /** 在任意相对坐标放一台冲压机 (F053 环扫测试用非固定坐标)。 */
    private static GunsmithPressBlockEntity placePress(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, ModMunitionsBlocks.GUNSMITH_PRESS.get());
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(rel))
                instanceof GunsmithPressBlockEntity press)) {
            throw new IllegalStateException("冲压机 BE 没有出现在 " + rel);
        }
        return press;
    }

    /**
     * 装配台是 2x2 结构: 四格必须一次摆齐, 否则任一格的 updateShape 会把结构判成断裂并自毁
     * (与 GunsmithAssemblyBusinessGameTests.placeStructure 同一手法)。
     */
    private static GunsmithAssemblyBenchBlockEntity placeAssembly(GameTestHelper helper) {
        GunsmithAssemblyBenchBlock block =
                (GunsmithAssemblyBenchBlock) ModMunitionsBlocks.GUNSMITH_ASSEMBLY_BENCH.get();
        Direction facing = Direction.NORTH;
        for (GunsmithAssemblyBenchBlock.Part part : GunsmithAssemblyBenchBlock.Part.values()) {
            BlockState state = block.defaultBlockState()
                    .setValue(GunsmithAssemblyBenchBlock.FACING, facing)
                    .setValue(GunsmithAssemblyBenchBlock.PART, part)
                    .setValue(GunsmithAssemblyBenchBlock.ACTIVE, false);
            helper.setBlock(GunsmithAssemblyBenchBlock.partPos(ASSEMBLY_MAIN_REL, facing, part), state);
        }
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(ASSEMBLY_MAIN_REL))
                instanceof GunsmithAssemblyBenchBlockEntity assembly)) {
            throw new IllegalStateException("装配台 BE 没有出现在 " + ASSEMBLY_MAIN_REL);
        }
        return assembly;
    }

    /** 把玩家挪到某个台位上 (就近取台按玩家 blockPosition 找; 不挪的话 mock 玩家还站在世界出生点)。 */
    private static void standAt(GameTestHelper helper, ServerPlayer player, BlockPos rel) {
        BlockPos abs = helper.absolutePos(rel);
        player.moveTo(abs.getX() + 0.5D, abs.getY(), abs.getZ() + 0.5D);
    }

    /** 备一整批料 (四件套各按 config 的单批消耗量放)。 */
    private static void stockOneBatch(MunitionsBenchBlockEntity bench) {
        bench.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_PRIMER,
                new ItemStack(ModMunitionsItems.PRIMER.get(), MunitionsConfig.RECIPE_PRIMER_COST.get()));
        bench.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_CASING,
                new ItemStack(ModMunitionsItems.CASING.get(), MunitionsConfig.RECIPE_CASING_COST.get()));
        bench.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_BULLET_HEAD,
                new ItemStack(ModMunitionsItems.BULLET_HEAD.get(), MunitionsConfig.RECIPE_BULLET_HEAD_COST.get()));
        bench.inventory().setStackInSlot(MunitionsBenchBlockEntity.SLOT_PROPELLANT,
                new ItemStack(ModMunitionsItems.PROPELLANT.get(), MunitionsConfig.RECIPE_PROPELLANT_COST.get()));
    }

    /**
     * 把 BE 的 lastSettleTick 经 NBT 往返注入回拨, 使下一次结算的 elapsed 恰为 ticksAgo。
     * GameTest 世界主时钟不可在单测内直控, NBT 往返是唯一确定性手段 (与 MunitionsGameTests 同一手法):
     * saveWithoutMetadata 取全状态, 只改时间戳再 load 回, 主人/选中口径/料槽原样保留。
     */
    private static void backdateSettleTick(MunitionsBenchBlockEntity bench, GameTestHelper helper, long ticksAgo) {
        CompoundTag tag = bench.saveWithoutMetadata();
        tag.putLong("LastSettleTick", helper.getLevel().getGameTime() - ticksAgo);
        tag.putBoolean("SettleInitialized", true);
        bench.load(tag);
    }
}
