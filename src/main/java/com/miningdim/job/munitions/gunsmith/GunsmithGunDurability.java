package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.MunitionsConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * WOK 枪匠成品枪的服务端权威耐久数据。不复用原版 Damage，避免与 TaCZ 自有弹药/配件 NBT
 * 冲突。新枪在装配时写入；旧版已装配枪在首次开火或维修时惰性迁移为满耐久。
 */
public final class GunsmithGunDurability {

    public static final String DURABILITY_KEY = "Durability";
    private static final String VERSION_KEY = "version";
    private static final String ORIGINAL_MAXIMUM_KEY = "originalMaximum";
    private static final String MAXIMUM_KEY = "maximum";
    private static final String CURRENT_KEY = "current";
    private static final String REPAIRS_KEY = "repairs";
    private static final int CURRENT_VERSION = 2;

    private GunsmithGunDurability() {
    }

    public static boolean isManagedGun(ItemStack stack) {
        return tryManaged(stack) != null;
    }

    /**
     * 容器谓词、客户端渲染与 TaCZ 开火事件的统一入口：一次解析出部件与耐久快照，读不出来返回 null。
     * 这几处都挂在玩家点击 / 开火链上，没有外层 Controller 兜底，让解析异常穿透包处理器就是崩服 (审查 2)；
     * 同时避免同一发子弹反复重解析整套部件 (审查 40)。
     */
    @Nullable
    public static Managed tryManaged(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) {
            return null;
        }
        GunsmithGunStats stats = GunsmithGunStats.tryFrom(stack);
        if (stats == null) {
            return null;
        }
        try {
            return new Managed(stats, view(stats, stack));
        } catch (IllegalArgumentException malformed) {
            // 与 GunsmithGunStats.tryFrom 同口径的容错边界：耐久段畸形的枪在只读路径上降级为"非托管枪"
            // (不可放入 / 不可维修)，硬校验只保留在装配与维修的服务端写入路径上。
            return null;
        }
    }

    /**
     * 维修用替换件是否合格：必须与枪上该槽实际装着的组件同型号，且品质档不低于它。
     * 维修只改 Durability 子标签、不重算 Parts/Stats，若放行降级件，最便宜的普通基础件就能给
     * 传奇 / 势力组件枪无限续命，稀缺组件的成本退化成一次性投入 (审查 30)。
     */
    public static boolean isRepairReplacement(GunsmithGunStats stats, ItemStack replacement) {
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(replacement, "replacement");
        GunsmithPlatform platform = stats.blueprint().platform();
        GunsmithPressPart part = repairPart(platform);
        if (!GunsmithAssemblyRecipe.matchesPart(replacement, part, platform)) {
            return false;
        }
        GunsmithGunStats.PartSummary installed = installedPart(stats, part);
        GunsmithPartItem.PartData data = GunsmithPartItem.requirePartData(replacement);
        return data.variant() == installed.variant()
                && data.quality().index() >= installed.quality().index();
    }

    /** 新装配枪从当前平台配置与组件修正取初始耐久，并覆盖基础枪上可能残留的旧耐久。 */
    public static State initializeNew(ItemStack stack) {
        return initializeNew(requireManagedStats(stack), stack);
    }

    /** 只读视图：未迁移的旧枪显示为当前配置的满耐久，不在客户端 tooltip 阶段写 NBT。 */
    public static State view(ItemStack stack) {
        return view(requireManagedStats(stack), stack);
    }

    /** 服务端可变边界：把未带耐久数据的旧枪迁移为满耐久。 */
    public static State ensureInitialized(ItemStack stack) {
        return ensureInitialized(requireManagedStats(stack), stack);
    }

    /**
     * 每一次 TaCZ 确认发射的子弹扣 1 点。返回 canFire=false 表示开火前已经归零，调用方必须取消事件。
     */
    public static ShotWear consumeShot(ItemStack stack) {
        GunsmithGunStats stats = requireManagedStats(stack);
        return consumeShot(view(stats, stack), stack);
    }

    /** 开火链专用：调用方已持 {@link Managed} 快照，这里不再重新解析部件 (审查 40)。 */
    public static ShotWear consumeShot(Managed managed, ItemStack stack) {
        Objects.requireNonNull(managed, "managed");
        Objects.requireNonNull(stack, "stack");
        return consumeShot(managed.state(), stack);
    }

    public static RepairPreview repairPreview(ItemStack stack) {
        GunsmithGunStats stats = requireManagedStats(stack);
        return repairPreview(new Managed(stats, view(stats, stack)));
    }

    public static RepairPreview repairPreview(Managed managed) {
        Objects.requireNonNull(managed, "managed");
        GunsmithPlatform platform = managed.stats().blueprint().platform();
        State state = managed.state();
        int floor = minimumMaximum(state.originalMaximum());
        if (state.maximum() <= floor) {
            return new RepairPreview(RepairStatus.EXHAUSTED, state, state.maximum(), 0,
                    repairPart(platform));
        }
        if (state.current() >= state.maximum()) {
            return new RepairPreview(RepairStatus.FULL, state, state.maximum(), 0,
                    repairPart(platform));
        }
        int loss = Math.max(1, (int) Math.ceil(state.originalMaximum() * repairLoss(platform)));
        int nextMaximum = Math.max(floor, state.maximum() - loss);
        if (nextMaximum <= state.current()) {
            return new RepairPreview(RepairStatus.INSUFFICIENT_WEAR, state, nextMaximum, 0,
                    repairPart(platform));
        }
        return new RepairPreview(RepairStatus.AVAILABLE, state, nextMaximum,
                nextMaximum - state.current(), repairPart(platform));
    }

    public static RepairResult repair(ItemStack stack) {
        GunsmithGunStats stats = requireManagedStats(stack);
        State before = ensureInitialized(stats, stack);
        RepairPreview preview = repairPreview(new Managed(stats, before));
        if (preview.status() != RepairStatus.AVAILABLE) {
            return new RepairResult(false, preview.status(), before, before);
        }
        State after = new State(before.originalMaximum(), preview.nextMaximum(),
                preview.nextMaximum(), before.repairs() + 1);
        write(stack, after);
        return new RepairResult(true, RepairStatus.AVAILABLE, before, after);
    }

    public static GunsmithPressPart repairPart(GunsmithPlatform platform) {
        return switch (Objects.requireNonNull(platform, "platform")) {
            case AR, AK, MARKSMAN, MACHINE_GUN, SHOTGUN -> GunsmithPressPart.BOLT;
            case PISTOL -> GunsmithPressPart.SLIDE;
            case BULLPUP, SMG -> GunsmithPressPart.RECEIVER;
            case SNIPER -> GunsmithPressPart.FIRING_PIN;
        };
    }

    public static int initialMaximum(GunsmithPlatform platform) {
        return switch (Objects.requireNonNull(platform, "platform")) {
            case AR -> MunitionsConfig.GUN_DURABILITY_AR.get();
            case AK -> MunitionsConfig.GUN_DURABILITY_AK.get();
            case PISTOL -> MunitionsConfig.GUN_DURABILITY_PISTOL.get();
            case BULLPUP -> MunitionsConfig.GUN_DURABILITY_BULLPUP.get();
            case MARKSMAN -> MunitionsConfig.GUN_DURABILITY_MARKSMAN.get();
            case SNIPER -> MunitionsConfig.GUN_DURABILITY_SNIPER.get();
            case MACHINE_GUN -> MunitionsConfig.GUN_DURABILITY_MACHINE_GUN.get();
            case SHOTGUN -> MunitionsConfig.GUN_DURABILITY_SHOTGUN.get();
            case SMG -> MunitionsConfig.GUN_DURABILITY_SMG.get();
        };
    }

    public static double repairLoss(GunsmithPlatform platform) {
        return switch (Objects.requireNonNull(platform, "platform")) {
            case AR -> MunitionsConfig.GUN_REPAIR_LOSS_AR.get();
            case AK -> MunitionsConfig.GUN_REPAIR_LOSS_AK.get();
            case PISTOL -> MunitionsConfig.GUN_REPAIR_LOSS_PISTOL.get();
            case BULLPUP -> MunitionsConfig.GUN_REPAIR_LOSS_BULLPUP.get();
            case MARKSMAN -> MunitionsConfig.GUN_REPAIR_LOSS_MARKSMAN.get();
            case SNIPER -> MunitionsConfig.GUN_REPAIR_LOSS_SNIPER.get();
            case MACHINE_GUN -> MunitionsConfig.GUN_REPAIR_LOSS_MACHINE_GUN.get();
            case SHOTGUN -> MunitionsConfig.GUN_REPAIR_LOSS_SHOTGUN.get();
            case SMG -> MunitionsConfig.GUN_REPAIR_LOSS_SMG.get();
        };
    }

    public static int minimumMaximum(int originalMaximum) {
        if (originalMaximum <= 0) {
            throw new IllegalArgumentException("Original gun durability must be positive");
        }
        return Math.max(1, (int) Math.ceil(originalMaximum * MunitionsConfig.GUN_REPAIR_MINIMUM_RATIO.get()));
    }

    private static State initializeNew(GunsmithGunStats stats, ItemStack stack) {
        int maximum = initialMaximum(stats);
        State state = new State(maximum, maximum, maximum, 0);
        write(stack, state);
        return state;
    }

    private static State view(GunsmithGunStats stats, ItemStack stack) {
        State stored = readStored(stack);
        if (stored != null) {
            return stored;
        }
        int maximum = initialMaximum(stats);
        return new State(maximum, maximum, maximum, 0);
    }

    private static State ensureInitialized(GunsmithGunStats stats, ItemStack stack) {
        State stored = readStored(stack);
        return stored != null ? stored : initializeNew(stats, stack);
    }

    private static ShotWear consumeShot(State before, ItemStack stack) {
        if (before.current() <= 0) {
            return new ShotWear(false, false, before);
        }
        State after = new State(before.originalMaximum(), before.maximum(),
                before.current() - 1, before.repairs());
        write(stack, after);
        return new ShotWear(true, after.current() == 0, after);
    }

    private static GunsmithGunStats.PartSummary installedPart(GunsmithGunStats stats, GunsmithPressPart part) {
        for (GunsmithGunStats.PartSummary summary : stats.parts()) {
            if (summary.part() == part) {
                return summary;
            }
        }
        throw new IllegalArgumentException("Gunsmith gun has no installed " + part.id() + " to service");
    }

    private static int initialMaximum(GunsmithGunStats stats) {
        return scalePositive(initialMaximum(stats.blueprint().platform()),
                stats.maximumDurabilityMultiplier());
    }

    private static GunsmithGunStats requireManagedStats(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        GunsmithGunStats stats = GunsmithGunStats.from(stack);
        if (stats == null) {
            throw new IllegalArgumentException("Item stack is not a WOK gunsmith firearm");
        }
        return stats;
    }

    @Nullable
    private static State readStored(ItemStack stack) {
        CompoundTag itemTag = stack.getTag();
        if (itemTag == null || !itemTag.contains(GunsmithGunStats.ROOT_KEY, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag gunsmith = itemTag.getCompound(GunsmithGunStats.ROOT_KEY);
        if (!gunsmith.contains(DURABILITY_KEY)) {
            return null;
        }
        if (!gunsmith.contains(DURABILITY_KEY, Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Gunsmith durability data is not a compound");
        }
        CompoundTag durability = gunsmith.getCompound(DURABILITY_KEY);
        int version = requireInt(durability, VERSION_KEY);
        // 耐久段与本子系统同批落地, write() 恒写 CURRENT_VERSION, 任何存档里都不会留下别的版本号;
        // 出现别的版本号只可能是外部改档, 按畸形数据处理而不是替它编一套迁移 (审查 70)。
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported gunsmith durability version: " + version);
        }
        return new State(
                requireInt(durability, ORIGINAL_MAXIMUM_KEY),
                requireInt(durability, MAXIMUM_KEY),
                requireInt(durability, CURRENT_KEY),
                requireInt(durability, REPAIRS_KEY));
    }

    private static int scalePositive(int value, double multiplier) {
        if (value <= 0 || !Double.isFinite(multiplier) || multiplier <= 0.0D) {
            throw new IllegalArgumentException("Gun durability scaling requires positive finite values");
        }
        return Math.max(1, (int) Math.floor(value * multiplier));
    }

    private static int requireInt(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_INT)) {
            throw new IllegalArgumentException("Gunsmith durability has no integer value for " + key);
        }
        return tag.getInt(key);
    }

    private static void write(ItemStack stack, State state) {
        CompoundTag durability = new CompoundTag();
        durability.putInt(VERSION_KEY, CURRENT_VERSION);
        durability.putInt(ORIGINAL_MAXIMUM_KEY, state.originalMaximum());
        durability.putInt(MAXIMUM_KEY, state.maximum());
        durability.putInt(CURRENT_KEY, state.current());
        durability.putInt(REPAIRS_KEY, state.repairs());
        stack.getOrCreateTag().getCompound(GunsmithGunStats.ROOT_KEY)
                .put(DURABILITY_KEY, durability);
    }

    public enum RepairStatus {
        AVAILABLE,
        FULL,
        INSUFFICIENT_WEAR,
        EXHAUSTED
    }

    public record State(int originalMaximum, int maximum, int current, int repairs) {
        public State {
            if (originalMaximum <= 0) {
                throw new IllegalArgumentException("Original gun durability must be positive");
            }
            if (maximum <= 0 || maximum > originalMaximum) {
                throw new IllegalArgumentException("Gun maximum durability is outside the original maximum");
            }
            if (current < 0 || current > maximum) {
                throw new IllegalArgumentException("Gun current durability is outside its maximum");
            }
            if (repairs < 0) {
                throw new IllegalArgumentException("Gun repair count must not be negative");
            }
        }

        public double remainingRatio() {
            return (double) current / maximum;
        }
    }

    /** 一把托管枪的单次解析结果：部件视图 + 耐久快照，供同一帧 / 同一发子弹内的多处判定复用。 */
    public record Managed(GunsmithGunStats stats, State state) {
        public Managed {
            Objects.requireNonNull(stats, "stats");
            Objects.requireNonNull(state, "state");
        }
    }

    public record ShotWear(boolean canFire, boolean becameBroken, State state) {
    }

    public record RepairPreview(RepairStatus status, State before, int nextMaximum,
                                int restored, GunsmithPressPart requiredPart) {
        public boolean available() {
            return status == RepairStatus.AVAILABLE;
        }
    }

    public record RepairResult(boolean repaired, RepairStatus status, State before, State after) {
    }
}
