package com.miningdim.quest;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务进度持久层。挂在 overworld 的 DimensionDataStorage, 文件名 {@value #DATA_NAME}。
 *
 * <b>只持久化定义 id, 不持久化定义本身。</b> 目标、奖励、描述都从 {@link QuestPool} 反查 —— 存目标就意味着
 * 每次调平衡都要写存档迁移, 而任务内容是会频繁改的东西。代价是内容池删掉某条定义后, 老存档里对应的进度会
 * 被丢弃 (见 {@link #loadProgress}); 这是刻意取舍: 丢一条任务进度远好过让整块存档加载失败。
 *
 * 线程: 仅服务端主线程读写。任何写后必须 {@link #setDirty()}, 否则不落盘。
 * 1.20.1 的 {@code computeIfAbsent} 是三参签名 (load, create, name); {@code SavedData.Factory} 是 1.20.2+ 才有。
 */
public final class QuestSavedData extends SavedData {

    /** DimensionDataStorage 数据文件名。 */
    public static final String DATA_NAME = "miningdim_quest";

    private static final String K_BOARDS = "boards";
    private static final String K_UUID = "uuid";
    private static final String K_DAILY_STAMP = "dailyStamp";
    private static final String K_WEEKLY_STAMP = "weeklyStamp";
    private static final String K_DAILY = "daily";
    private static final String K_WEEKLY = "weekly";
    private static final String K_SPECIAL = "special";
    private static final String K_SPECIAL_OFFER = "lastSpecialOffer";
    private static final String K_CHAINS = "chains";
    private static final String K_CHAIN_ID = "chainId";
    private static final String K_STAGE = "stage";
    private static final String K_ID = "id";
    private static final String K_COUNT = "count";
    private static final String K_CLAIMED = "claimed";

    private final Map<UUID, QuestBoard> boards = new HashMap<>();

    public QuestSavedData() {
    }

    /**
     * 取/建 overworld 的任务持久数据。
     *
     * pool 经 lambda 捕获传进反序列化: {@link SavedData} 的 load 函数签名只吃 {@link CompoundTag}, 而反查定义
     * 必须有池子。捕获比静态字段干净 —— 后者会让"存档加载时池子还没初始化"变成一个隐式时序约束。
     */
    public static QuestSavedData get(ServerLevel overworld, QuestPool pool) {
        return overworld.getDataStorage().computeIfAbsent(
                tag -> load(tag, pool), QuestSavedData::new, DATA_NAME);
    }

    /** 取该玩家的任务板; 首次访问自动建一块空板 (不标脏 —— 空板没有需要落盘的内容)。 */
    public QuestBoard board(UUID playerId) {
        return boards.computeIfAbsent(playerId, key -> new QuestBoard());
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag boardList = new ListTag();
        for (Map.Entry<UUID, QuestBoard> entry : boards.entrySet()) {
            QuestBoard board = entry.getValue();
            CompoundTag boardTag = new CompoundTag();
            boardTag.putUUID(K_UUID, entry.getKey());
            boardTag.putLong(K_DAILY_STAMP, board.dailyStamp());
            boardTag.putLong(K_WEEKLY_STAMP, board.weeklyStamp());
            boardTag.putLong(K_SPECIAL_OFFER, board.lastSpecialOfferGameTime());
            boardTag.put(K_DAILY, saveProgresses(board.daily()));
            boardTag.put(K_WEEKLY, saveProgresses(board.weekly()));
            boardTag.put(K_SPECIAL, saveProgresses(board.special()));

            ListTag chainList = new ListTag();
            for (QuestChainState state : board.chains()) {
                CompoundTag chainTag = new CompoundTag();
                chainTag.putString(K_CHAIN_ID, state.chain().id());
                chainTag.putInt(K_STAGE, state.stageIndex());
                QuestProgress current = state.current();
                if (current != null) {
                    chainTag.putInt(K_COUNT, current.count());
                    chainTag.putBoolean(K_CLAIMED, current.claimed());
                }
                chainList.add(chainTag);
            }
            boardTag.put(K_CHAINS, chainList);
            boardList.add(boardTag);
        }
        tag.put(K_BOARDS, boardList);
        return tag;
    }

    private static ListTag saveProgresses(List<QuestProgress> progresses) {
        ListTag list = new ListTag();
        for (QuestProgress progress : progresses) {
            CompoundTag entry = new CompoundTag();
            entry.putString(K_ID, progress.definition().id());
            entry.putInt(K_COUNT, progress.count());
            entry.putBoolean(K_CLAIMED, progress.claimed());
            list.add(entry);
        }
        return list;
    }

    public static QuestSavedData load(CompoundTag tag, QuestPool pool) {
        QuestSavedData data = new QuestSavedData();
        ListTag boardList = tag.getList(K_BOARDS, Tag.TAG_COMPOUND);
        for (int i = 0; i < boardList.size(); i++) {
            CompoundTag boardTag = boardList.getCompound(i);
            if (!boardTag.hasUUID(K_UUID)) {
                continue;
            }
            QuestBoard board = new QuestBoard();
            board.restorePeriodic(
                    boardTag.getLong(K_DAILY_STAMP),
                    loadProgress(boardTag.getList(K_DAILY, Tag.TAG_COMPOUND), pool),
                    boardTag.getLong(K_WEEKLY_STAMP),
                    loadProgress(boardTag.getList(K_WEEKLY, Tag.TAG_COMPOUND), pool));
            board.restoreSpecial(
                    loadProgress(boardTag.getList(K_SPECIAL, Tag.TAG_COMPOUND), pool),
                    boardTag.getLong(K_SPECIAL_OFFER));

            ListTag chainList = boardTag.getList(K_CHAINS, Tag.TAG_COMPOUND);
            for (int c = 0; c < chainList.size(); c++) {
                CompoundTag chainTag = chainList.getCompound(c);
                QuestChain chain = pool.chain(chainTag.getString(K_CHAIN_ID));
                if (chain == null) {
                    continue; // 任务线已从内容池移除, 丢弃该玩家在其上的进度。
                }
                int stage = Math.max(0, Math.min(chainTag.getInt(K_STAGE), chain.stageCount()));
                QuestProgress current = stage < chain.stageCount()
                        ? new QuestProgress(chain.stages().get(stage),
                                chainTag.getInt(K_COUNT), chainTag.getBoolean(K_CLAIMED))
                        : null;
                board.restoreChain(new QuestChainState(chain, stage, current));
            }
            data.boards.put(boardTag.getUUID(K_UUID), board);
        }
        return data;
    }

    /**
     * 反序列化一组进度, 沿途丢弃已不在内容池里的 id。
     *
     * 丢弃而非抛异常: 内容池每次增删都会让老存档出现失配, 让整块存档加载失败等于一次内容更新就能锁死全服
     * 的任务数据。丢弃的后果只是该玩家下次翻日重新发牌。
     */
    private static List<QuestProgress> loadProgress(ListTag list, QuestPool pool) {
        List<QuestProgress> progresses = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            QuestDefinition definition = pool.byId(entry.getString(K_ID));
            if (definition == null) {
                continue;
            }
            progresses.add(new QuestProgress(definition, entry.getInt(K_COUNT), entry.getBoolean(K_CLAIMED)));
        }
        return progresses;
    }
}
