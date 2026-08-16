package com.miningdim.marriage;

import com.miningdim.config.MiningServerConfig;
import com.miningdim.core.MiningConstants;
import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 共享背包容器菜单 (结婚系统 spec 第四章; 复用 {@link AbstractMiningMenu} 脚手架, 远程 menu 经
 * {@link com.miningdim.menu.ModMenus#remoteMenuType} 注册)。蹲下右键结婚戒指远程开 (无方块), 双方可同开。
 *
 * 服务端权威 (防 dupe 红线):
 *  - 服务端构造把容器槽绑到 {@link MarriageBackpackContainer} (同一 marriageId 全窗口共享的唯一权威容器);
 *    可见格数 = 婚龄等级派生 ({@link MarriageTuning#backpackVisibleSlots}), 只铺前 N 槽 (容器恒 54, 等级控暴露)。
 *  - 每个容器槽用带白名单的 Slot ({@link Slot#mayPlace} -> 容器 canPlaceItem -> {@link SharedBackpackWhitelist}),
 *    高级矿/皮肤/绑定装备放不进 (服务端拦, 客户端伪造也越不过)。
 *  - {@link MenuValidity#ofRemote}: 配偶在线 + 同维度 + 距离上限内才保持打开; 任一不满足原版自动关闭 (spec 第四章
 *    "任一方登出/掉线强制关闭"的稳态兜底, 登出瞬时关闭由 {@link MarriageBackpackSessions#forceCloseAll} 主动做)。
 *
 * 客户端构造 (远程工厂在客户端调): 读 extraData 的可见格数, 用等大小 {@link SimpleContainer} 占位铺同样多槽
 * (纯视图; 客户端只渲染, 变更回服务端校验后由原版 menu 同步广播给另一端窗口, 严禁两端各自对账)。
 */
public final class MarriageBackpackMenu extends AbstractMiningMenu {

    /** 容器槽区左上角像素 (玩家背包在其下方)。 */
    private static final int CONTAINER_ORIGIN_X = 8;
    private static final int CONTAINER_ORIGIN_Y = 18;
    private static final int SLOT_PX = 18;
    private static final int COLS = 9;

    /** 本菜单服务的关系 id (会话注销/校验用; 客户端侧也持有以供 stillValid 与诊断)。 */
    private final long marriageId;

    /** 服务端会话登记表 (关窗注销; 客户端构造为 null)。 */
    @Nullable
    private final MarriageBackpackSessions sessions;

    /**
     * 打开这个窗口的玩家 (合并入栈归属闸判据, 见 {@link #moveItemStackTo}); 客户端纯视图构造无该概念, 为 null
     * (客户端槽不是 {@link DepositAccountingSlot}, 闸判据用不到, 留 null 不影响行为)。
     */
    @Nullable
    private final UUID viewerId;

    /**
     * 服务端构造 (由 {@link Provider} 调)。绑真权威容器 + 婚龄等级派生可见格数 + 远程有效性谓词 + 槽级归属记账。
     *
     * @param windowId      窗口 id
     * @param playerInv     打开者背包
     * @param container     该关系的唯一权威共享容器
     * @param visibleSlots  本次暴露的格数 (婚龄等级派生)
     * @param sessions      会话登记表 (关窗注销打开者)
     * @param validity      远程有效性谓词 (配偶在线 + 同维度 + 距离上限)
     * @param viewerId      打开这个窗口的玩家 (槽级归属记账用; spec 第六章闸 2 清算依据 "谁放入谁取回")
     */
    public MarriageBackpackMenu(int windowId, Inventory playerInv, MarriageBackpackContainer container,
                                int visibleSlots, MarriageBackpackSessions sessions, MenuValidity validity,
                                UUID viewerId) {
        super(MarriageRegistration.BACKPACK_MENU.get(), windowId, visibleSlots, validity);
        this.marriageId = container.marriageId();
        this.sessions = sessions;
        this.viewerId = viewerId;
        addAuthoritativeBackpackSlots(container, visibleSlots, viewerId);
        addPlayerInventory(playerInv, 8, playerInvOriginY(visibleSlots));
    }

    /**
     * 客户端构造 (远程工厂; extraData = marriageId(long) + visibleSlots(varInt))。纯视图: 占位容器 + 同样多槽 +
     * 远程有效性恒真 (客户端不裁有效性, 由服务端关窗)。
     */
    public MarriageBackpackMenu(int windowId, Inventory playerInv, FriendlyByteBuf extraData) {
        this(windowId, playerInv, extraData.readLong(), extraData.readVarInt());
    }

    private MarriageBackpackMenu(int windowId, Inventory playerInv, long marriageId, int visibleSlots) {
        super(MarriageRegistration.BACKPACK_MENU.get(), windowId, visibleSlots, MenuValidity.ofRemote(Player::isAlive));
        this.marriageId = marriageId;
        this.sessions = null;
        this.viewerId = null;
        SimpleContainer view = new SimpleContainer(Math.max(visibleSlots, 1));
        addViewOnlyBackpackSlots(view, visibleSlots);
        addPlayerInventory(playerInv, 8, playerInvOriginY(visibleSlots));
    }

    /** 槽位坐标 (前 visibleSlots 格标准网格布局); 服务端记账槽与客户端纯视图槽共用同一份坐标算式。 */
    private static int slotX(int index) {
        return CONTAINER_ORIGIN_X + (index % COLS) * SLOT_PX;
    }

    private static int slotY(int index) {
        return CONTAINER_ORIGIN_Y + (index / COLS) * SLOT_PX;
    }

    /**
     * 服务端铺容器槽: 带白名单 mayPlace + 槽级归属记账 (set 覆写, 见 {@link DepositAccountingSlot})。
     * viewerId 是打开此窗口的玩家, 认领记账只发生在这条路径 (服务端唯一权威)。
     */
    private void addAuthoritativeBackpackSlots(MarriageBackpackContainer container, int visibleSlots, UUID viewerId) {
        for (int i = 0; i < visibleSlots; i++) {
            this.addSlot(new DepositAccountingSlot(container, i, slotX(i), slotY(i), viewerId));
        }
    }

    /** 客户端铺纯视图槽 (占位容器 + 白名单 mayPlace 供本地预判; 不做归属记账, 变更以服务端广播为准)。 */
    private void addViewOnlyBackpackSlots(Container container, int visibleSlots) {
        for (int i = 0; i < visibleSlots; i++) {
            int x = slotX(i);
            int y = slotY(i);
            this.addSlot(new Slot(container, i, x, y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    // 服务端权威白名单 (容器 canPlaceItem); 高级矿/皮肤/绑定装备拒入 (spec 第四章)。
                    return container.canPlaceItem(this.getSlotIndex(), stack);
                }
            });
        }
    }

    /**
     * 服务端记账槽: 在原有白名单 mayPlace 之外覆写 {@link Slot#set}, 把"该槽从空变非空"这一刻的操作者记为归属
     * (spec 第六章闸 2 清算依据 "谁放入谁取回")。{@link Slot#setByPlayer} 直接委派 {@code set(ItemStack)}, 而
     * {@link AbstractContainerMenu} 的 doClick 与 moveItemStackTo 的全部放入路径都经 setByPlayer, 故覆写此一处
     * 即覆盖点击/拖拽/Shift 快移; 取出走 container.removeItem, 归属释放已在容器层做 (见 MarriageBackpackContainer)。
     */
    private static final class DepositAccountingSlot extends Slot {

        private final MarriageBackpackContainer container;
        private final UUID viewerId;

        private DepositAccountingSlot(MarriageBackpackContainer container, int slot, int x, int y, UUID viewerId) {
            super(container, slot, x, y);
            this.container = container;
            this.viewerId = viewerId;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            // 服务端权威白名单 (容器 canPlaceItem); 高级矿/皮肤/绑定装备拒入 (spec 第四章)。
            return container.canPlaceItem(this.getSlotIndex(), stack);
        }

        @Override
        public void set(ItemStack stack) {
            boolean wasEmpty = this.getItem().isEmpty();
            super.set(stack);
            if (wasEmpty && !stack.isEmpty()) {
                container.claimSlot(this.getSlotIndex(), viewerId);
            }
        }

        /** 该槽当前归属 (合并入栈归属闸判据, 见 {@link MarriageBackpackMenu#moveItemStackTo}); 无归属为 null。 */
        UUID depositor() {
            return container.depositorOf(this.getSlotIndex());
        }
    }

    /** 玩家背包区左上 y: 容器槽行数下方留 14px 间隙 (标准箱式布局)。 */
    private static int playerInvOriginY(int visibleSlots) {
        int rows = Math.max(1, (visibleSlots + COLS - 1) / COLS);
        return CONTAINER_ORIGIN_Y + rows * SLOT_PX + 14;
    }

    public long marriageId() {
        return marriageId;
    }

    /**
     * 覆写 vanilla {@link AbstractContainerMenu#moveItemStackTo} (基类 {@link com.miningdim.menu.AbstractMiningMenu#quickMoveStack}
     * 的 Shift 快移唯一调用点)。逐字复刻 vanilla 算法 (已按本项目锁定的 forge-1.20.1-47.3.0 源核实), 唯一改动是
     * "合并入已有同种栈"这一阶段追加归属闸: vanilla 该阶段只判 {@code ItemStack.isSameItemSameTags}, 直接
     * {@code itemstack.setCount(j); slot.setChanged();}, 完全不经 {@link Slot#mayPlace}/{@link Slot#set}, 是
     * 槽级归属记账 (spec 第六章闸 2/3 "谁放入谁取回") 唯一漏记的路径 —— 配偶 Shift 把同种物品并入对方已占用的
     * 槽, 归属仍停留在原持有者, 离婚清算按槽归属结算时会把对方并入的部分错判给原持有者, 且可被刻意占坑放大。
     * 归属不属于本 viewer 的 {@link DepositAccountingSlot} 在合并阶段被跳过, 剩余数量落到第二阶段"找空槽"逻辑,
     * 正常经 {@link DepositAccountingSlot#set} 记为本 viewer 新认领的槽。
     */
    @Override
    protected boolean moveItemStackTo(ItemStack stack, int startIndex, int endIndex, boolean reverseDirection) {
        boolean moved = false;
        int i = reverseDirection ? endIndex - 1 : startIndex;

        if (stack.isStackable()) {
            while (!stack.isEmpty()) {
                if (reverseDirection ? i < startIndex : i >= endIndex) {
                    break;
                }
                Slot slot = this.slots.get(i);
                ItemStack slotStack = slot.getItem();
                if (!slotStack.isEmpty() && ItemStack.isSameItemSameTags(stack, slotStack) && mayMergeInto(slot)) {
                    int sum = slotStack.getCount() + stack.getCount();
                    int maxSize = Math.min(slot.getMaxStackSize(), stack.getMaxStackSize());
                    if (sum <= maxSize) {
                        stack.setCount(0);
                        slotStack.setCount(sum);
                        slot.setChanged();
                        moved = true;
                    } else if (slotStack.getCount() < maxSize) {
                        stack.shrink(maxSize - slotStack.getCount());
                        slotStack.setCount(maxSize);
                        slot.setChanged();
                        moved = true;
                    }
                }
                i += reverseDirection ? -1 : 1;
            }
        }

        if (!stack.isEmpty()) {
            i = reverseDirection ? endIndex - 1 : startIndex;
            while (reverseDirection ? i >= startIndex : i < endIndex) {
                Slot slot = this.slots.get(i);
                if (slot.getItem().isEmpty() && slot.mayPlace(stack)) {
                    int amount = Math.min(stack.getCount(), slot.getMaxStackSize());
                    slot.setByPlayer(stack.split(amount));
                    slot.setChanged();
                    moved = true;
                    break;
                }
                i += reverseDirection ? -1 : 1;
            }
        }

        return moved;
    }

    /** 合并阶段的归属闸: 目标是本菜单记账槽且已有归属、归属又不是本 viewer 时拒绝合并。 */
    private boolean mayMergeInto(Slot slot) {
        if (!(slot instanceof DepositAccountingSlot accounted)) {
            return true;
        }
        UUID depositor = accounted.depositor();
        return depositor == null || depositor.equals(viewerId);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // 服务端关窗: 注销打开者 (掉线强制关闭与正常关闭都汇于此, 经 closeContainer 触发)。
        if (sessions != null && player instanceof ServerPlayer serverPlayer) {
            sessions.onClosed(marriageId, serverPlayer);
        }
    }

    /**
     * 服务端打开共享背包的 MenuProvider (无 BlockPos; 远程 menu)。绑权威容器 + 婚龄等级派生可见格数 +
     * 远程有效性谓词 (配偶在线 + 同维度 + 距离上限)。
     */
    public static final class Provider implements MenuProvider {

        private final MarriageState state;
        private final MarriageRegistry registry;
        private final MarriageBackpackSessions sessions;
        private final ServerLevel overworld;
        private final int visibleSlots;

        public Provider(MarriageState state, MarriageRegistry registry, MarriageBackpackSessions sessions,
                        ServerLevel overworld) {
            this.state = state;
            this.registry = registry;
            this.sessions = sessions;
            this.overworld = overworld;
            long now = overworld.getGameTime();
            int level = MarriageTuning.backpackLevel(state.marriedSinceTick(), now);
            this.visibleSlots = MarriageTuning.backpackVisibleSlots(level);
        }

        public int visibleSlots() {
            return visibleSlots;
        }

        public long marriageId() {
            return state.marriageId();
        }

        /** extraData 写入: marriageId(long) + visibleSlots(varInt), 与客户端远程工厂解码一一对应。 */
        public void writeExtra(FriendlyByteBuf buf) {
            buf.writeLong(state.marriageId());
            buf.writeVarInt(visibleSlots);
        }

        @Override
        public Component getDisplayName() {
            return Component.translatable("container.miningdim.marriage_backpack");
        }

        @Nullable
        @Override
        public AbstractContainerMenu createMenu(int windowId, Inventory playerInv, Player player) {
            MarriageBackpackContainer container = sessions.containerFor(state, registry);
            MenuValidity validity = spouseProximityValidity(player.getUUID());
            return new MarriageBackpackMenu(windowId, playerInv, container, visibleSlots, sessions, validity,
                    player.getUUID());
        }

        /**
         * 远程有效性谓词 (spec 第四章): 该 viewer 的配偶仍在线 且 与 viewer 同维度 且 在距离上限内, 才保持打开。
         * 配偶离线/换维度/拉远即 false -> 原版自动关闭 (掉线瞬时关闭由 forceCloseAll 主动做, 本谓词是稳态兜底)。
         */
        private MenuValidity spouseProximityValidity(UUID viewerId) {
            int rangeBlocks = MiningServerConfig.MARRIAGE_BACKPACK_OPEN_RANGE.get();
            double rangeSqr = (double) rangeBlocks * rangeBlocks;
            UUID spouseId = state.spouseOf(viewerId);
            return MenuValidity.ofRemote(viewer -> {
                ServerPlayer spouse = overworld.getServer().getPlayerList().getPlayer(spouseId);
                if (spouse == null) {
                    return false;
                }
                if (spouse.level() != viewer.level()) {
                    return false;
                }
                return viewer.distanceToSqr(spouse) <= rangeSqr;
            });
        }
    }

    /** 客户端背景贴图 (像素留 runClient; 与 agent 面板同 placeholder 路线)。 */
    public static final net.minecraft.resources.ResourceLocation BG =
            new net.minecraft.resources.ResourceLocation(MiningConstants.MODID, "textures/gui/container/marriage_backpack.png");
}
