package com.miningdim.caseopening;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.miningdim.caseopening.store.CaseDao;
import com.miningdim.caseopening.store.CaseOpeningRow;
import com.miningdim.caseopening.store.CaseOpeningStatus;
import com.miningdim.caseopening.store.SkinAssetRow;
import com.miningdim.webui.server.WebUiBusinessException;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Server-authoritative orchestrator for pre-roll, dual-currency Saga, durable ownership and TaCZ application. */
public final class CaseOpeningService {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/caseopening");

    public record Wallet(long credit, long azure) {
    }

    public record OpenResult(CaseOpeningRow opening, SkinAssetRow asset,
                             boolean replayed, List<CaseSkin> reel) {
    }

    public record ApplyResult(SkinAssetRow asset) {
    }

    private final CaseDao dao;
    private final CaseEconomyOperations economy;
    private final CaseRoller roller;
    private final BooleanSupplier enabled;
    private final BooleanSupplier taczLoaded;
    private final BooleanSupplier caseResourcesAvailable;
    private final LongSupplier creditCost;
    private final LongSupplier azureCost;
    private final Supplier<CaseWeights> weights;
    private final IntSupplier openCooldownTicks;
    private final Map<UUID, Long> lastNewOpenTick = new HashMap<>();
    /** A successful full reconciliation is needed only once per server process; a hard crash clears this set. */
    private final Set<UUID> recoveryAuditedPlayers = new HashSet<>();

    public CaseOpeningService(CaseDao dao, CaseEconomyOperations economy, CaseRoller roller,
                              BooleanSupplier enabled, BooleanSupplier taczLoaded,
                              BooleanSupplier caseResourcesAvailable,
                              LongSupplier creditCost, LongSupplier azureCost,
                              Supplier<CaseWeights> weights, IntSupplier openCooldownTicks) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.roller = Objects.requireNonNull(roller, "roller");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.taczLoaded = Objects.requireNonNull(taczLoaded, "taczLoaded");
        this.caseResourcesAvailable = Objects.requireNonNull(caseResourcesAvailable, "caseResourcesAvailable");
        this.creditCost = Objects.requireNonNull(creditCost, "creditCost");
        this.azureCost = Objects.requireNonNull(azureCost, "azureCost");
        this.weights = Objects.requireNonNull(weights, "weights");
        this.openCooldownTicks = Objects.requireNonNull(openCooldownTicks, "openCooldownTicks");
    }

    public boolean enabled() {
        return enabled.getAsBoolean() && integrationAvailable();
    }

    public long creditCost() {
        return creditCost.getAsLong();
    }

    public long azureCost() {
        return azureCost.getAsLong();
    }

    public CaseWeights weights() {
        return weights.get();
    }

    public Wallet wallet(ServerPlayer player) {
        return new Wallet(economy.creditBalance(player), economy.azureBalance(player));
    }

    public List<SkinAssetRow> ownedAssets(ServerPlayer player) {
        // A SQL asset whose SavedData debit vanished in a hard crash is quarantined until login recovery re-charges it.
        return dao.ownedAssets(player.getUUID()).stream()
                .filter(this::isEconomySettled)
                .toList();
    }

    /** Creates or resumes the durable opening identified by openingId. Safe to replay across reconnects/restarts. */
    public synchronized OpenResult open(ServerPlayer player, UUID openingId, String caseId) {
        if (!enabled.getAsBoolean()) {
            throw new WebUiBusinessException("CASE_DISABLED", "开箱系统当前已关闭", false);
        }
        if (!integrationAvailable()) {
            throw new WebUiBusinessException("CASE_DISABLED", "TaCZ 或武器箱资源包未就绪，开箱系统不可用", false);
        }
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(openingId, "openingId");
        CaseCatalog.requireCase(caseId);

        // 先对账再放行新开箱。recoveryAuditedPlayers 此前只是个备忘录: open 从不查它, 于是挂着未结清资产的
        // 玩家可以无限开新箱, 每一箱都在扩大不一致面。recoverFor 自身幂等 (已对账即刻返回), 失败会抛出,
        // 正好把这个玩家挡在门外直到人工或下次恢复把账理清。
        recoverFor(player);

        CaseOpeningRow row = dao.findOpening(openingId);
        boolean replayed = row != null;
        if (row == null) {
            long requiredCredit = requirePositive(creditCost(), "credit cost");
            long requiredAzure = requirePositive(azureCost(), "azure cost");
            // Account for every fresh opening id before looking at balances so zero-balance spam is throttled too.
            enforceNewOpenRateLimit(player);
            Wallet available = wallet(player);
            if (available.credit() < requiredCredit || available.azure() < requiredAzure) {
                throw new WebUiBusinessException("INSUFFICIENT_FUNDS", "余额不足：需要 " + requiredCredit
                        + " CREDIT 和 " + requiredAzure + " AZURE", false);
            }
            try {
                row = reserve(player, openingId, caseId, requiredCredit, requiredAzure);
            } catch (RuntimeException failure) {
                // INSERT may have succeeded before an I/O error made its outcome ambiguous.
                recoveryAuditedPlayers.remove(player.getUUID());
                throw failure;
            }
        }
        validateIdentity(row, player.getUUID(), caseId);
        try {
            SkinAssetRow asset = resume(player, row);
            CaseOpeningRow committed = requireOpening(openingId);
            return new OpenResult(committed, asset, replayed, decodeReel(committed.reelJson()));
        } catch (RuntimeException failure) {
            // A failed Saga attempt must make the next login perform a fresh full reconciliation.
            recoveryAuditedPlayers.remove(player.getUUID());
            throw failure;
        }
    }

    /** Completes every interrupted Saga for this player. Called at login before accepting fresh opens. */
    public synchronized int recoverFor(ServerPlayer player) {
        UUID ownerId = player.getUUID();
        if (recoveryAuditedPlayers.contains(ownerId)) {
            return 0;
        }
        int recovered = 0;
        RuntimeException firstFailure = null;
        for (CaseOpeningRow row : dao.recoverableOpenings(ownerId)) {
            try {
                if (row.status() == CaseOpeningStatus.REFUNDED) {
                    if (reconcileRefunded(row)) {
                        recovered++;
                    }
                    continue;
                }
                boolean alreadySettled = row.status() == CaseOpeningStatus.COMMITTED
                        && economy.state(row.ownerId(), row.openingId()) == CaseEconomyOperations.State.COMPLETED;
                resume(player, row);
                if (!alreadySettled) {
                    recovered++;
                }
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        recoveryAuditedPlayers.add(ownerId);
        return recovered;
    }

    public ApplyResult apply(ServerPlayer player, UUID assetId) {
        if (!integrationAvailable()) {
            throw new WebUiBusinessException("TACZ_UNAVAILABLE", "TaCZ 或武器箱资源包未就绪，无法应用枪械皮肤", false);
        }
        SkinAssetRow asset = dao.findOwnedAsset(player.getUUID(), assetId);
        if (asset == null) {
            throw new WebUiBusinessException("ASSET_NOT_OWNED", "你不拥有该皮肤资产: " + assetId, false);
        }
        if (!isEconomySettled(asset)) {
            CaseOpeningRow source = requireOpening(asset.sourceOpeningId());
            resume(player, source);
            if (!isEconomySettled(asset)) {
                throw new IllegalStateException("该皮肤资产仍在账本恢复中: " + assetId);
            }
        }
        CaseTaczBridge.apply(player, asset);
        return new ApplyResult(asset);
    }

    /** Strips a case display when the held gun's persisted asset no longer belongs to the holder. */
    public boolean enforceMainHand(ServerPlayer player) {
        return enforceGunStack(player, player.getMainHandItem());
    }

    /** TaCZ event boundary: validates the exact gun stack involved in draw/fire/shoot. */
    boolean enforceGunStack(ServerPlayer player, ItemStack stack) {
        return taczLoaded.getAsBoolean()
                && CaseTaczBridge.enforce(player, stack, dao, this::isEconomySettled);
    }

    private boolean integrationAvailable() {
        return taczLoaded.getAsBoolean() && caseResourcesAvailable.getAsBoolean();
    }

    private CaseOpeningRow reserve(ServerPlayer player, UUID openingId, String caseId,
                                   long requiredCredit, long requiredAzure) {
        CaseWeights table = weights();
        CaseSkin result = roller.roll(table);
        CaseRoller.Reel reel = roller.buildReel(table, result);
        long now = System.currentTimeMillis();
        CaseOpeningRow proposed = new CaseOpeningRow(
                openingId, player.getUUID(), caseId,
                requiredCredit, requiredAzure,
                CaseOpeningStatus.RESERVED,
                UUID.randomUUID(), result.skinId(), result.rarity(),
                result.gunId().toString(), result.displayId().toString(),
                encodeReel(reel.entries()), reel.stopIndex(), now, now);
        return dao.reserve(proposed);
    }

    private SkinAssetRow resume(ServerPlayer player, CaseOpeningRow initial) {
        CaseOpeningRow row = requireOpening(initial.openingId());
        validateIdentity(row, player.getUUID(), row.caseId());
        if (row.status() == CaseOpeningStatus.REFUNDED) {
            reconcileRefunded(row);
            throw new WebUiBusinessException("OPENING_REFUNDED", "该开箱事务已退款，请使用新的 openingId", false);
        }
        if (row.status() == CaseOpeningStatus.COMMITTED) {
            SkinAssetRow existing = requireAsset(row.assetId());
            // 补扣款与推进终态同事务: 扣了钱却没推进到 COMPLETED, 这条记录下次登录还会再被补扣一次。
            return economy.inTransaction(() -> {
                // 无条件 charge, 不以 state 短路。charge 对同域同玩家同金额的既有记录本就幂等 (返回已持久化
                // 状态且不重复扣款), 而元组不符时会抛 OPERATION_CONFLICT。先查 state 再决定是否扣款, 等于把
                // 账本的全元组校验挡在门外: 只要账本里存在该玩家任意一条记录, 扣款就被整段跳过。
                if (!economy.charge(player, row.openingId(), row.creditCost(), row.azureCost())) {
                    throw new IllegalStateException("已提交皮肤的货币账本需要恢复，但当前余额不足: " + row.openingId());
                }
                CaseEconomyOperations.State finalized = economy.complete(row.ownerId(), row.openingId());
                if (finalized != CaseEconomyOperations.State.COMPLETED) {
                    throw new IllegalStateException("开箱账本已提交但货币操作不是 COMPLETED: "
                            + row.openingId() + " -> " + finalized);
                }
                return existing;
            });
        }

        CaseEconomyOperations.State moneyState = economy.state(row.ownerId(), row.openingId());
        if (moneyState == CaseEconomyOperations.State.REFUNDED) {
            dao.markRefunded(row.openingId(), System.currentTimeMillis());
            throw new WebUiBusinessException("OPENING_REFUNDED", "该开箱事务已退款，请使用新的 openingId", false);
        }

        // 扣钱与发资产落在同一个事务里。钱与开箱库合库后这才成为可能, 而这正是规格要求的"扣钥匙 + 扣箱子 +
        // 发皮肤 必须是单个原子事务"。崩溃在提交前两边都回滚, 提交后两边都在, 白嫖窗口从结构上消失。
        try {
            return economy.inTransaction(() -> {
                // 除已退款这一终态外一律无条件 charge, 由账本的全元组校验做闸门, 而不是先查 state 再决定。
                if (!economy.charge(player, row.openingId(), row.creditCost(), row.azureCost())) {
                    // 余额不足是正常拒绝而非故障: 抛出让事务回滚 (此路径本就没产生任何副作用), 由外层把
                    // 开箱行标记为 REFUNDED —— 该标记必须在事务【之外】, 否则会被这次回滚一并撤销。
                    throw new InsufficientFunds();
                }
                if (!dao.markDebited(row.openingId(), System.currentTimeMillis())) {
                    throw new IllegalStateException("无法推进开箱事务到 DEBITED: " + row.openingId());
                }
                SkinAssetRow asset = new SkinAssetRow(
                        row.assetId(), row.ownerId(), row.skinId(), row.rarity(), row.gunId(), row.displayId(),
                        row.openingId(), row.createdAt(), 0L);
                SkinAssetRow committed = dao.commitOpening(row.openingId(), asset, System.currentTimeMillis());
                CaseEconomyOperations.State finalState = economy.complete(row.ownerId(), row.openingId());
                if (finalState != CaseEconomyOperations.State.COMPLETED) {
                    throw new IllegalStateException("货币操作无法完成: " + row.openingId() + " -> " + finalState);
                }
                return committed;
            });
        } catch (InsufficientFunds insufficient) {
            dao.markRefunded(row.openingId(), System.currentTimeMillis());
            throw new IllegalStateException("余额不足：需要 " + row.creditCost()
                    + " CREDIT 与 " + row.azureCost() + " AZURE");
        } catch (RuntimeException failure) {
            // 事务已回滚, 正常情况下钱与资产都没动、行仍是 RESERVED。仍走对账是为了覆盖提交结果未知的
            // 那一类失败 (连接在 commit 期间断开), 此时只能重新读库确认, 不能凭猜测退款。
            return reconcileFailure(row, failure);
        }
    }

    /** 余额不足的内部信号: 只用于把事务从内层拉回外层做补偿标记, 不外泄。 */
    private static final class InsufficientFunds extends RuntimeException {
        InsufficientFunds() {
            super(null, null, false, false);
        }
    }

    private SkinAssetRow reconcileFailure(CaseOpeningRow row, RuntimeException failure) {
        final CaseOpeningRow after;
        try {
            after = dao.findOpening(row.openingId());
        } catch (RuntimeException ambiguousStoreFailure) {
            failure.addSuppressed(ambiguousStoreFailure);
            // SQL commit outcome is unknown. Keep the durable debit for login recovery; refunding here could mint a free asset.
            throw failure;
        }
        if (after != null && after.status() == CaseOpeningStatus.COMMITTED) {
            SkinAssetRow asset = requireAsset(after.assetId());
            CaseEconomyOperations.State state = economy.complete(after.ownerId(), after.openingId());
            if (state == CaseEconomyOperations.State.COMPLETED) {
                return asset;
            }
            throw failure;
        }

        CaseEconomyOperations.State state = economy.state(row.ownerId(), row.openingId());
        if (state == CaseEconomyOperations.State.DEBITED) {
            CaseEconomyOperations.State refunded = economy.refund(row.ownerId(), row.openingId());
            if (refunded == CaseEconomyOperations.State.REFUNDED) {
                dao.markRefunded(row.openingId(), System.currentTimeMillis());
            }
        } else if (state == CaseEconomyOperations.State.REFUNDED) {
            dao.markRefunded(row.openingId(), System.currentTimeMillis());
        }
        throw failure;
    }

    /** Reconciles the crash window where SQL reached REFUNDED before the economy refund was committed. */
    private boolean reconcileRefunded(CaseOpeningRow row) {
        CaseEconomyOperations.State state = economy.state(row.ownerId(), row.openingId());
        if (state == CaseEconomyOperations.State.DEBITED) {
            CaseEconomyOperations.State refunded = economy.refund(row.ownerId(), row.openingId());
            if (refunded != CaseEconomyOperations.State.REFUNDED) {
                throw new IllegalStateException("退款账本无法完成: " + row.openingId() + " -> " + refunded);
            }
            return true;
        }
        if (state == CaseEconomyOperations.State.NONE || state == CaseEconomyOperations.State.REFUNDED) {
            return false;
        }
        // SQL 说已退款、账本说已完成: 两边互相矛盾且都不可信, 自动选一边都可能凭空造钱或吞钱。
        // 落成隔离终态并告警, 而不是抛异常 —— 靠抛异常表达"已隔离"会让该玩家每次登录都抛, 且抛出点之后的
        // 后续恢复与 enforceMainHand 被整段跳过, 一行坏数据就瘫掉这个玩家的整条恢复链路。
        dao.markQuarantined(row.openingId(), System.currentTimeMillis());
        LOGGER.error("[miningdim] 开箱事务已隔离, 需人工核对: opening={} owner={} 账本状态={}",
                row.openingId(), row.ownerId(), state);
        return false;
    }

    /**
     * 启动期全量对账 (跨玩家)。
     *
     * 登录驱动的恢复捞不到从此不再上线的玩家: 他们的 RESERVED/DEBITED 行会永久悬挂。本方法在服务端启动时
     * 把全服待对账行过一遍, 只做不需要玩家在场的处置 —— 钱已扣的退款、已提交但账本停在已扣款的推进终态、
     * 钱从未动的作废。唯一留给登录的是"资产已发但账本查无此笔"的补扣款, 那一步需要真实玩家对象。
     *
     * @return 实际处置的行数
     */
    public synchronized int reconcileAtStartup() {
        int handled = 0;
        for (CaseOpeningRow row : dao.allRecoverableOpenings()) {
            CaseEconomyOperations.State state = economy.state(row.ownerId(), row.openingId());
            switch (row.status()) {
                case REFUNDED -> {
                    if (reconcileRefunded(row)) {
                        handled++;
                    }
                }
                case RESERVED, DEBITED -> handled += reconcileUnfinished(row, state);
                case COMMITTED -> handled += reconcileCommitted(row, state);
                default -> {
                    // QUARANTINED 不会出现在待对账集合里; 此分支只是让枚举扩展时编译期暴露遗漏。
                }
            }
        }
        return handled;
    }

    /** RESERVED/DEBITED: 资产从未发出, 因此只需要让钱回到玩家手里并作废这一行。 */
    private int reconcileUnfinished(CaseOpeningRow row, CaseEconomyOperations.State state) {
        if (state == CaseEconomyOperations.State.DEBITED) {
            if (economy.refund(row.ownerId(), row.openingId()) != CaseEconomyOperations.State.REFUNDED) {
                dao.markQuarantined(row.openingId(), System.currentTimeMillis());
                LOGGER.error("[miningdim] 未完成开箱退款失败, 已隔离: opening={} owner={}",
                        row.openingId(), row.ownerId());
                return 0;
            }
            dao.markRefunded(row.openingId(), System.currentTimeMillis());
            return 1;
        }
        if (state == CaseEconomyOperations.State.NONE) {
            // 钱一分没动 (单事务下崩溃只会停在这里), 直接作废该行, 玩家用新 openingId 重开即可。
            dao.markRefunded(row.openingId(), System.currentTimeMillis());
            return 1;
        }
        // COMPLETED / REFUNDED 与"未完成"互相矛盾: 无法自动判定, 隔离。
        dao.markQuarantined(row.openingId(), System.currentTimeMillis());
        LOGGER.error("[miningdim] 未完成开箱的账本状态自相矛盾, 已隔离: opening={} owner={} 账本状态={}",
                row.openingId(), row.ownerId(), state);
        return 0;
    }

    /** COMMITTED: 资产已发。钱已扣就只差推进终态; 账本查无此笔则必须补扣款, 那一步要玩家在场, 留给登录。 */
    private int reconcileCommitted(CaseOpeningRow row, CaseEconomyOperations.State state) {
        if (state == CaseEconomyOperations.State.DEBITED) {
            if (economy.complete(row.ownerId(), row.openingId()) != CaseEconomyOperations.State.COMPLETED) {
                dao.markQuarantined(row.openingId(), System.currentTimeMillis());
                LOGGER.error("[miningdim] 已提交开箱无法推进终态, 已隔离: opening={} owner={}",
                        row.openingId(), row.ownerId());
                return 0;
            }
            return 1;
        }
        if (state == CaseEconomyOperations.State.REFUNDED) {
            dao.markQuarantined(row.openingId(), System.currentTimeMillis());
            LOGGER.error("[miningdim] 已提交开箱的账本却是已退款, 已隔离: opening={} owner={}",
                    row.openingId(), row.ownerId());
            return 0;
        }
        // COMPLETED 无需处置; NONE 需要补扣款, 由登录恢复处理 (ownedAssets 已把它挡在可用资产之外)。
        return 0;
    }

    private void enforceNewOpenRateLimit(ServerPlayer player) {
        int cooldown = openCooldownTicks.getAsInt();
        if (cooldown <= 0) {
            throw new IllegalStateException("open cooldown ticks must be positive, got " + cooldown);
        }
        long now = player.server.getTickCount();
        Long previous = lastNewOpenTick.get(player.getUUID());
        if (previous != null && now >= previous && now - previous < cooldown) {
            throw new WebUiBusinessException("RATE_LIMITED", "开箱请求过快，请稍后再试", false);
        }
        lastNewOpenTick.put(player.getUUID(), now);
    }

    private CaseOpeningRow requireOpening(UUID openingId) {
        CaseOpeningRow row = dao.findOpening(openingId);
        if (row == null) {
            throw new IllegalStateException("missing case opening " + openingId);
        }
        return row;
    }

    private SkinAssetRow requireAsset(UUID assetId) {
        SkinAssetRow asset = dao.findAsset(assetId);
        if (asset == null) {
            throw new IllegalStateException("missing skin asset " + assetId);
        }
        return asset;
    }

    private boolean isEconomySettled(SkinAssetRow asset) {
        return economy.state(asset.ownerId(), asset.sourceOpeningId()) == CaseEconomyOperations.State.COMPLETED;
    }

    private static void validateIdentity(CaseOpeningRow row, UUID ownerId, String caseId) {
        if (!row.ownerId().equals(ownerId) || !row.caseId().equals(caseId)) {
            throw new WebUiBusinessException("OPENING_ID_CONFLICT",
                    "openingId already belongs to a different player or case: " + row.openingId(), false);
        }
    }

    private static long requirePositive(long value, String label) {
        if (value <= 0L) {
            throw new IllegalStateException(label + " must be positive, got " + value);
        }
        return value;
    }

    private static String encodeReel(List<CaseSkin> reel) {
        JsonArray array = new JsonArray();
        for (CaseSkin skin : reel) {
            array.add(skin.skinId());
        }
        return array.toString();
    }

    private static List<CaseSkin> decodeReel(String json) {
        JsonArray array = JsonParser.parseString(json).getAsJsonArray();
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(element -> CaseCatalog.requireSkin(element.getAsString()))
                .toList();
    }
}
