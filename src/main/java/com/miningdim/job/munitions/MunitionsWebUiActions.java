package com.miningdim.job.munitions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.munitions.block.GunsmithAssemblyBenchBlockEntity;
import com.miningdim.job.munitions.block.GunsmithPressBlockEntity;
import com.miningdim.job.munitions.block.MunitionsBenchBlock;
import com.miningdim.job.munitions.block.MunitionsBenchBlockEntity;
import com.miningdim.job.munitions.gunsmith.GunsmithAssemblyRecipe;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprint;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPartVariant;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

/**
 * 军火商面板的两条 WebUiAction: job.munitions.state (三台机器的远程只读镜像) 与 job.blueprints (图纸静态表)。
 *
 * <h2>只读纪律</h2>
 * 两条都不写任何状态。特别是 job.munitions.state <b>绝不</b>调 {@link MunitionsBenchBlockEntity#onAccess}
 * 或 settleForOwner —— 那条路径会扣工费、扣料、发经验。面板刷新是玩家每隔几秒就会触发一次的读操作, 把结算
 * 挂在它上面等于让"开着面板"变成一种产能加速器, 且远程结算绕开了 "GUI 打开帧 / tick 帧" 这两个本就受
 * 主人在线约束的结算时机。数值一律经各 BE 的 {@link ContainerData} 读 —— 与原生 GUI 同一份权威快照, 不新开取数路径。
 *
 * <h2>台位怎么找到 (与前端假定的差异, 已写进交付报告)</h2>
 * 全工程<b>没有</b>"玩家 -&gt; 已放置台位坐标"的注册表: {@link MunitionsSavedData} 只按 UUID 记军火台<b>数量</b>,
 * 冲压机与装配台连归属字段都没有。故本 action 按"发送者当前所在维度、以其所在区块为心的
 * {@value #SEARCH_CHUNK_RADIUS} 区块半径内、且<b>已加载</b>的区块"逐台就近取:
 *  - 军火台额外过归属 ({@link MunitionsBenchBlockEntity#isOwner}), 别人的台不会出现在你的面板上;
 *  - 冲压机/装配台<b>无归属概念</b> (游戏内谁都能右键打开, 没有上锁位), 故只按距离取 —— 这不新增泄露面,
 *    它显示的东西与走过去右键一下看到的完全一致。
 * 因此 pos 为 null 的语义是"这个半径内没扫到", 不是"你没造过"。全局造了几台由 benchesPlaced/benchCap 两个
 * 字段回答 (那两个才是跨维度的权威计数)。
 *
 * <h2>三行的形状</h2>
 * stations 恒 3 行且顺序恒定 (军火台/冲压机/装配台)。共有字段 (stationId/nameKey/pos/running/progressTicks/
 * requiredTicks/outputItemId/outputCount) 三行同形; 各台<b>特有</b>的状态收在 detail 里 —— 口径与缓冲只有军火台
 * 有, 平台/部位/品质/变体只有冲压机有, 图纸只有装配台有。把它们摊平到同一层会让每行长出十几个"对另外两台恒为
 * null"的键, 前端每渲染一行都要先猜自己在渲染哪一台。
 *
 * <h2>回执体积</h2>
 * job.munitions.state 恒 3 行, 无增长路径。job.blueprints 是 {@link GunsmithBlueprint} 枚举整表 dump
 * (今 9 款枪 x 最多 11 个部位), 编译期定长, 运行期不可能变长, 故不做分页 —— 静态表分页只会让前端多写一个
 * 永远只有一页的翻页器。体积由 {@code MunitionsWebUiGameTests} 的余量断言守住: 枚举涨到撑破下行上限之前,
 * 那条测试先红。
 */
public final class MunitionsWebUiActions {

    /**
     * 本类专用 Gson: 必须 serializeNulls。
     *
     * 三台机器没扫到时 pos 是真 null, 空闲时 outputItemId 是真 null, 未选口径时 caliberId 是真 null。
     * 默认 Gson 会把值为 null 的键<b>整键丢掉</b>, 前端契约写的是 {@code T | null}, 拿到 undefined 即契约破裂
     * (与 {@code MinerWebUiActions} 同一条理由)。
     */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /** 三台机器的稳定 stationId (逐字取自方块注册名; 前端按它认行, 数组顺序恒定为 台/冲压/装配)。 */
    private static final String STATION_BENCH = "munitions_bench";
    private static final String STATION_PRESS = "gunsmith_press";
    private static final String STATION_ASSEMBLY = "gunsmith_assembly_bench";

    /**
     * 就近搜索半径 (区块)。4 区块 = {@value #SEARCH_RADIUS_BLOCKS} 格, 覆盖一个基地的量级, 又不会把
     * "整个存档扫一遍" 的代价压到每次面板刷新上。只看 {@code getChunkNow} 拿得到的已加载区块: 面板刷新
     * 绝不能顺手把区块加载起来 (那会给玩家一个用平板强制加载区块的手段)。
     */
    private static final int SEARCH_CHUNK_RADIUS = 4;

    /** 搜索半径的方块表示 (下发给前端解释 pos=null 的含义)。 */
    private static final int SEARCH_RADIUS_BLOCKS = SEARCH_CHUNK_RADIUS * 16;

    private static final double SEARCH_RADIUS_SQR =
            (double) SEARCH_RADIUS_BLOCKS * SEARCH_RADIUS_BLOCKS;

    /**
     * 无归属机台 (枪匠冲压机 / 装配台) 的可见半径。这两台在设计上是公用设施, BE 里没有 ownerUUID 也没有锁,
     * 所以服务端<b>无法</b>判断某台是不是调用者的。
     *
     * 若按军火台那 64 格发, 面板就成了一台隔墙远程坐标探测器: 站在地表刷一下, 就能拿到别人地下密闭基地里
     * 那两台机器的精确坐标, 外加他正在冲什么零件 —— 无需探索、无需破墙、无需视线。
     * 压到 16 格后, 要看到别人的机台就得先走到它跟前, 此时肉眼本来也看得见, 面板不再额外泄漏任何东西。
     *
     * 真正的解法是给这两个 BE 补归属字段 (像军火台那样), 那是持久化改动, 不属于本轮接线范围。
     */
    private static final int PUBLIC_STATION_RADIUS_BLOCKS = 16;

    private static final double PUBLIC_STATION_RADIUS_SQR =
            (double) PUBLIC_STATION_RADIUS_BLOCKS * PUBLIC_STATION_RADIUS_BLOCKS;

    /**
     * 无归属机台参与判定的区块环上限 (F053 扫描收敛用)。
     *
     * 推导: 区块偏移 {@code |d| >= 2} 的任意方块与玩家的最小距离 &gt;= {@code (|d|-1)*16 + 1 = 17} 格,
     * 大于 {@link #PUBLIC_STATION_RADIUS_BLOCKS}=16, 故这两台机器不可能出现在 {@code |d| >= 2} 的区块环里 ——
     * 只扫 r &lt;= 1 (以玩家所在区块为心的 3x3 区块) 与扫全部 81 个区块相比, 判定结果逐字相同。
     */
    private static final int PUBLIC_STATION_CHUNK_RADIUS = 1;

    /**
     * 军火台进度的 tick 还原系数。
     *
     * 军火台的 ContainerData 把进度<b>按秒</b>过线 (vanilla 的 ClientboundContainerSetDataPacket 的 value 是
     * int16, L1 一批 57600 tick 直发即符号回绕), 见 MunitionsBenchBlockEntity.dataAccess。此处 x20 还原成 tick,
     * 与 {@code MunitionsBenchMenu.productionProgressTicks} 逐字同一口径 —— 面板与原生 GUI 必须显示同一个数。
     */
    private static final int BENCH_PROGRESS_TICKS_PER_UNIT = 20;

    private MunitionsWebUiActions() {
    }

    /** 把两条 action 注册进派发器 (由 {@link MunitionsSystem#register} 调用一次)。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("job.munitions.state", STATE);
        WebUiServerDispatcher.register("job.blueprints", BLUEPRINTS);
    }

    // ============================================================
    // job.munitions.state: {} -> 三台机器的只读镜像 + 全局台数
    // ============================================================

    static final WebUiAction STATE = (sender, payload) -> {
        int level = JobServices.jobService().level(sender, JobId.MUNITIONS);

        JsonObject result = new JsonObject();
        result.addProperty("level", level);
        // 全局台数是跨维度的权威事实 (SavedData 按 UUID 计), 与就近扫到几台无关 —— 前端要靠它区分
        // "我根本没造" 和 "我人不在台子旁边"。
        result.addProperty("benchCap", MunitionsLevels.tableCount(level));
        result.addProperty("benchesPlaced",
                MunitionsSavedData.get(sender.server.overworld()).benchCount(sender.getUUID()));
        result.addProperty("searchRadiusBlocks", SEARCH_RADIUS_BLOCKS);
        // 两台公用机台的可见半径比军火台小得多, 且原因与"没造"无关。不单独发这个数, 前端就只能拿
        // searchRadiusBlocks 去解释 pos=null, 把"站远了"错讲成"你还没造"。
        result.addProperty("publicStationRadiusBlocks", PUBLIC_STATION_RADIUS_BLOCKS);
        // 枪匠冲压/装配整条链的总开关 (3A 章试作, 默认关)。关着时装配台点开工只会吐一句拒绝, 面板必须先讲清楚。
        result.addProperty("gunsmithEnabled", MunitionsConfig.GUNSMITH_ENABLED.get());

        NearbyStations nearby = NearbyStations.around(sender);
        JsonArray stations = new JsonArray();
        stations.add(benchJson(nearby.bench, level));
        stations.add(pressJson(nearby.press));
        stations.add(assemblyJson(nearby.assembly));
        result.add("stations", stations);
        return GSON.toJson(result);
    };

    /** 军火台一行。未扫到时除 stationId/nameKey 外全是 null/false/0 (前端见 pos=null 即不渲染遥测)。 */
    private static JsonObject benchJson(@Nullable MunitionsBenchBlockEntity bench, int ownerLevel) {
        JsonObject row = stationRow(STATION_BENCH, ModMunitionsBlocks.MUNITIONS_BENCH.get().getDescriptionId());
        JsonObject detail = new JsonObject();
        row.add("detail", detail);
        if (bench == null) {
            return row;
        }

        // 这一行的 nameKey 换成实际扫到的那一档的方块名 (六档军火台是六个注册名, 产能上限各不相同)。
        row.addProperty("nameKey", bench.getBlockState().getBlock().getDescriptionId());
        row.add("pos", posJson(bench.getBlockPos()));

        ContainerData data = bench.dataAccess();
        row.addProperty("running", data.get(MunitionsBenchBlockEntity.DATA_CRAFTING_ACTIVE) != 0);
        row.addProperty("progressTicks",
                data.get(MunitionsBenchBlockEntity.DATA_PRODUCTION_PROGRESS_TICKS) * BENCH_PROGRESS_TICKS_PER_UNIT);
        row.addProperty("requiredTicks",
                data.get(MunitionsBenchBlockEntity.DATA_PRODUCTION_REQUIRED_TICKS) * BENCH_PROGRESS_TICKS_PER_UNIT);
        putOutput(row, bench.inventory().getStackInSlot(MunitionsBenchBlockEntity.SLOT_OUTPUT));

        int caliberIndex = data.get(MunitionsBenchBlockEntity.DATA_SELECTED_CALIBER);
        // -1 是 "未选口径" 的哨兵。不能直接喂 byIndex —— 它对越界值兜底回 PISTOL, 面板会凭空显示选了手枪弹。
        if (caliberIndex < 0) {
            detail.add("caliberId", JsonNull.INSTANCE);
        } else {
            detail.addProperty("caliberId", MunitionsCaliber.byIndex(caliberIndex).name().toLowerCase());
        }
        // 缓冲发数才是产出权威: 输出槽的 TACZ 弹只是它的可视物化, TACZ 未装时槽恒空而缓冲照常累积。
        detail.addProperty("bufferedRounds", data.get(MunitionsBenchBlockEntity.DATA_BUFFERED_ROUNDS));
        detail.addProperty("locked", data.get(MunitionsBenchBlockEntity.DATA_LOCKED) != 0);

        // 下面三个数按台主当前等级现算, 不读 BE 的 ContainerData。那三格背后是 ownerLevelCache /
        // refineUnlockedForOwnerCache 两个缓存, 只在开 GUI / 选口径 / 开工 / 已选口径的结算里才刷新:
        // 一台放下后没选过口径的台, 缓存恒停在默认 1 级, 而玩家走过去右键时 onAccess 会无条件先刷缓存,
        // 于是原生 GUI 显示 L6 / 面板显示 L1, 同一台机器两个界面三个数全不一样。
        // 本行已按 isOwner 过滤 (扫到的必是自己的台), sender 的等级就是台主等级, 直接现算即可。
        int effectiveLevel = effectiveBenchLevel(bench, ownerLevel);
        detail.addProperty("bufferCap", MunitionsLevels.bufferPerTable(effectiveLevel));
        detail.addProperty("refineUnlocked", MunitionsLevels.isRefineUnlocked(effectiveLevel));
        // 台档会把主人等级压到该档上限 (旧注册名的台才是全档), 故这里发的是"这台实际按几级算产能"。
        detail.addProperty("effectiveLevel", effectiveLevel);
        detail.addProperty("continuousCrafting",
                data.get(MunitionsBenchBlockEntity.DATA_CONTINUOUS_CRAFTING) != 0);
        return row;
    }

    /**
     * 这台按几级算产能。与 {@code MunitionsBenchBlockEntity.effectiveOwnerLevel} 同口径: 台档 (六个注册名)
     * 会把主人等级钳到该档上限, 非军火台方块 (理论上不可达) 回落到纯等级钳制。
     */
    private static int effectiveBenchLevel(MunitionsBenchBlockEntity bench, int ownerLevel) {
        return bench.getBlockState().getBlock() instanceof MunitionsBenchBlock benchBlock
                ? benchBlock.effectiveLevelFor(ownerLevel)
                : MunitionsLevels.clampLevel(ownerLevel);
    }

    /** 枪匠冲压机一行。它的 ContainerData 进度是真 tick (不像军火台按秒过线), 原样发。 */
    private static JsonObject pressJson(@Nullable GunsmithPressBlockEntity press) {
        JsonObject row = stationRow(STATION_PRESS, ModMunitionsBlocks.GUNSMITH_PRESS.get().getDescriptionId());
        JsonObject detail = new JsonObject();
        row.add("detail", detail);
        if (press == null) {
            return row;
        }

        row.add("pos", posJson(press.getBlockPos()));
        ContainerData data = press.dataAccess();
        row.addProperty("running", data.get(GunsmithPressBlockEntity.DATA_ACTIVE) != 0);
        row.addProperty("progressTicks", data.get(GunsmithPressBlockEntity.DATA_PROGRESS_TICKS));
        row.addProperty("requiredTicks", data.get(GunsmithPressBlockEntity.DATA_REQUIRED_TICKS));
        putOutput(row, press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_OUTPUT));

        detail.addProperty("platformId",
                GunsmithPlatform.byIndex(data.get(GunsmithPressBlockEntity.DATA_SELECTED_PLATFORM)).id());
        detail.addProperty("partId",
                GunsmithPressPart.byIndex(data.get(GunsmithPressBlockEntity.DATA_SELECTED_PART)).id());
        detail.addProperty("qualityId",
                GunsmithPartQuality.byIndex(data.get(GunsmithPressBlockEntity.DATA_SELECTED_QUALITY)).id());
        detail.addProperty("variantId",
                GunsmithPartVariant.byIndex(data.get(GunsmithPressBlockEntity.DATA_SELECTED_VARIANT)).id());
        return row;
    }

    /**
     * 枪匠装配台一行。
     *
     * 它<b>没有</b> ContainerData (菜单直接持 BE 引用, 见 GunsmithAssemblyMenu), 也没有暴露已进行 tick 数的只读
     * 方法 —— animationEndTick 是私有字段。故 progressTicks 发真 null 而不是编一个 0 出来: 发 0 会让面板画出一条
     * "运行中但进度永远为零" 的进度条, 那是假数据。requiredTicks 发常量 ASSEMBLY_DURATION_TICKS (真值),
     * 前端在 progressTicks 为 null 时应当画不定态 (来回跑的条) 而不是 0%。
     */
    private static JsonObject assemblyJson(@Nullable GunsmithAssemblyBenchBlockEntity assembly) {
        JsonObject row = stationRow(STATION_ASSEMBLY,
                ModMunitionsBlocks.GUNSMITH_ASSEMBLY_BENCH.get().getDescriptionId());
        JsonObject detail = new JsonObject();
        row.add("detail", detail);
        if (assembly == null) {
            return row;
        }

        row.add("pos", posJson(assembly.getBlockPos()));
        row.addProperty("running", assembly.isAnimating());
        row.addProperty("requiredTicks", GunsmithAssemblyBenchBlockEntity.ASSEMBLY_DURATION_TICKS);
        putOutput(row, assembly.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT));

        ItemStack blueprintStack = assembly.inventory()
                .getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT);
        if (GunsmithAssemblyRecipe.isBlueprint(blueprintStack)) {
            detail.addProperty("blueprintId", GunsmithAssemblyRecipe.blueprint(blueprintStack).templateId());
        } else {
            detail.add("blueprintId", JsonNull.INSTANCE);
        }
        return row;
    }

    /** 一行机器的空壳 (未扫到时的完整形状; 扫到后由各自的 json 方法逐字段覆盖)。 */
    private static JsonObject stationRow(String stationId, String nameKey) {
        JsonObject row = new JsonObject();
        row.addProperty("stationId", stationId);
        row.addProperty("nameKey", nameKey);
        row.add("pos", JsonNull.INSTANCE);
        row.addProperty("running", false);
        row.add("progressTicks", JsonNull.INSTANCE);
        row.add("requiredTicks", JsonNull.INSTANCE);
        row.add("outputItemId", JsonNull.INSTANCE);
        row.addProperty("outputCount", 0);
        return row;
    }

    /** 输出槽物化结果; 空槽发 null 而不是空串 (空串会被前端渲染成一个没有名字的物品格)。 */
    private static void putOutput(JsonObject row, ItemStack output) {
        if (output.isEmpty()) {
            return;
        }
        row.addProperty("outputItemId", itemId(output.getItem()));
        row.addProperty("outputCount", output.getCount());
    }

    private static JsonObject posJson(BlockPos pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }

    // ============================================================
    // job.blueprints: {} -> GunsmithBlueprint 枚举整表
    // ============================================================

    /**
     * 图纸百科 (纯静态表, 与玩家状态无关)。
     *
     * 一律发翻译键不发中文: 专用服务端不加载 lang 文件, 在服务端 getString() 出来的只会是键本身。
     * 枪名键 {@code tacz.gun.*.name} 属 TACZ 的 lang, 未装 TACZ 的客户端解不出 —— 那是真实情况, 不在这里
     * 伪造一个本 mod 的替代名。
     *
     * 零件的物品 id 提到顶层只发一份 (partItemId/partDescriptionId), 不在每个部位行里重复: 九款图纸共 50 余个
     * 部位行, 而它们的 itemId <b>逐字相同</b> —— 195 种零件全注册在同一个 {@code miningdim:gunsmith_part} 之下,
     * 靠 NBT 区分。每行重复一遍就是白烧三千字符的下行预算, 而这条回执的上限是 32767。部位靠 partId/labelKey 显示,
     * itemId 只用来取图标。
     */
    static final WebUiAction BLUEPRINTS = (sender, payload) -> {
        Item partItem = ModMunitionsItems.GUNSMITH_PART.get();

        JsonArray blueprints = new JsonArray();
        for (GunsmithBlueprint blueprint : GunsmithBlueprint.values()) {
            JsonObject row = new JsonObject();
            row.addProperty("blueprintId", blueprint.templateId());
            row.addProperty("gunId", blueprint.gunId().toString());
            // 图纸物品名是 "<枪名> 图纸" 这个套壳键 + 枪名键两层, 两个都发, 前端自己拼 (与物品栏里的名字一致)。
            row.addProperty("nameKey", "item.miningdim.gunsmith_blueprint.name");
            row.addProperty("gunNameKey", blueprint.nameKey());
            row.addProperty("platformId", blueprint.platform().id());
            row.addProperty("platformLabelKey", blueprint.platform().labelKey());

            JsonArray parts = new JsonArray();
            for (GunsmithPressPart part : blueprint.requiredParts()) {
                JsonObject partRow = new JsonObject();
                partRow.addProperty("partId", part.id());
                partRow.addProperty("labelKey", part.labelKey());
                // 每个部位恰好 1 件: 装配台的部位槽 getSlotLimit 恒 1, 开工时每个必需部位 extractItem(…, 1)。
                partRow.addProperty("count", 1);
                parts.add(partRow);
            }
            row.add("requiredParts", parts);
            blueprints.add(row);
        }

        JsonObject result = new JsonObject();
        result.add("blueprints", blueprints);
        result.addProperty("blueprintCount", blueprints.size());
        result.addProperty("partItemId", itemId(partItem));
        result.addProperty("partDescriptionId", partItem.getDescriptionId());
        // 装配台总开关同发一份: 图纸页最常见的疑问就是"照着做为什么点不动"。
        result.addProperty("gunsmithEnabled", MunitionsConfig.GUNSMITH_ENABLED.get());
        return GSON.toJson(result);
    };

    private static String itemId(Item item) {
        return ForgeRegistries.ITEMS.getKey(item).toString();
    }

    // ============================================================
    // 就近取台
    // ============================================================

    /**
     * 发送者附近三台机器的 BE (各取最近的一台; 没有则 null)。
     *
     * 只遍历已加载区块的 BE 索引表 ({@link LevelChunk#getBlockEntities()}), 不逐格 getBlockState —— 一个
     * 9x9 区块的方块盒是三百多万格, 而 BE 索引表通常只有几十项。
     */
    private static final class NearbyStations {

        @Nullable
        private MunitionsBenchBlockEntity bench;
        @Nullable
        private GunsmithPressBlockEntity press;
        @Nullable
        private GunsmithAssemblyBenchBlockEntity assembly;

        private double benchDistSqr = Double.MAX_VALUE;
        private double pressDistSqr = Double.MAX_VALUE;
        private double assemblyDistSqr = Double.MAX_VALUE;

        /**
         * 按环 (Chebyshev 距离) 由内向外扫, 而不是无条件扫满 {@value #SEARCH_CHUNK_RADIUS} 半径的正方形区块盒。
         * 与今天逐字相同的判定结果由两点保证:
         *  - 无归属机台只在 r &lt;= {@link #PUBLIC_STATION_CHUNK_RADIUS} 的环参与判定 (推导见该常量注释), 更外层
         *    的环本就不可能让它们命中距离截断, 跳过对应的两段 instanceof 判定不改变最终选出的那一台;
         *  - 军火台在每环扫完后做提前收敛: 若本环结束时已有命中且其距离 &lt;= r*16, 未访问的环 r+1 上任意方块
         *    与玩家的最小距离 &gt;= r*16 (环 r+1 的区块与玩家所在区块的 Chebyshev 距离为 r+1, 故其最近的方块
         *    到玩家所在区块边缘至少还有 r*16 格), 不可能比已找到的更近, 故可安全提前退出。
         *    常见情形 (玩家就站在自家台旁) 会在 r=1 收敛, 81 个区块降到 9 个; 附近确实没台的最坏情形下,
         *    扫描范围与今天完全一致。
         */
        static NearbyStations around(ServerPlayer sender) {
            NearbyStations found = new NearbyStations();
            ServerLevel level = sender.serverLevel();
            BlockPos origin = sender.blockPosition();
            ChunkPos center = new ChunkPos(origin);
            for (int r = 0; r <= SEARCH_CHUNK_RADIUS; r++) {
                boolean publicStationRing = r <= PUBLIC_STATION_CHUNK_RADIUS;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue; // 内圈已在更早的 r 扫过, 只取本环的边界偏移。
                        }
                        LevelChunk chunk = level.getChunkSource().getChunkNow(center.x + dx, center.z + dz);
                        if (chunk == null) {
                            continue; // 未加载: 跳过而不是 getChunk 强载 (面板刷新不许变成区块加载器)。
                        }
                        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                            found.offer(sender, origin, blockEntity, publicStationRing);
                        }
                    }
                }
                double convergedRadius = (double) r * 16;
                if (found.bench != null && found.benchDistSqr <= convergedRadius * convergedRadius) {
                    break;
                }
            }
            return found;
        }

        private void offer(ServerPlayer sender, BlockPos origin, BlockEntity blockEntity,
                            boolean publicStationRing) {
            double distSqr = blockEntity.getBlockPos().distSqr(origin);
            if (blockEntity instanceof MunitionsBenchBlockEntity candidate) {
                // 军火台唯一有归属字段的一台: 只认自己的, 别人的产线不进你的面板。
                // 距离另需真截断: 区块盒最远可达 SEARCH_CHUNK_RADIUS 环, 不截断则回执里
                // 那个 searchRadiusBlocks=64 是句谎话, 前端照它画距离刻度会把台位画到圈外。
                if (candidate.isOwner(sender) && distSqr <= SEARCH_RADIUS_SQR && distSqr < benchDistSqr) {
                    bench = candidate;
                    benchDistSqr = distSqr;
                }
            } else if (!publicStationRing) {
                // r > PUBLIC_STATION_CHUNK_RADIUS: 无归属机台在这个环里不可能命中距离截断 (推导见该常量注释),
                // 省去两段 instanceof。
            } else if (blockEntity instanceof GunsmithPressBlockEntity candidate) {
                if (distSqr <= PUBLIC_STATION_RADIUS_SQR && distSqr < pressDistSqr) {
                    press = candidate;
                    pressDistSqr = distSqr;
                }
            } else if (blockEntity instanceof GunsmithAssemblyBenchBlockEntity candidate) {
                if (distSqr <= PUBLIC_STATION_RADIUS_SQR && distSqr < assemblyDistSqr) {
                    assembly = candidate;
                    assemblyDistSqr = distSqr;
                }
            }
        }
    }
}
