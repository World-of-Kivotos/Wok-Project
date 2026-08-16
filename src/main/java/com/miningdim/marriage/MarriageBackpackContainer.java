package com.miningdim.marriage;

import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 共享背包的服务端唯一权威 {@link Container} (结婚系统 spec 第四章防 dupe 核心)。直接以 {@link MarriageState#sharedInv}
 * 这个 {@link NonNullList} 为后备存储 —— 不复制、不镜像, 容器内容与权威落点是同一引用, 杜绝"两端各自对账"的 dupe 面。
 *
 * 防 dupe 纪律:
 *  - 同一 marriageId 的所有打开会话 (双方各开/同方多开) 由 {@link MarriageBackpackSessions} 复用同一个本容器实例,
 *    故所有窗口操作的是同一份 NonNullList; 配合 menu 取放经 {@code server.execute} 回主线程串行 (spec 第四章),
 *    并发同 slot 取放只可能出一份 (主线程顺序执行 setItem/removeItem, 无中间复制)。
 *  - 每次 {@link #setChanged()} 标脏 {@link MarriageRegistry} (内容是关系 SavedData 的一部分, 必须落盘)。
 *
 * 等级暴露: 容器物理大小恒为 {@link MarriageState#SHARED_INV_SIZE} (54), 不随等级变 (升级不丢物); 当前可见格数由
 * menu 据婚龄等级铺多少 Slot 决定, 本容器只负责存储与白名单, 不自裁可见性 (容量边界在 menu 层, 见 {@link MarriageBackpackMenu})。
 *
 * 槽级归属记账分工 (spec 第六章闸 2 清算依据 "谁放入谁取回"): 认领在 {@link MarriageBackpackMenu} 层做
 * (只有那里知道当前操作的是哪个玩家), 释放在本容器层做 (取出物品统一经 removeItem/removeItemNoUpdate/setItem,
 * 不管是否经过菜单的 Slot 写入路径都会落到这里, 归属清理不会漏)。
 */
public final class MarriageBackpackContainer implements Container {

    private final MarriageState state;
    private final MarriageRegistry registry;

    /**
     * @param state    本容器服务的婚姻关系 (内容落 state.sharedInv)
     * @param registry 关系注册表 (setChanged 时标脏, 保证共享背包内容随存档落盘)
     */
    public MarriageBackpackContainer(MarriageState state, MarriageRegistry registry) {
        if (state == null || registry == null) {
            throw new IllegalArgumentException("MarriageBackpackContainer requires non-null state and registry");
        }
        this.state = state;
        this.registry = registry;
    }

    /** 本容器服务的关系 id (会话复用/校验用)。 */
    public long marriageId() {
        return state.marriageId();
    }

    /** 认领某槽的归属 (仅菜单层的记账 Slot 调用, 见 {@link MarriageBackpackMenu})。 */
    public void claimSlot(int slot, java.util.UUID depositor) {
        state.claimSlot(slot, depositor);
        setChanged();
    }

    /** 该槽当前归属的玩家; 无归属返回 null (菜单层合并入栈归属闸判据, 见 {@link MarriageBackpackMenu})。 */
    public java.util.UUID depositorOf(int slot) {
        return state.depositorOf(slot);
    }

    private NonNullList<ItemStack> items() {
        return state.sharedInv();
    }

    @Override
    public int getContainerSize() {
        return MarriageState.SHARED_INV_SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items()) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return items().get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack removed = ContainerHelper.removeItem(items(), slot, amount);
        if (!removed.isEmpty()) {
            if (items().get(slot).isEmpty()) {
                state.releaseSlot(slot);
            }
            setChanged();
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack removed = ContainerHelper.takeItem(items(), slot);
        if (!removed.isEmpty()) {
            if (items().get(slot).isEmpty()) {
                state.releaseSlot(slot);
            }
            setChanged();
        }
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items().set(slot, stack);
        if (!stack.isEmpty() && stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
        if (items().get(slot).isEmpty()) {
            state.releaseSlot(slot);
        }
        setChanged();
    }

    @Override
    public void setChanged() {
        // 共享背包内容是关系 SavedData 的一部分: 任何写都须标脏 Registry 才会随存档落盘 (spec 第九章持久化纪律)。
        registry.setDirty();
    }

    @Override
    public boolean stillValid(Player player) {
        // 容器本身不裁有效性 (远程开/距离/配偶在线由 menu 的 MenuValidity.ofRemote 裁决, spec 第四章)。
        return true;
    }

    /**
     * 白名单闸 (spec 第四章): 服务端权威拒绝高级矿/皮肤凭证/绑定装备进入共享背包。原版 Slot.mayPlace 会调本法,
     * 故 shift 快移与拖放都被拦在容器边界, 客户端 menu 即便伪造也无法越过 (服务端容器层校验)。
     *
     * 离婚公示期冻结 (spec 第六章闸 2) 在白名单判据之前拦截: menu 层已经在开窗环节拒绝公示期内的开窗请求,
     * 这里是服务端兜底, 防已经开着的窗口 (提交离婚发生在配偶已开窗期间) 或伪造客户端继续塞东西。
     */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (state.hasPendingDivorce()) {
            return false;
        }
        return SharedBackpackWhitelist.isAllowed(stack);
    }

    @Override
    public void clearContent() {
        items().clear();
        state.clearAllSlotDepositors();
        setChanged();
    }
}
