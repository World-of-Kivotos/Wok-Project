package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.MunitionsConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

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
    private static final int LEGACY_VERSION = 1;

    private GunsmithGunDurability() {
    }

    public static boolean isManagedGun(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        return !stack.isEmpty() && GunsmithGunStats.from(stack) != null;
    }

    /** 新装配枪从当前平台配置与组件修正取初始耐久，并覆盖基础枪上可能残留的旧耐久。 */
    public static State initializeNew(ItemStack stack) {
        GunsmithGunStats stats = requireManagedStats(stack);
        int maximum = initialMaximum(stats);
        State state = new State(maximum, maximum, maximum, 0);
        write(stack, state);
        return state;
    }

    /** 只读视图：未迁移的旧枪显示为当前配置的满耐久，不在客户端 tooltip 阶段写 NBT。 */
    public static State view(ItemStack stack) {
        GunsmithGunStats stats = requireManagedStats(stack);
        StoredState stored = readStored(stack);
        if (stored != null) {
            return migrate(stats, stored);
        }
        int maximum = initialMaximum(stats);
        return new State(maximum, maximum, maximum, 0);
    }

    /** 服务端可变边界：把未带耐久数据的旧枪迁移为满耐久。 */
    public static State ensureInitialized(ItemStack stack) {
        GunsmithGunStats stats = requireManagedStats(stack);
        StoredState stored = readStored(stack);
        if (stored == null) {
            return initializeNew(stack);
        }
        State migrated = migrate(stats, stored);
        if (stored.version() != CURRENT_VERSION) {
            write(stack, migrated);
        }
        return migrated;
    }

    public static boolean isBroken(ItemStack stack) {
        return isManagedGun(stack) && view(stack).current() <= 0;
    }

    /**
     * 每一次 TaCZ 确认发射的子弹扣 1 点。返回 canFire=false 表示开火前已经归零，调用方必须取消事件。
     */
    public static ShotWear consumeShot(ItemStack stack) {
        State before = ensureInitialized(stack);
        if (before.current() <= 0) {
            return new ShotWear(false, false, before);
        }
        State after = new State(before.originalMaximum(), before.maximum(),
                before.current() - 1, before.repairs());
        write(stack, after);
        return new ShotWear(true, after.current() == 0, after);
    }

    public static RepairPreview repairPreview(ItemStack stack) {
        GunsmithGunStats stats = requireManagedStats(stack);
        State state = view(stack);
        GunsmithPlatform platform = stats.blueprint().platform();
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
        ensureInitialized(stack);
        RepairPreview preview = repairPreview(stack);
        if (preview.status() != RepairStatus.AVAILABLE) {
            return new RepairResult(false, preview.status(), preview.before(), preview.before());
        }
        State before = preview.before();
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

    private static int initialMaximum(GunsmithGunStats stats) {
        return scalePositive(initialMaximum(stats.blueprint().platform()),
                stats.maximumDurabilityMultiplier());
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

    private static GunsmithGunStats requireManagedStats(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        GunsmithGunStats stats = GunsmithGunStats.from(stack);
        if (stats == null) {
            throw new IllegalArgumentException("Item stack is not a WOK gunsmith firearm");
        }
        return stats;
    }

    private static StoredState readStored(ItemStack stack) {
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
        requireInt(durability, VERSION_KEY);
        int version = durability.getInt(VERSION_KEY);
        if (version != LEGACY_VERSION && version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported gunsmith durability version: "
                    + version);
        }
        return new StoredState(version, new State(
                requireInt(durability, ORIGINAL_MAXIMUM_KEY),
                requireInt(durability, MAXIMUM_KEY),
                requireInt(durability, CURRENT_KEY),
                requireInt(durability, REPAIRS_KEY)));
    }

    private static State migrate(GunsmithGunStats stats, StoredState stored) {
        State state = stored.state();
        if (stored.version() == CURRENT_VERSION) {
            return state;
        }
        double multiplier = stats.maximumDurabilityMultiplier();
        if (Double.compare(multiplier, 1.0D) == 0) {
            return state;
        }
        int originalMaximum = scalePositive(state.originalMaximum(), multiplier);
        int maximum = scalePositive(state.maximum(), multiplier);
        int current = state.current() == 0 ? 0 : scalePositive(state.current(), multiplier);
        return new State(originalMaximum, maximum, Math.min(current, maximum), state.repairs());
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

    private record StoredState(int version, State state) {
    }
}
