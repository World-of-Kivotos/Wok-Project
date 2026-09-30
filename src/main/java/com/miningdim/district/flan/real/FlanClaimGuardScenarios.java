package com.miningdim.district.flan.real;

import com.google.gson.JsonObject;
import com.miningdim.district.DistrictLimits;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.flan.DisabledFlanGateway;
import com.miningdim.district.flan.DistrictReconciler;
import com.miningdim.district.guard.GuardSettings;
import com.miningdim.district.guard.GuardTestZones;
import com.miningdim.district.guard.PersonalClaimGuard;
import com.miningdim.testutil.MockGameTestPlayers;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.flemmli97.flan.claim.Claim;
import io.github.flemmli97.flan.claim.ClaimBox;
import io.github.flemmli97.flan.claim.ClaimStorage;
import io.github.flemmli97.flan.event.ItemInteractEvents;
import io.github.flemmli97.flan.player.ClaimMode;
import io.github.flemmli97.flan.player.PlayerClaimData;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.miningdim.district.DistrictTestEnv.uuidOf;

/**
 * 个人圈地限制 (设计文档 22.20) 对真 Flan 的用例体, 登记在 {@link FlanClaimGuardGameTests} 与 {@link FlanRealGameTests}
 * (外围已有的个人领地两条)。本类没有 {@code @GameTestHolder}, 只在 Flan 在位时才被加载。
 *
 * <p>约定 (在 20.10 的基础上):
 * <ul>
 *   <li>每条用例一个槽位 (30–41), 区一律放在槽位里偏移 {@value #D0}–{@value #D1} (200 × 200), 禁圈区是偏移
 *       {@code D0 − 8}–{@code D1 + 8}。开跑前与结束时都删掉槽位里的全部顶层领地。</li>
 *   <li>玩家: {@code MockGameTestPlayers.makeMockServerPlayerWithChannel} (createClaim 一路要给玩家发聊天, 不进世界的
 *       ServerPlayer 没有连接会空指针); 聊天从它的 EmbeddedChannel 读。Flan 的默认配置下新玩家有 500 格、领地至少 100 格、
 *       圈地冷却 0: 领地取 10 × 10 到 17 × 12, 一块领地换一个玩家。金锄头的 {@code claimLandHandling} 自带 10 tick 的
 *       点击冷却, 一个玩家只点一下: 先用公开的 {@code setEditingCorner} / {@code setEditClaim} 摆好"第一下"。</li>
 *   <li>要站位置的命令 ({@code add rect}、{@code expand}) 用 setPos / setYRot 把玩家摆进槽位 (只改坐标, 不加载区块),
 *       用完摆回原处; {@code /flan add <两角>} 要求两角所在的区块已加载, 先载进来。</li>
 *   <li>OP 照 22.14: 往 OP 名单放 {@code ServerOpListEntry(profile, 等级, false)}, 结束时移除。</li>
 *   <li>全部同步, 不跨 tick。</li>
 * </ul>
 */
final class FlanClaimGuardScenarios {

    private static final String DIM = DistrictTestEnv.DIMENSION;
    private static final String KEY = "district.miningdim.command.";

    /** 槽位里测试区的范围 (偏移)。 */
    private static final int D0 = 1000;
    private static final int D1 = 1199;
    /** 点击与站立的高度。 */
    private static final int Y = 64;

    private static final String NETHER = "minecraft:the_nether";

    private static final String CREATE_DENIED = PersonalClaimGuard.KEY_CREATE_DENIED;
    private static final String RESIZE_DENIED = PersonalClaimGuard.KEY_RESIZE_DENIED;
    private static final String STALE_EDIT = PersonalClaimGuard.KEY_STALE_EDIT;
    private static final String BUFFER = String.valueOf(DistrictLimits.BUFFER_BLOCKS);

    private FlanClaimGuardScenarios() {
    }

    // ================================================================
    // 新圈 (F1)
    // ================================================================

    /**
     * 槽位 30, 有父领地。非 OP 对 [minX − 20, minX − 8] 调 createClaim: false, 名下没有领地, 聊天收到
     * claim_create_denied (区名、8); [minX − 21, minX − 9]: true, 主人是 TA; 只碰到角的: 拒。
     */
    static void createInBufferRejectedJustOutsideAccepted(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 30); Slot slot = new Slot(helper, 30)) {
            env.districtAt("abydos", env.slotBounds(D0, D0, D1, D1));
            helper.assertTrue(env.districtRecord("abydos").flanClaimId() != null, "前提: 区有父领地");
            slot.zone();
            ClaimStorage storage = slot.storage();

            ServerPlayer denied = slot.player();
            boolean made = storage.createClaim(slot.at(D0 - 20, 1050), slot.at(D0 - 8, 1061), denied);
            List<Component> said = chat(denied);
            helper.assertTrue(!made && slot.claimsOf(denied).isEmpty(),
                    "碰到 minX − 8: createClaim 返回 false, 名下没有领地");
            helper.assertTrue(argsOf(said, CREATE_DENIED).equals(List.of(slot.key, BUFFER)),
                    "聊天栏收到 claim_create_denied (区名、8), 实为 " + keysOf(said));

            ServerPlayer accepted = slot.player();
            boolean outside = storage.createClaim(slot.at(D0 - 21, 1050), slot.at(D0 - 9, 1061), accepted);
            List<Claim> owned = slot.claimsOf(accepted);
            helper.assertTrue(outside && owned.size() == 1 && accepted.getUUID().equals(owned.get(0).getOwner())
                            && areaOf(owned.get(0)).equals(slot.box(D0 - 21, 1050, D0 - 9, 1061)),
                    "止于 minX − 9: 照常圈出来, 主人是 TA");
            helper.assertTrue(!keysOf(chat(accepted)).contains(CREATE_DENIED), "外面圈地不提示");

            ServerPlayer corner = slot.player();
            boolean cornerMade = storage.createClaim(slot.at(D0 - 20, D0 - 20), slot.at(D0 - 8, D0 - 8), corner);
            helper.assertTrue(!cornerMade && slot.claimsOf(corner).isEmpty()
                            && keysOf(chat(corner)).contains(CREATE_DENIED),
                    "只碰到禁圈区的一个角 (minX − 8, minZ − 8) 也拒");
        }
        helper.succeed();
    }

    /**
     * 槽位 31, 只装区域、不建父领地 (模拟 DEGRADED 时建的区、父领地被删): 整块在区里的 createClaim 被拒, 提示是本节的键,
     * 不是 Flan 的 flan.conflictOther (那里本来就没有别的领地可冲突)。
     */
    static void insideWithoutParentClaimIsRejected(GameTestHelper helper) {
        try (Slot slot = new Slot(helper, 31)) {
            slot.zone();
            helper.assertTrue(slot.storage().getClaimAt(FlanTestClaims.probe(slot.x(1055), slot.z(1055))) == null,
                    "前提: 区里没有任何领地 (父领地不在)");
            ServerPlayer player = slot.player();
            boolean made = slot.storage().createClaim(slot.at(1050, 1050), slot.at(1061, 1061), player);
            List<String> keys = keysOf(chat(player));
            helper.assertTrue(!made && slot.claimsOf(player).isEmpty(), "区里新圈被拒");
            helper.assertTrue(keys.contains(CREATE_DENIED) && !keys.contains("flan.conflictOther"),
                    "提示是本节的键, 实为 " + keys);
        }
        helper.succeed();
    }

    /**
     * 槽位 32: setEditMode(DEFAULT_3D) 后在外围 y 100–120 圈 3D 领地: 拒 (只看 X/Z); 对照: 外面的 3D 领地照常圈得出来。
     * 区只装在 minecraft:the_nether 时, 主世界的同一坐标: 放。
     */
    static void threeDClaimAndOtherDimension(GameTestHelper helper) {
        try (Slot slot = new Slot(helper, 32)) {
            slot.zone();
            ClaimStorage storage = slot.storage();
            ServerPlayer builder = slot.player();
            PlayerClaimData.get(builder).setEditMode(ClaimMode.DEFAULT_3D);
            helper.assertTrue(PlayerClaimData.get(builder).getClaimMode().is3d, "前提: 3D 模式");
            boolean inBuffer = storage.createClaim(slot.at(D0 - 15, 100, 1050), slot.at(D0 - 6, 120, 1059), builder);
            helper.assertTrue(!inBuffer && slot.claimsOf(builder).isEmpty()
                            && keysOf(chat(builder)).contains(CREATE_DENIED),
                    "浮在 y 100–120 的 3D 领地照样在区的那几列里: 拒");
            boolean outside = storage.createClaim(slot.at(D0 - 30, 100, 1050), slot.at(D0 - 21, 120, 1059), builder);
            List<Claim> owned = slot.claimsOf(builder);
            helper.assertTrue(outside && owned.size() == 1 && owned.get(0).is3d(),
                    "对照: 外面的 3D 领地照常圈出来 (走的确实是 3D 那一支)");

            GuardTestZones.remove(slot.key);
            GuardTestZones.putAbsolute("minecraft:the_nether", slot.key, slot.x(D0), slot.z(D0), slot.x(D1),
                    slot.z(D1));
            ServerPlayer overworld = slot.player();
            boolean sameColumns = storage.createClaim(slot.at(D0 - 20, 1050), slot.at(D0 - 8, 1061), overworld);
            helper.assertTrue(sameColumns && slot.claimsOf(overworld).size() == 1,
                    "区只在下界时, 主世界的同一坐标照常圈");
        }
        helper.succeed();
    }

    // ================================================================
    // 金锄头与改范围 (F2)
    // ================================================================

    /**
     * 槽位 33, 走真金锄头第二下的那段代码 (claimLandHandling): 第一下记在外面、第二下落进外围: 没有新领地、收到提示、已点的角
     * 被清掉; 外面一块领地, 拖角拖进外围: 范围不变; 拖到远离区的一侧: 范围照改。
     */
    static void goldenHoeClicksAreGuarded(GameTestHelper helper) {
        try (Slot slot = new Slot(helper, 33)) {
            slot.zone();
            ClaimStorage storage = slot.storage();

            ServerPlayer creator = slot.player();
            PlayerClaimData creatorData = PlayerClaimData.get(creator);
            creatorData.setEditingCorner(slot.at(D0 - 25, 1050));
            ItemInteractEvents.claimLandHandling(creator, slot.at(D0 - 7, 1060));
            helper.assertTrue(slot.claimsOf(creator).isEmpty() && keysOf(chat(creator)).contains(CREATE_DENIED)
                            && creatorData.editingCorner() == null,
                    "第二下落进外围: 没有新领地、收到提示、已点的角被清掉");

            ServerPlayer dragger = slot.player();
            helper.assertTrue(storage.createClaim(slot.at(D0 - 40, 1050), slot.at(D0 - 29, 1061), dragger),
                    "前提: 外面圈一块");
            Claim dragged = only(helper, slot.claimsOf(dragger));
            PlotArea before = areaOf(dragged);
            PlayerClaimData draggerData = PlayerClaimData.get(dragger);
            draggerData.setEditClaim(dragged, Y);
            draggerData.setEditingCorner(slot.at(D0 - 29, 1061));
            chat(dragger);
            ItemInteractEvents.claimLandHandling(dragger, slot.at(D0 - 7, 1061));
            helper.assertTrue(areaOf(dragged).equals(before) && keysOf(chat(dragger)).contains(RESIZE_DENIED)
                            && draggerData.currentEdit() == null,
                    "拖角拖进外围: 范围不变, 收到提示, 编辑状态清掉; 实为 " + areaOf(dragged));

            ServerPlayer mover = slot.player();
            helper.assertTrue(storage.createClaim(slot.at(D0 - 60, 1050), slot.at(D0 - 49, 1061), mover),
                    "前提: 再远一点圈一块");
            Claim moved = only(helper, slot.claimsOf(mover));
            ClaimBox box = moved.getDimensions();
            PlotArea expected = PersonalClaimGuard.resizedBox(box.minX(), box.minZ(), box.maxX(), box.maxZ(),
                    slot.x(D0 - 60), slot.z(1050), slot.x(D0 - 70), slot.z(1050));
            PlayerClaimData moverData = PlayerClaimData.get(mover);
            moverData.setEditClaim(moved, Y);
            moverData.setEditingCorner(slot.at(D0 - 60, 1050));
            ItemInteractEvents.claimLandHandling(mover, slot.at(D0 - 70, 1050));
            helper.assertTrue(areaOf(moved).equals(expected)
                            && expected.equals(slot.box(D0 - 70, 1050, D0 - 49, 1061)),
                    "拖到远离区的一侧: 范围照改, 与 resizedBox 一致; 实为 " + areaOf(moved));
        }
        helper.succeed();
    }

    /**
     * 槽位 34: 外面的领地 resizeClaim 扩进外围: false、范围不变、收到 claim_resize_denied; 用 rawPlayerClaim (不经 F1) 放一块
     * 压着外围的老领地: 缩小、远端外挪放行, 沿外围加长拒; 每次放行之后 Flan 的范围等于 resizedBox 算出的框。
     */
    static void resizeRules(GameTestHelper helper) {
        try (Slot slot = new Slot(helper, 34)) {
            slot.zone();
            ClaimStorage storage = slot.storage();
            // 外面那块放在 Z 1100 起, 离下面老领地的挪动范围远一点 (两块重叠的话 Flan 自己会以冲突拒绝)。
            ServerPlayer outsider = slot.player();
            helper.assertTrue(storage.createClaim(slot.at(D0 - 30, 1100), slot.at(D0 - 19, 1111), outsider),
                    "前提: 外面圈一块");
            Claim outside = only(helper, slot.claimsOf(outsider));
            expectResize(helper, storage, outside, outsider, slot.at(D0 - 19, 1111), slot.at(D0 - 5, 1111), false,
                    "外面的领地扩进外围");

            ServerPlayer owner = slot.player();
            Claim old = FlanTestClaims.rawPlayerClaim(slot.level, slot.box(D0 - 15, 1050, D0 - 4, 1061),
                    owner.getUUID());
            expectResize(helper, storage, old, owner, slot.at(D0 - 4, 1061), slot.at(D0 - 6, 1061), true,
                    "压着外围的老领地缩小");
            expectResize(helper, storage, old, owner, slot.at(D0 - 15, 1050), slot.at(D0 - 22, 1050), true,
                    "老领地把远离区的一边往外挪 (外围那部分不变)");
            helper.assertTrue(areaOf(old).equals(slot.box(D0 - 22, 1050, D0 - 6, 1061)), "两次放行之后的范围");
            expectResize(helper, storage, old, owner, slot.at(D0 - 6, 1061), slot.at(D0 - 6, 1075), false,
                    "老领地沿外围加长");
        }
        helper.succeed();
    }

    // ================================================================
    // 金锄头的陈旧编辑 (22.22)
    // ================================================================

    /**
     * 槽位 40: 编辑状态与登记不一致时, F2 拒并提示 claim_stale_edit (不是 claim_resize_denied), 领地范围与登记不变,
     * 编辑状态照常清掉; 主人、2 级 OP、管理员领地一律拒。对照组照改。
     */
    static void inconsistentEditIsRefusedSlot40(GameTestHelper helper) {
        ServerLevel netherLevel = FlanTestClaims.level(helper.getLevel().getServer(), NETHER);
        try (Slot slot = new Slot(helper, 40)) {
            slot.zone();
            ClaimStorage overworld = slot.storage();
            ClaimStorage nether = FlanTestClaims.storage(netherLevel);

            ServerPlayer owner = slot.player();
            helper.assertTrue(overworld.createClaim(slot.at(D0 - 40, 1050), slot.at(D0 - 29, 1061), owner),
                    "前提: 外面圈一块");
            Claim claim = only(helper, slot.claimsOf(owner));
            PlotArea before = areaOf(claim);
            PlayerClaimData data = PlayerClaimData.get(owner);
            data.setEditClaim(claim, Y);
            data.setEditingCorner(slot.at(D0 - 29, 1061));
            chat(owner);
            slot.inLevel(owner, netherLevel,
                    () -> ItemInteractEvents.claimLandHandling(owner, slot.at(D0 - 5, 1061)));
            List<String> keys = keysOf(chat(owner));
            helper.assertTrue(keys.contains(STALE_EDIT) && !keys.contains(RESIZE_DENIED),
                    "编辑状态与登记不一致: 收到 claim_stale_edit, 实为 " + keys);
            helper.assertTrue(areaOf(claim).equals(before) && !claim.isRemoved() && claim.getLevel() == slot.level,
                    "领地范围不变, 实为 " + areaOf(claim));
            helper.assertTrue(overworld.getFromUUID(claim.getClaimID()) == claim
                            && nether.getFromUUID(claim.getClaimID()) == null
                            && nether.allClaimsFromPlayer(owner.getUUID()).isEmpty(),
                    "登记不变");
            helper.assertTrue(data.currentEdit() == null && data.editingCorner() == null, "编辑状态照常清掉");

            expectStale(helper, nether, claim, owner, slot.at(D0 - 29, 1061), slot.at(D0 - 5, 1061),
                    "往外围拖");
            expectStale(helper, nether, claim, owner, slot.at(D0 - 40, 1050), slot.at(D0 - 45, 1050),
                    "往远离区的一侧拖 (不碰任何禁圈区)");
            ServerPlayer op = slot.op(2);
            helper.assertTrue(op.hasPermissions(2), "前提: 2 级 OP");
            expectStale(helper, nether, claim, op, slot.at(D0 - 40, 1050), slot.at(D0 - 45, 1050), "2 级 OP");
            Claim admin = FlanTestClaims.rawAdminClaim(slot.level, slot.box(D0 - 80, 1050, D0 - 69, 1061));
            expectStale(helper, nether, admin, op, slot.at(D0 - 69, 1061), slot.at(D0 - 66, 1061),
                    "管理员领地 (父领地同样)");

            PlotArea expected = slot.box(D0 - 45, 1050, D0 - 29, 1061);
            helper.assertTrue(overworld.resizeClaim(claim, slot.at(D0 - 40, 1050), slot.at(D0 - 45, 1050), owner)
                            && areaOf(claim).equals(expected) && overworld.getFromUUID(claim.getClaimID()) == claim
                            && nether.getFromUUID(claim.getClaimID()) == null,
                    "对照: 往远离区的一侧拖照改, 实为 " + areaOf(claim));
        } finally {
            purge(netherLevel, FlanTestClaims.slotArea(40));
        }
        helper.succeed();
    }

    /**
     * 槽位 41: 编辑状态与登记不一致时, F2 拒并提示 claim_stale_edit, 领地范围与登记不变; 2 级 OP 同样拒。
     */
    static void inconsistentEditIsRefusedSlot41(GameTestHelper helper) {
        try (Slot slot = new Slot(helper, 41)) {
            slot.zone();
            ClaimStorage storage = slot.storage();

            ServerPlayer owner = slot.player();
            Claim claim = FlanTestClaims.rawPlayerClaim(slot.level, slot.box(D0 - 15, 1050, D0 - 4, 1061),
                    owner.getUUID());
            PlayerClaimData data = PlayerClaimData.get(owner);
            data.setEditClaim(claim, Y);
            data.setEditingCorner(slot.at(D0 - 4, 1061));
            FlanTestClaims.adminDelete(slot.level, claim);
            helper.assertTrue(claim.isRemoved() && storage.getFromUUID(claim.getClaimID()) == null
                            && data.currentEdit() == claim,
                    "前提: 编辑状态与登记不一致");
            chat(owner);
            ItemInteractEvents.claimLandHandling(owner, slot.at(D0 - 6, 1061));
            List<String> keys = keysOf(chat(owner));
            helper.assertTrue(keys.contains(STALE_EDIT), "编辑状态与登记不一致: 收到 claim_stale_edit, 实为 " + keys);
            expectStillDeleted(helper, slot, claim, owner, "外围老领地");
            helper.assertTrue(data.currentEdit() == null && data.editingCorner() == null, "编辑状态照常清掉");

            ServerPlayer self = slot.player();
            helper.assertTrue(storage.createClaim(slot.at(D0 - 40, 1050), slot.at(D0 - 29, 1061), self),
                    "前提: 外面圈一块");
            Claim own = only(helper, slot.claimsOf(self));
            PlayerClaimData selfData = PlayerClaimData.get(self);
            selfData.setEditClaim(own, Y);
            selfData.setEditingCorner(slot.at(D0 - 40, 1050));
            storage.deleteClaim(own, true, ClaimMode.DEFAULT, slot.level);
            chat(self);
            ItemInteractEvents.claimLandHandling(self, slot.at(D0 - 45, 1050));
            helper.assertTrue(keysOf(chat(self)).contains(STALE_EDIT), "外面那块: 收到 claim_stale_edit");
            expectStillDeleted(helper, slot, own, self, "外面那块");

            ServerPlayer op = slot.op(2);
            chat(op);
            helper.assertTrue(!storage.resizeClaim(own, slot.at(D0 - 40, 1050), slot.at(D0 - 45, 1050), op)
                            && keysOf(chat(op)).contains(STALE_EDIT),
                    "2 级 OP 直接调 resizeClaim: 同样拒");
            expectStillDeleted(helper, slot, own, self, "OP 之后");
        }
        helper.succeed();
    }

    /** 编辑状态与登记不一致时改范围: 拒, 收到 claim_stale_edit, 范围与登记都不变。 */
    private static void expectStale(GameTestHelper helper, ClaimStorage storage, Claim claim, ServerPlayer actor,
                                    BlockPos from, BlockPos to, String what) {
        ClaimStorage home = FlanTestClaims.storage(claim.getLevel());
        PlotArea before = areaOf(claim);
        chat(actor);
        boolean result = storage.resizeClaim(claim, from, to, actor);
        List<String> keys = keysOf(chat(actor));
        helper.assertTrue(!result && areaOf(claim).equals(before) && !claim.isRemoved()
                        && keys.contains(STALE_EDIT) && !keys.contains(RESIZE_DENIED),
                what + ": 拒, 范围不变 (" + areaOf(claim) + "), 收到 claim_stale_edit, 实为 " + keys);
        helper.assertTrue(home.getFromUUID(claim.getClaimID()) == claim && storage.getFromUUID(claim.getClaimID()) == null,
                what + ": 登记不变");
    }

    /** 编辑被拒之后, 这块领地的登记不变。 */
    private static void expectStillDeleted(GameTestHelper helper, Slot slot, Claim claim, ServerPlayer owner,
                                           String what) {
        ClaimStorage storage = slot.storage();
        ClaimBox box = claim.getDimensions();
        BlockPos middle = FlanTestClaims.probe((box.minX() + box.maxX()) / 2, (box.minZ() + box.maxZ()) / 2);
        helper.assertTrue(claim.isRemoved() && storage.getFromUUID(claim.getClaimID()) == null
                        && storage.allClaimsFromPlayer(owner.getUUID()).isEmpty() && storage.getClaimAt(middle) == null,
                what + ": 登记不变");
    }

    /**
     * 删掉这个世界的存储里与这片范围相交的全部顶层领地, 不管 removed 标志 (清理用例可能留下的残余)。
     */
    private static void purge(ServerLevel level, PlotArea area) {
        ClaimStorage storage = FlanTestClaims.storage(level);
        for (Set<Claim> owned : List.copyOf(storage.getClaims().values())) {
            for (Claim claim : List.copyOf(owned)) {
                if (areaOf(claim).overlaps(area)) {
                    storage.deleteClaim(claim, true, ClaimMode.DEFAULT, level);
                }
            }
        }
    }

    /** 改一次范围并核对: 放行时 Flan 的范围等于 resizedBox (公式与 Flan 一致), 拒绝时范围不变、收到提示。 */
    private static void expectResize(GameTestHelper helper, ClaimStorage storage, Claim claim, ServerPlayer actor,
                                     BlockPos from, BlockPos to, boolean allowed, String what) {
        ClaimBox box = claim.getDimensions();
        PlotArea before = areaOf(claim);
        PlotArea formula = PersonalClaimGuard.resizedBox(box.minX(), box.minZ(), box.maxX(), box.maxZ(), from.getX(),
                from.getZ(), to.getX(), to.getZ());
        chat(actor);
        boolean result = storage.resizeClaim(claim, from, to, actor);
        List<String> keys = keysOf(chat(actor));
        PlotArea after = areaOf(claim);
        if (allowed) {
            helper.assertTrue(result && after.equals(formula) && !keys.contains(RESIZE_DENIED),
                    what + ": 放行, Flan 改完的范围 " + after + " 应等于 resizedBox " + formula);
        } else {
            helper.assertTrue(!result && after.equals(before) && keys.contains(RESIZE_DENIED),
                    what + ": 拒, 范围不变 (" + after + "), 收到 claim_resize_denied, 实为 " + keys);
        }
    }

    // ================================================================
    // /flan 命令
    // ================================================================

    /**
     * 槽位 35, 以 mock 玩家执行: /flan add &lt;外围里的两角&gt;、站在外围里 /flan add rect 10 10: 都没有新领地 (对照: 外面的
     * 两条照常圈出来); 朝着区 /flan expand 15: 范围不变; 背着区 /flan expand 10: 照常扩。
     */
    static void flanCommandsAreGuarded(GameTestHelper helper) {
        try (Slot slot = new Slot(helper, 35)) {
            slot.zone();
            BlockPos from = slot.at(D0 - 15, 1050);
            BlockPos to = slot.at(D0 - 4, 1061);
            slot.loadChunks(from, to);
            ServerPlayer adder = slot.player();
            helper.assertTrue(slot.run(adder, "flan add " + coords(from) + " " + coords(to)) == 1
                            && slot.claimsOf(adder).isEmpty() && keysOf(chat(adder)).contains(CREATE_DENIED),
                    "/flan add 两角落在外围: 命令跑到了 createClaim, 没有新领地");
            BlockPos controlFrom = slot.at(D0 - 40, 1050);
            BlockPos controlTo = slot.at(D0 - 29, 1061);
            slot.loadChunks(controlFrom, controlTo);
            ServerPlayer control = slot.player();
            helper.assertTrue(slot.run(control, "flan add " + coords(controlFrom) + " " + coords(controlTo)) == 1
                            && slot.claimsOf(control).size() == 1,
                    "对照: 外面的 /flan add 照常圈出来");

            ServerPlayer rect = slot.player();
            slot.stand(rect, D0 - 5, 1055, 0f);
            helper.assertTrue(slot.run(rect, "flan add rect 10 10") == 1 && slot.claimsOf(rect).isEmpty()
                            && keysOf(chat(rect)).contains(CREATE_DENIED),
                    "站在外围里 /flan add rect 10 10: 没有新领地");
            ServerPlayer rectControl = slot.player();
            slot.stand(rectControl, D0 - 60, 1055, 0f);
            helper.assertTrue(slot.run(rectControl, "flan add rect 10 10") == 1
                            && slot.claimsOf(rectControl).size() == 1,
                    "对照: 站在外面 /flan add rect 10 10 照常圈出来");

            ServerPlayer expander = slot.player();
            helper.assertTrue(slot.storage().createClaim(slot.at(D0 - 30, 1070), slot.at(D0 - 19, 1081), expander),
                    "前提: 外面圈一块");
            Claim claim = only(helper, slot.claimsOf(expander));
            PlotArea before = areaOf(claim);
            slot.stand(expander, D0 - 25, 1075, -90f);
            chat(expander);
            helper.assertTrue(slot.run(expander, "flan expand 15") == 0 && areaOf(claim).equals(before)
                            && keysOf(chat(expander)).contains(RESIZE_DENIED),
                    "朝着区 (东) /flan expand 15: 范围不变, 实为 " + areaOf(claim));
            slot.stand(expander, D0 - 25, 1075, 90f);
            helper.assertTrue(slot.run(expander, "flan expand 10") == 1
                            && areaOf(claim).equals(slot.box(D0 - 40, 1070, D0 - 19, 1081)),
                    "背着区 (西) /flan expand 10: 照常扩, 实为 " + areaOf(claim));
        }
        helper.succeed();
    }

    // ================================================================
    // 认人与例外、开关
    // ================================================================

    /**
     * 槽位 36: 2 级 OP 在外围 createClaim: true, 收到 claim_op_exempt; 1 级 OP: false; UUID 在 2 级 OP 名单里的
     * FakePlayer: false (与机械动力禁令同一口径)。
     */
    static void opExemptionMatchesTheCreateBan(GameTestHelper helper) {
        try (Slot slot = new Slot(helper, 36)) {
            String districtId = slot.zone();
            ClaimStorage storage = slot.storage();
            ServerPlayer op = slot.op(2);
            helper.assertTrue(op.hasPermissions(2) && !(op instanceof FakePlayer), "前提: 2 级 OP, 真玩家");
            boolean opMade = storage.createClaim(slot.at(D0 - 15, 1050), slot.at(D0 - 4, 1061), op);
            List<Component> said = chat(op);
            List<Claim> owned = slot.claimsOf(op);
            helper.assertTrue(opMade && owned.size() == 1 && op.getUUID().equals(owned.get(0).getOwner()),
                    "2 级 OP 在外围照常圈");
            helper.assertTrue(argsOf(said, PersonalClaimGuard.KEY_OP_EXEMPT).equals(List.of(slot.key, BUFFER,
                            districtId)) && !keysOf(said).contains(CREATE_DENIED),
                    "OP 收到金色提示 (区名、8、区 id), 实为 " + keysOf(said));

            ServerPlayer levelOne = slot.op(1);
            helper.assertTrue(!levelOne.hasPermissions(2), "前提: 1 级 OP");
            helper.assertTrue(!storage.createClaim(slot.at(D0 - 15, 1070), slot.at(D0 - 4, 1081), levelOne)
                            && slot.claimsOf(levelOne).isEmpty() && keysOf(chat(levelOne)).contains(CREATE_DENIED),
                    "1 级 OP 不例外");

            FakePlayer fake = slot.fake(2);
            helper.assertTrue(fake.hasPermissions(2), "前提: 假玩家的 UUID 在 2 级 OP 名单里");
            helper.assertTrue(!storage.createClaim(slot.at(D0 - 15, 1090), slot.at(D0 - 4, 1101), fake)
                            && slot.claimsOf(fake).isEmpty(),
                    "OP 的假玩家 (机械手之类) 不例外");
        }
        helper.succeed();
    }

    /**
     * 槽位 37: 非 OP 调 resizeClaim 把父领地外扩 4 格: true (管理员领地不管, 权限由 Flan 在 claimLandHandling 查); 外围老
     * 领地里划子领地: 成功; 急停 personalClaims = false: 外围 createClaim 成功; 门面 OFF (suspend): 同样成功。
     */
    static void adminClaimsSubclaimsAndSwitch(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 37); Slot slot = new Slot(helper, 37)) {
            env.districtAt("abydos", env.slotBounds(D0, D0, D1, D1));
            slot.zone();
            ClaimStorage storage = slot.storage();
            Claim parent = FlanTestClaims.claim(slot.level, env.districtRecord("abydos").flanClaimId());
            helper.assertTrue(parent != null && parent.isAdminClaim(), "前提: 父领地是管理员领地");
            ServerPlayer player = slot.player();
            ClaimBox box = parent.getDimensions();
            boolean resized = storage.resizeClaim(parent, new BlockPos(box.maxX(), Y, box.maxZ()),
                    new BlockPos(box.maxX() + 4, Y, box.maxZ()), player);
            helper.assertTrue(resized && parent.getDimensions().maxX() == box.maxX() + 4
                            && !keysOf(chat(player)).contains(RESIZE_DENIED),
                    "管理员领地改范围不归本节管");

            ServerPlayer owner = slot.player();
            Claim old = FlanTestClaims.rawPlayerClaim(slot.level, slot.box(D0 - 15, 1050, D0 - 4, 1061),
                    owner.getUUID());
            Claim sub = FlanTestClaims.rawSubclaim(slot.level, old, slot.box(D0 - 12, 1052, D0 - 6, 1058));
            helper.assertTrue(sub.parentClaim() == old && old.getAllSubclaims().size() == 1,
                    "外围老领地里划子领地: 成功 (只是细分已有的面积)");

            GuardSettings batch = GuardTestZones.settings();
            try {
                GuardTestZones.useSettings(batch.withPersonalClaims(false));
                ServerPlayer switchedOff = slot.player();
                helper.assertTrue(storage.createClaim(slot.at(D0 - 15, 1070), slot.at(D0 - 4, 1081), switchedOff)
                                && slot.claimsOf(switchedOff).size() == 1,
                        "急停 personalClaims = false: 外围照常圈");
            } finally {
                GuardTestZones.useSettings(batch);
            }
            try (AutoCloseable off = GuardTestZones.suspend()) {
                ServerPlayer suspended = slot.player();
                helper.assertTrue(storage.createClaim(slot.at(D0 - 15, 1090), slot.at(D0 - 4, 1101), suspended)
                                && slot.claimsOf(suspended).size() == 1,
                        "门面 OFF (功能关着): 外围照常圈");
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
        helper.succeed();
    }

    // ================================================================
    // 外围已有的个人领地 (P40, 登记在 FlanRealGameTests)
    // ================================================================

    /**
     * 槽位 38: 外围里一块 2D、一块 3D, 另一块止于 minX − 9 (在外面): /district personalclaims 返回 2, 列出这两块 (主人、
     * 范围、3D 的 Y、离区边格数), 外面那块不在; 命令、一轮自动对账、resync 前后三块的 toJson 逐字相同。领地对接没有生效时
     * 回"领地对接没有生效"。
     */
    static void personalClaimsAreListedNeverDeleted(GameTestHelper helper) {
        ServerLevel level = FlanTestClaims.level(helper.getLevel().getServer(), DIM);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 38)) {
            env.districtAt("abydos", env.slotBounds(D0, D0, D1, D1));
            env.seen("Buffer_A");
            Claim flat = FlanTestClaims.rawPlayerClaim(level, env.slotArea(D0 - 15, 1050, D0 - 4, 1061),
                    uuidOf("Buffer_A"));
            Claim threeD = FlanTestClaims.storage(level).createAdminClaim(
                    new BlockPos(env.x(D1 + 4), 100, env.z(1100)), new BlockPos(env.x(D1 + 13), 120, env.z(1109)),
                    level, true);
            helper.assertTrue(threeD != null, "前提: 3D 领地圈得出来");
            FlanTestClaims.storage(level).transferOwner(threeD, uuidOf("Buffer_B"));
            Claim outside = FlanTestClaims.rawPlayerClaim(level, env.slotArea(D0 - 21, 1120, D0 - 9, 1131),
                    uuidOf("Outside_C"));
            List<String> before = json(flat, threeD, outside);

            FlanRealScenarios.Console console = new FlanRealScenarios.Console(helper);
            List<String> keys = console.expect("district personalclaims abydos", 2, KEY + "personalclaims.header");
            List<String> texts = console.texts();
            helper.assertTrue(console.lastArgs(KEY + "personalclaims.header").equals(List.of("abydos", BUFFER, "2")),
                    "表头: 区、8、2 块");
            helper.assertTrue(texts.contains("Buffer_A") && texts.contains(idPrefix(flat))
                            && texts.contains(idPrefix(threeD))
                            && texts.contains(uuidOf("Buffer_B").toString().substring(0, 8))
                            && texts.contains(rangeText(flat)) && texts.contains("100") && texts.contains("120")
                            && texts.contains("4"),
                    "列出主人 (见过的按名字, 没见过的按 UUID 前 8 位)、范围、3D 的 Y、离区边 4 格, 实为 " + texts);
            helper.assertTrue(keys.contains(KEY + "personalclaims.three_d") && keys.contains(KEY + "personalclaims.flat")
                            && keys.contains(KEY + "personalclaims.buffer") && keys.contains(KEY + "personalclaims.advice"),
                    "2D、3D、外围的位置与处理办法都有, 实为 " + keys);
            helper.assertTrue(!texts.contains(idPrefix(outside)), "止于 minX − 9 的那块不在清单里");

            env.ctx.reconciler().reconcileDistrict("abydos", DistrictReconciler.Mode.AUTO);
            env.ctx.reconciler().reconcileDistrict("abydos", DistrictReconciler.Mode.EXPLICIT);
            helper.assertTrue(json(flat, threeD, outside).equals(before)
                            && !flat.isRemoved() && !threeD.isRemoved() && !outside.isRemoved(),
                    "命令、自动对账、resync 前后三块个人领地一个字都没变");
        }
        try (DistrictTestEnv degraded = DistrictTestEnv.openWith(new DisabledFlanGateway("测试"))) {
            degraded.districtAt("abydos", degraded.slotBounds(D0, D0, D1, D1));
            new FlanRealScenarios.Console(helper).expect("district personalclaims abydos", 0,
                    KEY + "flan_unavailable");
        }
        helper.succeed();
    }

    /**
     * 槽位 39: 管理员领地的外围 (北边) 有一块个人领地: bind 预览有那一行, confirm 回显摘要 1, 那块不变; 用测试工具把父领地
     * 往西外扩 5 格, 另一块也落进外围: bounds sync 回显摘要 2。
     */
    static void bindAndBoundsSyncReportBufferClaims(GameTestHelper helper) {
        ServerLevel level = FlanTestClaims.level(helper.getLevel().getServer(), DIM);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 39)) {
            Claim raw = FlanTestClaims.rawAdminClaim(level, env.slotArea(D0, D0, D1, D1));
            Claim north = FlanTestClaims.rawPlayerClaim(level, env.slotArea(1050, D0 - 15, 1061, D0 - 4),
                    uuidOf("North_P"));
            Claim west = FlanTestClaims.rawPlayerClaim(level, env.slotArea(D0 - 25, 1050, D0 - 12, 1061),
                    uuidOf("West_P"));
            List<String> before = json(north, west);

            FlanRealScenarios.Console console = new FlanRealScenarios.Console(helper);
            String bind = "district bind abydos " + env.x(1050) + " " + env.z(1050);
            List<String> preview = console.expect(bind, 1, KEY + "bind.preview.confirm");
            helper.assertTrue(preview.contains(KEY + "personalclaims.preview")
                            && console.lastArgs(KEY + "personalclaims.preview").equals(List.of(BUFFER, "1")),
                    "bind 预览多一行: 外围 8 格内有 1 块个人领地, 实为 " + preview);
            List<String> bound = console.expect(bind + " confirm", 1, KEY + "bind.done");
            helper.assertTrue(bound.contains(KEY + "personalclaims.summary")
                            && console.lastArgs(KEY + "personalclaims.summary").equals(List.of(BUFFER, "1", "abydos")),
                    "confirm 回显摘要 1, 实为 " + bound);
            helper.assertTrue(json(north, west).equals(before), "绑定不碰外围的个人领地");

            FlanTestClaims.simulateGoldenHoeResize(level, raw, env.slotArea(D0 - 5, D0, D1, D1));
            List<String> synced = console.expect("district bounds abydos sync", 1, KEY + "bounds.sync.done");
            helper.assertTrue(synced.contains(KEY + "personalclaims.summary")
                            && console.lastArgs(KEY + "personalclaims.summary").equals(List.of(BUFFER, "2", "abydos")),
                    "父领地往西扩 5 格之后, 西边那块也落进外围: bounds sync 回显摘要 2, 实为 " + synced);
            helper.assertTrue(json(north, west).equals(before) && !north.isRemoved() && !west.isRemoved(),
                    "改范围也不碰外围的个人领地");
        }
        helper.succeed();
    }

    // ================================================================
    // 工具
    // ================================================================

    /** 一个槽位: 测试区、mock 玩家、OP 名单, close 时复原 (玩家摆回原处再移出, OP 移出名单, 区拿掉, 领地删掉)。 */
    private static final class Slot implements AutoCloseable {

        final GameTestHelper helper;
        final MinecraftServer server;
        final ServerLevel level;
        final PlotArea area;
        final String key;
        private final Map<ServerPlayer, Vec3> players = new LinkedHashMap<>();
        private final List<GameProfile> ops = new ArrayList<>();

        Slot(GameTestHelper helper, int slot) {
            this.helper = helper;
            this.server = helper.getLevel().getServer();
            this.level = FlanTestClaims.level(server, DIM);
            this.area = FlanTestClaims.slotArea(slot);
            this.key = "claim" + slot;
            FlanTestClaims.deleteTopLevelClaims(server, DIM, area);
        }

        int x(int dx) {
            return area.minX() + dx;
        }

        int z(int dz) {
            return area.minZ() + dz;
        }

        BlockPos at(int dx, int dz) {
            return at(dx, Y, dz);
        }

        BlockPos at(int dx, int y, int dz) {
            return new BlockPos(x(dx), y, z(dz));
        }

        PlotArea box(int minDx, int minDz, int maxDx, int maxDz) {
            return new PlotArea(x(minDx), z(minDz), x(maxDx), z(maxDz));
        }

        ClaimStorage storage() {
            return FlanTestClaims.storage(level);
        }

        List<Claim> claimsOf(ServerPlayer player) {
            return FlanTestClaims.playerClaims(level, player.getUUID());
        }

        /** 在槽位的偏移 D0–D1 装测试区 (区 id = "gt-" + key, 显示名 = key)。 */
        String zone() {
            return GuardTestZones.putAbsolute(DIM, key, x(D0), z(D0), x(D1), z(D1));
        }

        /** 一个新的 mock 玩家 (随机 UUID), 登录时收到的包先读掉。 */
        ServerPlayer player() {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            players.put(player, player.position());
            chat(player);
            return player;
        }

        /** 放进 OP 名单 (给定等级) 的 mock 玩家。 */
        ServerPlayer op(int permissionLevel) {
            ServerPlayer player = player();
            ops.add(player.getGameProfile());
            server.getPlayerList().getOps().add(new ServerOpListEntry(player.getGameProfile(), permissionLevel,
                    false));
            return player;
        }

        /** UUID 放进 OP 名单的 Forge 假玩家 (不进玩家列表)。 */
        FakePlayer fake(int permissionLevel) {
            GameProfile profile = new GameProfile(UUID.randomUUID(), "Gt_Fake_Op");
            ops.add(profile);
            server.getPlayerList().getOps().add(new ServerOpListEntry(profile, permissionLevel, false));
            return new FakePlayer(level, profile);
        }

        /**
         * 临时把玩家的 serverLevel 换成 other 做一件事, 做完 (出错也一样) 换回。不走真的换维度流程, 全程同步、
         * 不跨 tick。
         */
        void inLevel(ServerPlayer player, ServerLevel other, Runnable action) {
            ServerLevel home = player.serverLevel();
            player.setServerLevel(other);
            try {
                helper.assertTrue(player.serverLevel() == other, "前提: 玩家现在在 " + other.dimension().location());
                action.run();
            } finally {
                player.setServerLevel(home);
            }
        }

        /** 把玩家摆到槽位里的一列 (只改坐标与朝向, 不加载区块)。 */
        void stand(ServerPlayer player, int dx, int dz, float yRot) {
            player.setPos(x(dx) + 0.5, Y, z(dz) + 0.5);
            player.setYRot(yRot);
        }

        /** 把这几格所在的区块载进来 (/flan add &lt;两角&gt; 的 getLoadedBlockPos 要求已加载)。 */
        void loadChunks(BlockPos... positions) {
            for (BlockPos pos : positions) {
                level.getChunk(pos);
                helper.assertTrue(level.hasChunkAt(pos), "前提: " + pos + " 所在的区块已加载");
            }
        }

        /** 以这个玩家的身份执行命令, 返回命令的返回值; 解析或执行抛语法错时让用例失败。 */
        int run(ServerPlayer player, String command) {
            try {
                return server.getCommands().getDispatcher().execute(command, player.createCommandSourceStack());
            } catch (CommandSyntaxException syntax) {
                helper.fail("/" + command + " 没能执行: " + syntax.getMessage());
                return -1;
            }
        }

        @Override
        public void close() {
            try {
                for (GameProfile profile : ops) {
                    server.getPlayerList().getOps().remove(profile);
                }
                for (Map.Entry<ServerPlayer, Vec3> entry : players.entrySet()) {
                    Vec3 home = entry.getValue();
                    entry.getKey().setPos(home.x, home.y, home.z);
                    DistrictTestEnv.removePlayer(helper, entry.getKey());
                }
            } finally {
                GuardTestZones.remove(key);
                FlanTestClaims.deleteTopLevelClaims(server, DIM, area);
            }
        }
    }

    private static Claim only(GameTestHelper helper, List<Claim> claims) {
        helper.assertTrue(claims.size() == 1, "应恰好一块领地, 实为 " + claims.size());
        return claims.get(0);
    }

    private static PlotArea areaOf(Claim claim) {
        return FlanTestClaims.areaOf(claim.getDimensions());
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** /district personalclaims 清单里的范围写法。 */
    private static String rangeText(Claim claim) {
        ClaimBox box = claim.getDimensions();
        return "(" + box.minX() + ", " + box.minZ() + ") ~ (" + box.maxX() + ", " + box.maxZ() + ")";
    }

    private static String idPrefix(Claim claim) {
        return claim.getClaimID().toString().substring(0, 8);
    }

    private static List<String> json(Claim... claims) {
        List<String> out = new ArrayList<>();
        for (Claim claim : claims) {
            out.add(claim.toJson(new JsonObject()).toString());
        }
        return out;
    }

    /** 读掉这个 mock 玩家至今收到的全部聊天 (系统消息)。 */
    private static List<Component> chat(ServerPlayer player) {
        EmbeddedChannel channel = (EmbeddedChannel) player.connection.connection.channel();
        List<Component> messages = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            try {
                if (outbound instanceof ClientboundSystemChatPacket packet) {
                    messages.add(packet.content());
                }
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        }
        return messages;
    }

    private static List<String> keysOf(List<Component> messages) {
        List<String> keys = new ArrayList<>();
        for (Component message : messages) {
            if (message.getContents() instanceof TranslatableContents translatable) {
                keys.add(translatable.getKey());
            }
        }
        return keys;
    }

    /** 某个语言键的参数 (最后出现的那一条; 没出现为空)。 */
    private static List<String> argsOf(List<Component> messages, String key) {
        List<String> args = List.of();
        for (Component message : messages) {
            if (message.getContents() instanceof TranslatableContents translatable
                    && key.equals(translatable.getKey())) {
                List<String> found = new ArrayList<>();
                for (Object arg : translatable.getArgs()) {
                    found.add(arg instanceof Component component ? component.getString() : String.valueOf(arg));
                }
                args = found;
            }
        }
        return args;
    }
}
