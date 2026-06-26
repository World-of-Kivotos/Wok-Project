package com.miningdim.marriage;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 单段婚姻关系的权威数据载体 (结婚系统 spec 第九章; 仿 {@link com.miningdim.core.InstanceState})。
 * {@link MarriageRegistry} 单例持有, 其持久副本随 overworld 存档落盘。
 *
 * 双人关系 (两 UUID 绑定): partnerA / partnerB 无序对 (谁先 propose 谁是 A, 业务上对等)。共享背包内容是
 * 本系统唯一权威落点 (spec 第四章防 dupe), 阶段 1 先建 {@link NonNullList} 容器但保持空, 阶段 2 接共享背包 menu
 * 时才读写。里程碑领取记录 ({@link #claimedMilestones}) 用 "双方 UUID 对 + 里程碑" 去重 (spec 第六章: 换
 * marriageId 不重发同一里程碑), 故以字符串 id 集合形式持久化。
 *
 * 线程纪律: 仅服务端主线程读写 (典礼/离婚/共享背包取放均经 server.execute 串行回主线程, spec 第四章)。
 * marriageId / partnerA / partnerB 创建后不可变 (final); 其余运行态可变。
 */
public final class MarriageState {

    /** 持久自增主键, 全 mod 唯一, 不复用 (仿 instanceId)。 */
    private final long marriageId;

    /** 配偶 A (典礼时的发起方; 与 B 业务对等)。 */
    private final UUID partnerA;

    /** 配偶 B。 */
    private final UUID partnerB;

    /** 典礼完成时的 overworld game time (婚龄起算点; spec 第四/五章婚龄阶梯解锁基准)。 */
    private long marriedSinceTick;

    /** 共享背包当前解锁等级 1..5 (按婚龄阶梯; spec 第四章, 阶段 2 用)。 */
    private int sharedInvLevel;

    /** 传送到伴侣当前解锁等级 1..5 (按婚龄阶梯; spec 第五章, 阶段 4 用)。 */
    private int teleportLevel;

    /** 该关系累计离婚次数 (再婚冷却递增基数; spec 第六章, 阶段 5 用)。本段关系内随结离刷取递增。 */
    private int divorceCount;

    /** 最近一次典礼的 game time (再婚冷却 untilTick 计算基准; spec 第六章, 阶段 5 用)。 */
    private long lastWeddingTick;

    /**
     * 共享背包内容唯一权威 (spec 第四章防 dupe: 服务端唯一权威容器, NBT 编解码)。阶段 1 先建容器但保持空,
     * 阶段 2 共享背包 menu 落地时按 sharedInvLevel 决定可用格数并读写。容量取最高 5 级满格, 低级 menu 只暴露
     * 前若干格 (容器恒定大小, 等级只控暴露面, 避免降级丢物)。
     */
    private final NonNullList<ItemStack> sharedInv;

    /**
     * 已领取的一次性里程碑去重集合 (spec 第六章: "双方 UUID 对 + 里程碑" 为键, 换 marriageId 也不重发同一里程碑)。
     * 本集合按里程碑 id 存; 跨 marriageId 的 UUID 对去重由 {@link MarriageRegistry} 在重建里程碑历史时合并 (阶段 5)。
     */
    private final Set<String> claimedMilestones = new HashSet<>();

    /** 共享背包容器固定大小 (最高 5 级满格; 阶段 2 按等级暴露子集)。 */
    public static final int SHARED_INV_SIZE = 54;

    public MarriageState(long marriageId, UUID partnerA, UUID partnerB, long marriedSinceTick) {
        if (partnerA == null || partnerB == null) {
            throw new IllegalArgumentException("marriage partners must not be null");
        }
        if (partnerA.equals(partnerB)) {
            throw new IllegalArgumentException("a player cannot marry themselves: " + partnerA);
        }
        this.marriageId = marriageId;
        this.partnerA = partnerA;
        this.partnerB = partnerB;
        this.marriedSinceTick = marriedSinceTick;
        this.lastWeddingTick = marriedSinceTick;
        this.sharedInvLevel = 1;
        this.teleportLevel = 1;
        this.divorceCount = 0;
        this.sharedInv = NonNullList.withSize(SHARED_INV_SIZE, ItemStack.EMPTY);
    }

    // ---- 不可变字段 ----

    public long marriageId() {
        return marriageId;
    }

    public UUID partnerA() {
        return partnerA;
    }

    public UUID partnerB() {
        return partnerB;
    }

    /** 该关系是否包含此玩家 (forPlayer 反查与离婚清算用)。 */
    public boolean involves(UUID player) {
        return partnerA.equals(player) || partnerB.equals(player);
    }

    /** 取此玩家的配偶 UUID; 玩家不在本关系中抛 (调用方须先 involves 校验)。 */
    public UUID spouseOf(UUID player) {
        if (partnerA.equals(player)) {
            return partnerB;
        }
        if (partnerB.equals(player)) {
            return partnerA;
        }
        throw new IllegalArgumentException("player " + player + " is not part of marriage " + marriageId);
    }

    // ---- 可变运行态 ----

    public long marriedSinceTick() {
        return marriedSinceTick;
    }

    public void setMarriedSinceTick(long tick) {
        this.marriedSinceTick = tick;
    }

    public int sharedInvLevel() {
        return sharedInvLevel;
    }

    public void setSharedInvLevel(int level) {
        this.sharedInvLevel = level;
    }

    public int teleportLevel() {
        return teleportLevel;
    }

    public void setTeleportLevel(int level) {
        this.teleportLevel = level;
    }

    public int divorceCount() {
        return divorceCount;
    }

    public void setDivorceCount(int count) {
        this.divorceCount = count;
    }

    public long lastWeddingTick() {
        return lastWeddingTick;
    }

    public void setLastWeddingTick(long tick) {
        this.lastWeddingTick = tick;
    }

    /** 共享背包内容唯一权威视图 (阶段 2 共享背包 menu 读写; 阶段 1 恒空)。 */
    public NonNullList<ItemStack> sharedInv() {
        return sharedInv;
    }

    /** 该里程碑是否已领取 (一次性福利去重; spec 第六章)。 */
    public boolean hasClaimedMilestone(String milestoneId) {
        return claimedMilestones.contains(milestoneId);
    }

    /** 标记里程碑已领取; 返回是否为首次领取 (false 表示已领过, 调用方据此不重发)。 */
    public boolean claimMilestone(String milestoneId) {
        return claimedMilestones.add(milestoneId);
    }

    /** 已领里程碑只读集合 (离婚转历史表时合并到 UUID 对历史用)。 */
    public Set<String> claimedMilestones() {
        return claimedMilestones;
    }

    // ---- 持久化 (供 MarriageRegistry; 仿 InstanceState.save/load) ----

    private static final String K_ID = "marriageId";
    private static final String K_A_MOST = "partnerAMost";
    private static final String K_A_LEAST = "partnerALeast";
    private static final String K_B_MOST = "partnerBMost";
    private static final String K_B_LEAST = "partnerBLeast";
    private static final String K_MARRIED_SINCE = "marriedSinceTick";
    private static final String K_SHARED_LEVEL = "sharedInvLevel";
    private static final String K_TP_LEVEL = "teleportLevel";
    private static final String K_DIVORCE_COUNT = "divorceCount";
    private static final String K_LAST_WEDDING = "lastWeddingTick";
    private static final String K_SHARED_INV = "sharedInv";
    private static final String K_INV_SLOT = "Slot";
    private static final String K_MILESTONES = "claimedMilestones";

    /**
     * 序列化为 CompoundTag。共享背包 ListTag 写法仿 {@link com.miningdim.core.InstanceState} 的 players 列表:
     * 逐非空槽写 {Slot:byte, ItemStack...} (空槽不写, 加载按 Slot 索引还原)。里程碑集合存为字符串 ListTag。
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong(K_ID, marriageId);
        tag.putLong(K_A_MOST, partnerA.getMostSignificantBits());
        tag.putLong(K_A_LEAST, partnerA.getLeastSignificantBits());
        tag.putLong(K_B_MOST, partnerB.getMostSignificantBits());
        tag.putLong(K_B_LEAST, partnerB.getLeastSignificantBits());
        tag.putLong(K_MARRIED_SINCE, marriedSinceTick);
        tag.putInt(K_SHARED_LEVEL, sharedInvLevel);
        tag.putInt(K_TP_LEVEL, teleportLevel);
        tag.putInt(K_DIVORCE_COUNT, divorceCount);
        tag.putLong(K_LAST_WEDDING, lastWeddingTick);

        ListTag inv = new ListTag();
        for (int slot = 0; slot < sharedInv.size(); slot++) {
            ItemStack stack = sharedInv.get(slot);
            if (!stack.isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putByte(K_INV_SLOT, (byte) slot);
                stack.save(slotTag);
                inv.add(slotTag);
            }
        }
        tag.put(K_SHARED_INV, inv);

        ListTag milestones = new ListTag();
        for (String id : claimedMilestones) {
            milestones.add(net.minecraft.nbt.StringTag.valueOf(id));
        }
        tag.put(K_MILESTONES, milestones);
        return tag;
    }

    /** 从 CompoundTag 还原 (MarriageRegistry.load 逐条调用)。 */
    public static MarriageState load(CompoundTag tag) {
        long id = tag.getLong(K_ID);
        UUID a = new UUID(tag.getLong(K_A_MOST), tag.getLong(K_A_LEAST));
        UUID b = new UUID(tag.getLong(K_B_MOST), tag.getLong(K_B_LEAST));
        MarriageState st = new MarriageState(id, a, b, tag.getLong(K_MARRIED_SINCE));
        st.sharedInvLevel = tag.getInt(K_SHARED_LEVEL);
        st.teleportLevel = tag.getInt(K_TP_LEVEL);
        st.divorceCount = tag.getInt(K_DIVORCE_COUNT);
        st.lastWeddingTick = tag.getLong(K_LAST_WEDDING);

        ListTag inv = tag.getList(K_SHARED_INV, Tag.TAG_COMPOUND);
        for (int i = 0; i < inv.size(); i++) {
            CompoundTag slotTag = inv.getCompound(i);
            int slot = slotTag.getByte(K_INV_SLOT) & 0xFF;
            if (slot >= 0 && slot < st.sharedInv.size()) {
                st.sharedInv.set(slot, ItemStack.of(slotTag));
            }
        }

        ListTag milestones = tag.getList(K_MILESTONES, Tag.TAG_STRING);
        for (int i = 0; i < milestones.size(); i++) {
            st.claimedMilestones.add(milestones.getString(i));
        }
        return st;
    }
}
