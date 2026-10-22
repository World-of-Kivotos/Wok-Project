package com.miningdim.job.farmer.granary;

import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 把一个囤连里面的东西搬进囤主的待领粮单 (Spoilage_Granary_DesignSpec「退出、拆走、移出」「有货的囤被移除」)。
 *
 * 三条路都走这里, 免得各写一份搬运:
 * <ul>
 *   <li><b>被移出 / 自己退出</b> (命令): 东西连同囤一起进对方的单子, 方块从世界里拿掉, 额度归还;</li>
 *   <li><b>房主拆本房间未入册的空囤</b>: 囤进放置者的单子 (房主拿不走);</li>
 *   <li><b>非玩家路径</b> (指令 / 爆炸 / 活塞): 方块本来就要没了, 里面的东西不许掉在地上, 直接进单子。</li>
 * </ul>
 *
 * 搬运之前**先把账结到这一刻** ({@link GranaryBinBlockEntity#settleAll}): 东西在囤里停过的那一段不能丢,
 * 进了单子再从头算等于把玩家已经攒下的停表抹掉。
 *
 * 停表语义按来路分: 被移出 / 被别人拆走的人在单子里**照常停表 7 天** (不是自己造成的), 自己退出或打包的
 * 不停表 (自己选的, 同时堵住「远程打包当不用盖房子的冰箱」)。宽限窗口按「出事起 7 天」算 —— 单子里的
 * 东西不是「可以修的损坏」, 那条「首次上线起 3 天」的延展与 28 天上限在这里没有意义 (停表额度本来
 * 就只有 21 天)。
 */
public final class GranaryTransfer {

    private GranaryTransfer() {
    }

    /**
     * 把囤里的东西搬进囤主的待领粮单, **不碰方块** (给「方块已经被移除」的那些路径用)。
     *
     * @param reason 来路 (决定还停不停表)
     * @return 搬了东西的囤主 (没有归属记录时返回 null, 调用方按老办法处理)
     */
    public static UUID absorb(ServerLevel level, BlockPos pos, GranaryPending.Reason reason, long nowHour) {
        return move(level, pos, reason, nowHour, false);
    }

    /**
     * 把囤连东西一起搬走, 并把方块从世界里拿掉 (给命令那两条路用)。
     *
     * @return 搬了东西的囤主 (没有归属记录时返回 null)
     */
    public static UUID takeDown(ServerLevel level, BlockPos pos, GranaryPending.Reason reason, long nowHour) {
        return move(level, pos, reason, nowHour, true);
    }

    private static UUID move(ServerLevel level, BlockPos pos, GranaryPending.Reason reason, long nowHour,
                             boolean removeBlock) {
        GranarySavedData data = GranarySavedData.get(level);
        ResourceLocation dimension = level.dimension().location();
        UUID owner = data.ownerOf(dimension, pos);
        if (owner == null) {
            return null;
        }
        long gid = data.granaryOfBin(dimension, pos);
        List<ItemStack> items = new ArrayList<>();
        if (level.getBlockEntity(pos) instanceof GranaryBinBlockEntity bin) {
            // 先把囤里的账结到这一刻: 已经停过的那一段要跟着东西一起进单子。
            bin.settleAll(nowHour);
            for (int slot = 0; slot < bin.getContainerSize(); slot++) {
                ItemStack stack = bin.getItem(slot);
                if (!stack.isEmpty()) {
                    items.add(stack.copy());
                }
            }
            bin.clearContent();
        }
        if (removeBlock) {
            items.add(new ItemStack(level.getBlockState(pos).getBlock()));
        }
        long graceUntil = reason.stopsClock() ? nowHour + GranaryGrace.DAMAGE_HOURS : 0L;
        GranaryPending.put(data, owner, gid, reason, nowHour, graceUntil, items);
        // 连线与启用状态跟着方块一起走 (方块没了, 那些派生索引留着只会指向空气)。
        data.releaseBin(dimension, pos);
        if (removeBlock) {
            BlockState state = level.getBlockState(pos);
            level.removeBlock(pos, false);
            level.levelEvent(2001, pos, Block.getId(state));
        }
        return owner;
    }
}
