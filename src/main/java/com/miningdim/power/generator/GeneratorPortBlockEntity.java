package com.miningdim.power.generator;

import com.miningdim.power.GeneratorMultiblockBlock;
import com.miningdim.power.PowerRegistry;
import com.miningdim.power.grid.VoltageAwareEnergyStorage;
import com.miningdim.power.grid.VoltageClass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 后部端口只转发控制器的能源能力，不拥有任何可持久化运行数据。 */
public final class GeneratorPortBlockEntity extends BlockEntity {

    private static final Logger PORT_LOGGER = LoggerFactory.getLogger("miningdim/power");
    private static final String K_CONTROLLER = "controller";
    private static final String K_LINK_VERSION = "linkVersion";

    private @Nullable BlockPos controllerPos;
    private long linkVersion;
    private final LazyOptional<VoltageAwareEnergyStorage> energyCap = LazyOptional.of(() -> new PortEnergyStorage());

    public GeneratorPortBlockEntity(BlockPos pos, BlockState state) {
        super(PowerRegistry.GENERATOR_PORT_BE.get(), pos, state);
        if (state.getValue(GeneratorMultiblockBlock.PART) != GeneratorMultiblockBlock.PORT_PART) {
            throw new IllegalArgumentException("generator port requires rear port state at " + pos);
        }
    }

    public void linkTo(BlockPos controllerPos, long linkVersion) {
        if (linkVersion <= 0L) {
            throw new IllegalArgumentException("generator port link version must be positive");
        }
        if (!controllerPos.equals(this.controllerPos) || this.linkVersion != linkVersion) {
            this.controllerPos = controllerPos.immutable();
            this.linkVersion = linkVersion;
            setChanged();
        }
    }

    public @Nullable BlockPos controllerPos() {
        return controllerPos;
    }

    public long linkVersion() {
        return linkVersion;
    }

    private @Nullable GeneratorBlockEntity controller() {
        if (!(level instanceof ServerLevel serverLevel) || controllerPos == null
                || !serverLevel.hasChunkAt(controllerPos)) {
            return null;
        }
        BlockEntity blockEntity = serverLevel.getBlockEntity(controllerPos);
        if (!(blockEntity instanceof GeneratorBlockEntity controller)
                || controller.portLinkVersion() != linkVersion
                || !controller.portPos().equals(worldPosition)) {
            return null;
        }
        return controller;
    }

    private Direction outputDirection() {
        return getBlockState().getValue(GeneratorMultiblockBlock.FACING).getOpposite();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            GeneratorBlockEntity.ensureLegacyEntities(serverLevel, worldPosition);
        }
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ENERGY && side == outputDirection()) {
            return energyCap.cast();
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        energyCap.invalidate();
    }

    /**
     * 未连上控制器时只写空记录, 不抛异常。
     *
     * 端口方块落地与 {@link GeneratorBlockEntity#ensurePortProxy()} 之间必然存在一个"方块实体已在、
     * 尚未 link"的窗口: 放置补格是逐格 setBlock, 而链接要等 12 格补齐后才做。任何挂在 setBlock 上的
     * 观察者 (Cairn 的方块审计、BetterAutoSave 的区块快照、Forge 的 BlockSnapshot) 都会在这个窗口里
     * 触发一次保存 —— 这里原本抛异常, 于是把放置补格的循环当场炸断, 结构永远停在残缺态 (线上幽灵方块
     * 的真实成因), 自愈路径清理时更会直接崩服。
     *
     * link 是控制器派生的运行期状态, 不是这里的真相源: 控制器持久化了 portRebuildRequired 与
     * portLinkVersion, ensurePortProxy 每次都能重建。故保存路径一律不得因缺 link 而失败。
     */
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (controllerPos == null || linkVersion <= 0L) {
            PORT_LOGGER.warn("generator port saved without controller link at {}; controller will relink", worldPosition);
            return;
        }
        tag.putLong(K_CONTROLLER, controllerPos.asLong());
        tag.putLong(K_LINK_VERSION, linkVersion);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (!tag.contains(K_CONTROLLER, Tag.TAG_LONG) || !tag.contains(K_LINK_VERSION, Tag.TAG_LONG)) {
            // 与 saveAdditional 成对: 允许存在未 link 的存档记录, 等控制器 ensurePortProxy 重建。
            controllerPos = null;
            linkVersion = 0L;
            return;
        }
        controllerPos = BlockPos.of(tag.getLong(K_CONTROLLER));
        linkVersion = tag.getLong(K_LINK_VERSION);
        if (linkVersion <= 0L) {
            throw new IllegalStateException("generator port NBT has invalid link version at " + worldPosition);
        }
    }

    private final class PortEnergyStorage implements VoltageAwareEnergyStorage {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return 0;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            GeneratorBlockEntity controller = controller();
            return controller == null ? 0 : controller.extractForNetwork(maxExtract, simulate);
        }

        @Override
        public int getEnergyStored() {
            GeneratorBlockEntity controller = controller();
            return controller == null ? 0 : controller.storedFe();
        }

        @Override
        public int getMaxEnergyStored() {
            GeneratorBlockEntity controller = controller();
            return controller == null ? 0 : controller.bufferCapacityFe();
        }

        @Override
        public boolean canExtract() {
            GeneratorBlockEntity controller = controller();
            return controller != null && !controller.isMeltdown();
        }

        @Override
        public boolean canReceive() {
            return false;
        }

        @Override
        public VoltageClass outputVoltage() {
            GeneratorBlockEntity controller = controller();
            return controller == null ? GeneratorSpec.forBlock(getBlockState().getBlock()).sourceVoltage()
                    : controller.spec().sourceVoltage();
        }

        @Override
        public void reportOvervoltage(VoltageClass networkLimit) {
            GeneratorBlockEntity controller = controller();
            if (controller != null) {
                controller.reportOvervoltage(networkLimit);
            }
        }
    }
}
