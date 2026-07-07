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
     * 服务端构造 (由 {@link Provider} 调)。绑真权威容器 + 婚龄等级派生可见格数 + 远程有效性谓词。
     *
     * @param windowId      窗口 id
     * @param playerInv     打开者背包
     * @param container     该关系的唯一权威共享容器
     * @param visibleSlots  本次暴露的格数 (婚龄等级派生)
     * @param sessions      会话登记表 (关窗注销打开者)
     * @param validity      远程有效性谓词 (配偶在线 + 同维度 + 距离上限)
     */
    public MarriageBackpackMenu(int windowId, Inventory playerInv, MarriageBackpackContainer container,
                                int visibleSlots, MarriageBackpackSessions sessions, MenuValidity validity) {
        super(MarriageRegistration.BACKPACK_MENU.get(), windowId, visibleSlots, validity);
        this.marriageId = container.marriageId();
        this.sessions = sessions;
        addBackpackSlots(container, visibleSlots);
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
        SimpleContainer view = new SimpleContainer(Math.max(visibleSlots, 1));
        addBackpackSlots(view, visibleSlots);
        addPlayerInventory(playerInv, 8, playerInvOriginY(visibleSlots));
    }

    /** 铺容器槽 (前 visibleSlots 格; 每格带白名单 mayPlace)。客户端用占位容器 view 时 mayPlace 同样生效但无碍 (服务端权威)。 */
    private void addBackpackSlots(Container container, int visibleSlots) {
        for (int i = 0; i < visibleSlots; i++) {
            int row = i / COLS;
            int col = i % COLS;
            int x = CONTAINER_ORIGIN_X + col * SLOT_PX;
            int y = CONTAINER_ORIGIN_Y + row * SLOT_PX;
            this.addSlot(new Slot(container, i, x, y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    // 服务端权威白名单 (容器 canPlaceItem); 高级矿/皮肤/绑定装备拒入 (spec 第四章)。
                    return container.canPlaceItem(this.getSlotIndex(), stack);
                }
            });
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
            return new MarriageBackpackMenu(windowId, playerInv, container, visibleSlots, sessions, validity);
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
