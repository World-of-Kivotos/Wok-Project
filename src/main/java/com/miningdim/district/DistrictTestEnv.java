package com.miningdim.district;

import com.miningdim.district.access.Actor;
import com.miningdim.district.core.AcademyCatalog;
import com.miningdim.district.core.DistrictBounds;
import com.miningdim.district.core.DistrictError;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictRuleException;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.flan.FlanGateway;
import com.miningdim.district.flan.RecordingFlanGateway;
import com.miningdim.district.flan.real.ClaimBackupFiles;
import com.miningdim.district.flan.real.FlanBridge;
import com.miningdim.district.flan.real.FlanCompat;
import com.miningdim.district.flan.real.FlanTestClaims;
import com.miningdim.district.guard.GuardSettings;
import com.miningdim.district.service.DistrictAdminService;
import com.miningdim.district.service.DistrictContext;
import com.miningdim.district.service.PlotLayoutService;
import com.miningdim.district.service.ServerPlayerDirectory;
import com.miningdim.district.store.SqliteDistrictRepository;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.store.MiningDb;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * 自管区 GameTest 夹具 (设计文档 18.1): 内存统一库 + 同一条连接上的经济账本 + 记录型假网关 + 可拨时钟, 并把
 * {@link DistrictServices} 换成这份测试 context (登录事件等真实钩子因此作用在测试库上), close 时复原并关库。
 * {@link #openWithRealFlan} 换成真 Flan 网关 (设计文档 20.10): 每条用例一个槽位, 开跑前与 close 时清掉槽位里的领地。
 *
 * 账本与仓储建在同一条连接上 ({@code SqliteEconomyLedger.openInMemory()} + {@code ledger.connection()}), 复现生产环境
 * "钱与地块同库同事务"的真实条件; 经济门面只经 context 的 supplier 注入, 不动全局的 EconomyServices。
 * 玩家解析只查见过的玩家表 ({@link #seen}), 与测试服上真实在线的玩家无关。
 */
public final class DistrictTestEnv implements AutoCloseable {

    /**
     * -PwithoutFlan 的专门一轮 (build.gradle 不把 Flan 放进开发运行时, 设计文档 20.10) 在 GameTest 服务端上设的系统属性:
     * 那一轮核对没有 Flan 的生产路径, 真 Flan 的用例与两处"开发运行时有 Flan"的断言按它改走无 Flan 的分支。
     */
    public static final String WITHOUT_FLAN_PROPERTY = "miningdim.district.withoutFlan";

    /** 与称号模块的测试同值。 */
    public static final long T0 = 1_790_000_000_000L;

    /** 这一轮是不是 -PwithoutFlan 的专门一轮 (开发运行时刻意没有 Flan)。 */
    public static boolean withoutFlanRun() {
        return Boolean.getBoolean(WITHOUT_FLAN_PROPERTY);
    }
    public static final long DAY = 24L * 3_600_000L;
    public static final String DIMENSION = "minecraft:overworld";

    public final SqliteEconomyLedger ledger;
    public final SqliteDistrictRepository repo;
    public final FlanGateway gateway;
    public final AtomicLong clock = new AtomicLong(T0);
    public final DistrictContext ctx;
    /** 默认的管理员 (OP) 操作人。 */
    public final Actor admin = Actor.player(uuidOf("Op_Admin"), "Op_Admin", true);

    @Nullable
    private final DistrictContext previousContext;
    /** close 时先于复原门面执行 (真 Flan 夹具: 删掉本用例建过的全部顶层领地)。 */
    private final List<Runnable> cleanups = new ArrayList<>();
    /** 真 Flan 夹具的槽位原点 (记录型夹具为 0, 0)。 */
    private int originX;
    private int originZ;
    /** 真 Flan 夹具的备份目录 (记录型夹具为 null)。 */
    @Nullable
    private Path backupRoot;

    private DistrictTestEnv(boolean withEconomy, @Nullable Function<LongSupplier, FlanGateway> gatewayFactory) {
        this(withEconomy, gatewayFactory, GuardSettings.defaults());
    }

    private DistrictTestEnv(boolean withEconomy, @Nullable Function<LongSupplier, FlanGateway> gatewayFactory,
                            GuardSettings guards) {
        this.ledger = SqliteEconomyLedger.openInMemory();
        this.repo = new SqliteDistrictRepository(ledger.connection());
        repo.ensureAcademies(AcademyCatalog.LAUNCH, T0);
        this.gateway = gatewayFactory == null ? new RecordingFlanGateway() : gatewayFactory.apply(clock::get);
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        IEconomyService economy = new EconomyService(ledger, new AbuseGuard(),
                id -> states.computeIfAbsent(id, ignored -> new PlayerAbuseState()));
        this.ctx = DistrictContext.of(repo, gateway, clock::get, () -> withEconomy ? economy : null,
                ServerPlayerDirectory.seenTableOnly(repo, clock::get), guards, DistrictTestEnv::onlineOnTestServer);
        this.previousContext = DistrictServices.isRegistered() ? DistrictServices.context() : null;
        DistrictServices.register(ctx);
    }

    /** 标准夹具: 经济在线, 记录型网关。 */
    public static DistrictTestEnv open() {
        return new DistrictTestEnv(true, null);
    }

    /** 标准夹具, 指定守卫的配置 (/district machines 按它分类; 守卫本身不装进门面, 见 22.3)。 */
    public static DistrictTestEnv openWithGuards(GuardSettings guards) {
        return new DistrictTestEnv(true, null, guards);
    }

    /** 经济门面取不到 (买地报 ECONOMY_OFFLINE)。 */
    public static DistrictTestEnv openWithoutEconomy() {
        return new DistrictTestEnv(false, null);
    }

    /** 指定网关 (如 DisabledFlanGateway)。 */
    public static DistrictTestEnv openWith(FlanGateway gateway) {
        return new DistrictTestEnv(true, clock -> gateway);
    }

    /**
     * 真 Flan 夹具 (设计文档 20.10), 用第 slot 号槽位 ({@link FlanTestClaims#slotArea}), 备份目录与轮换上限取生产默认值。
     */
    public static DistrictTestEnv openWithRealFlan(GameTestHelper helper, int slot) {
        return openWithRealFlan(helper, slot, null, ClaimBackupFiles.KEEP_START, ClaimBackupFiles.KEEP_OTHER);
    }

    /**
     * 真 Flan 夹具 (只由 FlanRealScenarios 调用, 而它只在 FlanRealGameTests.requireFlan 放行之后才跑): 开发运行时必须加载了
     * 服主批准的 Flan (build.gradle 的 runtimeOnly, 20.1), 开服自检
     * ({@link FlanCompat#check}) 必须通过, 否则用例直接失败并带上问题清单; 网关是真网关 ({@link FlanBridge#create}),
     * 注入本夹具的可拨时钟与本用例专用的备份目录 (默认在世界目录的 miningdim/district-flan-backups-gametest/slot-N, 开跑前
     * 清空; 传入 backupRoot 时原样使用, 不清空)。开跑前与 close 时都删掉槽位里的全部顶层领地。
     */
    public static DistrictTestEnv openWithRealFlan(GameTestHelper helper, int slot, @Nullable Path backupRoot,
                                                   int keepStart, int keepOther) {
        MinecraftServer server = helper.getLevel().getServer();
        String version = DistrictSystem.loadedFlanVersion();
        if (version == null) {
            helper.fail("开发运行时没有加载 Flan (build.gradle 的 runtimeOnly fg.deobf, 设计文档 20.1)");
        }
        FlanCompat.Result check = FlanCompat.check(version, FlanCompat.expectedSignatures(),
                DistrictTestEnv.class.getClassLoader(), server);
        if (!check.ok()) {
            helper.fail("Flan 开服自检没有通过: " + check.problems());
        }
        Path root = backupRoot;
        if (root == null) {
            root = server.getWorldPath(LevelResource.ROOT).resolve("miningdim/district-flan-backups-gametest")
                    .resolve("slot-" + slot);
            deleteTree(root);
        }
        PlotArea slotArea = FlanTestClaims.slotArea(slot);
        FlanTestClaims.deleteTopLevelClaims(server, DIMENSION, slotArea);
        Path gatewayRoot = root;
        DistrictTestEnv env = new DistrictTestEnv(true, clock -> {
            try {
                return FlanBridge.create(server, clock, gatewayRoot, keepStart, keepOther);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("building the real Flan gateway failed", failure);
            }
        });
        env.originX = slotArea.minX();
        env.originZ = slotArea.minZ();
        env.backupRoot = root;
        env.onClose(() -> FlanTestClaims.deleteTopLevelClaims(server, DIMENSION, slotArea));
        return env;
    }

    /** 记录型网关 (标准夹具下)。 */
    public RecordingFlanGateway recording() {
        return (RecordingFlanGateway) gateway;
    }

    /** close 时要做的事 (先于复原门面与关库)。 */
    public void onClose(Runnable cleanup) {
        cleanups.add(cleanup);
    }

    /** 真 Flan 夹具的备份目录。 */
    public Path backupRoot() {
        if (backupRoot == null) {
            throw new IllegalStateException("only the real Flan fixture has a backup root");
        }
        return backupRoot;
    }

    @Override
    public void close() {
        try {
            for (Runnable cleanup : cleanups) {
                cleanup.run();
            }
        } finally {
            try {
                DistrictServices.reset();
                if (previousContext != null) {
                    DistrictServices.register(previousContext);
                }
            } finally {
                MiningDb.close(ledger.connection());
            }
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("fixture: clearing " + root + " failed", failure);
        }
    }

    // ================================================================
    // 真 Flan 夹具的坐标 (槽位原点 + 偏移)
    // ================================================================

    public int x(int dx) {
        return originX + dx;
    }

    public int z(int dz) {
        return originZ + dz;
    }

    /** 槽位里的一片范围 (偏移量, 主世界)。 */
    public DistrictBounds slotBounds(int minDx, int minDz, int maxDx, int maxDz) {
        return new DistrictBounds(DIMENSION, x(minDx), z(minDz), x(maxDx), z(maxDz));
    }

    /** 槽位里的一片地块范围 (偏移量)。 */
    public PlotArea slotArea(int minDx, int minDz, int maxDx, int maxDz) {
        return new PlotArea(x(minDx), z(minDz), x(maxDx), z(maxDz));
    }

    /** 在给定范围上建自管区 (控制台身份)。 */
    public DistrictRecord districtAt(String academyId, DistrictBounds bounds) {
        return district(academyId, bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
    }

    /** 以管理员身份在槽位里划一块地 (偏移量), 返回 plotId。 */
    public String plotAt(String districtId, int minDx, int minDz, int maxDx, int maxDz) {
        return plot(districtId, x(minDx), z(minDz), x(maxDx), z(maxDz));
    }

    // ================================================================
    // 玩家与身份
    // ================================================================

    public static UUID uuidOf(String name) {
        return UUIDUtil.createOfflinePlayerUUID(name);
    }

    /** 普通玩家 (UUID 取名字的离线 UUID)。 */
    public static Actor player(String name) {
        return Actor.player(uuidOf(name), name, false);
    }

    /** OP 玩家。 */
    public static Actor op(String name) {
        return Actor.player(uuidOf(name), name, true);
    }

    /** 把这些名字记成进过服 (最后在线取当前时钟)。 */
    public void seen(String... names) {
        for (String name : names) {
            repo.upsertSeenOnLogin(uuidOf(name), name, clock.get());
        }
    }

    public void advance(long millis) {
        clock.addAndGet(millis);
    }

    // ================================================================
    // 造数据的捷径 (一律走真实的服务方法, 除了 own)
    // ================================================================

    /** 建一个自管区 (控制台身份)。 */
    public DistrictRecord district(String academyId, int minX, int minZ, int maxX, int maxZ) {
        DistrictAdminService.CommandResult result = ctx.admin().createDistrict(Actor.console(), academyId,
                new DistrictBounds(DIMENSION, minX, minZ, maxX, maxZ), null);
        if (!result.ok() || result.district() == null) {
            throw new IllegalStateException("fixture district " + academyId + " failed: " + result.outcome());
        }
        return result.district();
    }

    /**
     * 同一个学院把自己原来那片地重新绑回来 (控制台身份): 解绑不删 Flan 领地, 旧的父领地还在那片地上, 阶段 2 起建区会被
     * FLAN_CLAIM_EXISTS 拦下, 改走 /district bind 收编旧的父领地 (设计文档 20.6)。(x, z) 是旧父领地里的任意一列。
     */
    public DistrictRecord rebind(String academyId, int x, int z) {
        DistrictAdminService.CommandResult result = ctx.admin().bindDistrict(Actor.console(), academyId, DIMENSION,
                x, z, true);
        if (!result.ok() || result.district() == null) {
            throw new IllegalStateException("fixture rebind " + academyId + " failed: " + result.outcome() + " "
                    + result.detail());
        }
        return result.district();
    }

    /** 阿拜多斯自管区: X/Z 0 ~ 199。 */
    public DistrictRecord abydos() {
        return district("abydos", 0, 0, 199, 199);
    }

    /** 千年自管区: X 1000 ~ 1199, Z 0 ~ 199。 */
    public DistrictRecord millennium() {
        return district("millennium", 1000, 0, 1199, 199);
    }

    /** 以管理员身份加一名进过服的住户。 */
    public MemberRecord resident(String districtId, String name) {
        seen(name);
        return ctx.residents().addResident(admin, districtId, name, false).resident();
    }

    public void warden(String districtId, String name) {
        ctx.admin().setWarden(admin, districtId, name);
    }

    /** 以管理员身份划一块地, 返回 plotId。 */
    public String plot(String districtId, int minX, int minZ, int maxX, int maxZ) {
        return ctx.layout().create(admin, districtId,
                PlotLayoutService.AreaInput.of(new PlotArea(minX, minZ, maxX, maxZ))).plot().plotId();
    }

    /**
     * 让某位住户直接成为一块空置地的户主 (不经买地: 与钱无关的测试用它; 买地本身由 PlotMarketGameTests 覆盖)。
     * 与买地一样清空朋友、三列回默认, 提交后整块重写。
     */
    public void own(String plotId, String memberName) {
        MemberRecord member = member(memberName);
        repo.inTransaction(() -> {
            if (!repo.assignOwner(plotId, member.uuid(), member.name())) {
                throw new IllegalStateException("fixture: plot " + plotId + " is not vacant");
            }
            repo.deleteFriends(plotId);
            repo.resetPlotCells(plotId);
            return null;
        });
        ctx.flanSync().writePlotState(plotId);
    }

    public MemberRecord member(String name) {
        return repo.memberByNameLower(DistrictTexts.lower(name))
                .orElseThrow(() -> new IllegalStateException("fixture: no member named " + name));
    }

    public PlotRecord plotRecord(String plotId) {
        return repo.plot(plotId).orElseThrow(() -> new IllegalStateException("fixture: no plot " + plotId));
    }

    /** 这块地现在的范围 (买地时作为"确认时看到的范围"送回去)。 */
    public PlotArea area(String plotId) {
        return plotRecord(plotId).area();
    }

    public DistrictRecord districtRecord(String districtId) {
        return repo.anyDistrict(districtId)
                .orElseThrow(() -> new IllegalStateException("fixture: no district " + districtId));
    }

    /** 给某人的钱包直接入账 (账本层, 不经 faucet)。 */
    public void fund(String name, long credit) {
        ledger.credit(uuidOf(name), Currency.CREDIT, credit);
    }

    public long balance(String name) {
        return ledger.balance(uuidOf(name), Currency.CREDIT);
    }

    /** 在测试库上执行一条语句 (故障注入用 TEMP 触发器等)。 */
    public void exec(String sql) {
        try (Statement statement = ledger.connection().createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("fixture statement failed: " + sql, exception);
        }
    }

    // ================================================================
    // 断言
    // ================================================================

    /** 执行 body, 断言它以指定码拒绝, 返回该拒绝 (没有拒绝或码不对时让 GameTest 失败)。 */
    public static DistrictRuleException expect(GameTestHelper helper, DistrictError code, String what, Runnable body) {
        try {
            body.run();
        } catch (DistrictRuleException rejection) {
            helper.assertTrue(rejection.code() == code,
                    what + ": 应报 " + code + ", 实得 " + rejection.code() + " (" + rejection.getMessage() + ")");
            return rejection;
        }
        helper.fail(what + ": 应报 " + code + ", 实际没有拒绝");
        throw new IllegalStateException("unreachable");
    }

    /**
     * 通知的"谁在线" (22.11): GameTest 服务端的玩家列表, {@link #onlinePlayer} 造的 mock 玩家就在里面; 没有服务器时谁都
     * 不在线。
     */
    @Nullable
    private static ServerPlayer onlineOnTestServer(UUID uuid) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null || server.getPlayerList() == null ? null : server.getPlayerList().getPlayer(uuid);
    }

    /** 造一个可被 PlayerList 接受的 mock 玩家 (买地要按在线玩家扣款)。调用方负责 {@link #removePlayer}。 */
    public static ServerPlayer onlinePlayer(GameTestHelper helper, String name) {
        return com.miningdim.testutil.MockGameTestPlayers.makeMockServerPlayerWithChannel(helper,
                new GameProfile(uuidOf(name), name));
    }

    public static void removePlayer(GameTestHelper helper, @Nullable ServerPlayer player) {
        if (player != null) {
            helper.getLevel().getServer().getPlayerList().remove(player);
        }
    }
}
