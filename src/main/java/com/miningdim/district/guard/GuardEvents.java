package com.miningdim.district.guard;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 守卫的 Forge 事件 (设计文档 22.5、22.6 ①、22.8、22.9 岩浆点火), 由 DistrictSystem.register 登记到 Forge 总线。
 * 只在服务端判定; 任何异常一律放行, 每小时至多记一次 ERROR。
 *
 * <ul>
 *   <li>放置禁令 (22.5): 真玩家、非 OP 在在用自管区内或外围 8 格内放"机器" (CreateBlockPolicy) → 整次拦下, 动作栏提示
 *       (同一名玩家 1 秒内至多一条)。多方块放置里任何一格落在范围内就拦。OP (2 级、不是假玩家) 例外。</li>
 *   <li>机械动力的假玩家 (22.6 ①): 目标在自管区里的放、拆、改 (犁耕地、去皮、压路)、右键、左键、装倒桶、对实体用物品、
 *       攻击, 一律取消; 外围 8 格里只拦"放机器"。{@code createMachinery = false} 时不管它们 (交给 Flan)。</li>
 *   <li>denyUse (22.8): 真玩家、非 OP 右键自管区内的"机器" → 取消 (useBlock、useItem 都 DENY)。潜行 + 机械动力扳手放行,
 *       徒手拆 (左键与 BreakEvent) 不受影响, 外围 8 格不管。</li>
 *   <li>岩浆点火 (22.9): FluidPlaceBlockEvent 不看取消, 越界时把新状态改回原状态 (单向口径 liquidPos → pos)。</li>
 * </ul>
 */
public final class GuardEvents {

    public static final String KEY_PLACE_DENIED = "district.miningdim.guard.create_place_denied";
    public static final String KEY_PLACE_DENIED_BUFFER = "district.miningdim.guard.create_place_denied_buffer";
    public static final String KEY_USE_DENIED = "district.miningdim.guard.create_use_denied";
    public static final String KEY_INCOMPLETE_OP = "district.miningdim.guard.create_incomplete_op";

    /** 机械动力扳手 (潜行 + 扳手右键是拆下机器的正路, 不拦)。 */
    public static final ResourceLocation CREATE_WRENCH = new ResourceLocation("create", "wrench");

    /** 同一名玩家两条动作栏提示的最小间隔。 */
    private static final long MESSAGE_INTERVAL_MS = 1_000L;
    private static final Map<UUID, Long> LAST_MESSAGE = new ConcurrentHashMap<>();

    /** GameTest 登记的"扳手" (测试专用入口)。 */
    @Nullable
    private static volatile ResourceLocation testWrench;

    private GuardEvents() {
    }

    // ================================================================
    // 放置 (22.5) 与机械动力假玩家的放置 (22.6 ①)
    // ================================================================

    /** 一次放置的结论 (22.5 的表)。 */
    public enum PlacementVerdict {
        ALLOW,
        /** 真玩家、非 OP, 机器落在自管区里: 拦, 提示。 */
        DENY_DISTRICT,
        /** 真玩家、非 OP, 机器落在外围 8 格里 (区外): 拦, 提示。 */
        DENY_BUFFER,
        /** 机械动力的假玩家: 拦, 不提示。 */
        DENY_CREATE_ACTOR
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        try {
            if (!(event.getLevel() instanceof Level level) || level.isClientSide
                    || !(event.getEntity() instanceof Player player)) {
                return;
            }
            PlacementVerdict verdict = placementVerdict(DistrictWorldGuards.view(), level, player,
                    event.getPlacedBlock(), positionsOf(event));
            switch (verdict) {
                case DENY_DISTRICT -> {
                    event.setCanceled(true);
                    tellActionBar(player, KEY_PLACE_DENIED);
                }
                case DENY_BUFFER -> {
                    event.setCanceled(true);
                    tellActionBar(player, KEY_PLACE_DENIED_BUFFER);
                }
                case DENY_CREATE_ACTOR -> event.setCanceled(true);
                case ALLOW -> {
                }
            }
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("placement event", failure);
        }
    }

    /**
     * 22.5 的表: (真玩家非 OP / OP / 机械动力的假玩家 / 别的假玩家) × (机器 / 装饰) × (区内 / 外围 8 格 / 更远)。
     * 多方块放置里任何一格落在范围内就整次拦下。
     */
    public static PlacementVerdict placementVerdict(GuardView view, Level level, @Nullable Player player,
                                                    BlockState placed, List<BlockPos> positions) {
        if (player == null || level.isClientSide || !view.banActive()) {
            return PlacementVerdict.ALLOW;
        }
        boolean machine = view.policy().isMachine(placed.getBlock());
        DistrictZoneSnapshot zones = view.zones();
        if (GuardActors.isCreateFakePlayer(player)) {
            if (!view.settings().createMachinery()) {
                return PlacementVerdict.ALLOW;
            }
            for (BlockPos pos : positions) {
                if (zones.zoneAt(level, pos.getX(), pos.getZ()) != 0
                        || (machine && zones.inBanArea(level, pos.getX(), pos.getZ()))) {
                    return PlacementVerdict.DENY_CREATE_ACTOR;
                }
            }
            return PlacementVerdict.ALLOW;
        }
        if (!machine || !GuardActors.isRealPlayer(player) || GuardActors.isExemptOp(player)) {
            return PlacementVerdict.ALLOW;
        }
        boolean inBuffer = false;
        for (BlockPos pos : positions) {
            if (zones.zoneAt(level, pos.getX(), pos.getZ()) != 0) {
                return PlacementVerdict.DENY_DISTRICT;
            }
            inBuffer |= zones.inBanArea(level, pos.getX(), pos.getZ());
        }
        return inBuffer ? PlacementVerdict.DENY_BUFFER : PlacementVerdict.ALLOW;
    }

    private static List<BlockPos> positionsOf(BlockEvent.EntityPlaceEvent event) {
        List<BlockPos> positions = new ArrayList<>();
        positions.add(event.getPos());
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (BlockSnapshot snapshot : multi.getReplacedBlockSnapshots()) {
                positions.add(snapshot.getPos());
            }
        }
        return positions;
    }

    // ================================================================
    // 机械动力的假玩家 (22.6 ①)
    // ================================================================

    /** 机械动力的假玩家、机器拦截开着、目标在自管区里。 */
    private static boolean createFakePlayerTouches(@Nullable Player player, @Nullable Level level,
                                                   @Nullable BlockPos target) {
        if (player == null || level == null || level.isClientSide || target == null
                || !GuardActors.isCreateFakePlayer(player)) {
            return false;
        }
        GuardView view = DistrictWorldGuards.view();
        return view.machineryActive() && view.zones().zoneAt(level, target.getX(), target.getZ()) != 0;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BlockEvent.BreakEvent event) {
        try {
            if (event.getLevel() instanceof Level level
                    && createFakePlayerTouches(event.getPlayer(), level, event.getPos())) {
                event.setCanceled(true);
            }
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("break event", failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onToolModification(BlockEvent.BlockToolModificationEvent event) {
        try {
            if (event.getLevel() instanceof Level level
                    && createFakePlayerTouches(event.getPlayer(), level, event.getPos())) {
                event.setCanceled(true);
            }
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("tool modification event", failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        try {
            Player player = event.getEntity();
            Level level = event.getLevel();
            if (createFakePlayerTouches(player, level, event.getPos())) {
                deny(event);
                return;
            }
            if (useDenied(player, level, event.getPos(), event.getItemStack())) {
                deny(event);
                tellActionBar(player, KEY_USE_DENIED);
            }
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("right click event", failure);
        }
    }

    private static void deny(PlayerInteractEvent.RightClickBlock event) {
        // 机械动力读 useBlock / useItem 两个值; Forge 47.3.0 起取消会把两者都置 DENY, 这里再写一次不依赖这个细节。
        event.setUseBlock(Event.Result.DENY);
        event.setUseItem(Event.Result.DENY);
        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        try {
            if (createFakePlayerTouches(event.getEntity(), event.getLevel(), event.getPos())) {
                event.setCanceled(true);
            }
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("left click event", failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onFillBucket(FillBucketEvent event) {
        try {
            HitResult target = event.getTarget();
            if (!(target instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
                return;
            }
            Level level = event.getLevel();
            Player player = event.getEntity();
            if (createFakePlayerTouches(player, level, hit.getBlockPos())
                    || createFakePlayerTouches(player, level, hit.getBlockPos().relative(hit.getDirection()))) {
                event.setCanceled(true);
            }
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("fill bucket event", failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        try {
            if (targetTouched(event.getEntity(), event.getTarget())) {
                event.setCancellationResult(InteractionResult.FAIL);
                event.setCanceled(true);
            }
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("entity interact event", failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        try {
            if (targetTouched(event.getEntity(), event.getTarget())) {
                event.setCancellationResult(InteractionResult.FAIL);
                event.setCanceled(true);
            }
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("entity interact event", failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onAttackEntity(AttackEntityEvent event) {
        try {
            if (targetTouched(event.getEntity(), event.getTarget())) {
                event.setCanceled(true);
            }
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("attack entity event", failure);
        }
    }

    private static boolean targetTouched(@Nullable Player player, @Nullable Entity target) {
        return target != null && createFakePlayerTouches(player, target.level(), target.blockPosition());
    }

    // ================================================================
    // denyUse (22.8)
    // ================================================================

    /** 真玩家、非 OP、denyUse 开着、位置在自管区内、方块是"机器"、不是潜行 + 扳手。 */
    private static boolean useDenied(@Nullable Player player, @Nullable Level level, BlockPos pos, ItemStack held) {
        if (level == null || level.isClientSide || !GuardActors.isRealPlayer(player)
                || GuardActors.isExemptOp(player)) {
            return false;
        }
        GuardView view = DistrictWorldGuards.view();
        if (!view.banActive() || !view.settings().denyUse()
                || view.zones().zoneAt(level, pos.getX(), pos.getZ()) == 0) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (!view.policy().isMachine(state.getBlock())) {
            return false;
        }
        return !(player.isShiftKeyDown() && isWrench(held));
    }

    private static boolean isWrench(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && (id.equals(CREATE_WRENCH) || id.equals(testWrench));
    }

    /**
     * 只供 GameTest: 把一种物品登记成"扳手", 返回复原用的句柄。只在 GameTest 服务端上可用 (门同
     * DistrictFeature.forceForTest)。
     */
    public static AutoCloseable registerTestWrench(ResourceLocation item) {
        if (!(ServerLifecycleHooks.getCurrentServer() instanceof GameTestServer)) {
            throw new IllegalStateException("GuardEvents.registerTestWrench is only available on the GameTest server");
        }
        ResourceLocation previous = testWrench;
        testWrench = item;
        return () -> testWrench = previous;
    }

    // ================================================================
    // 岩浆点火 (22.9)
    // ================================================================

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFluidPlaceBlock(BlockEvent.FluidPlaceBlockEvent event) {
        try {
            if (event.getLevel() instanceof Level level
                    && !DistrictWorldGuards.lavaMayIgnite(level, event.getLiquidPos(), event.getPos())) {
                event.setNewState(event.getOriginalState());
            }
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("fluid place event", failure);
        }
    }

    // ================================================================
    // 提示
    // ================================================================

    /** 动作栏提示, 同一名玩家 1 秒内至多一条。 */
    public static void tellActionBar(Player player, String key) {
        long now = System.currentTimeMillis();
        Long previous = LAST_MESSAGE.get(player.getUUID());
        if (previous != null && now - previous < MESSAGE_INTERVAL_MS) {
            return;
        }
        if (LAST_MESSAGE.size() > 256) {
            LAST_MESSAGE.clear();
        }
        LAST_MESSAGE.put(player.getUUID(), now);
        player.displayClientMessage(Component.translatable(key), true);
    }

    /** 清掉动作栏提示的节流 (GameTest 在两次断言之间用)。 */
    public static void clearActionBarThrottle() {
        LAST_MESSAGE.clear();
    }

    /** 这些键都在语言文件里 (DistrictLangGameTests 核对)。 */
    public static Set<String> keys() {
        return Set.of(KEY_PLACE_DENIED, KEY_PLACE_DENIED_BUFFER, KEY_USE_DENIED, KEY_INCOMPLETE_OP);
    }
}
