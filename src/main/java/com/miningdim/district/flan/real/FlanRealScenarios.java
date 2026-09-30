package com.miningdim.district.flan.real;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.miningdim.district.DistrictFeature;
import com.miningdim.district.DistrictSystem;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.FriendRecord;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PermissionCatalog;
import com.miningdim.district.core.PermissionCells;
import com.miningdim.district.core.PlotSyncStatus;
import com.miningdim.district.core.ResidentSyncStatus;
import com.miningdim.district.flan.ClaimHandle;
import com.miningdim.district.flan.ClaimPermissionSnapshot;
import com.miningdim.district.flan.DisabledFlanGateway;
import com.miningdim.district.flan.DistrictReconciler;
import com.miningdim.district.flan.FlanGroupNames;
import com.miningdim.district.flan.FlanPermissionPolicy;
import com.miningdim.district.flan.FlanPermissions;
import com.miningdim.district.flan.FlanResult;
import com.miningdim.district.flan.GatewaySelector;
import com.miningdim.district.flan.KnownPermission;
import com.miningdim.district.flan.PermValue;
import com.miningdim.district.flan.ReconcileScheduler;
import com.miningdim.district.service.DistrictAdminService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.util.Either;
import io.github.flemmli97.flan.claim.Claim;
import io.github.flemmli97.flan.claim.ClaimBox;
import io.github.flemmli97.flan.config.Config;
import io.github.flemmli97.flan.config.ConfigHandler;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static com.miningdim.district.DistrictTestEnv.player;
import static com.miningdim.district.DistrictTestEnv.uuidOf;

/**
 * 真 Flan 的 GameTest 的用例体 (设计文档 20.10): 开发运行时加载着服主批准的 flan-1.20.1-1.11.16-forge.jar (build.gradle
 * 的 runtimeOnly fg.deobf, 内嵌的 lingua_bib 原样取出后作为根 mod 加载, 20.1 出路 ③), 这里的每条用例都经真网关写真
 * Flan, 再用 Flan 自己的判定入口 ({@code ClaimHandler.canInteract}、{@code getForPermissionCheck}、放行清单) 核对效果。
 *
 * <p>登记在 {@link FlanRealGameTests} 里: 那个 holder 的方法签名里没有任何 Flan 类型, 先查 Flan 在不在位再调这里。
 * GameTest 框架登记用例时要对 holder 调 getDeclaredMethods(), 签名里 (连同 lambda 生成的合成方法) 带 Claim 之类的类型,
 * 没有 Flan 的运行时整个 GameTest 服务端就会在跑任何用例之前 NoClassDefFoundError; 本类没有 {@code @GameTestHolder},
 * 只在 Flan 在位时才被加载。
 *
 * <p>约定 (在 18.1 的基础上):
 * <ul>
 *   <li>开发运行时一定有 Flan (S1): {@link FlanRealGameTests#requireFlan} 与夹具 {@link DistrictTestEnv#openWithRealFlan}
 *       在 Flan 不在位或自检不过时直接失败, 不跳过 (只有 -PwithoutFlan 的专门一轮例外, 见 20.10);</li>
 *   <li>每条用例一个固定槽位 ({@link FlanTestClaims#slotArea}), 开跑前与结束时都删掉槽位里的全部顶层领地;</li>
 *   <li>判定用真 ServerPlayer (类恰好是 ServerPlayer), 不放进世界; OP 临时放进 OP 名单;</li>
 *   <li>全部同步完成, 不跨 tick; 对账用"整轮同步跑完"的入口, 不经时间切片。</li>
 * </ul>
 */
final class FlanRealScenarios {

    private static final String KEY = "district.miningdim.command.";
    private static final String DIM = DistrictTestEnv.DIMENSION;

    private static final String PLACE = "flan:place";
    private static final String BREAK = "flan:break";
    private static final String DOOR = "flan:door";
    private static final String CONTAINER = "flan:open_container";
    private static final String JUKEBOX = "flan:jukebox";
    private static final String CAN_STAY = "flan:can_stay";
    private static final String PICKUP = "flan:pickup";

    // ================================================================
    // 自检与失败保护
    // ================================================================

    /**
     * 开服自检在批准的 Flan 上整体通过: 版本串、签名表、必需的 id、全局标志; 读到的权限表与本模块内置的 BUILTIN 逐项
     * 相同 (66 个: 开发运行时没有机械动力, 没有 create_contraption), 其中全局的恰好是 FlanPermissions.GLOBAL 的 15 个,
     * 非全局 51 个; 全表没有问题、没有表外的未知权限。真实的开服选择因此选出真网关。
     */
    static void selfCheckPassesOnTheApprovedFlan(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        helper.assertTrue(ModList.get().isLoaded(FlanCompat.MOD_ID), "开发运行时加载着 Flan (20.1 出路 ③)");
        String version = DistrictSystem.loadedFlanVersion();
        helper.assertTrue(FlanCompat.VERIFIED_VERSION.equals(version), "版本串是核对过的那一个, 实为 " + version);
        FlanCompat.Result check = FlanCompat.check(version, FlanCompat.expectedSignatures(),
                FlanRealScenarios.class.getClassLoader(), server);
        helper.assertTrue(check.ok() && check.problems().isEmpty(), "签名表与探查全过, 实为 " + check.problems());
        List<KnownPermission> table = check.table();
        helper.assertTrue(table.size() == 66 && new HashSet<>(table).equals(new HashSet<>(FlanPermissions.BUILTIN)),
                "真 Flan 的权限表与内置表逐项相同 (id、全局、出厂值、要求显式), 实为 " + table.size() + " 个");
        Set<String> globals = new HashSet<>();
        table.stream().filter(KnownPermission::global).forEach(permission -> globals.add(permission.id()));
        helper.assertTrue(globals.equals(FlanPermissions.GLOBAL) && table.size() - globals.size() == 51,
                "全局的恰好是那 15 个, 非全局 51 个, 实为 " + globals);
        FlanPermissionPolicy policy = FlanPermissionPolicy.of(table);
        helper.assertTrue(policy.problems().isEmpty() && policy.unknownIds().isEmpty(),
                "全表没有问题、没有表外的未知权限, 实为 " + policy.problems() + " / " + policy.unknownIds());
        GatewaySelector.Selection selection = DistrictSystem.selectGateway(server);
        helper.assertTrue(selection.state() == DistrictFeature.State.LIVE
                        && selection.gateway() instanceof FlanClaimGateway,
                "真实的开服选择选出真网关, 实为 " + selection);
        helper.succeed();
    }

    /**
     * 自检不过就退回 Disabled, 一次写入都不做 (20.2): 签名表里写错一项 (tryCreateSubClaim 的返回类型), 问题清单点名那一项;
     * 选择器选出 Disabled、工厂一次都没被调用。再经服务建区、加住户、划地块: Flan 里管理员领地的块数不变, 每块已有领地的
     * toJson 前后一致; 名单行与地块都是 failed, 原因里带"自检未通过"。
     */
    static void selfCheckMismatchFallsBackWithoutAnyWrite(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = level(helper);
        String version = DistrictSystem.loadedFlanVersion();
        List<FlanCompat.Signature> wrong = new ArrayList<>();
        for (FlanCompat.Signature signature : FlanCompat.expectedSignatures()) {
            wrong.add(signature.name().equals("tryCreateSubClaim")
                    ? new FlanCompat.Signature(signature.owner(), signature.kind(), signature.name(), signature.params(),
                    "java.util.List", signature.reqs())
                    : signature);
        }
        FlanCompat.Result check = FlanCompat.check(version, wrong, FlanRealScenarios.class.getClassLoader(), server);
        helper.assertTrue(!check.ok() && check.problems().size() == 1
                        && check.problems().get(0).contains("tryCreateSubClaim"),
                "写错一项: 不通过, 问题清单只点名那一项, 实为 " + check.problems());
        int[] built = {0};
        GatewaySelector.Selection selection = GatewaySelector.select(server, true, version, () -> check, () -> {
            built[0]++;
            throw new IllegalStateException("the real gateway must not be built when the self-check fails");
        });
        helper.assertTrue(selection.gateway() instanceof DisabledFlanGateway disabled
                        && DistrictTexts.FLAN_REASON_SELF_CHECK.equals(disabled.reason())
                        && selection.state() == DistrictFeature.State.DEGRADED && built[0] == 0
                        && selection.problems().equals(check.problems()),
                "选出 Disabled (原因: 自检未通过), 真网关根本没被造出来, 实为 " + selection);

        FlanTestClaims.deleteTopLevelClaims(server, DIM, FlanTestClaims.slotArea(1));
        Map<UUID, String> before = FlanTestClaims.snapshot(level);
        int adminClaims = FlanTestClaims.adminClaimCount(level);
        try (DistrictTestEnv env = DistrictTestEnv.openWith(selection.gateway())) {
            int x = FlanTestClaims.slotArea(1).minX();
            int z = FlanTestClaims.slotArea(1).minZ();
            env.district("abydos", x, z, x + 399, z + 399);
            MemberRecord resident = env.resident("abydos", "Resident_R");
            String plot = env.plot("abydos", x + 100, z + 100, x + 115, z + 115);
            helper.assertTrue(resident.syncStatus() == ResidentSyncStatus.FAILED
                            && String.valueOf(resident.syncError()).contains("自检未通过"),
                    "住户如实显示同步失败, 原因带自检未通过, 实为 " + resident);
            helper.assertTrue(env.plotRecord(plot).syncStatus() == PlotSyncStatus.FAILED
                            && String.valueOf(env.plotRecord(plot).syncError()).contains("自检未通过"),
                    "地块同样 failed, 实为 " + env.plotRecord(plot).syncError());
            helper.assertTrue(env.districtRecord("abydos").flanClaimId() == null
                            && env.districtRecord("abydos").needsReconcile(),
                    "父领地没建成, 置对账标记等 Flan 修好后补齐");
        }
        helper.assertTrue(FlanTestClaims.adminClaimCount(level) == adminClaims
                        && FlanTestClaims.snapshot(level).equals(before),
                "Flan 里一个字都没变: 管理员领地块数不变, 每块已有领地的 toJson 前后一致");
        helper.succeed();
    }

    /**
     * 网关把 Flan 的异常与不合规的调用一律转成失败 (20.2): 已删除领地的句柄、维度 id 不合法 (Flan 调用链上抛
     * ResourceLocationException)、非服务器线程上的调用; 写前预检拒绝往父领地写外来组、管理类写真、按组写全局权限、
     * 未知的权限 id。没有一样会抛到调用方, Flan 的值也都没变。
     */
    static void gatewayTurnsFlanExceptionsIntoFailures(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 18)) {
            abydos(env);
            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            Claim parent = parentOf(level, env, "abydos");
            ClaimHandle parentHandle = new ClaimHandle(DIM, parent.getClaimID(), null);
            String group = FlanGroupNames.districtResident("abydos");
            int breakBefore = parent.groupHasPerm(group, rl(BREAK));

            Claim plotClaim = plotOf(level, env, plot);
            ClaimHandle stale = new ClaimHandle(DIM, plotClaim.getClaimID(), parent.getClaimID());
            parent.deleteSubClaim(plotClaim);
            FlanResult<Void> onStale = env.gateway.setGroupPermission(stale, FlanGroupNames.plotOwner(plot), BREAK,
                    PermValue.FALSE);
            helper.assertTrue(!onStale.ok() && env.gateway.readMembers(stale).isEmpty(),
                    "已删除领地的句柄: 写入报失败、读返回空, 实为 " + onStale);

            ClaimHandle badDimension = new ClaimHandle("Not A Dimension!", parent.getClaimID(), null);
            FlanResult<Void> thrown = env.gateway.setGroupPermission(badDimension, group, BREAK, PermValue.TRUE);
            helper.assertTrue(!thrown.ok() && String.valueOf(thrown.error()).contains("Flan 调用出错"),
                    "调用链上抛出的异常转成失败, 实为 " + thrown);
            helper.assertTrue(env.gateway.findDistrictClaim("Not A Dimension!", parent.getClaimID()).isEmpty(),
                    "读方法出错时返回空");

            FlanResult<Void> offThread;
            Optional<ClaimHandle> offThreadRead;
            try {
                offThread = CompletableFuture.supplyAsync(() -> env.gateway.setGroupPermission(parentHandle, group, BREAK,
                        breakBefore == 1 ? PermValue.FALSE : PermValue.TRUE)).get();
                offThreadRead = CompletableFuture.supplyAsync(() -> env.gateway.findDistrictClaim(DIM,
                        parent.getClaimID())).get();
            } catch (InterruptedException | ExecutionException failure) {
                throw new IllegalStateException(failure);
            }
            helper.assertTrue(!offThread.ok() && String.valueOf(offThread.error()).contains("服务器线程")
                            && offThreadRead.isEmpty(),
                    "非服务器线程上的调用: 写入报失败、读返回空, 实为 " + offThread);

            helper.assertTrue(!env.gateway.setGroupPermission(parentHandle, "Visitor", DOOR, PermValue.TRUE).ok(),
                    "父领地上只许写本区居民组");
            helper.assertTrue(!env.gateway.setGroupPermission(parentHandle, group, "flan:edit_perms", PermValue.TRUE).ok()
                            && !env.gateway.setDefaultPermission(parentHandle, "flan:edit_claim", PermValue.TRUE).ok(),
                    "管理类权限不许写真");
            helper.assertTrue(!env.gateway.setGroupPermission(parentHandle, group, "flan:explosions", PermValue.TRUE).ok(),
                    "全局权限不能按组写");
            helper.assertTrue(!env.gateway.setGroupPermission(parentHandle, group, "othermod:nope", PermValue.TRUE).ok(),
                    "权限表里没有的 id 拒绝");
            helper.assertTrue(parent.groupHasPerm(group, rl(BREAK)) == breakBefore && !parent.groups().contains("Visitor")
                            && parent.groupHasPerm(group, rl("flan:edit_perms")) != 1,
                    "Flan 里的值都没变");
        }
        helper.succeed();
    }

    // ================================================================
    // 父领地
    // ================================================================

    /**
     * 建区: 父领地是管理员领地、X/Z 与库相符、底在世界底、2D (全高); 组只有本区居民组 —— 开发配置自带的默认组
     * (Co-Owner 有全部权限含 edit_*, Visitor) 一个都没套上, 而 Flan 配置的 defaultGroups 前后是同一个对象、内容不变。
     * 建之前强制备份 (原因 create), 备份不在任何 data/claims 之内; 建完显式存盘 (世界没有 /save-off 时 !AdminClaims.json
     * 里有这块领地)。
     */
    static void districtClaimIsFullHeightAdminWithoutDefaultGroups(GameTestHelper helper) {
        ServerLevel level = level(helper);
        Config config = ConfigHandler.CONFIG;
        Map<String, Map<ResourceLocation, Boolean>> groupsObject = config.defaultGroups;
        Map<String, Map<ResourceLocation, Boolean>> groupsCopy = deepCopy(groupsObject);
        helper.assertTrue(groupsObject.containsKey("Co-Owner") && groupsObject.containsKey("Visitor"),
                "前提: 开发配置的 defaultGroups 自带 Co-Owner、Visitor, 实为 " + groupsObject.keySet());
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 2)) {
            DistrictRecord district = abydos(env);
            Claim claim = parentOf(level, env, "abydos");
            ClaimBox box = claim.getDimensions();
            helper.assertTrue(claim.isAdminClaim() && !claim.isSubclaim() && !claim.is3d(), "2D 管理员领地");
            helper.assertTrue(box.minX() == env.x(0) && box.minZ() == env.z(0) && box.maxX() == env.x(399)
                            && box.maxZ() == env.z(399) && box.minY() == level.getMinBuildHeight(),
                    "X/Z 与库相符、底在世界底 (全高), 实为 " + box);
            helper.assertTrue(claim.groups().equals(List.of(FlanGroupNames.districtResident("abydos"))),
                    "组只有本区居民组, 默认组一个都没有, 实为 " + claim.groups());
            helper.assertTrue(claim.getClaimName().equals(DistrictTexts.claimName(district.displayName())),
                    "名字是自管区显示名, 实为 " + claim.getClaimName());
            helper.assertTrue(config.defaultGroups == groupsObject && deepCopy(config.defaultGroups).equals(groupsCopy),
                    "Flan 配置的 defaultGroups 前后是同一个对象、内容不变 (临时换空之后换回)");

            List<String> backups = backupFiles(env);
            helper.assertTrue(backups.size() == 1 && backups.get(0).endsWith("-create.AdminClaims.json.bak"),
                    "建父领地之前强制备份一份 (原因 create), 实为 " + backups);
            Path backup = env.backupRoot().resolve("minecraft_overworld").resolve(backups.get(0));
            helper.assertTrue(!ClaimBackupFiles.insideClaimsFolder(backup), "备份不在任何 data/claims 之内: " + backup);

            if (!level.noSave) {
                Path saved = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve("claims")
                        .resolve("!AdminClaims.json");
                helper.assertTrue(Files.isRegularFile(saved) && read(saved).contains(claim.getClaimID().toString()),
                        "建完显式存盘: !AdminClaims.json 里有这块领地");
            }
        }
        helper.succeed();
    }

    /**
     * 本区居民组 (20.3): 父领地的成员只有名单里生效了的住户 (待生效的不写); 居民组逐格等于全表的"公共区域住户列",
     * 默认逐格等于"外人列 + 区域规则"; 四个管理类权限在组和默认里都不为真。移出住户之后, TA 既不在父领地, 也不在任何地块。
     */
    static void residentsGroupCarriesPublicPermissionsAndMembers(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 3)) {
            abydos(env);
            env.resident("abydos", "Synced_S");
            MemberRecord pending = env.ctx.residents().addResident(env.admin, "abydos", "Pending_P", true).resident();
            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            Claim parent = parentOf(level, env, "abydos");
            String group = FlanGroupNames.districtResident("abydos");
            helper.assertTrue(pending.syncStatus() == ResidentSyncStatus.PENDING, "前提: 另一位是待生效");
            helper.assertTrue(FlanTestClaims.members(parent).equals(Map.of(uuidOf("Synced_S"), group)),
                    "父领地的成员只有生效了的那位, 实为 " + FlanTestClaims.members(parent));

            FlanPermissionPolicy policy = env.ctx.flanSync().policy();
            PermissionCells cells = env.repo.districtCells("abydos");
            for (Map.Entry<String, PermValue> cell : policy.districtResidentGroup(cells).entrySet()) {
                int actual = parent.groupHasPerm(group, rl(cell.getKey()));
                helper.assertTrue(actual == (cell.getValue() == PermValue.TRUE ? 1 : 0),
                        "居民组 " + cell.getKey() + " 应为 " + cell.getValue() + ", 实为 " + actual);
            }
            for (Map.Entry<String, PermValue> cell : policy.districtDefaults(cells).entrySet()) {
                int actual = parent.permEnabled(rl(cell.getKey()));
                boolean ok = cell.getValue() == PermValue.TRUE ? actual == 1 : actual != 1;
                helper.assertTrue(ok, "默认 " + cell.getKey() + " 应为 " + cell.getValue() + ", 实为 " + actual);
            }
            for (String admin : FlanPermissions.ADMIN) {
                helper.assertTrue(parent.groupHasPerm(group, rl(admin)) == 0 && parent.permEnabled(rl(admin)) != 1,
                        "管理类 " + admin + " 在组里显式为假、默认不为真");
            }
            Claim plotClaim = plotOf(level, env, plot);
            helper.assertTrue(FlanGroupNames.plotResident(plot).equals(
                    FlanTestClaims.members(plotClaim).get(uuidOf("Synced_S"))), "前提: TA 在地块的居民组里");

            env.ctx.residents().removeResident(env.admin, "abydos", "Synced_S", "inactive", "不上线");
            helper.assertTrue(!FlanTestClaims.members(parent).containsKey(uuidOf("Synced_S"))
                            && !FlanTestClaims.members(plotClaim).containsKey(uuidOf("Synced_S")),
                    "移出住户之后: TA 不在父领地, 也不在任何地块");
        }
        helper.succeed();
    }

    // ================================================================
    // 地块
    // ================================================================

    /**
     * 建地块时的清理 (20.3): 先用 Flan 自己的 tryCreateSubClaim 核对浅拷贝语义 —— 子领地与父领地共用组的内层 Map,
     * 连成员和药水一起抄过去; 再直接调网关的 createPlotClaim (差异写入之前的那一刻): 继承来的 d_ 组、成员与药水都没了,
     * 地块已经关上 —— 恰好是本块的三个 p_ 组、对全部非全局权限显式为假, 默认同样全假, 全局权限没有键; 公共区域能拆的
     * 住户在里面拆不了, 外人连构造器预填为真的"捡东西"也没有。最后经服务划地块: 组恰好是自己的三个 p_ 组、没有 d_,
     * 成员只有期望的人, 药水为空, 任一组的内层 Map 都不是父领地的那个对象, 改地块的组不影响父领地。
     */
    static void plotCreationScrubsInheritedGroupsMembersAndPotions(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 4)) {
            abydos(env);
            env.resident("abydos", "Resident_R");
            env.seen("Outsider_X");
            Claim parent = parentOf(level, env, "abydos");
            String districtGroup = FlanGroupNames.districtResident("abydos");
            parent.addPotion(MobEffects.MOVEMENT_SPEED, 1);

            Claim raw = FlanTestClaims.rawSubclaim(level, parent, env.slotArea(300, 300, 310, 310));
            helper.assertTrue(FlanTestClaims.groupMaps(raw).get(districtGroup)
                            == FlanTestClaims.groupMaps(parent).get(districtGroup)
                            && FlanTestClaims.members(raw).containsKey(uuidOf("Resident_R"))
                            && !raw.getPotions().isEmpty(),
                    "Flan 的浅拷贝: 子领地与父领地共用组的内层 Map, 成员和药水一起抄过去");
            parent.deleteSubClaim(raw);

            env.ctx.permissions().setPermission(env.admin, "abydos", "break", "resident", true);
            helper.assertTrue(parent.groupHasPerm(districtGroup, rl(BREAK)) == 1, "前提: 父领地居民组能拆");
            FlanResult<ClaimHandle> bare = env.gateway.createPlotClaim(new ClaimHandle(DIM, parent.getClaimID(), null),
                    env.slotArea(200, 200, 215, 215), "bare", "bare");
            Claim bareClaim = bare.ok() && bare.value() != null
                    ? FlanTestClaims.subclaim(parent, bare.value().claimId())
                    : null;
            helper.assertTrue(bareClaim != null, "网关建得出子领地, 实为 " + bare);
            helper.assertTrue(new HashSet<>(bareClaim.groups()).equals(Set.of(FlanGroupNames.plotOwner("bare"),
                            FlanGroupNames.plotFriend("bare"), FlanGroupNames.plotResident("bare"))),
                    "差异写入之前: 继承来的 d_ 组已删掉, 只有本块的三个 p_ 组, 实为 " + bareClaim.groups());
            helper.assertTrue(FlanTestClaims.members(bareClaim).isEmpty() && bareClaim.getPotions().isEmpty(),
                    "差异写入之前: 复制来的成员与药水已清掉, 实为 " + FlanTestClaims.members(bareClaim));
            for (KnownPermission permission : env.gateway.permissionTable()) {
                ResourceLocation id = rl(permission.id());
                if (permission.global()) {
                    helper.assertTrue(bareClaim.permEnabled(id) == -1, "关着的地块: 全局权限没有键 " + permission.id());
                    continue;
                }
                helper.assertTrue(bareClaim.permEnabled(id) == 0, "关着的地块: 默认显式为假 " + permission.id());
                for (String group : bareClaim.groups()) {
                    helper.assertTrue(bareClaim.groupHasPerm(group, id) == 0,
                            "关着的地块: " + group + " 显式为假 " + permission.id());
                }
            }
            BlockPos inBare = at(env, 205, 205);
            helper.assertTrue(!FlanTestClaims.can(FlanTestClaims.realPlayer(level, "Resident_R"), inBare, BREAK)
                            && !FlanTestClaims.can(FlanTestClaims.realPlayer(level, "Outsider_X"), inBare, PICKUP),
                    "差异写入之前也不泄漏: 公共区域能拆的住户拆不了, 外人连捡东西也不行");
            helper.assertTrue(env.gateway.deletePlotClaim(bare.value()).ok(), "删掉这块试验用的子领地");

            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            Claim plotClaim = plotOf(level, env, plot);
            helper.assertTrue(plotClaim.groups().equals(List.of(FlanGroupNames.plotFriend(plot),
                            FlanGroupNames.plotOwner(plot), FlanGroupNames.plotResident(plot))),
                    "地块的组恰好是自己的三个 p_ 组, 没有 d_, 实为 " + plotClaim.groups());
            helper.assertTrue(FlanTestClaims.members(plotClaim).equals(Map.of(uuidOf("Resident_R"),
                            FlanGroupNames.plotResident(plot))) && plotClaim.getPotions().isEmpty(),
                    "成员只有期望的人, 药水为空, 实为 " + FlanTestClaims.members(plotClaim));
            for (Map<ResourceLocation, Boolean> inner : FlanTestClaims.groupMaps(plotClaim).values()) {
                helper.assertTrue(!sharesAny(parent, inner), "地块任一组的内层 Map 都不是父领地的那个对象");
            }
            int before = parent.groupHasPerm(districtGroup, rl(BREAK));
            ClaimHandle plotHandle = new ClaimHandle(DIM, plotClaim.getClaimID(), parent.getClaimID());
            FlanResult<Void> changed = env.gateway.setGroupPermission(plotHandle, FlanGroupNames.plotResident(plot),
                    BREAK, before == 1 ? PermValue.FALSE : PermValue.TRUE);
            helper.assertTrue(changed.ok() && parent.groupHasPerm(districtGroup, rl(BREAK)) == before,
                    "改地块的组不影响父领地");
        }
        helper.succeed();
    }

    /** 组名全服唯一 (p_&lt;plotId&gt;_…): 改 A 的朋友列, B 的三个组、默认与成员逐格不变。 */
    static void groupNamesAreUniquePerPlot(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 5)) {
            abydos(env);
            env.resident("abydos", "Owner_A");
            env.resident("abydos", "Owner_B");
            String a = env.plotAt("abydos", 100, 100, 115, 115);
            String b = env.plotAt("abydos", 140, 100, 155, 115);
            env.own(a, "Owner_A");
            env.own(b, "Owner_B");
            Claim plotA = plotOf(level, env, a);
            Claim plotB = plotOf(level, env, b);
            ClaimHandle handleB = new ClaimHandle(DIM, plotB.getClaimID(), plotB.parentClaim().getClaimID());
            ClaimPermissionSnapshot before = env.gateway.readPermissions(handleB);
            Map<UUID, String> membersBefore = FlanTestClaims.members(plotB);
            helper.assertTrue(plotA.groupHasPerm(FlanGroupNames.plotFriend(a), rl(BREAK)) == 1,
                    "前提: A 的朋友列默认能拆");

            env.ctx.plotOwners().setPermission(player("Owner_A"), "abydos", a, "break", "friend", false);
            helper.assertTrue(plotA.groupHasPerm(FlanGroupNames.plotFriend(a), rl(BREAK)) == 0, "A 的朋友列改成了不能拆");
            helper.assertTrue(env.gateway.readPermissions(handleB).equals(before)
                            && FlanTestClaims.members(plotB).equals(membersBefore)
                            && plotB.groupHasPerm(FlanGroupNames.plotFriend(b), rl(BREAK)) == 1,
                    "B 的三个组、默认与成员逐格不变");
        }
        helper.succeed();
    }

    /**
     * 新地块写满全表 (20.3): 三个组与默认对全部 51 个非全局权限都有显式值; 15 个全局权限在地块上都没有键 (-1, 跟随
     * 父领地), 包括新领地构造时按出厂值预填为真的 lock_items、snow_golem、enderman。
     */
    static void newPlotWritesEveryNonGlobalPermissionAndUnsetsEveryGlobal(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 6)) {
            abydos(env);
            env.resident("abydos", "Owner_O");
            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            env.own(plot, "Owner_O");
            Claim plotClaim = plotOf(level, env, plot);
            List<KnownPermission> table = env.gateway.permissionTable();
            int nonGlobal = 0;
            for (KnownPermission permission : table) {
                ResourceLocation id = rl(permission.id());
                if (permission.global()) {
                    helper.assertTrue(plotClaim.permEnabled(id) == -1,
                            "全局权限在地块上没有键: " + permission.id() + " = " + plotClaim.permEnabled(id));
                    continue;
                }
                nonGlobal++;
                helper.assertTrue(plotClaim.permEnabled(id) != -1, "地块默认显式写了 " + permission.id());
                for (String group : plotClaim.groups()) {
                    helper.assertTrue(plotClaim.groupHasPerm(group, id) != -1, group + " 显式写了 " + permission.id());
                }
            }
            helper.assertTrue(nonGlobal == 51 && table.size() == 66, "51 个非全局、15 个全局, 实为 " + nonGlobal);
            helper.assertTrue(env.plotRecord(plot).syncStatus() == PlotSyncStatus.SYNCED, "地块 synced");
        }
        helper.succeed();
    }

    /**
     * 四类受众经 Flan 自己的判定落地 (20.3): 一块有户主的地, 三列设成能区分的值 —— 朋友能放不能拆, 其他住户只能开门,
     * 外人什么都不行。户主、朋友、其他住户、外人、OP 五个真 ServerPlayer 在地块内对放置、破坏、开门、开箱子, 以及跟随项
     * jukebox (跟随"开箱子等容器") 的判定逐个相符; 2 级 OP 能做一切 (服主已拍板不拦), 1 级 OP 不绕过 (P14), 按外人算。
     */
    static void audiencesResolveThroughRealFlan(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 7)) {
            abydos(env);
            env.resident("abydos", "Owner_O");
            env.resident("abydos", "Friend_F");
            env.resident("abydos", "Other_R");
            env.seen("Outsider_X");
            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            env.own(plot, "Owner_O");
            env.ctx.plotOwners().addFriend(player("Owner_O"), "abydos", plot, "Friend_F", false);
            env.ctx.plotOwners().setPermission(player("Owner_O"), "abydos", plot, "break", "friend", false);
            env.ctx.plotOwners().setPermission(player("Owner_O"), "abydos", plot, "door", "outsider", false);
            helper.assertTrue(env.plotRecord(plot).syncStatus() == PlotSyncStatus.SYNCED, "前提: 地块 synced");

            BlockPos in = at(env, 105, 105);
            Map<String, List<Boolean>> expected = new LinkedHashMap<>();
            expected.put("Owner_O", List.of(true, true, true, true, true));
            expected.put("Friend_F", List.of(true, false, true, true, true));
            expected.put("Other_R", List.of(false, false, true, false, false));
            expected.put("Outsider_X", List.of(false, false, false, false, false));
            for (Map.Entry<String, List<Boolean>> row : expected.entrySet()) {
                ServerPlayer who = FlanTestClaims.realPlayer(level, row.getKey());
                helper.assertTrue(!who.hasPermissions(2), "前提: " + row.getKey() + " 不是 OP");
                List<Boolean> actual = decisions(who, in, PLACE, BREAK, DOOR, CONTAINER, JUKEBOX);
                helper.assertTrue(actual.equals(row.getValue()),
                        row.getKey() + " 放/拆/门/箱子/唱片机 应为 " + row.getValue() + ", 实为 " + actual);
            }
            ServerPlayer op = FlanTestClaims.realPlayer(level, "Op_Visitor");
            try (FlanTestClaims.Restore ignored = FlanTestClaims.op(op, 2)) {
                helper.assertTrue(op.hasPermissions(2), "前提: OP 名单里的 2 级");
                helper.assertTrue(decisions(op, in, PLACE, BREAK, DOOR, CONTAINER, JUKEBOX)
                                .equals(List.of(true, true, true, true, true)),
                        "2 级 OP 在别人的地块里什么都能做 (Flan 的 OP 绕过, 服主已拍板不拦)");
            }
            ServerPlayer moderator = FlanTestClaims.realPlayer(level, "Op_Level_One");
            try (FlanTestClaims.Restore ignored = FlanTestClaims.op(moderator, 1)) {
                helper.assertTrue(moderator.hasPermissions(1) && !moderator.hasPermissions(2), "前提: 1 级 OP");
                helper.assertTrue(decisions(moderator, in, PLACE, BREAK, DOOR, CONTAINER, JUKEBOX)
                                .equals(List.of(false, false, false, false, false)),
                        "1 级 OP 不绕过 (P14), 在别人的地块里按外人算");
            }
        }
        helper.succeed();
    }

    /**
     * 泄漏测试 (20.3 第 1 条): 公共区域住户列的"破坏方块"开着 (父领地居民组 break 为真), 住户在公共区域能拆; 同一个
     * 住户在别人的地块里不能拆 (TA 在那块地的居民组, 居民列的破坏关着); 把 TA 从那块地的成员里删掉 (模拟同步失败) 仍然
     * 不能拆, 因为落到了地块的默认 (外人列); 外人两处都不能拆。父领地的组在地块里一律不读。
     */
    static void publicBreakForResidentsDoesNotLeakIntoPlots(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 8)) {
            abydos(env);
            env.resident("abydos", "Owner_O");
            env.resident("abydos", "Resident_R");
            env.seen("Outsider_X");
            env.ctx.permissions().setPermission(env.admin, "abydos", "break", "resident", true);
            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            env.own(plot, "Owner_O");
            Claim parent = parentOf(level, env, "abydos");
            Claim plotClaim = plotOf(level, env, plot);
            helper.assertTrue(parent.groupHasPerm(FlanGroupNames.districtResident("abydos"), rl(BREAK)) == 1,
                    "前提: 父领地居民组的破坏方块为真");
            ServerPlayer resident = FlanTestClaims.realPlayer(level, "Resident_R");
            ServerPlayer outsider = FlanTestClaims.realPlayer(level, "Outsider_X");
            BlockPos publicArea = at(env, 50, 50);
            BlockPos inPlot = at(env, 105, 105);

            helper.assertTrue(FlanTestClaims.can(resident, publicArea, BREAK), "住户在公共区域能拆");
            helper.assertTrue(!FlanTestClaims.can(resident, inPlot, BREAK), "同一个住户在别人的地块里不能拆");
            plotClaim.setPlayerGroup(uuidOf("Resident_R"), null, true);
            helper.assertTrue(!FlanTestClaims.members(plotClaim).containsKey(uuidOf("Resident_R"))
                            && !FlanTestClaims.can(resident, inPlot, BREAK),
                    "从地块成员里删掉 (模拟同步失败) 仍不能拆: 落到地块的默认 (外人列), 不读父领地的组");
            helper.assertTrue(!FlanTestClaims.can(outsider, publicArea, BREAK) && !FlanTestClaims.can(outsider, inPlot, BREAK),
                    "外人两处都不能拆");
        }
        helper.succeed();
    }

    /**
     * 区域规则写在父领地上、地块跟随 (20.3 第 4 条): 全区列"爆炸伤害"关着时地块内 explosions 判定为假, 打开之后地块内
     * 立刻为真, 而地块的 permEnabled(explosions) 始终是 -1; mob_spawn 取反写 (目录"允许怪物自然生成"为真 = Flan 不阻止);
     * 其余 8 个全局权限在父领地与地块里的判定都等于各自的出厂值。
     */
    static void regionRulesLiveOnTheParentAndPlotsFollow(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 9)) {
            abydos(env);
            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            Claim plotClaim = plotOf(level, env, plot);
            BlockPos inPlot = at(env, 105, 105);
            BlockPos publicArea = at(env, 50, 50);
            ResourceLocation explosions = rl("flan:explosions");

            helper.assertTrue(!FlanTestClaims.regionAllows(level, inPlot, "flan:explosions")
                            && plotClaim.permEnabled(explosions) == -1,
                    "爆炸伤害关着: 地块内判定为假, 地块上没有自己的值");
            env.ctx.permissions().setPermission(env.admin, "abydos", "explosions", "district", true);
            helper.assertTrue(FlanTestClaims.regionAllows(level, inPlot, "flan:explosions")
                            && FlanTestClaims.regionAllows(level, publicArea, "flan:explosions")
                            && plotClaim.permEnabled(explosions) == -1,
                    "打开之后地块内立刻为真 (跟随父领地), 地块上仍然没有自己的值");

            helper.assertTrue(!FlanTestClaims.regionAllows(level, inPlot, "flan:mob_spawn"),
                    "目录\"允许怪物自然生成\"为真 = Flan 的 mob_spawn (阻止) 为假");
            env.ctx.permissions().setPermission(env.admin, "abydos", "mob_spawn", "district", false);
            helper.assertTrue(FlanTestClaims.regionAllows(level, inPlot, "flan:mob_spawn"),
                    "关掉怪物自然生成 = Flan 的 mob_spawn 为真 (取反)");

            Set<String> regionIds = new HashSet<>();
            PermissionCatalog.regionItems().forEach(item -> regionIds.addAll(item.flanIds()));
            for (KnownPermission permission : env.gateway.permissionTable()) {
                if (!permission.global() || regionIds.contains(permission.id())) {
                    continue;
                }
                helper.assertTrue(FlanTestClaims.regionAllows(level, publicArea, permission.id()) == permission.defaultValue()
                                && FlanTestClaims.regionAllows(level, inPlot, permission.id()) == permission.defaultValue()
                                && plotClaim.permEnabled(rl(permission.id())) == -1,
                        permission.id() + " 在父领地与地块里都等于出厂值 " + permission.defaultValue());
            }
        }
        helper.succeed();
    }

    /**
     * 冻结 (20.3): 移出户主触发冻结, 原户主、朋友、其他住户、外人对放置、破坏、开箱子、停留全部为假, 地块成员为空; 库里的
     * 三列和朋友前后逐格相同; OP 照样能进能做。原户主回到本区、解冻之后, 每个人的判定回到冻结前。
     */
    static void frozenPlotDeniesEveryoneAndKeepsSavedSettings(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 10)) {
            abydos(env);
            env.resident("abydos", "Owner_O");
            env.resident("abydos", "Friend_F");
            env.resident("abydos", "Other_R");
            env.seen("Outsider_X");
            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            env.own(plot, "Owner_O");
            env.ctx.plotOwners().addFriend(player("Owner_O"), "abydos", plot, "Friend_F", false);
            env.ctx.plotOwners().setPermission(player("Owner_O"), "abydos", plot, "break", "friend", false);
            env.ctx.plotOwners().setPermission(player("Owner_O"), "abydos", plot, "container", "resident", true);
            BlockPos in = at(env, 105, 105);
            List<String> people = List.of("Owner_O", "Friend_F", "Other_R", "Outsider_X");
            Map<String, List<Boolean>> beforeFreeze = new LinkedHashMap<>();
            for (String name : people) {
                beforeFreeze.put(name, decisions(FlanTestClaims.realPlayer(level, name), in, PLACE, BREAK, CONTAINER,
                        CAN_STAY));
            }
            Map<String, Map<String, Boolean>> cells = env.repo.plotCells(plot).asMap();
            List<FriendRecord> friends = env.repo.friendsOf(plot);

            env.ctx.residents().removeResident(env.admin, "abydos", "Owner_O", "inactive", "不上线");
            helper.assertTrue(env.plotRecord(plot).frozen(), "前提: 移出户主之后地块冻结");
            Claim plotClaim = plotOf(level, env, plot);
            for (String name : people) {
                List<Boolean> actual = decisions(FlanTestClaims.realPlayer(level, name), in, PLACE, BREAK, CONTAINER,
                        CAN_STAY);
                helper.assertTrue(actual.equals(List.of(false, false, false, false)),
                        name + " 在冻结的地块里 放/拆/箱子/停留 全部为假, 实为 " + actual);
            }
            helper.assertTrue(FlanTestClaims.members(plotClaim).isEmpty(), "冻结时地块成员为空");
            helper.assertTrue(env.repo.plotCells(plot).asMap().equals(cells) && env.repo.friendsOf(plot).equals(friends),
                    "库里的三列与朋友不动");
            ServerPlayer op = FlanTestClaims.realPlayer(level, "Op_Visitor");
            try (FlanTestClaims.Restore ignored = FlanTestClaims.op(op, 2)) {
                helper.assertTrue(decisions(op, in, PLACE, BREAK, CONTAINER, CAN_STAY)
                                .equals(List.of(true, true, true, true)),
                        "OP 在冻结的地块里照样能进能做 (服主已拍板不拦)");
            }

            env.ctx.residents().addResident(env.admin, "abydos", "Owner_O", false);
            env.ctx.freezes().unfreeze(env.admin, "abydos", plot);
            for (String name : people) {
                List<Boolean> actual = decisions(FlanTestClaims.realPlayer(level, name), in, PLACE, BREAK, CONTAINER,
                        CAN_STAY);
                helper.assertTrue(actual.equals(beforeFreeze.get(name)),
                        name + " 解冻后回到冻结前 " + beforeFreeze.get(name) + ", 实为 " + actual);
            }
            helper.assertTrue(env.plotRecord(plot).syncStatus() == PlotSyncStatus.SYNCED, "解冻后 synced");
        }
        helper.succeed();
    }

    // ================================================================
    // 对账
    // ================================================================

    /**
     * 对账改回有人用 /flan 做的改动 (20.4): 用 /flan 命令底层的同一批公开调用 —— 给朋友组开踩坏农田、把陌生人放进户主组、
     * 改父领地的一格默认、给父领地加一个 Visitor 组 (连成员)、加假玩家和药水 —— 跑一轮自动对账: 全部改回, 报告的改回项数
     * 与种类精确相符, 地块 synced, 对账标记清掉。再跑一轮: 零改动, 领地不被标脏。
     */
    static void reconcileRevertsManualFlanEdits(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 11)) {
            abydos(env);
            env.resident("abydos", "Owner_O");
            env.resident("abydos", "Friend_F");
            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            env.own(plot, "Owner_O");
            env.ctx.plotOwners().addFriend(player("Owner_O"), "abydos", plot, "Friend_F", false);
            Claim parent = parentOf(level, env, "abydos");
            Claim plotClaim = plotOf(level, env, plot);
            UUID stranger = uuidOf("Stranger_S");
            String friendGroup = FlanGroupNames.plotFriend(plot);
            helper.assertTrue(plotClaim.groupHasPerm(friendGroup, rl("flan:trample")) == 0, "前提: 朋友默认不能踩坏农田");

            plotClaim.editPerms(null, friendGroup, rl("flan:trample"), 1, true);
            plotClaim.setPlayerGroup(stranger, FlanGroupNames.plotOwner(plot), true);
            parent.editGlobalPerms(null, rl(PLACE), 1);
            parent.editPerms(null, "Visitor", rl(DOOR), 1, true);
            parent.setPlayerGroup(stranger, "Visitor", true);
            parent.modifyFakePlayerUUID(UUID.randomUUID(), false);
            parent.addPotion(MobEffects.MOVEMENT_SPEED, 1);
            env.repo.markNeedsReconcile("abydos");

            DistrictReconciler.DistrictProgress first = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.AUTO);
            helper.assertTrue(first.reverted() == 5,
                    "父领地 3 项 (清假玩家与药水、删 Visitor 组连同成员、默认 place) + 地块 2 项 (移出陌生人、朋友组 "
                            + "trample), 实为 " + first.reverted() + " " + first.changes());
            helper.assertTrue(plotClaim.groupHasPerm(friendGroup, rl("flan:trample")) == 0
                            && !FlanTestClaims.members(plotClaim).containsKey(stranger), "地块改回");
            helper.assertTrue(parent.permEnabled(rl(PLACE)) != 1 && !parent.groups().contains("Visitor")
                            && !FlanTestClaims.members(parent).containsKey(stranger)
                            && parent.getAllowedFakePlayerUUID().isEmpty() && parent.getPotions().isEmpty(),
                    "父领地改回: 多余的组连同成员删掉, 假玩家与药水清空");
            helper.assertTrue(env.plotRecord(plot).syncStatus() == PlotSyncStatus.SYNCED
                            && !env.districtRecord("abydos").needsReconcile() && first.unfixable().isEmpty(),
                    "地块 synced, 整轮干净时清掉对账标记, 实为 " + first.unfixable());

            // Flan 只在顶层领地上记"脏": 子领地的 setDirty 转给父领地, 子领地自己的 isDirty() 停在构造时的 true。
            parent.setDirty(false);
            DistrictReconciler.DistrictProgress second = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.AUTO);
            helper.assertTrue(second.reverted() == 0 && second.changes().isEmpty() && !parent.isDirty(),
                    "再跑一轮: 零改动, 领地 (连同地块) 不被标脏, 实为 " + second.changes());
        }
        helper.succeed();
    }

    /**
     * /district inspect 与 /district resync 对真 Flan (20.8): 有人给地块居民组开了破坏; inspect 只列出差异、一个字都不写,
     * 返回 1; resync 先强制备份 (原因 resync) 再改回, 返回成功的项数。
     */
    static void inspectPreviewsAndResyncRevertsThroughCommands(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 19)) {
            abydos(env);
            env.resident("abydos", "Owner_O");
            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            env.own(plot, "Owner_O");
            Claim plotClaim = plotOf(level, env, plot);
            String residentGroup = FlanGroupNames.plotResident(plot);
            plotClaim.editPerms(null, residentGroup, rl(BREAK), 1, true);
            Map<UUID, String> before = FlanTestClaims.snapshot(level);

            Console console = new Console(helper);
            console.expect("district inspect abydos", 1, KEY + "inspect.header");
            helper.assertTrue(console.texts().stream().anyMatch(text -> text.contains(residentGroup)
                            && text.contains(BREAK)),
                    "inspect 列出这一格差异, 实为 " + console.texts());
            helper.assertTrue(FlanTestClaims.snapshot(level).equals(before)
                            && plotClaim.groupHasPerm(residentGroup, rl(BREAK)) == 1,
                    "inspect 一个字都不写");

            List<String> keys = console.expect("district resync abydos", 3, KEY + "resync.done");
            helper.assertTrue(plotClaim.groupHasPerm(residentGroup, rl(BREAK)) == 0,
                    "resync 改回, 返回成功的项数 (父领地 1 + 住户 1 + 地块 1), 反馈 " + keys);
            helper.assertTrue(backupFiles(env).stream().anyMatch(name -> name.endsWith("-resync.AdminClaims.json.bak")),
                    "resync 先强制备份, 实为 " + backupFiles(env));
        }
        helper.succeed();
    }

    /**
     * 子领地的归属 (20.4): 父领地下另有一块外来子领地 (像 OP 手划的, 带着浅拷贝来的 d_ 组)、一块本模块的孤儿 (删地块时没能
     * 删掉子领地, 墓碑还在)、一块错位的本区地块 (库里的领地 id 指向不存在的子领地)。自动对账: 删孤儿 (先备份, 原因
     * orphan), 外来的只报告不删, 错位的被认领、不新建; /district resync 之后外来的也没了, 本区地块不动。
     */
    static void reconcileHandlesForeignAndOrphanSubclaims(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 12)) {
            abydos(env);
            env.resident("abydos", "Resident_R");
            String kept = env.plotAt("abydos", 100, 100, 115, 115);
            String deleted = env.plotAt("abydos", 140, 100, 155, 115);
            Claim parent = parentOf(level, env, "abydos");
            Claim foreign = FlanTestClaims.rawSubclaim(level, parent, env.slotArea(300, 300, 310, 310));
            UUID orphan = env.plotRecord(deleted).flanClaimId();
            env.repo.setPlotClaimId(deleted, UUID.randomUUID());
            env.ctx.layout().delete(env.admin, "abydos", deleted);
            helper.assertTrue(FlanTestClaims.subclaim(parent, orphan) != null,
                    "前提: 删地块时没能删掉子领地, 成了孤儿 (墓碑不存领地 id)");
            UUID keptClaim = env.plotRecord(kept).flanClaimId();
            env.repo.setPlotClaimId(kept, UUID.randomUUID());
            helper.assertTrue(parent.getAllSubclaims().size() == 3, "前提: 三块子领地");

            DistrictReconciler.DistrictProgress auto = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.AUTO);
            helper.assertTrue(FlanTestClaims.subclaim(parent, orphan) == null, "有墓碑的本模块子领地 (孤儿): 自动对账删掉");
            helper.assertTrue(backupFiles(env).stream().anyMatch(name -> name.endsWith("-orphan.AdminClaims.json.bak")),
                    "删孤儿之前强制备份, 实为 " + backupFiles(env));
            helper.assertTrue(FlanTestClaims.subclaim(parent, foreign.getClaimID()) != null
                            && auto.reportedSubclaims().size() == 1,
                    "外来子领地: 自动对账只报告不删, 实为 " + auto.reportedSubclaims());
            helper.assertTrue(keptClaim.equals(env.plotRecord(kept).flanClaimId()) && parent.getAllSubclaims().size() == 2
                            && env.plotRecord(kept).syncStatus() == PlotSyncStatus.SYNCED,
                    "错位的本区地块: 认领带着它组键的子领地, 不新建");

            new Console(helper).expect("district resync abydos", 3, KEY + "resync.done");
            List<UUID> left = new ArrayList<>();
            parent.getAllSubclaims().forEach(sub -> left.add(sub.getClaimID()));
            helper.assertTrue(left.equals(List.of(keptClaim)), "resync 连外来子领地也删, 本区地块不动, 实为 " + left);
        }
        helper.succeed();
    }

    /**
     * 对账从不碰没有绑定的领地 (20.4): 槽位里另有一块无关的管理员领地、一块玩家领地、一个已解绑区的父领地 (连同它的
     * 地块, 解绑后又被人改过)。建区、划地、自动对账、resync、整轮定时对账全跑一遍, 这三块领地的 toJson 前后逐字相同。
     */
    static void reconcileNeverTouchesUnboundClaims(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 13)) {
            Claim other = FlanTestClaims.rawAdminClaim(level, env.slotArea(1000, 1000, 1100, 1100));
            Claim playerClaim = FlanTestClaims.rawPlayerClaim(level, env.slotArea(1200, 1200, 1300, 1300),
                    uuidOf("Someone_S"));
            env.districtAt("millennium", env.slotBounds(2000, 2000, 2399, 2399));
            env.plotAt("millennium", 2100, 2100, 2115, 2115);
            Claim archived = parentOf(level, env, "millennium");
            env.ctx.admin().unbind(env.admin, "millennium");
            archived.editPerms(null, FlanGroupNames.districtResident("millennium"), rl(BREAK), 1, true);
            helper.assertTrue(!playerClaim.isAdminClaim() && other.isAdminClaim(), "前提: 一块玩家领地、一块管理员领地");
            List<String> before = json(other, playerClaim, archived);

            abydos(env);
            env.resident("abydos", "Resident_R");
            env.plotAt("abydos", 100, 100, 115, 115);
            env.ctx.reconciler().reconcileDistrict("abydos", DistrictReconciler.Mode.AUTO);
            env.ctx.reconciler().reconcileDistrict("abydos", DistrictReconciler.Mode.EXPLICIT);
            new ReconcileScheduler(env.clock::get, System::nanoTime).runFullRoundNow(env.ctx.reconciler(), env.repo,
                    env.gateway);
            helper.assertTrue(json(other, playerClaim, archived).equals(before),
                    "无关的管理员领地、玩家领地、已解绑区的父领地 (连同它的子领地) 前后逐字相同");
        }
        helper.succeed();
    }

    /** 推送也按差异写 (20.4): 库不变时把地块、公共区域开关、住户的成员资格各推一遍, Flan 一次改动都没有, 领地不被标脏。 */
    static void pushesWriteOnlyDifferences(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 17)) {
            abydos(env);
            env.resident("abydos", "Owner_O");
            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            env.own(plot, "Owner_O");
            Claim parent = parentOf(level, env, "abydos");
            Claim plotClaim = plotOf(level, env, plot);
            // Flan 只在顶层领地上记"脏" (子领地的 setDirty 转给父领地), 所以看父领地这一个标记就覆盖了地块。
            parent.setDirty(false);
            env.ctx.flanSync().writePlotState(plot);
            env.ctx.flanSync().writeDistrictState("abydos");
            env.ctx.flanSync().syncResidentMembership("abydos", uuidOf("Owner_O"));
            helper.assertTrue(!parent.isDirty() && env.plotRecord(plot).syncStatus() == PlotSyncStatus.SYNCED,
                    "同一个状态再推一遍: Flan 零改动, 领地 (连同地块) 不被标脏");
            env.ctx.plotOwners().setPermission(player("Owner_O"), "abydos", plot, "door", "outsider", false);
            helper.assertTrue(parent.isDirty() && plotClaim.permEnabled(rl(DOOR)) == 0,
                    "改一格: 写了那一格, 标脏落在父领地上 (存盘时整个管理员领地文件重写)");
        }
        helper.succeed();
    }

    /**
     * 存盘再读回之后不用重写 (20.3 "父领地默认权限的假与缺省等价"): Flan 存盘时父领地的 GlobalPerms 只存值为真的 id,
     * 地块的真假都存。先在公共区域的外人列关掉"捡地上的东西" (出厂值为真, 父领地上于是显式存着一个假), 再把父领地按
     * 存盘格式 toJson、用读盘的 Claim.fromJson 读回来换进存储 (模拟一次存盘 + 重启): 父领地上的"假"变成了"缺省", 地块上的
     * "假"原样保留; 对账零改动、领地不被标脏; 每个人在公共区域与地块里的判定与之前相同。
     */
    static void saveAndReloadNeedsNoRewrites(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 21)) {
            abydos(env);
            env.resident("abydos", "Owner_O");
            env.resident("abydos", "Other_R");
            env.seen("Outsider_X");
            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            env.own(plot, "Owner_O");
            // 新领地构造时按出厂值预填为真的 pickup, 在公共区域的外人列关掉: 父领地默认上于是显式存着一个"假"。
            // (出厂值为假的权限在父领地上本来就没有键, 按"假与缺省等价"也不会去写。)
            env.ctx.permissions().setPermission(env.admin, "abydos", "pickup", "outsider", false);
            List<String> people = List.of("Owner_O", "Other_R", "Outsider_X");
            Map<String, List<Boolean>> before = matrix(level, env, people);
            Claim parent = parentOf(level, env, "abydos");
            helper.assertTrue(parent.permEnabled(rl(PICKUP)) == 0 && parent.permEnabled(rl(PLACE)) == -1,
                    "前提: 父领地默认的 pickup 显式存着假, 出厂值为假的 place 没有键");

            Claim reloaded = FlanTestClaims.simulateSaveAndReload(level, parent);
            helper.assertTrue(reloaded != parent && reloaded.getClaimID().equals(parent.getClaimID())
                            && reloaded.permEnabled(rl(PICKUP)) == -1,
                    "读回来的父领地上, 默认的假变成了缺省, 实为 " + reloaded.permEnabled(rl(PICKUP)));
            Claim plotClaim = plotOf(level, env, plot);
            helper.assertTrue(plotClaim.permEnabled(rl(PLACE)) == 0 && plotClaim.parentClaim() == reloaded,
                    "读回来的地块: 默认的假原样保留, 挂在读回来的父领地下");

            reloaded.setDirty(false);
            DistrictReconciler.DistrictProgress round = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.AUTO);
            helper.assertTrue(round.changes().isEmpty() && round.unfixable().isEmpty() && !reloaded.isDirty(),
                    "对账零改动、领地不被标脏, 实为 " + round.changes() + " / " + round.unfixable());
            helper.assertTrue(matrix(level, env, people).equals(before), "每个人的判定与之前相同");
        }
        helper.succeed();
    }

    // ================================================================
    // 绑定与范围
    // ================================================================

    /**
     * /district bind 收编已有的管理员领地 (20.6): 先像 OP 用 /flan 那样圈一块带默认组 (Co-Owner、Visitor) 的管理员领地,
     * 再加成员、两块子领地、假玩家和药水。bind 预览一个字都不写; confirm 之后组只剩本区居民组、成员与子领地全清、假玩家
     * 与药水为空、库里记着这块领地的 id 与范围, 收编前强制备份 (原因 bind); 之后照常加住户。已被在用区绑着的领地、玩家
     * 领地、没有领地的位置分别被拒。
     */
    static void bindAdoptsAnExistingAdminClaim(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 14)) {
            Claim raw = FlanTestClaims.rawAdminClaim(level, env.slotArea(0, 0, 399, 399));
            helper.assertTrue(raw.groups().contains("Co-Owner") && raw.groups().contains("Visitor"),
                    "前提: /flan 圈的管理员领地带着默认组, 实为 " + raw.groups());
            raw.setPlayerGroup(uuidOf("Stranger_S"), "Visitor", true);
            FlanTestClaims.rawSubclaim(level, raw, env.slotArea(10, 10, 20, 20));
            FlanTestClaims.rawSubclaim(level, raw, env.slotArea(30, 30, 40, 40));
            raw.modifyFakePlayerUUID(UUID.randomUUID(), false);
            raw.addPotion(MobEffects.MOVEMENT_SPEED, 1);
            env.seen("Resident_R");
            Map<UUID, String> before = FlanTestClaims.snapshot(level);

            Console console = new Console(helper);
            console.expect("district create abydos " + env.x(0) + " " + env.z(0) + " " + env.x(399) + " " + env.z(399),
                    0, KEY + "flan_claim_exists");
            helper.assertTrue(console.texts().stream().anyMatch(text -> text.contains(raw.getClaimID() + " (admin)"))
                            && env.repo.liveDistricts().isEmpty(),
                    "这片范围已有管理员领地: 建区被拒, 标出 (admin) 并提示改用 bind, 实为 " + console.texts());
            List<String> preview = console.expect("district bind abydos " + env.x(50) + " " + env.z(50), 1,
                    KEY + "bind.preview.confirm");
            helper.assertTrue(preview.contains(KEY + "bind.preview.header")
                            && console.lastArgs(KEY + "bind.preview.confirm").equals(List.of("2")),
                    "先列预览, 点明会删掉 2 块子领地, 实为 " + preview);
            helper.assertTrue(env.repo.liveDistricts().isEmpty() && FlanTestClaims.snapshot(level).equals(before),
                    "预览一个字都不写");
            console.expect("district bind abydos " + env.x(3000) + " " + env.z(3000) + " confirm", 0,
                    KEY + "claim_not_found");
            FlanTestClaims.rawPlayerClaim(level, env.slotArea(600, 600, 700, 700), uuidOf("Someone_S"));
            console.expect("district bind abydos " + env.x(650) + " " + env.z(650) + " confirm", 0,
                    KEY + "claim_not_admin");

            console.expect("district bind abydos " + env.x(50) + " " + env.z(50) + " confirm", 1, KEY + "bind.done");
            DistrictRecord abydos = env.districtRecord("abydos");
            helper.assertTrue(raw.getClaimID().equals(abydos.flanClaimId())
                            && abydos.bounds().equals(env.slotBounds(0, 0, 399, 399)),
                    "库里记着这块领地的 id, 范围取领地的 X/Z, 实为 " + abydos);
            helper.assertTrue(raw.groups().equals(List.of(FlanGroupNames.districtResident("abydos")))
                            && FlanTestClaims.members(raw).isEmpty() && raw.getAllSubclaims().isEmpty()
                            && raw.getAllowedFakePlayerUUID().isEmpty() && raw.getPotions().isEmpty()
                            && raw.getClaimName().equals(DistrictTexts.claimName(abydos.displayName())),
                    "收编: 只剩本区居民组, 成员、子领地、假玩家与药水全清, 实为 " + raw.groups());
            helper.assertTrue(backupFiles(env).stream().anyMatch(name -> name.endsWith("-bind.AdminClaims.json.bak")),
                    "收编前强制备份, 实为 " + backupFiles(env));
            env.resident("abydos", "Resident_R");
            helper.assertTrue(FlanGroupNames.districtResident("abydos").equals(
                    FlanTestClaims.members(raw).get(uuidOf("Resident_R"))), "之后照常加住户");
            helper.assertTrue(env.ctx.admin().bindDistrict(env.admin, "millennium", DIM, env.x(50), env.z(50), true)
                            .outcome() == DistrictAdminService.CommandOutcome.CLAIM_ALREADY_BOUND,
                    "已被在用区绑着的领地被拒");
        }
        helper.succeed();
    }

    /**
     * 父领地丢了 (有人 /flan adminDelete, 20.6): 自动对账只报告、置标记, 不擅自重建; 父领地还在时 /district claim recreate
     * 拒绝; 丢了之后 recreate 在库里的范围上新建一块 (先强制备份, 原因 recreate), 新 id 写回库, 再 resync 把地块按库重建,
     * 户主回到户主组, 判定与丢之前一样。
     */
    static void recreateRebuildsAMissingDistrictClaim(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 20)) {
            abydos(env);
            env.resident("abydos", "Owner_O");
            env.seen("Outsider_X");
            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            env.own(plot, "Owner_O");
            Console console = new Console(helper);
            console.expect("district claim abydos recreate", 0, KEY + "claim_present");

            Claim old = parentOf(level, env, "abydos");
            UUID oldId = old.getClaimID();
            FlanTestClaims.adminDelete(level, old);
            DistrictReconciler.DistrictProgress auto = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.AUTO);
            helper.assertTrue(auto.skipped() && env.districtRecord("abydos").needsReconcile()
                            && oldId.equals(env.districtRecord("abydos").flanClaimId())
                            && FlanTestClaims.storage(level).getClaimAt(at(env, 50, 50)) == null,
                    "对账不擅自重建, 只置标记, 实为 " + auto.unfixable());

            console.expect("district claim abydos recreate", 1, KEY + "claim.recreate.done");
            UUID recreated = env.districtRecord("abydos").flanClaimId();
            Claim fresh = FlanTestClaims.claim(level, recreated);
            helper.assertTrue(recreated != null && !recreated.equals(oldId) && fresh != null && fresh.isAdminClaim()
                            && fresh.groups().equals(List.of(FlanGroupNames.districtResident("abydos"))),
                    "新建的父领地: 新 id 写回库, 只有本区居民组");
            Claim plotClaim = plotOf(level, env, plot);
            helper.assertTrue(FlanGroupNames.plotOwner(plot).equals(FlanTestClaims.members(plotClaim).get(uuidOf("Owner_O")))
                            && FlanTestClaims.can(FlanTestClaims.realPlayer(level, "Owner_O"), at(env, 105, 105), BREAK)
                            && !FlanTestClaims.can(FlanTestClaims.realPlayer(level, "Outsider_X"), at(env, 105, 105), BREAK),
                    "resync 把地块按库重建: 户主回到户主组, 外人照旧不能拆");
            helper.assertTrue(backupFiles(env).stream().anyMatch(name -> name.endsWith("-recreate.AdminClaims.json.bak"))
                            && !env.districtRecord("abydos").needsReconcile(),
                    "重建前强制备份, 整轮对完清掉标记, 实为 " + backupFiles(env));
        }
        helper.succeed();
    }

    /**
     * 改范围走金锄头 + /district bounds sync (20.6): 模拟 OP 用金锄头把父领地扩大, 领地仍找得到、地块都还在; bounds sync
     * 把库改成领地的范围。再缩到压住一块地: 拒绝并列出该地块的编号, 库不变, 对账预演报告范围漂移与落在外面的地块。
     */
    static void boundsSyncReadsTheClaimAndRejectsPlotsOutside(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 15)) {
            abydos(env);
            String plot = env.plotAt("abydos", 300, 300, 320, 320);
            Claim parent = parentOf(level, env, "abydos");
            Console console = new Console(helper);
            console.expect("district bounds abydos sync", 1, KEY + "bounds.sync.unchanged");

            FlanTestClaims.simulateGoldenHoeResize(level, parent, env.slotArea(0, 0, 499, 499));
            helper.assertTrue(env.gateway.findDistrictClaim(DIM, parent.getClaimID()).isPresent()
                            && parent.getAllSubclaims().size() == 1
                            && FlanTestClaims.storage(level).getClaimAt(at(env, 450, 450)) == parent,
                    "金锄头改完: 领地仍找得到, 地块还在, 新范围生效");
            console.expect("district bounds abydos sync", 1, KEY + "bounds.sync.done");
            helper.assertTrue(env.districtRecord("abydos").bounds().equals(env.slotBounds(0, 0, 499, 499)),
                    "库跟着领地走");

            FlanTestClaims.simulateGoldenHoeResize(level, parent, env.slotArea(0, 0, 310, 310));
            console.expect("district bounds abydos sync", 0, KEY + "bounds.sync.plots_outside");
            helper.assertTrue(console.texts().stream().anyMatch(text -> text.contains(env.plotRecord(plot).code())),
                    "列出落在范围外的地块编号, 实为 " + console.texts());
            helper.assertTrue(env.districtRecord("abydos").bounds().equals(env.slotBounds(0, 0, 499, 499)),
                    "被拒时库不动");
            DistrictReconciler.DistrictProgress dry = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.DRY_RUN);
            helper.assertTrue(dry.unfixable().stream().anyMatch(line -> line.contains("district claim covers"))
                            && dry.unfixable().stream().anyMatch(line -> line.contains("lies outside the district claim")),
                    "对账预演报告范围漂移与落在外面的地块, 实为 " + dry.unfixable());
        }
        helper.succeed();
    }

    // ================================================================
    // 备份
    // ================================================================

    /**
     * 备份 (20.5): 在世界目录的 miningdim/… 下、不在任何 data/claims 之内, 后缀 .AdminClaims.json.bak; 内容是
     * JsonArray, 含本区父领地的 id 与嵌套的地块。10 分钟内再来一批不新增 (时钟可拨), 拨过 10 分钟就新增; resync 不受
     * 节流。按池轮换, 只删符合命名规则的文件。备份目录写不出来时: 本批一个字都不写 Flan, 住户与地块记 failed。
     */
    static void backupsAreWrittenOutsideDataClaimsAndRotated(GameTestHelper helper) {
        ServerLevel level = level(helper);
        MinecraftServer server = level.getServer();
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 16, null, 2, 3)) {
            abydos(env);
            env.resident("abydos", "Owner_O");
            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            helper.assertTrue(backupFiles(env).size() == 1, "10 分钟之内的写入不新增备份, 实为 " + backupFiles(env));

            env.advance(ClaimBackupFiles.THROTTLE_MS + 1);
            env.own(plot, "Owner_O");
            List<String> files = backupFiles(env);
            helper.assertTrue(files.size() == 2 && files.get(1).endsWith("-batch.AdminClaims.json.bak"),
                    "拨过 10 分钟: 这一批之前新增一份 (原因 batch), 实为 " + files);
            Path batch = env.backupRoot().resolve("minecraft_overworld").resolve(files.get(1));
            Path worldRoot = server.getWorldPath(LevelResource.ROOT);
            helper.assertTrue(!ClaimBackupFiles.insideClaimsFolder(batch) && batch.startsWith(worldRoot.resolve("miningdim"))
                            && !batch.startsWith(worldRoot.resolve("data")), "备份在世界目录的 miningdim 下, 不在 data/claims");
            Claim parent = parentOf(level, env, "abydos");
            JsonArray content = JsonParser.parseString(read(batch)).getAsJsonArray();
            JsonElement mine = null;
            for (JsonElement element : content) {
                if (parent.getClaimID().toString().equals(element.getAsJsonObject().get("ID").getAsString())) {
                    mine = element;
                }
            }
            helper.assertTrue(mine != null && mine.getAsJsonObject().getAsJsonArray("SubClaims").toString()
                            .contains(plotOf(level, env, plot).getClaimID().toString()),
                    "内容是 JsonArray, 含本区父领地与嵌套的地块 (改动之前的状态)");

            env.advance(60_000L);
            env.ctx.plotOwners().setPermission(player("Owner_O"), "abydos", plot, "door", "outsider", false);
            helper.assertTrue(backupFiles(env).size() == 2, "10 分钟之内再来一批: 不新增");
            env.ctx.reconciler().reconcileDistrict("abydos", DistrictReconciler.Mode.EXPLICIT);
            helper.assertTrue(backupFiles(env).size() == 3 && backupFiles(env).get(2).endsWith("-resync.AdminClaims.json.bak"),
                    "resync 不受节流, 照样新增, 实为 " + backupFiles(env));

            Path directory = env.backupRoot().resolve("minecraft_overworld");
            try {
                Files.writeString(directory.resolve("notes.txt"), "keep me", StandardCharsets.UTF_8);
                Files.writeString(directory.resolve("20200101-000000-000-start.AdminClaims.json"), "[]",
                        StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            String oldest = backupFiles(env).get(0);
            env.gateway.backupNow(DIM, "resync");
            List<String> rotated = backupFiles(env);
            helper.assertTrue(rotated.size() == 3 && !rotated.contains(oldest),
                    "其余原因的池上限 3: 超出后只删最旧的, 实为 " + rotated);
            for (int i = 0; i < 3; i++) {
                env.advance(1);
                env.gateway.backupNow(DIM, "start");
            }
            long starts = backupFiles(env).stream().filter(name -> name.endsWith("-start.AdminClaims.json.bak")).count();
            helper.assertTrue(starts == 2 && backupFiles(env).size() == 5, "start 池上限 2, 两个池各自轮换, 实为 "
                    + backupFiles(env));
            helper.assertTrue(Files.exists(directory.resolve("notes.txt"))
                            && Files.exists(directory.resolve("20200101-000000-000-start.AdminClaims.json")),
                    "只删符合命名规则的文件");
        }

        Path blocked = server.getWorldPath(LevelResource.ROOT).resolve("miningdim/district-flan-backups-gametest")
                .resolve("blocked-slot-16");
        try {
            Files.createDirectories(blocked.getParent());
            Files.writeString(blocked, "a regular file where the backup folder should be", StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 16, blocked, 10, 30)) {
            Map<UUID, String> before = FlanTestClaims.snapshot(level);
            abydos(env);
            MemberRecord resident = env.resident("abydos", "Owner_O");
            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            helper.assertTrue(env.districtRecord("abydos").flanClaimId() == null
                            && env.districtRecord("abydos").needsReconcile(),
                    "备份写不出来: 父领地不建, 置对账标记");
            helper.assertTrue(resident.syncStatus() == ResidentSyncStatus.FAILED
                            && env.plotRecord(plot).syncStatus() == PlotSyncStatus.FAILED
                            && DistrictTexts.FLAN_BACKUP_FAILED.equals(env.plotRecord(plot).syncError()),
                    "住户与地块记 failed, 原因是备份失败, 实为 " + env.plotRecord(plot).syncError());
            helper.assertTrue(FlanTestClaims.snapshot(level).equals(before), "本批一个字都不写 Flan");
        } finally {
            try {
                Files.deleteIfExists(blocked);
            } catch (IOException ignored) {
                // 下一轮开跑前 run/world 会整个删掉。
            }
        }
        helper.succeed();
    }

    // ================================================================
    // 复核补上的: 高度、放行清单、写库失败、存盘、改认
    // ================================================================

    /**
     * Flan 只按顶层领地判定保护 (20.3): 父领地不含的高度在每块地里都是野外。/flan 在 y=64 圈的管理员领地只往下探
     * defaultClaimDepth 格; 3D 领地只保护一段高度。bind: 3D 的被拒 (claim_not_full_height), 预览点明底偏高; 2D 但底偏高的
     * 收编时补到世界底, 地块的地下因此受保护。之后底又被抬高 (恢复了一份老备份) 时, 自动对账把它补回去并记一项改动。
     */
    static void bindExtendsAShallowClaimToTheWorldBottom(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 26)) {
            int bottom = level.getMinBuildHeight();
            Claim raw = FlanTestClaims.rawAdminClaimAt(level, env.slotArea(0, 0, 399, 399), 64, false);
            Claim cube = FlanTestClaims.rawAdminClaimAt(level, env.slotArea(1000, 1000, 1100, 1100), 64, true);
            BlockPos deep = new BlockPos(env.x(105), bottom + 1, env.z(105));
            helper.assertTrue(!raw.is3d() && raw.getDimensions().minY() > bottom
                            && FlanTestClaims.storage(level).getClaimAt(deep) == null && cube.is3d(),
                    "前提: 2D 领地的底在 " + raw.getDimensions().minY() + ", 底下是野外; 另一块是 3D");
            env.seen("Outsider_X");

            Console console = new Console(helper);
            console.expect("district bind abydos " + env.x(1050) + " " + env.z(1050) + " confirm", 0,
                    KEY + "claim_not_full_height");
            helper.assertTrue(env.repo.liveDistricts().isEmpty(), "3D 领地被拒, 库里一行都不写");
            console.expect("district bind abydos " + env.x(50) + " " + env.z(50), 1, KEY + "bind.preview.confirm");
            helper.assertTrue(console.texts().stream().anyMatch(text -> text.contains("bottom y "
                            + raw.getDimensions().minY())),
                    "预览点明领地的底偏高、会补到世界底, 实为 " + console.texts());

            console.expect("district bind abydos " + env.x(50) + " " + env.z(50) + " confirm", 1, KEY + "bind.done");
            helper.assertTrue(raw.getDimensions().minY() <= bottom
                            && FlanTestClaims.storage(level).getClaimAt(deep) == raw,
                    "收编把底补到世界底, 实为 " + raw.getDimensions().minY());
            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            helper.assertTrue(!FlanTestClaims.can(FlanTestClaims.realPlayer(level, "Outsider_X"), deep, BREAK)
                            && env.plotRecord(plot).syncStatus() == PlotSyncStatus.SYNCED,
                    "地块的地下也受保护: 外人拆不了");

            FlanTestClaims.setMinY(raw, 40);
            helper.assertTrue(FlanTestClaims.storage(level).getClaimAt(deep) == null, "前提: 底又被抬高了");
            DistrictReconciler.DistrictProgress round = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.AUTO);
            helper.assertTrue(FlanTestClaims.storage(level).getClaimAt(deep) == raw
                            && round.changes().stream().anyMatch(change -> change.contains("bottom y 40"))
                            && round.unfixable().isEmpty(),
                    "自动对账把底补回世界底, 记一项改动, 实为 " + round.changes() + " / " + round.unfixable());
        }
        helper.succeed();
    }

    /**
     * 放行清单 (20.3): Flan 的用方块、拆方块等事件在顶层领地上先查六张放行清单, 命中即放行, 不走组与默认 —— 地块里的
     * 判定落在父领地上, 父领地清单里的一条箱子会让每块地的箱子谁都能开。收编把它们清空; 之后有人在 /flan menu 里又加回去
     * (加得回去, 说明清的时候没弄坏 Flan 的名字索引), 自动对账再清一次并记一项改动。
     */
    static void allowListsAreClearedOnBindAndReconcile(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 25)) {
            Claim raw = FlanTestClaims.rawAdminClaim(level, env.slotArea(0, 0, 399, 399));
            BlockState chest = Blocks.CHEST.defaultBlockState();
            BlockState stone = Blocks.STONE.defaultBlockState();
            raw.allowedUseBlocks.addAllowedItem(Either.left(Blocks.CHEST));
            helper.assertTrue(raw.canUseBlockItem(chest), "前提: 放行清单里的箱子谁都能开");

            DistrictAdminService.CommandResult bound = env.ctx.admin().bindDistrict(env.admin, "abydos", DIM,
                    env.x(50), env.z(50), true);
            helper.assertTrue(bound.ok(), "收编成功, 实为 " + bound.outcome());
            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            BlockPos inPlot = at(env, 105, 105);
            helper.assertTrue(FlanTestClaims.storage(level).getForPermissionCheck(inPlot) == raw
                            && env.plotRecord(plot).syncStatus() == PlotSyncStatus.SYNCED,
                    "地块里的判定落在父领地上 (Flan 在它身上查放行清单)");
            helper.assertTrue(!raw.canUseBlockItem(chest) && raw.allowedUseBlocks.size() == 0,
                    "收编清空放行清单");

            raw.allowedUseBlocks.addAllowedItem(Either.left(Blocks.CHEST));
            raw.allowedBreakBlocks.addAllowedItem(Either.left(Blocks.STONE));
            helper.assertTrue(raw.canUseBlockItem(chest) && raw.canBreakBlockItem(stone),
                    "前提: 清空之后还加得回去 (清的时候没弄坏它的索引)");
            DistrictReconciler.DistrictProgress round = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.AUTO);
            helper.assertTrue(!raw.canUseBlockItem(chest) && !raw.canBreakBlockItem(stone)
                            && round.changes().stream().anyMatch(change -> change.contains("2 allow-list entries")),
                    "自动对账再清一次, 记一项改动, 实为 " + round.changes());
        }
        helper.succeed();
    }

    /**
     * 新建的子领地没能记进库 (20.3): 写库失败 (这里用 TEMP 触发器让 UPDATE flan_claim_id 失败) 时删掉这块子领地、地块
     * 如实标 failed; 触发器去掉后, 下一次写入重新建好、synced。父领地下不留来历不明的子领地。
     */
    static void unrecordedPlotClaimIsDeletedAgain(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 22)) {
            abydos(env);
            Claim parent = parentOf(level, env, "abydos");
            env.exec("CREATE TEMP TRIGGER fail_plot_claim BEFORE UPDATE OF flan_claim_id ON district_plot "
                    + "BEGIN SELECT RAISE(ABORT, 'injected'); END");
            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            helper.assertTrue(parent.getAllSubclaims().isEmpty() && env.plotRecord(plot).flanClaimId() == null
                            && env.plotRecord(plot).syncStatus() == PlotSyncStatus.FAILED
                            && DistrictTexts.PLOT_CLAIM_NOT_RECORDED.equals(env.plotRecord(plot).syncError()),
                    "写库失败: 子领地删掉, 地块如实标 failed, 实为 " + env.plotRecord(plot) + " / "
                            + parent.getAllSubclaims().size());
            env.exec("DROP TRIGGER fail_plot_claim");
            env.ctx.flanSync().writePlotState(plot);
            helper.assertTrue(parent.getAllSubclaims().size() == 1 && env.plotRecord(plot).flanClaimId() != null
                            && env.plotRecord(plot).syncStatus() == PlotSyncStatus.SYNCED,
                    "下一次写入重新建好, synced");
        }
        helper.succeed();
    }

    /**
     * 建好父领地却没能把 id 写进库 (20.6): 这里用 TEMP 触发器让 UPDATE district.flan_claim_id 失败。库里没有 id、Flan 里
     * 有一块名字与范围都恰好相符、没被任何自管区记着的管理员领地; 下一次对账认回这一块, 不再去建一块注定"重叠"的。
     */
    static void strayDistrictClaimIsRelinkedNotDuplicated(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 23)) {
            env.exec("CREATE TEMP TRIGGER fail_district_claim BEFORE UPDATE OF flan_claim_id ON district "
                    + "BEGIN SELECT RAISE(ABORT, 'injected'); END");
            DistrictRecord district = abydos(env);
            List<Claim> stray = FlanTestClaims.adminClaimsIn(level, FlanTestClaims.slotArea(23));
            helper.assertTrue(env.districtRecord("abydos").flanClaimId() == null && stray.size() == 1
                            && stray.get(0).getClaimName().equals(DistrictTexts.claimName(district.displayName())),
                    "前提: 领地建成了, 库里却没有它的 id, 实为 " + stray.size());
            env.exec("DROP TRIGGER fail_district_claim");

            env.ctx.reconciler().reconcileDistrict("abydos", DistrictReconciler.Mode.AUTO);
            helper.assertTrue(stray.get(0).getClaimID().equals(env.districtRecord("abydos").flanClaimId())
                            && FlanTestClaims.adminClaimsIn(level, FlanTestClaims.slotArea(23)).size() == 1
                            && stray.get(0).groups().equals(List.of(FlanGroupNames.districtResident("abydos"))),
                    "认回那一块, 不新建第二块, 组照库写好");
            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            helper.assertTrue(env.plotRecord(plot).syncStatus() == PlotSyncStatus.SYNCED, "之后照常划地");
        }
        helper.succeed();
    }

    /**
     * 恢复了不同时刻的 miningdim.db (20.6): 库里记着的父领地 id 在 Flan 里找不到, 那片地上却还是原来那块领地。recreate
     * 因"这片范围已有领地"被拒; /district claim relink 预览一个字都不写, 确认后本区改认那一列上的领地、先强制备份再
     * resync: 地块的子领地原样认回, 不重建, 判定照旧。领地还在时 relink 被拒。
     */
    static void relinkAdoptsTheLandsClaimAfterARestore(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 27)) {
            abydos(env);
            env.resident("abydos", "Owner_O");
            env.seen("Outsider_X");
            String plot = env.plotAt("abydos", 100, 100, 115, 115);
            env.own(plot, "Owner_O");
            Claim parent = parentOf(level, env, "abydos");
            UUID real = parent.getClaimID();
            UUID plotClaim = env.plotRecord(plot).flanClaimId();
            env.repo.setDistrictClaimId("abydos", UUID.randomUUID());

            Console console = new Console(helper);
            console.expect("district claim abydos recreate", 0, KEY + "flan_claim_exists");
            String relink = "district claim abydos relink " + env.x(50) + " " + env.z(50);
            console.expect(relink, 1, KEY + "claim.relink.preview.confirm");
            helper.assertTrue(!real.equals(env.districtRecord("abydos").flanClaimId()), "预览一个字都不写");
            console.expect(relink + " confirm", 1, KEY + "claim.relink.done");
            helper.assertTrue(real.equals(env.districtRecord("abydos").flanClaimId())
                            && plotClaim.equals(env.plotRecord(plot).flanClaimId())
                            && parent.getAllSubclaims().size() == 1
                            && env.plotRecord(plot).syncStatus() == PlotSyncStatus.SYNCED,
                    "改认原来那块: 地块的子领地原样认回, 不重建");
            helper.assertTrue(FlanTestClaims.can(FlanTestClaims.realPlayer(level, "Owner_O"), at(env, 105, 105), BREAK)
                            && !FlanTestClaims.can(FlanTestClaims.realPlayer(level, "Outsider_X"), at(env, 105, 105),
                            BREAK),
                    "判定照旧: 户主能拆, 外人不能");
            helper.assertTrue(backupFiles(env).stream().anyMatch(name -> name.endsWith("-resync.AdminClaims.json.bak")),
                    "改认之后的 resync 先强制备份, 实为 " + backupFiles(env));
            console.expect(relink + " confirm", 0, KEY + "claim_present");
        }
        helper.succeed();
    }

    /**
     * 反射改动也落盘 (20.4): 父领地上多了一个没有成员的组 (Visitor) 和一个假玩家, 存盘之后不脏。自动对账只做这两项改动
     * (删组走反射、移出假玩家的调用本身不标脏), 父领地必须因此被标脏, 下一次存盘 !AdminClaims.json 里就没有它们了 ——
     * 否则内存改好了、盘上没改, 重启之后漂移又回来。
     */
    static void reconcileRepairsReachDiskEvenWithoutOtherEdits(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (DistrictTestEnv env = DistrictTestEnv.openWithRealFlan(helper, 24)) {
            abydos(env);
            env.resident("abydos", "Resident_R");
            env.plotAt("abydos", 100, 100, 115, 115);
            Claim parent = parentOf(level, env, "abydos");
            UUID fake = UUID.randomUUID();
            parent.editPerms(null, "Visitor", rl(DOOR), 1, true);
            parent.modifyFakePlayerUUID(fake, false);
            FlanTestClaims.save(level);
            helper.assertTrue(!parent.isDirty(), "前提: 存盘之后父领地不脏");
            Path file = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve("claims")
                    .resolve("!AdminClaims.json");
            String before = savedClaim(file, parent.getClaimID());
            helper.assertTrue(before.contains("Visitor") && before.contains(fake.toString()), "前提: 两处改动已在盘上");

            DistrictReconciler.DistrictProgress round = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.AUTO);
            helper.assertTrue(round.reverted() == 2 && parent.isDirty(),
                    "对账只删了 Visitor 组、清了假玩家, 父领地因此被标脏, 实为 " + round.changes() + " dirty="
                            + parent.isDirty());
            FlanTestClaims.save(level);
            String after = savedClaim(file, parent.getClaimID());
            helper.assertTrue(!after.contains("Visitor") && !after.contains(fake.toString()),
                    "存盘之后盘上也没有了, 实为 " + after);
        }
        helper.succeed();
    }

    /** !AdminClaims.json 里某块领地的那一项 (找不到为空串)。 */
    private static String savedClaim(Path file, UUID claimId) {
        for (JsonElement element : JsonParser.parseString(read(file)).getAsJsonArray()) {
            if (claimId.toString().equals(element.getAsJsonObject().get("ID").getAsString())) {
                return element.toString();
            }
        }
        return "";
    }

    // ================================================================
    // 工具
    // ================================================================

    private static ServerLevel level(GameTestHelper helper) {
        return FlanTestClaims.level(helper.getLevel().getServer(), DIM);
    }

    private static ResourceLocation rl(String id) {
        return new ResourceLocation(id);
    }

    /** 阿拜多斯自管区: 槽位里偏移 0 ~ 399。 */
    private static DistrictRecord abydos(DistrictTestEnv env) {
        return env.districtAt("abydos", env.slotBounds(0, 0, 399, 399));
    }

    private static BlockPos at(DistrictTestEnv env, int dx, int dz) {
        return FlanTestClaims.probe(env.x(dx), env.z(dz));
    }

    private static Claim parentOf(ServerLevel level, DistrictTestEnv env, String districtId) {
        Claim claim = FlanTestClaims.claim(level, env.districtRecord(districtId).flanClaimId());
        if (claim == null) {
            throw new IllegalStateException("district " + districtId + " has no claim in Flan");
        }
        return claim;
    }

    private static Claim plotOf(ServerLevel level, DistrictTestEnv env, String plotId) {
        Claim parent = parentOf(level, env, env.plotRecord(plotId).districtId());
        Claim plot = FlanTestClaims.subclaim(parent, env.plotRecord(plotId).flanClaimId());
        if (plot == null) {
            throw new IllegalStateException("plot " + plotId + " has no subclaim in Flan");
        }
        return plot;
    }

    private static List<Boolean> decisions(ServerPlayer player, BlockPos pos, String... permissions) {
        List<Boolean> out = new ArrayList<>();
        for (String permission : permissions) {
            out.add(FlanTestClaims.can(player, pos, permission));
        }
        return out;
    }

    /** 每个人在公共区域 (50, 50) 与地块 (105, 105) 里对 放/拆/门/箱子/捡东西 的判定。 */
    private static Map<String, List<Boolean>> matrix(ServerLevel level, DistrictTestEnv env, List<String> people) {
        Map<String, List<Boolean>> out = new LinkedHashMap<>();
        for (String name : people) {
            ServerPlayer who = FlanTestClaims.realPlayer(level, name);
            List<Boolean> row = new ArrayList<>(decisions(who, at(env, 50, 50), PLACE, BREAK, DOOR, CONTAINER, PICKUP));
            row.addAll(decisions(who, at(env, 105, 105), PLACE, BREAK, DOOR, CONTAINER, PICKUP));
            out.put(name, row);
        }
        return out;
    }

    private static List<String> backupFiles(DistrictTestEnv env) {
        ClaimBackupFiles files = new ClaimBackupFiles(env.backupRoot(), env.clock::get, Integer.MAX_VALUE,
                Integer.MAX_VALUE);
        return files.list(DIM);
    }

    private static List<String> json(Claim... claims) {
        List<String> out = new ArrayList<>();
        for (Claim claim : claims) {
            out.add(claim.toJson(new com.google.gson.JsonObject()).toString());
        }
        return out;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("reading " + file + " failed", failure);
        }
    }

    private static Map<String, Map<ResourceLocation, Boolean>> deepCopy(Map<String, Map<ResourceLocation, Boolean>> map) {
        Map<String, Map<ResourceLocation, Boolean>> copy = new LinkedHashMap<>();
        map.forEach((key, inner) -> copy.put(key, new LinkedHashMap<>(inner)));
        return copy;
    }

    /** 这张内层 Map 是不是父领地某个组的那一个对象 (按引用比)。 */
    private static boolean sharesAny(Claim parent, Map<ResourceLocation, Boolean> inner) {
        for (Map<ResourceLocation, Boolean> map : FlanTestClaims.groupMaps(parent).values()) {
            if (map == inner) {
                return true;
            }
        }
        return false;
    }

    /** 以控制台身份执行命令并收集反馈 (与 DistrictCommandGameTests 的同名工具同口径; FlanClaimGuardScenarios 也用)。 */
    static final class Console {

        private final GameTestHelper helper;
        private final CommandDispatcher<CommandSourceStack> dispatcher;
        private final Capture out = new Capture();
        private final CommandSourceStack source;

        Console(GameTestHelper helper) {
            this.helper = helper;
            MinecraftServer server = helper.getLevel().getServer();
            this.dispatcher = server.getCommands().getDispatcher();
            this.source = server.createCommandSourceStack().withSource(out);
        }

        /** 执行并断言返回值与反馈里出现某个语言键; 返回这条命令产生的全部语言键。 */
        List<String> expect(String command, int expected, @Nullable String key) {
            out.messages.clear();
            Integer result;
            try {
                result = dispatcher.execute(command, source);
            } catch (CommandSyntaxException syntax) {
                result = null;
            }
            List<String> keys = new ArrayList<>();
            out.messages.forEach(message -> collectKeys(message, keys));
            helper.assertTrue(result != null && result == expected,
                    "/" + command + " 应返回 " + expected + ", 实为 " + result + " (反馈 " + keys + " " + texts() + ")");
            helper.assertTrue(key == null || keys.contains(key), "/" + command + " 的反馈应含 " + key + ", 实为 " + keys);
            return keys;
        }

        /** 最近一条命令反馈里的全部文字 (字面量与翻译参数)。 */
        List<String> texts() {
            List<String> texts = new ArrayList<>();
            out.messages.forEach(message -> collectTexts(message, texts));
            return texts;
        }

        /** 最近一条命令反馈里某个语言键的参数 (最后出现的那一条)。 */
        List<String> lastArgs(String key) {
            List<String> args = List.of();
            for (Component message : out.messages) {
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

    private static void collectKeys(Component component, List<String> keys) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            keys.add(translatable.getKey());
            for (Object argument : translatable.getArgs()) {
                if (argument instanceof Component nested) {
                    collectKeys(nested, keys);
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            collectKeys(sibling, keys);
        }
    }

    private static void collectTexts(Component component, List<String> texts) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            for (Object argument : translatable.getArgs()) {
                if (argument instanceof Component nested) {
                    collectTexts(nested, texts);
                } else {
                    texts.add(String.valueOf(argument));
                }
            }
        } else {
            texts.add(component.getString());
        }
        for (Component sibling : component.getSiblings()) {
            collectTexts(sibling, texts);
        }
    }

    /** 收集命令反馈的 CommandSource。 */
    private static final class Capture implements CommandSource {

        private final List<Component> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message);
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
    }
}
