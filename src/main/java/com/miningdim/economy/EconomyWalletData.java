package com.miningdim.economy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 合库之前的全服双货币账本 (矿山维度 SavedData, 数据文件名 {@value #DATA_NAME}), 现在只读。
 *
 * 账本已迁往统一 SQLite ({@link SqliteEconomyLedger}), 本类唯一的职责是把旧存档里的余额、双币操作与
 * 每日计数读出来交给 {@link EconomyLedgerBootstrap} 搬迁一次。之所以还留着而不是删掉: 真服存档里存在
 * 这份数据, 删掉读取路径等于让老存档的钱凭空消失。
 *
 * 只读不写: 本类不再提供任何改余额的入口, 也永远不调 {@link #setDirty()}, 因此 Minecraft 不会再落盘
 * 覆盖这个文件 —— 迁移完成后它原样留在存档里作回滚保险。{@link #save} 仍完整实现是为了万一被写回时
 * 不损坏该文件, 而不是给谁调用的。
 *
 * 1.20.1 的 computeIfAbsent 为三参 (load, create, name); SavedData.Factory 是 1.20.2+ 才引入, 本目标版本不可用。
 */
public final class EconomyWalletData extends SavedData {

    /** DimensionDataStorage 数据文件名。 */
    public static final String DATA_NAME = "miningdim_economy";

    private static final String K_WALLETS = "wallets";
    private static final String K_UUID = "uuid";
    private static final String K_WALLET = "wallet";

    private static final String K_BUNDLE_OPERATIONS = "bundleOperations";
    private static final String K_OPERATION_ID = "operationId";
    private static final String K_PLAYER_ID = "playerId";
    private static final String K_CREDIT_AMOUNT = "creditAmount";
    private static final String K_AZURE_AMOUNT = "azureAmount";
    private static final String K_OPERATION_STATUS = "status";
    private static final String K_OPERATION_DOMAIN = "domain";

    private static final String K_DAILY = "dailyCharges";
    private static final String K_DAILY_KEY = "key";
    private static final String K_DAILY_AMOUNT = "amount";
    private static final String K_DAILY_STAMP = "dayStamp";

    /** faucet 条目的衰减主闸小数余量 carry (仅 faucet 侧用, 扣费侧无此键)。 */
    private static final String K_FAUCET_CARRY = "creditCarry";

    private static final String K_FAUCET = "dailyFaucets";

    /** 每日计数键的拼接格式为 "玩家UUID|计数键"; UUID 文本恒为 36 字符, 故分隔符位置固定。 */
    private static final int UUID_TEXT_LENGTH = 36;

    private final Map<UUID, PlayerWallet> wallets = new HashMap<>();
    private final List<LegacyOperation> operations = new ArrayList<>();
    private final List<LegacyDailyCounter> dailyCharges = new ArrayList<>();
    private final List<LegacyDailyCounter> dailyFaucets = new ArrayList<>();

    /** 旧存档里的一笔双币幂等操作。 */
    public record LegacyOperation(UUID operationId, EconomyOperationDomain domain, UUID playerId,
                                  long creditAmount, long azureAmount, EconomyOperationStatus status) {
    }

    /** 旧存档里的一条每日计数 (扣费侧 creditCarry 恒为 0)。 */
    public record LegacyDailyCounter(UUID playerId, String counterKey, long amount, long dayStamp,
                                     double creditCarry) {
    }

    public EconomyWalletData() {
    }

    /** 取矿山维度上的旧账本 (不存在时得到一个空账本, 等价于"这个世界从未有过旧数据")。 */
    public static EconomyWalletData get(ServerLevel miningLevel) {
        return miningLevel.getDataStorage().computeIfAbsent(
                EconomyWalletData::load, EconomyWalletData::new, DATA_NAME);
    }

    /** 旧存档中的全部钱包 (玩家 UUID -> 余额)。 */
    public Map<UUID, PlayerWallet> wallets() {
        return Collections.unmodifiableMap(wallets);
    }

    /** 旧存档中的全部双币幂等操作。 */
    public List<LegacyOperation> operations() {
        return Collections.unmodifiableList(operations);
    }

    /** 旧存档中的扣费侧每日计数。 */
    public List<LegacyDailyCounter> dailyCharges() {
        return Collections.unmodifiableList(dailyCharges);
    }

    /** 旧存档中的 faucet 侧每日计数 (含小数余量 carry)。 */
    public List<LegacyDailyCounter> dailyFaucets() {
        return Collections.unmodifiableList(dailyFaucets);
    }

    /** 旧存档是否完全没有经济数据 (三类记录皆空)。 */
    public boolean isEmpty() {
        return wallets.isEmpty() && operations.isEmpty()
                && dailyCharges.isEmpty() && dailyFaucets.isEmpty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, PlayerWallet> e : wallets.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(K_UUID, e.getKey());
            entry.put(K_WALLET, e.getValue().save());
            list.add(entry);
        }
        tag.put(K_WALLETS, list);

        ListTag operationTags = new ListTag();
        for (LegacyOperation operation : operations) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(K_OPERATION_ID, operation.operationId());
            entry.putString(K_OPERATION_DOMAIN, operation.domain().id());
            entry.putUUID(K_PLAYER_ID, operation.playerId());
            entry.putLong(K_CREDIT_AMOUNT, operation.creditAmount());
            entry.putLong(K_AZURE_AMOUNT, operation.azureAmount());
            entry.putString(K_OPERATION_STATUS, operation.status().name());
            operationTags.add(entry);
        }
        tag.put(K_BUNDLE_OPERATIONS, operationTags);

        ListTag daily = new ListTag();
        for (LegacyDailyCounter counter : dailyCharges) {
            CompoundTag entry = new CompoundTag();
            entry.putString(K_DAILY_KEY, counter.playerId() + "|" + counter.counterKey());
            entry.putLong(K_DAILY_AMOUNT, counter.amount());
            entry.putLong(K_DAILY_STAMP, counter.dayStamp());
            daily.add(entry);
        }
        tag.put(K_DAILY, daily);

        ListTag faucets = new ListTag();
        for (LegacyDailyCounter counter : dailyFaucets) {
            CompoundTag entry = new CompoundTag();
            entry.putString(K_DAILY_KEY, counter.playerId() + "|" + counter.counterKey());
            entry.putLong(K_DAILY_AMOUNT, counter.amount());
            entry.putLong(K_DAILY_STAMP, counter.dayStamp());
            entry.putDouble(K_FAUCET_CARRY, counter.creditCarry());
            faucets.add(entry);
        }
        tag.put(K_FAUCET, faucets);
        return tag;
    }

    public static EconomyWalletData load(CompoundTag tag) {
        EconomyWalletData data = new EconomyWalletData();
        ListTag list = tag.getList(K_WALLETS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.hasUUID(K_UUID)) {
                data.wallets.put(entry.getUUID(K_UUID), PlayerWallet.load(entry.getCompound(K_WALLET)));
            }
        }
        ListTag operations = tag.getList(K_BUNDLE_OPERATIONS, Tag.TAG_COMPOUND);
        for (int i = 0; i < operations.size(); i++) {
            CompoundTag entry = operations.getCompound(i);
            if (!entry.hasUUID(K_OPERATION_ID) || !entry.hasUUID(K_PLAYER_ID)) {
                throw new IllegalArgumentException("Malformed persisted bundle operation UUIDs");
            }
            UUID operationId = entry.getUUID(K_OPERATION_ID);
            // 域是必填项, 不设缺省回退: 该机制随开箱系统一同首次落地, 存档中不存在无域的历史记录。
            // 若真读到缺域条目, 说明存档被外部改写或来自不兼容版本, 必须让它冒泡而不是猜一个域 ——
            // 猜错会让该条记录变成任意业务都能复用的免费付款凭据。
            String domainId = entry.getString(K_OPERATION_DOMAIN);
            if (domainId.isEmpty()) {
                throw new IllegalArgumentException(
                        "Persisted bundle operation is missing its domain: " + operationId);
            }
            data.operations.add(new LegacyOperation(
                    operationId,
                    EconomyOperationDomain.valueOf(domainId),
                    entry.getUUID(K_PLAYER_ID),
                    entry.getLong(K_CREDIT_AMOUNT),
                    entry.getLong(K_AZURE_AMOUNT),
                    EconomyOperationStatus.valueOf(entry.getString(K_OPERATION_STATUS))));
        }
        ListTag daily = tag.getList(K_DAILY, Tag.TAG_COMPOUND);
        for (int i = 0; i < daily.size(); i++) {
            CompoundTag entry = daily.getCompound(i);
            data.dailyCharges.add(counter(entry.getString(K_DAILY_KEY),
                    entry.getLong(K_DAILY_AMOUNT), entry.getLong(K_DAILY_STAMP), 0.0D));
        }
        ListTag faucets = tag.getList(K_FAUCET, Tag.TAG_COMPOUND);
        for (int i = 0; i < faucets.size(); i++) {
            CompoundTag entry = faucets.getCompound(i);
            // K_FAUCET_CARRY 缺失 (更早的存档) 时 getDouble 返回 0.0, 向后兼容无 carry 的历史条目。
            data.dailyFaucets.add(counter(entry.getString(K_DAILY_KEY),
                    entry.getLong(K_DAILY_AMOUNT), entry.getLong(K_DAILY_STAMP),
                    entry.getDouble(K_FAUCET_CARRY)));
        }
        return data;
    }

    /** 拆开 "玩家UUID|计数键"。格式不符说明存档被外部改写, 猜一个玩家等于把别人的额度记到他头上。 */
    private static LegacyDailyCounter counter(String composedKey, long amount, long dayStamp, double carry) {
        if (composedKey.length() <= UUID_TEXT_LENGTH || composedKey.charAt(UUID_TEXT_LENGTH) != '|') {
            throw new IllegalArgumentException("Malformed persisted daily counter key: " + composedKey);
        }
        UUID playerId = UUID.fromString(composedKey.substring(0, UUID_TEXT_LENGTH));
        return new LegacyDailyCounter(playerId, composedKey.substring(UUID_TEXT_LENGTH + 1),
                amount, dayStamp, carry);
    }
}
