package com.miningdim.entrance;

import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningServices;
import com.miningdim.registry.ModBlockEntities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 入口方块的方块实体 (R4)。两职责:
 *  1. 浮空字: 在方块正上方维护一个原版 {@link Display.TextDisplay} 浮空字实体 (billboard=CENTER, 无重力)。
 *     首次服务端 tick 时若浮空字缺失 (新放置 / 重载后实体丢失) 则补生成, 记其 UUID 持久化; setRemoved 时收回。
 *     文案取 {@link com.miningdim.core.IMiningConfig#entryLabel} 按难度缺省。
 *  2. 连点防抖: {@link #tryTrigger} 用冷却 tick 限制相邻两次进入触发, 防右键/踩踏连发刷入场队列。
 *
 * 难度从所属方块 ({@link EntranceBlock}) 读取, 不在 BE 另存一份, 避免双源漂移。
 */
public final class EntranceBlockEntity extends BlockEntity {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/entrance");

    /** 两次进入触发的最小间隔 (tick); 20t=1s, 足够吸收右键连点与踩踏每 tick 触发。 */
    private static final int TRIGGER_COOLDOWN_TICKS = 20;

    /** 浮空字相对方块原点的竖直偏移 (格): 悬在方块上方约一格半, 不被方块挡住。 */
    private static final double TEXT_Y_OFFSET = 1.4;

    /** 已生成的浮空字实体 UUID; null 表示尚未生成。持久化, 重载后据此找回既有实体。 */
    private UUID textDisplayId;

    /** 距离下次允许触发的剩余冷却 tick; 不持久化 (重载即清零, 行为正确)。 */
    private int triggerCooldown;

    public EntranceBlockEntity(net.minecraft.core.BlockPos pos, BlockState state) {
        super(ModBlockEntities.ENTRANCE.get(), pos, state);
    }

    /** 该入口对应的难度 (从方块读取, 单一真源)。非入口块上挂本 BE 属装配错误, 自然抛 (C9 不掩盖)。 */
    private Difficulty difficulty() {
        if (getBlockState().getBlock() instanceof EntranceBlock entrance) {
            return entrance.difficulty();
        }
        throw new IllegalStateException(
                "EntranceBlockEntity attached to non-entrance block at " + worldPosition);
    }

    /** 服务端每 tick: 推进冷却 + 确保浮空字存在 (经 EntranceBlock.getTicker 仅服务端调用)。 */
    public void serverTick() {
        if (triggerCooldown > 0) {
            triggerCooldown--;
        }
        if (level instanceof ServerLevel serverLevel) {
            ensureTextDisplay(serverLevel);
        }
    }

    /**
     * 右键/踩踏触发进入。冷却内静默忽略 (防连点); 经 {@link EntranceHooks} 转入场层。
     * 接线就绪后才进入冷却, 未接线 (维度未起) 不扣冷却以便玩家重试。
     */
    public void tryTrigger(ServerPlayer player) {
        if (triggerCooldown > 0) {
            return;
        }
        if (EntranceHooks.requestEnter(player, difficulty())) {
            triggerCooldown = TRIGGER_COOLDOWN_TICKS;
        }
    }

    // ---- 浮空字生命周期 ----

    /** 浮空字缺失时补生成 (新放置或重载后实体被卸载/丢失)。 */
    private void ensureTextDisplay(ServerLevel serverLevel) {
        if (textDisplayId != null) {
            Entity existing = serverLevel.getEntity(textDisplayId);
            if (existing != null && existing.isAlive()) {
                return;
            }
            // UUID 记录在册但实体已不存在 (被指令/区块卸载清掉): 重建。
            textDisplayId = null;
        }
        spawnTextDisplay(serverLevel);
    }

    private void spawnTextDisplay(ServerLevel serverLevel) {
        Display.TextDisplay display = EntityType.TEXT_DISPLAY.create(serverLevel);
        if (display == null) {
            LOGGER.warn("[miningdim] failed to create text_display for entrance at {}", worldPosition);
            return;
        }
        double x = worldPosition.getX() + 0.5;
        double y = worldPosition.getY() + TEXT_Y_OFFSET;
        double z = worldPosition.getZ() + 0.5;
        display.moveTo(x, y, z, 0.0f, 0.0f);

        // 1.20.1 的 Display 文本/朝向 setter 均为 private, 经 NBT 注入是唯一公开稳定路径:
        // 先取默认全量 tag (含上面 moveTo 后的 Pos/Rotation), 注入展示字段, 再 load 回去。
        Component label = Component.literal(MiningServices.config().entryLabel(difficulty()));
        CompoundTag tag = display.saveWithoutId(new CompoundTag());
        tag.putString("text", Component.Serializer.toJson(label));
        tag.putString("billboard", "center");        // Display.BillboardConstraints.CENTER 的序列化名
        tag.putBoolean("NoGravity", true);
        display.load(tag);

        serverLevel.addFreshEntity(display);
        textDisplayId = display.getUUID();
        setChanged();
    }

    /** 方块破坏/替换时收回浮空字 (R4)。setRemoved 覆盖正常破坏与世界关闭两条路径。 */
    @Override
    public void setRemoved() {
        discardTextDisplay();
        super.setRemoved();
    }

    private void discardTextDisplay() {
        if (textDisplayId != null && level instanceof ServerLevel serverLevel) {
            Entity existing = serverLevel.getEntity(textDisplayId);
            if (existing != null) {
                existing.discard();
            }
        }
        textDisplayId = null;
    }

    // ---- 持久化 (仅浮空字 UUID; 冷却不存) ----

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (textDisplayId != null) {
            tag.putUUID("TextDisplay", textDisplayId);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        textDisplayId = tag.hasUUID("TextDisplay") ? tag.getUUID("TextDisplay") : null;
    }
}
