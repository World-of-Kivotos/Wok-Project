package com.miningdim.job.agent.panel;

import com.miningdim.job.agent.AgentRegistry;
import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 战术扫描面板容器菜单 (SpecialAgent_Job_DesignSpec 五章; 复用公共 {@link AbstractMiningMenu} 脚手架)。无方块 (远程
 * menu), 无容器槽 (纯展示 + 点击发封印 C2S 的情报面板), 经 {@link com.miningdim.menu.ModMenus#remoteMenuType} 注册。
 *
 * 数据流: 扫描快照 ({@link AgentScanSnapshot}) 不经本 menu 的 extraData 传 (扫描脉冲产出的快照体积可变, 且需在面板
 * 打开期间多次刷新), 而走独立 S2C ({@code AgentScanSyncS2C}) 推给 {@code ClientAgentScanState}; 本 menu 只承载界面
 * 打开/关闭生命周期。封印点击经 C2S ({@code AgentSealRequestC2S}) 而非 vanilla clickMenuButton (封印需回传目标网络 id
 * + 词条注册名两字段, clickMenuButton 仅一个 int, 不足以承载)。
 *
 * stillValid: 远程谓词为玩家存活 (面板无方块/距离约束; 目标精英离场由服务端扫描快照刷新反映, 不在 menu 有效性判)。
 */
public final class AgentScanMenu extends AbstractMiningMenu {

    public AgentScanMenu(int windowId, Inventory playerInv, FriendlyByteBuf extraData) {
        // 无容器槽 (containerSlotCount=0); 远程有效性谓词: 玩家存活即有效。
        super(AgentRegistry.SCAN_MENU.get(), windowId, 0, MenuValidity.ofRemote(Player::isAlive));
        // extraData 无附加字段 (Provider 不写; 快照走独立 S2C); 不读, 保持远程工厂签名一致。
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // 无容器槽, 无快速移动 (纯情报展示 + 点击发封印 C2S)。
        return ItemStack.EMPTY;
    }

    /** 服务端打开扫描面板的 MenuProvider (无 BlockPos; 远程 menu)。打开后另发 {@code AgentScanSyncS2C} 推快照。 */
    public static final class Provider implements MenuProvider {

        @Override
        public Component getDisplayName() {
            return Component.translatable("container.miningdim.agent_scan");
        }

        @Nullable
        @Override
        public AbstractContainerMenu createMenu(int windowId, Inventory playerInv, Player player) {
            // 服务端侧用空 buf 构造 (远程工厂在客户端读同一空 buf)。
            return new AgentScanMenu(windowId, playerInv,
                    new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer()));
        }
    }
}
