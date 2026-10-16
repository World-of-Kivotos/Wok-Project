package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.JobId;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 开放名单的 GameTest (docs/Spoilage_Granary_DesignSpec.md「合建: 入册与权限」「谁管什么」)。
 *
 * 锁六件事: 判定本身 (囤主与「可存可取」的人能取, 「只存」的只能存, 名单外的人什么都不行)、
 * 名单最多 16 人、只有囤主能改自己囤的名单 (房主也不行)、私有囤连房主都存不进去而名单上的人存得进、
 * 默认名单在新放的囤上自动套用、以及存档往返与坏档报错; 另锁 `/granary audit` 的第三对配对
 * 「囤主 → 名单」。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryAccessGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_access";

    /** 每条用例占自己的一条槽位 (同一批次的用例并行跑且共用区域)。 */
    private static final int SLOT = 16;

    private static final int LOW = 1;
    private static final int HIGH = 3;

    // ============================================================
    // 判定 (纯函数)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void theListDecidesWhoMayStoreAndWhoMayTake(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        UUID helper_ = UUID.randomUUID();
        UUID guest = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        List<GranaryAccess.Trustee> list = new ArrayList<>();
        helper.assertTrue(GranaryAccess.isPrivate(list), "默认是私有囤");

        helper.assertTrue(GranaryAccess.add(list, owner, helper_,
                GranaryAccess.Permission.STORE_AND_TAKE) == GranaryAccess.Status.ADDED, "加一个人");
        helper.assertTrue(GranaryAccess.add(list, owner, guest,
                GranaryAccess.Permission.STORE_ONLY) == GranaryAccess.Status.ADDED, "再加一个只存的");
        helper.assertTrue(GranaryAccess.add(list, owner, helper_,
                GranaryAccess.Permission.STORE_ONLY) == GranaryAccess.Status.UPDATED, "改权限是替换不是新增");
        helper.assertTrue(list.size() == 2, "改权限不该多出人, 实际 " + list.size());

        helper.assertTrue(GranaryAccess.mayStore(owner, list, owner), "囤主能存");
        helper.assertTrue(GranaryAccess.mayTake(owner, list, owner), "囤主能取");
        helper.assertTrue(GranaryAccess.mayStore(owner, list, guest), "「只存」的人能存");
        helper.assertTrue(!GranaryAccess.mayTake(owner, list, guest), "「只存」的人不能取");
        helper.assertTrue(GranaryAccess.mayStore(owner, list, helper_), "改过权限的人还能存");
        helper.assertTrue(!GranaryAccess.mayTake(owner, list, helper_),
                "改成「只存」之后取不了了 (按新的算, 不是按加进来那一下)");
        helper.assertTrue(!GranaryAccess.mayStore(owner, list, stranger)
                && !GranaryAccess.mayTake(owner, list, stranger), "名单外的人什么都不行");
        helper.assertTrue(!GranaryAccess.mayStore(owner, list, null), "空玩家不放行");

        helper.assertTrue(GranaryAccess.add(list, owner, owner,
                GranaryAccess.Permission.STORE_AND_TAKE) == GranaryAccess.Status.IS_OWNER,
                "囤主不用进名单");
        helper.assertTrue(GranaryAccess.remove(list, guest), "移出一个人");
        helper.assertTrue(!GranaryAccess.remove(list, guest), "移出不在名单上的人是幂等的");
        helper.assertTrue(GranaryAccess.add(list, owner, guest,
                GranaryAccess.Permission.STORE_AND_TAKE) == GranaryAccess.Status.ADDED, "还能再加回来");
        helper.assertTrue(GranaryAccess.takeCount(list) == 1, "可存可取的人数要对 (只剩后来加回来的那一个), 实际 "
                + GranaryAccess.takeCount(list));
        GranaryAccess.clear(list);
        helper.assertTrue(GranaryAccess.isPrivate(list) && list.isEmpty(), "清空回私有");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void theListHoldsAtMostSixteenPlayers(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        List<GranaryAccess.Trustee> list = new ArrayList<>();
        for (int i = 0; i < GranaryAccess.MAX_TRUSTEES; i++) {
            helper.assertTrue(GranaryAccess.add(list, owner, UUID.randomUUID(),
                    GranaryAccess.Permission.STORE_ONLY) == GranaryAccess.Status.ADDED, "第 " + (i + 1) + " 个加得进");
        }
        helper.assertTrue(list.size() == GranaryAccess.MAX_TRUSTEES, "满员 16 人, 实际 " + list.size());
        helper.assertTrue(GranaryAccess.add(list, owner, UUID.randomUUID(),
                GranaryAccess.Permission.STORE_ONLY) == GranaryAccess.Status.FULL, "第 17 个加不进");
        // 满员时改已有成员的权限仍然可以 (那不是「再加一个」)。
        helper.assertTrue(GranaryAccess.add(list, owner, list.get(0).player(),
                GranaryAccess.Permission.STORE_AND_TAKE) == GranaryAccess.Status.UPDATED, "满员也能改权限");
        helper.succeed();
    }

    // ============================================================
    // 真囤: 默认名单、权限门与存入
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void newBinsAdoptTheDefaultList(GameTestHelper helper) {
        int slot = 0;
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(helper, "acowner");
        ServerPlayer guest = player(helper, "acguest");
        GranarySavedData data = GranarySavedData.get(level);

        // 设默认名单: 客人「可存可取」。
        List<GranaryAccess.Trustee> defaults = new ArrayList<>(data.trustDefault(owner.getUUID()));
        helper.assertTrue(GranaryAccess.add(defaults, owner.getUUID(), guest.getUUID(),
                GranaryAccess.Permission.STORE_AND_TAKE) == GranaryAccess.Status.ADDED, "设默认名单");
        data.setTrustDefault(owner.getUUID(), defaults);

        placeBin(helper, owner, bin(slot, LOW, LOW));
        GranaryBinBlockEntity bin = (GranaryBinBlockEntity) level.getBlockEntity(
                helper.absolutePos(bin(slot, LOW, LOW)));
        helper.assertTrue(bin.access().size() == 1, "新放的囤套用了默认名单, 实际 " + bin.access());
        helper.assertTrue(bin.access().get(0).player().equals(guest.getUUID())
                        && bin.access().get(0).permission() == GranaryAccess.Permission.STORE_AND_TAKE,
                "套用的是客人 + 可存可取");

        // 默认名单清掉之后放的囤是私有的。
        data.setTrustDefault(owner.getUUID(), List.of());
        placeBin(helper, owner, bin(slot, LOW, 2));
        GranaryBinBlockEntity second = (GranaryBinBlockEntity) level.getBlockEntity(
                helper.absolutePos(bin(slot, LOW, 2)));
        helper.assertTrue(second.access().isEmpty(), "清掉默认名单之后放的是私有囤");

        // 已经放下的囤不受默认名单变化影响 (改默认名单只影响以后放的)。
        helper.assertTrue(bin.access().size() == 1, "先放的那个囤名单没被动过");
        data.setTrustDefault(owner.getUUID(), List.of());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void onlyTheOwnerCanChangeTheListAndOnlyListedPlayersMayStore(GameTestHelper helper) {
        int slot = 1;
        Fixture fixture = accepted(helper, slot);
        ServerLevel level = fixture.level();
        ServerPlayer owner = fixture.owner();
        ServerPlayer guest = fixture.guest();
        ServerPlayer stranger = fixture.stranger();
        GranaryBinBlockEntity bin = fixture.binEntity(helper);
        BlockPos binPos = helper.absolutePos(bin(slot, LOW, LOW));
        Capture output = new Capture();

        // 私有囤 (默认): 成员与陌生人都存不进, 囤主自己存得进。
        guest.getInventory().setItem(9, new ItemStack(Items.WHEAT, 5));
        helper.assertTrue(GranaryDeposit.deposit(level, bin, guest).rejection()
                == GranaryDeposit.Rejection.NOT_ALLOWED, "A2 private bin rejects a member");
        helper.assertTrue(GranaryDeposit.deposit(level, bin, stranger).rejection()
                == GranaryDeposit.Rejection.NOT_ALLOWED, "A2 private bin rejects a stranger");
        helper.assertTrue(GranaryDeposit.deposit(level, bin, owner).accepted(), "A2 owner may store");

        // 开放给客人「只存」: 他存得进, 但取不了; 名单外的陌生人还是不行。
        bin.addTrustee(guest.getUUID(), GranaryAccess.Permission.STORE_ONLY);
        guest.getInventory().setItem(9, new ItemStack(Items.WHEAT, 5));
        helper.assertTrue(GranaryDeposit.deposit(level, bin, guest).accepted(), "A2 listed store-only may store");
        helper.assertTrue(!GranaryAccess.mayTake(owner.getUUID(), bin.access(), guest.getUUID()),
                "A2 store-only may not take");
        helper.assertTrue(GranaryDeposit.deposit(level, bin, stranger).rejection()
                == GranaryDeposit.Rejection.NOT_ALLOWED, "A2 stranger rejected by engine");

        // 命令那条路: 囤主看着自己的囤能设回私有。
        owner.moveTo(binPos.getX() + 0.5D, binPos.getY(), binPos.getZ() + 1.5D);
        lookAt(owner, binPos);
        output.clear();
        boolean seesBin = GranaryQuery.lookedAtBin(owner, 5.0D).isPresent();
        int cleared = server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output), "granary access private");
        helper.assertTrue(cleared == 1, "A2 owner may clear the list (seesBin=" + seesBin
                + ", look=" + owner.getLookAngle() + ", playerAt=" + owner.position()
                + ", binPos=" + binPos + ", keys=" + output.keys() + ")");
        helper.assertTrue(bin.access().isEmpty(), "A2 list cleared");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.access_cleared"),
                "A2 receipt key expected: " + output.joined());

        // 不是囤主的人 (哪怕是房主) 改不了: 这里让另一位玩家站到这个囤前面。
        bin.addTrustee(guest.getUUID(), GranaryAccess.Permission.STORE_ONLY);
        guest.moveTo(binPos.getX() + 0.5D, binPos.getY(), binPos.getZ() + 1.5D);
        lookAt(guest, binPos);
        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(guest.createCommandSourceStack()
                .withSource(output), "granary access private") == 0, "A2 non-owner cannot change the list");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.access_not_owner"),
                "A2 not-owner key expected: " + output.joined());
        helper.assertTrue(bin.access().size() == 1, "A2 list untouched after refusal");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void trustCommandAndAuditPairingWork(GameTestHelper helper) {
        int slot = 2;
        Fixture fixture = accepted(helper, slot);
        ServerLevel level = fixture.level();
        ServerPlayer owner = fixture.owner();
        ServerPlayer guest = fixture.guest();
        GranaryBinBlockEntity bin = fixture.binEntity(helper);
        Capture output = new Capture();

        // 默认名单: 加人 / 看 / 移出。
        owner.moveTo(fixture.centerPos().getX() + 1.5D, fixture.centerPos().getY(),
                fixture.centerPos().getZ() + 0.5D);
        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output), "granary trust add " + guest.getGameProfile().getName() + " take") == 1,
                "默认名单加一个可存可取的");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.trust_added"),
                "A2 receipt key expected: " + output.joined());
        helper.assertTrue(GranarySavedData.get(level).trustDefault(owner.getUUID()).size() == 1, "默认名单存下了");

        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output), "granary trust") == 1, "看默认名单");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.trust_header")
                        && output.keys().contains("message.miningdim.granary.command.access_row"),
                "要有表头与一行: " + output.joined());

        // audit: 第三对配对「囤主 → 名单」。
        bin.addTrustee(guest.getUUID(), GranaryAccess.Permission.STORE_AND_TAKE);
        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output).withPermission(2), "granary audit") >= 1, "OP 能盘点");
        helper.assertTrue(output.keys().contains("message.miningdim.granary.command.audit_trust"),
                "audit 要列出「囤主 → 名单」配对: " + output.joined());

        output.clear();
        helper.assertTrue(server(helper).getCommands().performPrefixedCommand(owner.createCommandSourceStack()
                .withSource(output), "granary trust clear") == 1, "清默认名单");
        helper.assertTrue(GranarySavedData.get(level).trustDefault(owner.getUUID()).isEmpty(), "清干净了");
        helper.succeed();
    }

    // ============================================================
    // 存档
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void accessAndTrustDefaultsSurviveSaveAndLoad(GameTestHelper helper) {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ListTag saved = GranaryAccess.save(List.of(
                new GranaryAccess.Trustee(first, GranaryAccess.Permission.STORE_AND_TAKE),
                new GranaryAccess.Trustee(second, GranaryAccess.Permission.STORE_ONLY)));
        List<GranaryAccess.Trustee> loaded = GranaryAccess.load(saved);
        helper.assertTrue(loaded.equals(List.of(
                new GranaryAccess.Trustee(first, GranaryAccess.Permission.STORE_AND_TAKE),
                new GranaryAccess.Trustee(second, GranaryAccess.Permission.STORE_ONLY))), "名单原样存读");

        // 重复的人只留第一个 (不是坏档)。
        ListTag duplicate = GranaryAccess.save(List.of(
                new GranaryAccess.Trustee(first, GranaryAccess.Permission.STORE_ONLY)));
        duplicate.add(saved.get(0).copy());
        helper.assertTrue(GranaryAccess.load(duplicate).size() == 1, "重复的人只留一个");
        helper.assertTrue(GranaryAccess.load(duplicate).get(0).permission()
                == GranaryAccess.Permission.STORE_ONLY, "留的是第一个");

        // 坏档: 缺人、认不出的权限、超过 16 人。
        ListTag missing = new ListTag();
        missing.add(new CompoundTag());
        helper.assertTrue(rejects(missing), "缺人直接报错");
        ListTag badPerm = GranaryAccess.save(List.of(
                new GranaryAccess.Trustee(first, GranaryAccess.Permission.STORE_ONLY)));
        ((CompoundTag) badPerm.get(0)).putString(GranaryConstants.KEY_PERM, "OWNER");
        helper.assertTrue(rejects(badPerm), "认不出的权限直接报错");
        ListTag tooLong = new ListTag();
        for (int i = 0; i <= GranaryAccess.MAX_TRUSTEES; i++) {
            tooLong.add(GranaryAccess.save(List.of(new GranaryAccess.Trustee(UUID.randomUUID(),
                    GranaryAccess.Permission.STORE_ONLY))).get(0));
        }
        helper.assertTrue(rejects(tooLong), "超过 16 人直接报错");

        // 账本的默认名单: 存读往返 + 老档没有这个键按空读。
        ServerLevel level = helper.getLevel();
        GranarySavedData data = GranarySavedData.get(level);
        data.setTrustDefault(first, List.of(new GranaryAccess.Trustee(second,
                GranaryAccess.Permission.STORE_AND_TAKE)));
        GranarySavedData reloaded = GranarySavedData.load(data.save(new CompoundTag()));
        helper.assertTrue(reloaded.trustDefault(first).size() == 1
                        && reloaded.trustDefault(first).get(0).player().equals(second),
                "默认名单随账本往返");
        CompoundTag withoutTrust = data.save(new CompoundTag());
        withoutTrust.remove(GranaryConstants.KEY_TRUST_DEFAULTS);
        helper.assertTrue(GranarySavedData.load(withoutTrust).trustDefault(first).isEmpty(),
                "这张表落地之前的档照读 (按空名单)");
        data.setTrustDefault(first, List.of());
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 一座验收过的粮棚: 房主 + 已入册的客人 + 一个没入册的陌生人。 */
    private record Fixture(int slot, ServerLevel level, ServerPlayer owner, ServerPlayer guest,
                           ServerPlayer stranger, long gid, BlockPos centerPos) {

        GranaryBinBlockEntity binEntity(GameTestHelper helper) {
            return (GranaryBinBlockEntity) level.getBlockEntity(helper.absolutePos(bin(slot, LOW, LOW)));
        }
    }

    private static Fixture accepted(GameTestHelper helper, int slot) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(helper, "acowner");
        ServerPlayer guest = player(helper, "acguest");
        ServerPlayer stranger = player(helper, "acstranger");
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, bin(slot, LOW, LOW));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        BlockPos centerPos = helper.absolutePos(center(slot));
        long gid = GranarySystem.granaryAt(level, centerPos);
        GranarySavedData data = GranarySavedData.get(level);
        data.putGranary(data.granary(gid).withMember(guest.getUUID()));
        helper.assertTrue(GranaryInspection.accept(level, centerPos, owner).accepted(),
                "fixture: accepted as shed");
        return new Fixture(slot, level, owner, guest, stranger, gid, centerPos);
    }

    /** 让这位玩家看向某个方块 (命令按准星选囤, 站着不动是选不中的)。 */
    private static void lookAt(ServerPlayer player, BlockPos pos) {
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
        double dx = pos.getX() + 0.5D - eye.x;
        double dy = pos.getY() + 0.5D - eye.y;
        double dz = pos.getZ() + 0.5D - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        player.setYRot(yaw);
        player.setXRot(pitch);
        // **玩家的视线取自头部朝向** (yHeadRot), 不是身体朝向: 只设 setYRot 的话 getViewVector / pick
        // 还是按老方向射 (mock 玩家没被同步过头朝向, 会一直朝着出生点那边)。
        player.setYHeadRot(yaw);
        // 设完朝向**再**把「上一 tick 的位姿」对齐: 视线按 新→旧 插值, 只设新的那份等于没转。
        player.setOldPosAndRot();
    }

    private static ServerPlayer player(GameTestHelper helper, String name) {
        String unique = name + "_" + UUID.randomUUID().toString().substring(0, 6);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper,
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), unique));
        com.miningdim.entry.MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家未挂载 capability"))
                .jobProgress(JobId.FARMER)
                .setLevel(1);
        return player;
    }

    private static void placeCenter(GameTestHelper helper, ServerPlayer player, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        GranarySystem.releaseCenter(level, pos);
        helper.setBlock(relative, GranaryBlocks.CENTER.get());
        BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(
                BlockSnapshot.create(level.dimension(), level, pos), Blocks.AIR.defaultBlockState(), player);
        GranarySystem.onBlockPlaced(event);
        helper.assertTrue(!event.isCanceled(), "fixture: center placeable");
        GranaryBlocks.CENTER.get().setPlacedBy(level, pos, level.getBlockState(pos), player, ItemStack.EMPTY);
    }

    private static void placeBin(GameTestHelper helper, ServerPlayer player, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        GranarySavedData.get(level).releaseBin(level.dimension().location(), pos);
        helper.setBlock(relative, GranaryBlocks.BIN.get());
        BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(
                BlockSnapshot.create(level.dimension(), level, pos), Blocks.AIR.defaultBlockState(), player);
        GranarySystem.onBlockPlaced(event);
        helper.assertTrue(!event.isCanceled(), "fixture: bin placeable");
        GranaryBlocks.BIN.get().setPlacedBy(level, pos, level.getBlockState(pos), player, ItemStack.EMPTY);
    }

    private static BlockPos center(int slot) {
        return new BlockPos(slot * SLOT + 2, 2, 2);
    }

    private static BlockPos vent(int slot) {
        return new BlockPos(slot * SLOT, 2, 2);
    }

    private static BlockPos bin(int slot, int x, int z) {
        return new BlockPos(slot * SLOT + x, LOW, z);
    }

    private static void buildRoom(GameTestHelper helper, int slot, int width) {
        int base = slot * SLOT;
        for (int x = 0; x <= width + 1; x++) {
            for (int y = 0; y <= HIGH + 1; y++) {
                for (int z = 0; z <= width + 1; z++) {
                    boolean shell = x == 0 || x == width + 1 || y == 0 || y == HIGH + 1 || z == 0 || z == width + 1;
                    BlockState state = shell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    helper.setBlock(new BlockPos(base + x, y, z), state);
                }
            }
        }
    }

    private static net.minecraft.server.MinecraftServer server(GameTestHelper helper) {
        return helper.getLevel().getServer();
    }

    private static boolean rejects(ListTag tag) {
        try {
            GranaryAccess.load(tag);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    /** 收命令输出的命令源 (断言看语言键与数字, 不看渲染出来的文字)。 */
    private static final class Capture implements CommandSource {

        private final List<Component> lines = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component component) {
            lines.add(component);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        void clear() {
            lines.clear();
        }

        List<String> keys() {
            List<String> keys = new ArrayList<>();
            for (Component line : lines) {
                collect(line, keys, null);
            }
            return keys;
        }

        List<Long> numbers() {
            List<Long> numbers = new ArrayList<>();
            for (Component line : lines) {
                collect(line, null, numbers);
            }
            return numbers;
        }

        private static void collect(Component component, List<String> keys, List<Long> numbers) {
            if (component.getContents() instanceof TranslatableContents contents) {
                if (keys != null) {
                    keys.add(contents.getKey());
                }
                for (Object arg : contents.getArgs()) {
                    if (arg instanceof Number number && numbers != null) {
                        numbers.add(number.longValue());
                    } else if (arg instanceof Component nested) {
                        collect(nested, keys, numbers);
                    }
                }
            }
            for (Component sibling : component.getSiblings()) {
                collect(sibling, keys, numbers);
            }
        }

        String joined() {
            List<String> rendered = new ArrayList<>();
            for (Component line : lines) {
                rendered.add(line.getString());
            }
            return String.join(" | ", rendered);
        }
    }
}
