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
 * 共享背包等级/传送等级不在本类存储: 二者是婚龄的纯函数, 由 {@link MarriageTuning} 按 {@link #marriedSinceTick}
 * 现算, 不落盘也不缓存 (原先的 sharedInvLevel/teleportLevel 派生字段全库零写入方, 属于"存档里躺着自洽但错误的
 * 数据", 已删除)。离婚次数由 {@link MarriageHistory} 按玩家 (而非本关系) 持有, 因为再婚冷却要跨越关系解除后
 * 依然生效; 本类不再重复存一份关系内计数 (原 divorceCount/lastWeddingTick 同理零写入方, 已删除)。
 *
 * 线程纪律: 仅服务端主线程读写 (典礼/离婚/共享背包取放均经 server.execute 串行回主线程, spec 第四章)。
 * marriageId / partnerA / partnerB / marriedSinceTick 创建后不可变 (final); 其余运行态可变。
 */
public final class MarriageState {

    /** 持久自增主键, 全 mod 唯一, 不复用 (仿 instanceId)。 */
    private final long marriageId;

    /** 配偶 A (典礼时的发起方; 与 B 业务对等)。 */
    private final UUID partnerA;

    /** 配偶 B。 */
    private final UUID partnerB;

    /** 典礼完成时的 overworld game time (婚龄起算点; spec 第四/五章婚龄阶梯解锁基准)。婚龄真源, 不可变。 */
    private final long marriedSinceTick;

    /**
     * 共享背包内容唯一权威 (spec 第四章防 dupe: 服务端唯一权威容器, NBT 编解码)。阶段 1 先建容器但保持空,
     * 阶段 2 共享背包 menu 落地时按婚龄现算等级决定可用格数并读写。容量取最高 5 级满格, 低级 menu 只暴露
     * 前若干格 (容器恒定大小, 等级只控暴露面, 避免降级丢物)。
     */
    private final NonNullList<ItemStack> sharedInv;

    /**
     * 每个共享背包槽的归属玩家 (谁把该槽从空变成非空即归谁; spec 第六章闸 2 清算依据 "谁放入谁取回")。
     * 与 sharedInv 一一对应, 无归属的槽 (旧存档残留 / 非菜单写入路径) 为 null, 离婚清算时按槽号奇偶平分。
     * 认领记账在 {@link MarriageBackpackMenu} (只有那里知道操作者是谁), 释放记账在
     * {@link MarriageBackpackContainer} (取出物品不经过菜单的 Slot 写入路径)。
     */
    private final UUID[] slotDepositors = new UUID[SHARED_INV_SIZE];

    /**
     * 已领取的一次性里程碑去重集合 (spec 第六章: "双方 UUID 对 + 里程碑" 为键, 换 marriageId 也不重发同一里程碑)。
     * 本集合按里程碑 id 存; 跨 marriageId 的 UUID 对去重由 {@link MarriageRegistry} 在重建里程碑历史时合并 (阶段 5)。
     */
    private final Set<String> claimedMilestones = new HashSet<>();

    /**
     * 待生效离婚发起方; null 表示当前无待生效离婚 (spec 第六章闸 2 escrow 公示期)。这三个字段有真实写入方
     * ({@link MarriageDivorce#file} 写入, {@link MarriageDivorce#cancel}/结算清空), 不是派生态, 与本类文档开头
     * 说明"不重复存派生数据"的原则不冲突。
     */
    private UUID pendingDivorceInitiator;

    /** 提交离婚时的 overworld game time (公示期到期时刻 = 该值 + {@link MarriageTuning#divorceEscrowTicks()})。 */
    private long pendingDivorceFiledTick;

    /** 提交离婚时实际扣除的信用点 (撤回时按此值全额退回, 与提交时的定价脱钩, 防中途改配置导致退款对不上)。 */
    private long pendingDivorceCost;

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

    /** 共享背包内容唯一权威视图 (阶段 2 共享背包 menu 读写; 阶段 1 恒空)。 */
    public NonNullList<ItemStack> sharedInv() {
        return sharedInv;
    }

    /** 该槽当前归属的玩家; 无归属 (含旧存档残留 / 非菜单写入路径) 返回 null。 */
    public UUID depositorOf(int slot) {
        return slotDepositors[slot];
    }

    /**
     * 认领某槽的归属。只在该槽当前无归属时写入, 已有归属不覆盖 —— 归属属于把该槽从空变非空的那个人,
     * 若允许覆盖, 配偶只需往对方已占用的整摞物品上合并一个同种物品, 就能把整槽据为己有。
     */
    public void claimSlot(int slot, UUID depositor) {
        if (slotDepositors[slot] == null) {
            slotDepositors[slot] = depositor;
        }
    }

    /** 释放某槽的归属 (槽变空时调用, 见 {@link MarriageBackpackContainer})。 */
    public void releaseSlot(int slot) {
        slotDepositors[slot] = null;
    }

    /** 清空全部槽归属 (容器 clearContent 时调用)。 */
    public void clearAllSlotDepositors() {
        java.util.Arrays.fill(slotDepositors, null);
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

    // ---- 待生效离婚 (escrow 公示期; spec 第六章闸 2) ----

    /** 当前是否存在待生效离婚。 */
    public boolean hasPendingDivorce() {
        return pendingDivorceInitiator != null;
    }

    /** 待生效离婚的发起方; 无 pending 时为 null。 */
    public UUID pendingDivorceInitiator() {
        return pendingDivorceInitiator;
    }

    /** 提交离婚时的 game time; 无 pending 时未定义 (调用方须先 hasPendingDivorce)。 */
    public long pendingDivorceFiledTick() {
        return pendingDivorceFiledTick;
    }

    /** 提交离婚时实际扣除的信用点 (撤回全额退款依据); 无 pending 时未定义。 */
    public long pendingDivorceCost() {
        return pendingDivorceCost;
    }

    /**
     * 开启公示期。initiator 必须属于本关系, 否则抛 (异常必痛, 不静默接受非本关系玩家的离婚意图)。
     * 已存在 pending 时再调也抛 —— 调用方 (MarriageDivorce.file) 必须先用 hasPendingDivorce 判断,
     * 已 pending 的重复提交走"不二次扣费"分支, 不会走到这里。
     */
    public void beginPendingDivorce(UUID initiator, long filedTick, long cost) {
        if (!involves(initiator)) {
            throw new IllegalArgumentException("initiator " + initiator + " is not part of marriage " + marriageId);
        }
        if (hasPendingDivorce()) {
            throw new IllegalStateException("marriage " + marriageId + " already has a pending divorce");
        }
        this.pendingDivorceInitiator = initiator;
        this.pendingDivorceFiledTick = filedTick;
        this.pendingDivorceCost = cost;
    }

    /** 清空待生效离婚 (撤回或结算生效后调用)。 */
    public void clearPendingDivorce() {
        this.pendingDivorceInitiator = null;
        this.pendingDivorceFiledTick = 0L;
        this.pendingDivorceCost = 0L;
    }

    // ---- 持久化 (供 MarriageRegistry; 仿 InstanceState.save/load) ----

    private static final String K_ID = "marriageId";
    private static final String K_A_MOST = "partnerAMost";
    private static final String K_A_LEAST = "partnerALeast";
    private static final String K_B_MOST = "partnerBMost";
    private static final String K_B_LEAST = "partnerBLeast";
    private static final String K_MARRIED_SINCE = "marriedSinceTick";
    private static final String K_SHARED_INV = "sharedInv";
    private static final String K_INV_SLOT = "Slot";
    private static final String K_DEPOSITOR_MOST = "DepositorMost";
    private static final String K_DEPOSITOR_LEAST = "DepositorLeast";
    private static final String K_MILESTONES = "claimedMilestones";
    private static final String K_PENDING_DIVORCE_INITIATOR_MOST = "pendingDivorceInitiatorMost";
    private static final String K_PENDING_DIVORCE_INITIATOR_LEAST = "pendingDivorceInitiatorLeast";
    private static final String K_PENDING_DIVORCE_FILED_TICK = "pendingDivorceFiledTick";
    private static final String K_PENDING_DIVORCE_COST = "pendingDivorceCost";

    /**
     * 序列化为 CompoundTag。共享背包 ListTag 写法仿 {@link com.miningdim.core.InstanceState} 的 players 列表:
     * 逐非空槽写 {Slot:byte, ItemStack..., [DepositorMost/DepositorLeast]} (空槽不写, 加载按 Slot 索引还原;
     * 归属只在该槽有归属时写, 与"空槽不写物品"同口径)。里程碑集合存为字符串 ListTag。待生效离婚三字段只在
     * 存在 pending 时写。
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong(K_ID, marriageId);
        tag.putLong(K_A_MOST, partnerA.getMostSignificantBits());
        tag.putLong(K_A_LEAST, partnerA.getLeastSignificantBits());
        tag.putLong(K_B_MOST, partnerB.getMostSignificantBits());
        tag.putLong(K_B_LEAST, partnerB.getLeastSignificantBits());
        tag.putLong(K_MARRIED_SINCE, marriedSinceTick);

        ListTag inv = new ListTag();
        for (int slot = 0; slot < sharedInv.size(); slot++) {
            ItemStack stack = sharedInv.get(slot);
            if (!stack.isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putByte(K_INV_SLOT, (byte) slot);
                stack.save(slotTag);
                UUID depositor = slotDepositors[slot];
                if (depositor != null) {
                    slotTag.putLong(K_DEPOSITOR_MOST, depositor.getMostSignificantBits());
                    slotTag.putLong(K_DEPOSITOR_LEAST, depositor.getLeastSignificantBits());
                }
                inv.add(slotTag);
            }
        }
        tag.put(K_SHARED_INV, inv);

        ListTag milestones = new ListTag();
        for (String id : claimedMilestones) {
            milestones.add(net.minecraft.nbt.StringTag.valueOf(id));
        }
        tag.put(K_MILESTONES, milestones);

        if (hasPendingDivorce()) {
            tag.putLong(K_PENDING_DIVORCE_INITIATOR_MOST, pendingDivorceInitiator.getMostSignificantBits());
            tag.putLong(K_PENDING_DIVORCE_INITIATOR_LEAST, pendingDivorceInitiator.getLeastSignificantBits());
            tag.putLong(K_PENDING_DIVORCE_FILED_TICK, pendingDivorceFiledTick);
            tag.putLong(K_PENDING_DIVORCE_COST, pendingDivorceCost);
        }
        return tag;
    }

    /** 从 CompoundTag 还原 (MarriageRegistry.load 逐条调用)。 */
    public static MarriageState load(CompoundTag tag) {
        long id = tag.getLong(K_ID);
        UUID a = new UUID(tag.getLong(K_A_MOST), tag.getLong(K_A_LEAST));
        UUID b = new UUID(tag.getLong(K_B_MOST), tag.getLong(K_B_LEAST));
        MarriageState st = new MarriageState(id, a, b, tag.getLong(K_MARRIED_SINCE));

        ListTag inv = tag.getList(K_SHARED_INV, Tag.TAG_COMPOUND);
        for (int i = 0; i < inv.size(); i++) {
            CompoundTag slotTag = inv.getCompound(i);
            int slot = slotTag.getByte(K_INV_SLOT) & 0xFF;
            if (slot >= 0 && slot < st.sharedInv.size()) {
                st.sharedInv.set(slot, ItemStack.of(slotTag));
                if (slotTag.contains(K_DEPOSITOR_MOST) && slotTag.contains(K_DEPOSITOR_LEAST)) {
                    st.slotDepositors[slot] = new UUID(slotTag.getLong(K_DEPOSITOR_MOST), slotTag.getLong(K_DEPOSITOR_LEAST));
                }
            }
        }

        ListTag milestones = tag.getList(K_MILESTONES, Tag.TAG_STRING);
        for (int i = 0; i < milestones.size(); i++) {
            st.claimedMilestones.add(milestones.getString(i));
        }

        if (tag.contains(K_PENDING_DIVORCE_INITIATOR_MOST) && tag.contains(K_PENDING_DIVORCE_INITIATOR_LEAST)) {
            st.pendingDivorceInitiator = new UUID(
                    tag.getLong(K_PENDING_DIVORCE_INITIATOR_MOST), tag.getLong(K_PENDING_DIVORCE_INITIATOR_LEAST));
            st.pendingDivorceFiledTick = tag.getLong(K_PENDING_DIVORCE_FILED_TICK);
            st.pendingDivorceCost = tag.getLong(K_PENDING_DIVORCE_COST);
        }
        return st;
    }
}
