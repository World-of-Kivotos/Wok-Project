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
        }

        CaseEconomyOperations.State moneyState = economy.state(row.ownerId(), row.openingId());
        if (moneyState == CaseEconomyOperations.State.REFUNDED) {
            dao.markRefunded(row.openingId(), System.currentTimeMillis());
            throw new WebUiBusinessException("OPENING_REFUNDED", "该开箱事务已退款，请使用新的 openingId", false);
        }
        // 同上: 除已退款这一终态外一律无条件 charge, 由账本的全元组校验做闸门, 而不是先查 state 再决定。
        if (!economy.charge(player, row.openingId(), row.creditCost(), row.azureCost())) {
            dao.markRefunded(row.openingId(), System.currentTimeMillis());
            throw new IllegalStateException("余额不足：需要 " + row.creditCost()
                    + " CREDIT 与 " + row.azureCost() + " AZURE");
        }

        try {
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
        } catch (RuntimeException failure) {
            return reconcileFailure(row, failure);
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

    /** Reconciles the crash window where SQL reached REFUNDED before the SavedData refund was saved. */
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
        throw new IllegalStateException("SQL 已退款但货币账本已完成，事务已隔离: "
                + row.openingId() + " -> " + state);
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
