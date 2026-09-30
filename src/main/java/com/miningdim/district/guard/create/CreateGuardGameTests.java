package com.miningdim.district.guard.create;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.guard.DistrictWorldGuards;
import com.miningdim.district.guard.GuardActors;
import com.miningdim.district.guard.GuardEvents;
import com.miningdim.district.guard.GuardEvents.PlacementVerdict;
import com.miningdim.district.guard.GuardSettings;
import com.miningdim.district.guard.GuardTestZones;
import com.miningdim.district.guard.GuardView;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 机械动力的分类与判定 (设计文档 22.4、22.5、22.6、22.14), 同步、不需要机械动力: 分类只用注册名、EntityBlock 与按类名找的
 * 接口, 测试设置把 minecraft 当成"机械动力"的命名空间; 判定函数 (CreateGuards、放置的 22.5 表、OP 认法) 只用 Minecraft
 * 类型, 直接调。区域放在远离任何结构的绝对坐标上 (只判定、不碰方块), 每条用例结束时拿掉。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class CreateGuardGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_create_policy";

    /** 远离任何 GameTest 结构的测试区: X/Z 各 100 格。 */
    private static final int X0 = 1_000_000;
    private static final int Z0 = 1_000_000;
    private static final int Y = -50;

    private CreateGuardGameTests() {
    }

    /** 测试设置: minecraft 算"机械动力", 拒绝活塞 (没有方块实体), 放行箱子 (有方块实体)。 */
    public static GuardSettings testSettings() {
        return GuardSettings.parse(true, true, List.of("minecraft"), List.of("minecraft:piston"),
                List.of("minecraft:chest"), true, namespace -> true, GuardSettings::blockRegistered);
    }

    @BeforeBatch(batch = BATCH)
    public static void beforeBatch(ServerLevel level) {
        GuardTestZones.begin(testSettings());
    }

    @AfterBatch(batch = BATCH)
    public static void afterBatch(ServerLevel level) {
        GuardTestZones.end();
    }

    /** 在远处装一个 100 × 100 的测试区, 返回用例键。 */
    private static String farDistrict(GameTestHelper helper, String key, int offset) {
        GuardTestZones.putAbsolute(helper.getLevel().dimension().location().toString(), key, X0 + offset, Z0,
                X0 + offset + 99, Z0 + 99);
        return key;
    }

    // ================================================================
    // 分类 (22.4)
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void classificationFollowsNamespaceBlockEntityAndLists(GameTestHelper helper) {
        CreateBlockPolicy policy = new CreateBlockPolicy(testSettings(), null);
        helper.assertTrue(policy.isMachine(Blocks.FURNACE), "带方块实体的是机器 (熔炉)");
        helper.assertTrue(!policy.isMachine(Blocks.CHEST), "allowBlocks 里的放行 (箱子)");
        helper.assertTrue(!policy.isMachine(Blocks.STONE), "没有方块实体、不会转的是装饰 (石头)");
        helper.assertTrue(policy.isMachine(Blocks.PISTON), "denyBlocks 里的按机器拦 (活塞)");
        helper.assertTrue(policy.isMachine(Blocks.FURNACE) && !policy.isMachine(Blocks.STONE), "缓存结果不变");
        CreateBlockPolicy kinetic = new CreateBlockPolicy(testSettings(), SimpleWaterloggedBlock.class);
        helper.assertTrue(kinetic.isMachine(Blocks.OAK_STAIRS) && !policy.isMachine(Blocks.OAK_STAIRS),
                "实现了\"会转\"接口的方块按机器拦 (以 SimpleWaterloggedBlock 代替 IRotate)");
        CreateBlockPolicy createOnly = new CreateBlockPolicy(GuardSettings.parse(true, true, List.of("create"),
                List.of(), List.of(), true, namespace -> false, id -> false), null);
        helper.assertTrue(!createOnly.isMachine(Blocks.FURNACE) && !createOnly.isMachine(Blocks.PISTON),
                "命名空间不在 namespaces 里的一律不是机器; 换一份设置就换一份缓存");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void badListEntriesAreReportedAndIgnored(GameTestHelper helper) {
        GuardSettings settings = GuardSettings.parse(true, true, List.of("minecraft", "Bad Space"),
                List.of("nocolon", "create:foo", "minecraft:no_such_block_gt", "minecraft:piston"),
                List.of("minecraft:chest"), true, namespace -> true, GuardSettings::blockRegistered);
        helper.assertTrue(settings.problems().size() == 4, "四条问题各记一条, 实为 " + settings.problems());
        helper.assertTrue(settings.namespaces().equals(java.util.Set.of("minecraft"))
                        && settings.denyBlocks().equals(java.util.Set.of(new ResourceLocation("minecraft", "piston")))
                        && settings.allowBlocks().equals(java.util.Set.of(new ResourceLocation("minecraft", "chest"))),
                "其余照常, 实为 " + settings);
        GuardSettings modAbsent = GuardSettings.parse(true, true, List.of("create"), List.of("create:not_really"),
                List.of(), true, namespace -> false, id -> false);
        helper.assertTrue(modAbsent.problems().isEmpty()
                        && modAbsent.denyBlocks().contains(new ResourceLocation("create", "not_really")),
                "命名空间的 mod 没装时不核对方块存在与否, 名单照常保留");
        GuardSettings defaults = GuardSettings.defaults();
        helper.assertTrue(defaults.denyBlocks().size() == 4 && defaults.allowBlocks().size() == 9
                        && defaults.namespaces().equals(java.util.Set.of("create", "ignored_void")) && defaults.crossPlot()
                        && defaults.createMachinery() && defaults.denyUse()
                        && (ModList.get().isLoaded("create") || defaults.problems().isEmpty()),
                "代码默认值: create 与 ignored_void, 4 + 9 项, 没装机械动力时不报问题, 实为 " + defaults);
        helper.succeed();
    }

    /**
     * 默认的 namespaces 含 ignored_void (整合包的机械动力重打包把机器方块登记在这个命名空间下, 22.4): 注册名在它下面的
     * 机器, 非 OP 在区内与外围 8 格放不下, 2 级 OP 照常; 同一命名空间里没有方块实体、也不会转的照常可放。开发运行时没有
     * ignored_void 的方块, 用合成的注册名: 高炉记作 ignored_void:gt_voided_machine, 平滑石头记作
     * ignored_void:gt_voided_casing, 其余照注册表。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ignoredVoidBlocksAreMachinesByDefault(GameTestHelper helper) {
        GuardSettings defaults = GuardSettings.defaults();
        helper.assertTrue(defaults.namespaces().contains("ignored_void") && defaults.namespaces().contains("create"),
                "默认的 namespaces 含 create 与 ignored_void, 实为 " + defaults.namespaces());
        Map<Block, ResourceLocation> synthetic = Map.of(
                Blocks.BLAST_FURNACE, new ResourceLocation("ignored_void", "gt_voided_machine"),
                Blocks.SMOOTH_STONE, new ResourceLocation("ignored_void", "gt_voided_casing"));
        CreateBlockPolicy policy = new CreateBlockPolicy(defaults, null,
                block -> synthetic.containsKey(block) ? synthetic.get(block) : ForgeRegistries.BLOCKS.getKey(block));
        helper.assertTrue(policy.isMachine(Blocks.BLAST_FURNACE) && policy.inNamespaces(Blocks.BLAST_FURNACE),
                "ignored_void 下带方块实体的算机器");
        helper.assertTrue(!policy.isMachine(Blocks.SMOOTH_STONE) && policy.inNamespaces(Blocks.SMOOTH_STONE),
                "ignored_void 下没有方块实体、不会转的是装饰");
        helper.assertTrue(!policy.isMachine(Blocks.FURNACE) && !policy.inNamespaces(Blocks.FURNACE),
                "注册名照旧的方块不受影响 (minecraft:furnace 不在默认命名空间里)");

        String key = farDistrict(helper, "ignoredVoid", 4000);
        ServerLevel level = helper.getLevel();
        int x0 = X0 + 4000;
        ServerPlayer real = null;
        ServerPlayer op = null;
        try {
            real = DistrictTestEnv.onlinePlayer(helper, "Gt_Void_Real");
            op = DistrictTestEnv.onlinePlayer(helper, "Gt_Void_Op");
            grantOp(level, op.getGameProfile(), 2);
            GuardView view = new GuardView(defaults, DistrictWorldGuards.view().zones(), policy, true);
            BlockPos inside = new BlockPos(x0 + 50, Y, Z0 + 50);
            BlockPos buffer = new BlockPos(x0 - 8, Y, Z0 + 50);
            BlockPos far = new BlockPos(x0 - 9, Y, Z0 + 50);
            BlockState machine = Blocks.BLAST_FURNACE.defaultBlockState();
            BlockState deco = Blocks.SMOOTH_STONE.defaultBlockState();
            helper.assertTrue(GuardEvents.placementVerdict(view, level, real, machine, List.of(inside))
                            == PlacementVerdict.DENY_DISTRICT
                            && GuardEvents.placementVerdict(view, level, real, machine, List.of(buffer))
                            == PlacementVerdict.DENY_BUFFER
                            && GuardEvents.placementVerdict(view, level, real, machine, List.of(far))
                            == PlacementVerdict.ALLOW,
                    "非 OP: ignored_void 的机器在区内、外围 8 格被拒, 更远照常");
            helper.assertTrue(GuardEvents.placementVerdict(view, level, op, machine, List.of(inside))
                            == PlacementVerdict.ALLOW
                            && GuardEvents.placementVerdict(view, level, op, machine, List.of(buffer))
                            == PlacementVerdict.ALLOW,
                    "2 级 OP 照常 (OP 例外不变)");
            helper.assertTrue(GuardEvents.placementVerdict(view, level, real, deco, List.of(inside))
                    == PlacementVerdict.ALLOW, "非 OP 放 ignored_void 的装饰照常");
        } finally {
            if (op != null) {
                level.getServer().getPlayerList().getOps().remove(op.getGameProfile());
            }
            DistrictTestEnv.removePlayer(helper, real);
            DistrictTestEnv.removePlayer(helper, op);
            GuardTestZones.remove(key);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void kineticInterfaceIsOptional(GameTestHelper helper) {
        Class<?> kinetic = CreateBlockPolicy.findKineticInterface();
        helper.assertTrue((kinetic != null) == ModList.get().isLoaded("create"),
                "IRotate 按类名找: 装了机械动力才找得到, 实为 " + kinetic);
        CreateBlockPolicy policy = CreateBlockPolicy.of(testSettings());
        helper.assertTrue(policy.isMachine(Blocks.FURNACE) && !policy.isMachine(Blocks.STONE),
                "没有机械动力时分类照常, 不抛异常");
        helper.succeed();
    }

    // ================================================================
    // 放置 (22.5) 与认人
    // ================================================================

    /** 22.5 的表逐格: (真玩家非 OP / OP / 机械动力假玩家 / 别的假玩家) × (机器 / 装饰) × (区内 / 外围 / 更远)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void placementDecisionMatrix(GameTestHelper helper) throws Exception {
        String key = farDistrict(helper, "placementMatrix", 0);
        ServerLevel level = helper.getLevel();
        ServerPlayer real = null;
        ServerPlayer op = null;
        GameProfile opProfile = null;
        try (AutoCloseable registered = GuardActors.registerTestCreateFakePlayer(TestCreateFakePlayer.class)) {
            real = DistrictTestEnv.onlinePlayer(helper, "Gt_Matrix_Real");
            op = DistrictTestEnv.onlinePlayer(helper, "Gt_Matrix_Op");
            opProfile = op.getGameProfile();
            grantOp(level, opProfile, 2);
            FakePlayer createFake = new TestCreateFakePlayer(level, new GameProfile(UUID.randomUUID(), "Deployer"));
            FakePlayer otherFake = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "OtherMod"));
            BlockPos inside = new BlockPos(X0 + 50, Y, Z0 + 50);
            BlockPos buffer = new BlockPos(X0 - 8, Y, Z0 + 50);
            BlockPos far = new BlockPos(X0 - 9, Y, Z0 + 50);
            BlockState machine = Blocks.FURNACE.defaultBlockState();
            BlockState deco = Blocks.STONE.defaultBlockState();
            GuardView view = DistrictWorldGuards.view();
            List<String> wrong = new ArrayList<>();
            Map<String, Player> actors = Map.of("real", real, "op", op, "createFake", createFake,
                    "otherFake", otherFake);
            Map<String, PlacementVerdict[]> expected = Map.of(
                    "real/machine", new PlacementVerdict[]{PlacementVerdict.DENY_DISTRICT, PlacementVerdict.DENY_BUFFER,
                            PlacementVerdict.ALLOW},
                    "real/deco", allow(),
                    "op/machine", allow(),
                    "op/deco", allow(),
                    "createFake/machine", new PlacementVerdict[]{PlacementVerdict.DENY_CREATE_ACTOR,
                            PlacementVerdict.DENY_CREATE_ACTOR, PlacementVerdict.ALLOW},
                    "createFake/deco", new PlacementVerdict[]{PlacementVerdict.DENY_CREATE_ACTOR,
                            PlacementVerdict.ALLOW, PlacementVerdict.ALLOW},
                    "otherFake/machine", allow(),
                    "otherFake/deco", allow());
            for (Map.Entry<String, Player> actor : actors.entrySet()) {
                for (String kind : List.of("machine", "deco")) {
                    BlockState placed = "machine".equals(kind) ? machine : deco;
                    PlacementVerdict[] want = expected.get(actor.getKey() + "/" + kind);
                    BlockPos[] where = {inside, buffer, far};
                    for (int i = 0; i < where.length; i++) {
                        PlacementVerdict got = GuardEvents.placementVerdict(view, level, actor.getValue(), placed,
                                List.of(where[i]));
                        if (got != want[i]) {
                            wrong.add(actor.getKey() + "/" + kind + "@" + i + ": " + got + " (应为 " + want[i] + ")");
                        }
                    }
                }
            }
            helper.assertTrue(wrong.isEmpty(), "22.5 的表对不上: " + wrong);
            helper.assertTrue(GuardEvents.placementVerdict(view, level, real, machine, List.of(far, inside))
                    == PlacementVerdict.DENY_DISTRICT, "多方块放置里任何一格落在区内就整次拦下");
            ServerLevel nether = level.getServer().getLevel(Level.NETHER);
            helper.assertTrue(nether == null || GuardEvents.placementVerdict(view, nether, real, machine,
                    List.of(inside)) == PlacementVerdict.ALLOW, "别的维度的同一坐标照常");
        } finally {
            if (opProfile != null) {
                level.getServer().getPlayerList().getOps().remove(opProfile);
            }
            DistrictTestEnv.removePlayer(helper, real);
            DistrictTestEnv.removePlayer(helper, op);
            GuardTestZones.remove(key);
        }
        helper.succeed();
    }

    private static PlacementVerdict[] allow() {
        return new PlacementVerdict[]{PlacementVerdict.ALLOW, PlacementVerdict.ALLOW, PlacementVerdict.ALLOW};
    }

    /** 一个 FakePlayer 子类, 档案 UUID 放进 2 级 OP 名单: hasPermissions(2) 为真, 但不是 OP 例外。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void opExemptionExcludesFakePlayers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GameProfile fakeProfile = new GameProfile(UUID.randomUUID(), "Gt_Op_Fake");
        ServerPlayer op = null;
        ServerPlayer levelOne = null;
        try {
            FakePlayer fake = new TestCreateFakePlayer(level, fakeProfile);
            grantOp(level, fakeProfile, 2);
            helper.assertTrue(fake.hasPermissions(2), "前提: 档案在 OP 名单里的假玩家 hasPermissions(2) 为真");
            helper.assertTrue(!GuardActors.isExemptOp(fake), "假玩家 (机械手的主人是 OP) 不算 OP 例外");
            op = DistrictTestEnv.onlinePlayer(helper, "Gt_Real_Op");
            grantOp(level, op.getGameProfile(), 2);
            helper.assertTrue(op.hasPermissions(2) && GuardActors.isExemptOp(op), "2 级的真玩家是 OP 例外");
            levelOne = DistrictTestEnv.onlinePlayer(helper, "Gt_Level_One");
            grantOp(level, levelOne.getGameProfile(), 1);
            helper.assertTrue(!GuardActors.isExemptOp(levelOne), "1 级 OP 不例外");
            helper.assertTrue(!GuardActors.isCreateFakePlayer(fake), "没登记时, 类名不在机械动力包里的假玩家不算");
        } finally {
            level.getServer().getPlayerList().getOps().remove(fakeProfile);
            if (op != null) {
                level.getServer().getPlayerList().getOps().remove(op.getGameProfile());
            }
            if (levelOne != null) {
                level.getServer().getPlayerList().getOps().remove(levelOne.getGameProfile());
            }
            DistrictTestEnv.removePlayer(helper, op);
            DistrictTestEnv.removePlayer(helper, levelOne);
        }
        helper.succeed();
    }

    // ================================================================
    // 机器的判定 (22.6)
    // ================================================================

    /**
     * CreateGuards 的每个方法: 受保护的格子拒; 外围 8 格只拒机器; 区外放行; 部件就近判定外扩 2 格的边界值; 轨道范围里
     * getBounds 之外的直线段也算。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void createGuardDecisions(GameTestHelper helper) throws Exception {
        String key = farDistrict(helper, "createDecisions", 1000);
        ServerLevel level = helper.getLevel();
        int x0 = X0 + 1000;
        ServerPlayer real = null;
        try (AutoCloseable registered = GuardActors.registerTestCreateFakePlayer(TestCreateFakePlayer.class)) {
            real = DistrictTestEnv.onlinePlayer(helper, "Gt_Decisions");
            FakePlayer fake = new TestCreateFakePlayer(level, new GameProfile(UUID.randomUUID(), "Deployer"));
            BlockPos inside = new BlockPos(x0 + 50, Y, Z0 + 50);
            BlockPos buffer = new BlockPos(x0 - 8, Y, Z0 + 50);
            BlockPos far = new BlockPos(x0 - 9, Y, Z0 + 50);
            BlockState machine = Blocks.FURNACE.defaultBlockState();
            BlockState deco = Blocks.STONE.defaultBlockState();

            helper.assertTrue(!CreateGuards.mayDestroy(level, inside, null) && !CreateGuards.mayDestroy(level, inside, fake)
                            && CreateGuards.mayDestroy(level, inside, real) && CreateGuards.mayDestroy(level, buffer, null)
                            && CreateGuards.mayDestroy(level, far, null),
                    "C1: 机器 (玩家为 null 或假玩家) 拆不了区内方块; 真玩家交给 BreakEvent; 区外照常");
            helper.assertTrue(!CreateGuards.schematicannonMayPlace(level, inside, deco)
                            && !CreateGuards.schematicannonMayPlace(level, buffer, machine)
                            && CreateGuards.schematicannonMayPlace(level, buffer, deco)
                            && CreateGuards.schematicannonMayPlace(level, far, machine),
                    "C2: 区内一律跳过, 外围 8 格只跳过机器");
            helper.assertTrue(!CreateGuards.mayBreak(level, inside) && CreateGuards.mayBreak(level, buffer)
                            && !CreateGuards.colliderMayEnter(level, inside) && CreateGuards.colliderMayEnter(level, far)
                            && !CreateGuards.mayMoveBlock(level, inside) && CreateGuards.mayMoveBlock(level, buffer)
                            && !CreateGuards.fluidMayReach(level, inside) && CreateGuards.fluidMayReach(level, far),
                    "C3、C4、C8、C10 ~ C13: 只看落点受不受保护");
            helper.assertTrue(!CreateGuards.contraptionMayPlace(level, inside, deco)
                            && !CreateGuards.contraptionMayPlace(level, buffer, machine)
                            && CreateGuards.contraptionMayPlace(level, buffer, deco)
                            && CreateGuards.contraptionMayPlace(level, far, machine),
                    "C9: 区内一律不放, 外围 8 格只不放机器");
            helper.assertTrue(!CreateGuards.actorMayActAt(level, new Vec3(x0 - 2 + 0.5, Y, Z0 + 50.5))
                            && CreateGuards.actorMayActAt(level, new Vec3(x0 - 3 + 0.5, Y, Z0 + 50.5))
                            && !CreateGuards.actorMayActAt(level, new Vec3(x0 + 101.5, Y, Z0 + 50.5))
                            && CreateGuards.actorMayActAt(level, new Vec3(x0 + 102.5, Y, Z0 + 50.5)),
                    "C5: 部件位置外扩 2 格碰到区就停, 3 格不碰");
            helper.assertTrue(!CreateGuards.actorMayAct(level, new Object(),
                            new AABB(x0 - 4, Y, Z0 + 50, x0 - 2, Y + 1, Z0 + 51))
                            && CreateGuards.actorMayAct(level, new Object(),
                            new AABB(x0 - 10, Y, Z0 + 50, x0 - 8, Y + 1, Z0 + 51)),
                    "C5: 部件位置取不到时退回装置实体的包围框外扩 2 格");

            BlockPos start = new BlockPos(x0 - 30, Y, Z0 + 50);
            Vec3 east = new Vec3(1, 0, 0);
            helper.assertTrue(CreateGuards.trackVerdict(level, start, east, 10, start, east, 0, null)
                            == CreateGuards.TrackVerdict.ALLOW
                            && CreateGuards.trackVerdict(level, start, east, 23, start, east, 0, null)
                            == CreateGuards.TrackVerdict.BUFFER
                            && CreateGuards.trackVerdict(level, start, east, 31, start, east, 0, null)
                            == CreateGuards.TrackVerdict.DISTRICT,
                    "C14: 直线延伸段逐格看, 进外围 8 格为 BUFFER, 进区为 DISTRICT");
            AABB farCurve = new AABB(x0 - 60, Y, Z0 + 40, x0 - 50, Y + 1, Z0 + 45);
            helper.assertTrue(CreateGuards.trackVerdict(level, start, east, 22, start, east, 0, farCurve)
                            == CreateGuards.TrackVerdict.BUFFER,
                    "C14: 曲线的包围框在远处, getBounds 之外的直线段 (有曲线时多一格) 也算");
            AABB curveInto = new AABB(x0 - 5, Y, Z0 + 40, x0 + 3, Y + 1, Z0 + 45);
            helper.assertTrue(CreateGuards.trackVerdict(level, start, east, 1, start, east, 0, curveInto)
                            == CreateGuards.TrackVerdict.DISTRICT,
                    "C14: 曲线的包围框碰到区就是 DISTRICT");
            helper.assertTrue(CreateGuards.trackVerdict(level, buffer, null, 0, buffer, null, 0, null)
                    == CreateGuards.TrackVerdict.BUFFER, "C14: 两端本身落在外围 8 格也算");
        } finally {
            DistrictTestEnv.removePlayer(helper, real);
            GuardTestZones.remove(key);
        }
        helper.succeed();
    }

    /**
     * 复核补上的 C15 ~ C19 (22.6、22.19): 传送带的框碰到禁放区就不铺 (OP 在 useOn 里放行过的同一条除外), 玩家连接时被拒并
     * 收到提示; 对称之杖去掉落到别的区域的镜像位置 (OP 例外); 机械臂、显示链接按单向口径; 土豆加农炮按受保护口径。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void lateHookDecisions(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x0 = X0 + 3000;
        String key = "lateHooks";
        GuardTestZones.putAbsolute(level.dimension().location().toString(), key, x0, Z0, x0 + 99, Z0 + 99,
                new int[]{x0 + 10, Z0 + 10, x0 + 20, Z0 + 20}, new int[]{x0 + 30, Z0 + 10, x0 + 40, Z0 + 20});
        ServerPlayer real = null;
        ServerPlayer op = null;
        try {
            real = DistrictTestEnv.onlinePlayer(helper, "Gt_Late_Real");
            op = DistrictTestEnv.onlinePlayer(helper, "Gt_Late_Op");
            grantOp(level, op.getGameProfile(), 2);
            BlockPos wild = new BlockPos(x0 - 40, Y, Z0 + 50);
            BlockPos bufferEnd = new BlockPos(x0 - 5, Y, Z0 + 50);
            BlockPos insideEnd = new BlockPos(x0 + 11, Y, Z0 + 50);
            BlockPos farEnd = new BlockPos(x0 - 20, Y, Z0 + 50);
            BlockPos plotA = new BlockPos(x0 + 15, Y, Z0 + 15);
            BlockPos plotB = new BlockPos(x0 + 35, Y, Z0 + 15);
            BlockPos publicArea = new BlockPos(x0 + 60, Y, Z0 + 60);

            // C15 createBelts: 框碰到外围或区内就不铺; 蓝图炮的起点在外围之外也一样。
            helper.assertTrue(CreateGuards.beltMayCreate(level, wild, farEnd)
                            && !CreateGuards.beltMayCreate(level, wild, bufferEnd)
                            && !CreateGuards.beltMayCreate(level, wild, insideEnd)
                            && !CreateGuards.beltMayCreate(level, farEnd, wild.east(60)),
                    "C15: 传送带的框碰到禁放区就整条不铺");
            // C15 useOn: 非 OP 被拒 (提示), OP 放行且同一条 createBelts 认得出来, 换一条不认。
            ItemStack belts = new ItemStack(Items.STICK);
            belts.getOrCreateTag().put(CreateGuards.BELT_FIRST_PULLEY, NbtUtils.writeBlockPos(wild));
            helper.assertTrue(CreateGuards.beltConnectBlocked(level, real, belts, bufferEnd)
                            && CreateGuards.beltConnectBlocked(level, real, belts, insideEnd)
                            && !CreateGuards.beltConnectBlocked(level, real, belts, farEnd),
                    "C15: 非 OP 连进禁放区被拒, 更远的照常");
            CreateGuards.beltUseStarted();
            helper.assertTrue(!CreateGuards.beltConnectBlocked(level, op, belts, insideEnd)
                            && CreateGuards.beltMayCreate(level, wild, insideEnd),
                    "C15: OP 亲手连的这一条放行");
            helper.assertTrue(!CreateGuards.beltMayCreate(level, wild, insideEnd),
                    "C15: OP 的放行只用一次, 之后 (例如蓝图炮) 同一条照拦");
            CreateGuards.beltUseStarted();
            helper.assertTrue(!CreateGuards.beltConnectBlocked(level, op, belts, insideEnd)
                            && !CreateGuards.beltMayCreate(level, wild, bufferEnd),
                    "C15: OP 放行的是那一条, 两端不同的照拦");
            CreateGuards.beltUseStarted();

            // C16 对称之杖: 镜像位置按单向口径过滤, OP 例外。
            Map<BlockPos, BlockState> mirror = new LinkedHashMap<>();
            mirror.put(plotA, Blocks.AIR.defaultBlockState());
            mirror.put(plotA.east(2), Blocks.AIR.defaultBlockState());
            mirror.put(plotB, Blocks.AIR.defaultBlockState());
            mirror.put(publicArea, Blocks.AIR.defaultBlockState());
            mirror.put(wild, Blocks.AIR.defaultBlockState());
            helper.assertTrue(CreateGuards.symmetryTargets(mirror, level, real, plotA)
                            .equals(Set.of(plotA, plotA.east(2), wild)),
                    "C16: 从地块 A 镜像到 B 与公共区域的去掉, A 自己与区外的留下");
            helper.assertTrue(CreateGuards.symmetryTargets(mirror, level, real, wild).equals(Set.of(wild)),
                    "C16: 从区外镜像进区里的全部去掉");
            helper.assertTrue(CreateGuards.symmetryTargets(mirror, level, op, wild).size() == mirror.size(),
                    "C16: OP 例外");

            // C17 机械臂 (单向口径): 交互点 = 机械臂 + tag.Pos。
            helper.assertTrue(!CreateGuards.armPointMayReach(level, new BlockPos(x0 - 3, Y, Z0 + 50),
                            armPoint(new BlockPos(5, 0, 0)))
                            && CreateGuards.armPointMayReach(level, new BlockPos(x0 - 3, Y, Z0 + 50),
                            armPoint(new BlockPos(-5, 0, 0)))
                            && CreateGuards.armPointMayReach(level, plotA, armPoint(new BlockPos(2, 0, 0)))
                            && !CreateGuards.armPointMayReach(level, plotA, armPoint(new BlockPos(20, 0, 0)))
                            && CreateGuards.armPointMayReach(level, plotA, new CompoundTag()),
                    "C17: 区外够不到区里, 地块 A 里的够得到 A 自己、够不到 B; 没有 Pos 的交给机械动力自己");
            // C18 显示链接 (单向口径, 目标与来源都看)。
            helper.assertTrue(!CreateGuards.displayLinkMayUpdate(level, wild, wild.above(), insideEnd)
                            && !CreateGuards.displayLinkMayUpdate(level, wild, insideEnd, wild.above())
                            && CreateGuards.displayLinkMayUpdate(level, wild, wild.above(), farEnd)
                            && CreateGuards.displayLinkMayUpdate(level, plotA, plotA.above(), plotA.east())
                            && !CreateGuards.displayLinkMayUpdate(level, plotA, plotA.above(), plotB),
                    "C18: 目标或来源在别的区域就不读不写");
            // C19 土豆加农炮 (受保护口径): 打中的方块或它那一面的邻格受保护就不执行。
            BlockPos edge = new BlockPos(x0 - 1, Y, Z0 + 50);
            helper.assertTrue(!CreateGuards.potatoMayHitBlock(level,
                            new BlockHitResult(Vec3.atCenterOf(edge), Direction.EAST, edge, false))
                            && CreateGuards.potatoMayHitBlock(level,
                            new BlockHitResult(Vec3.atCenterOf(edge), Direction.WEST, edge, false))
                            && !CreateGuards.potatoMayHitBlock(level,
                            new BlockHitResult(Vec3.atCenterOf(plotA), Direction.UP, plotA, false)),
                    "C19: 往区里放、在区里种都不执行; 区外照常");

            // createMachinery = false: 全部放行。
            GuardSettings base = GuardTestZones.settings();
            GuardTestZones.useSettings(base.withCreateMachinery(false));
            try {
                helper.assertTrue(CreateGuards.beltMayCreate(level, wild, insideEnd)
                                && !CreateGuards.beltConnectBlocked(level, real, belts, insideEnd)
                                && CreateGuards.symmetryTargets(mirror, level, real, wild).size() == mirror.size()
                                && CreateGuards.armPointMayReach(level, plotA, armPoint(new BlockPos(20, 0, 0)))
                                && CreateGuards.displayLinkMayUpdate(level, wild, wild, insideEnd)
                                && CreateGuards.potatoMayHitBlock(level,
                                new BlockHitResult(Vec3.atCenterOf(plotA), Direction.UP, plotA, false)),
                        "createMachinery = false 时 C15 ~ C19 全部放行");
            } finally {
                GuardTestZones.useSettings(base);
            }
        } finally {
            CreateGuards.beltUseStarted();
            if (op != null) {
                level.getServer().getPlayerList().getOps().remove(op.getGameProfile());
            }
            DistrictTestEnv.removePlayer(helper, real);
            DistrictTestEnv.removePlayer(helper, op);
            GuardTestZones.remove(key);
        }
        helper.succeed();
    }

    /** 机械臂交互点的 NBT (只有相对位置)。 */
    private static CompoundTag armPoint(BlockPos relative) {
        CompoundTag tag = new CompoundTag();
        tag.put(CreateGuards.ARM_POINT_POS, NbtUtils.writeBlockPos(relative));
        return tag;
    }

    /** createMachinery = false: CreateGuards 全部放行、机械动力的假玩家交给 Flan; 玩家的放置禁令照拦。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void machinerySwitchOffDisablesHooksButNotPlacement(GameTestHelper helper) throws Exception {
        String key = farDistrict(helper, "machinerySwitch", 2000);
        ServerLevel level = helper.getLevel();
        int x0 = X0 + 2000;
        GuardSettings base = GuardTestZones.settings();
        ServerPlayer real = null;
        try (AutoCloseable registered = GuardActors.registerTestCreateFakePlayer(TestCreateFakePlayer.class)) {
            real = DistrictTestEnv.onlinePlayer(helper, "Gt_Switch_Real");
            FakePlayer fake = new TestCreateFakePlayer(level, new GameProfile(UUID.randomUUID(), "Deployer"));
            BlockPos inside = new BlockPos(x0 + 50, Y, Z0 + 50);
            BlockPos buffer = new BlockPos(x0 - 8, Y, Z0 + 50);
            BlockState machine = Blocks.FURNACE.defaultBlockState();
            helper.assertTrue(!CreateGuards.mayDestroy(level, inside, null), "前提: 开关开着时拦");
            GuardTestZones.useSettings(base.withCreateMachinery(false));
            helper.assertTrue(CreateGuards.mayDestroy(level, inside, null) && CreateGuards.mayBreak(level, inside)
                            && CreateGuards.schematicannonMayPlace(level, inside, machine)
                            && CreateGuards.contraptionMayPlace(level, buffer, machine)
                            && CreateGuards.mayMoveBlock(level, inside) && CreateGuards.fluidMayReach(level, inside)
                            && CreateGuards.actorMayActAt(level, Vec3.atCenterOf(inside)),
                    "机器拦截全部放行");
            GuardView view = DistrictWorldGuards.view();
            helper.assertTrue(GuardEvents.placementVerdict(view, level, fake, machine, List.of(inside))
                    == PlacementVerdict.ALLOW, "机械动力的假玩家交给 Flan");
            helper.assertTrue(GuardEvents.placementVerdict(view, level, real, machine, List.of(inside))
                            == PlacementVerdict.DENY_DISTRICT
                            && GuardEvents.placementVerdict(view, level, real, machine, List.of(buffer))
                            == PlacementVerdict.DENY_BUFFER,
                    "玩家的放置禁令照拦 (没有开关)");
        } finally {
            GuardTestZones.useSettings(base);
            DistrictTestEnv.removePlayer(helper, real);
            GuardTestZones.remove(key);
        }
        helper.succeed();
    }

    private static void grantOp(ServerLevel level, GameProfile profile, int permissionLevel) {
        level.getServer().getPlayerList().getOps().add(new ServerOpListEntry(profile, permissionLevel, false));
    }

    /** 测试登记成"机械动力的假玩家"的 FakePlayer 子类。 */
    public static final class TestCreateFakePlayer extends FakePlayer {

        public TestCreateFakePlayer(ServerLevel level, GameProfile profile) {
            super(level, profile);
        }
    }
}
