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

/** 后部端口只转发控制器的能源能力，不拥有任何可持久化运行数据。 */
public final class GeneratorPortBlockEntity extends BlockEntity {

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

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (controllerPos == null || linkVersion <= 0L) {
            throw new IllegalStateException("generator port lacks controller link at " + worldPosition);
        }
        tag.putLong(K_CONTROLLER, controllerPos.asLong());
        tag.putLong(K_LINK_VERSION, linkVersion);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (!tag.contains(K_CONTROLLER, Tag.TAG_LONG) || !tag.contains(K_LINK_VERSION, Tag.TAG_LONG)) {
            throw new IllegalStateException("generator port NBT missing controller link at " + worldPosition);
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
