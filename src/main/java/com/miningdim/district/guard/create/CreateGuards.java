package com.miningdim.district.guard.create;

import com.miningdim.district.guard.DistrictWorldGuards;
import com.miningdim.district.guard.DistrictZoneSnapshot;
import com.miningdim.district.guard.GuardActors;
import com.miningdim.district.guard.GuardEvents;
import com.miningdim.district.guard.GuardView;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 机械动力各注入点的判定 (设计文档 22.6)。mixin 的方法体只有一行转调这里; 这里只用 Minecraft、Forge 与 Java 的类型,
 * 机械动力的对象一律当 Object 经 {@link CreateReflection} 读, 所以没装机械动力也能被 GameTest 直接调用。
 *
 * <p>口径"受保护" (22.2): 只看落点, 不看机器在哪、是谁放的; 落点在任何在用自管区里就拒。OP 的例外只到"亲手放置"为止。
 * C16 ~ C18 (对称之杖、机械臂、显示链接) 用"单向"口径: 它们搬的是物品、字, 或替玩家在自家地块里干活, 只拦落到别的
 * 区域的那一部分 (22.6)。客户端一律放行; {@code createMachinery = false} 时全部放行; 任何异常一律放行 (fail open)。
 */
public final class CreateGuards {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");

    /** 装置部件的就近判定: 部件位置外扩几格 (22.6 C5)。 */
    public static final int ACTOR_MARGIN = 2;

    private static final AtomicBoolean ACTOR_FALLBACK_WARNED = new AtomicBoolean();

    private CreateGuards() {
    }

    @Nullable
    private static GuardView active(@Nullable Level level) {
        if (level == null || level.isClientSide) {
            return null;
        }
        GuardView view = DistrictWorldGuards.view();
        return view.machineryActive() ? view : null;
    }

    private static boolean protectedAt(GuardView view, Level level, BlockPos pos) {
        return view.zones().zoneAt(level, pos.getX(), pos.getZ()) != 0;
    }

    /** 落点受保护, 或落点在外围 8 格内且要放的是机器。 */
    private static boolean placementBlocked(GuardView view, Level level, BlockPos pos, @Nullable BlockState state) {
        if (protectedAt(view, level, pos)) {
            return true;
        }
        return state != null && view.zones().inBanArea(level, pos.getX(), pos.getZ())
                && view.policy().isMachine(state.getBlock());
    }

    /** C1 BlockHelper.destroyBlockAs: 玩家为 null 或是假玩家、且位置受保护时不拆。真玩家走 BreakEvent, 交给 Flan。 */
    public static boolean mayDestroy(@Nullable Level level, @Nullable BlockPos pos, @Nullable Player player) {
        try {
            GuardView view = active(level);
            if (view == null || pos == null || (player != null && !(player instanceof FakePlayer))) {
                return true;
            }
            return !protectedAt(view, level, pos);
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("create destroyBlockAs", failure);
            return true;
        }
    }

    /** C2 SchematicannonBlockEntity.shouldPlace: 目标受保护, 或目标在外围 8 格内且要放的是机器 → 这一格跳过。 */
    public static boolean schematicannonMayPlace(@Nullable Level level, @Nullable BlockPos pos,
                                                 @Nullable BlockState toPlace) {
        try {
            GuardView view = active(level);
            if (view == null || pos == null) {
                return true;
            }
            return !placementBlocked(view, level, pos, toPlace);
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("create schematicannon", failure);
            return true;
        }
    }

    /** C3 BlockBreakingKineticBlockEntity.canBreak (breakingPos) 与 C4 BlockBreakingMovementBehaviour.canBreak。 */
    public static boolean mayBreak(@Nullable Level level, @Nullable BlockPos pos) {
        try {
            GuardView view = active(level);
            if (view == null || pos == null) {
                return true;
            }
            return !protectedAt(view, level, pos);
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("create canBreak", failure);
            return true;
        }
    }

    /**
     * C5 AbstractContraptionEntity.isActorActive: 部件位置外扩 2 格的框碰到任何在用自管区, 这个部件这一 tick 停下。
     * 位置取不到时退回装置实体自己的包围框外扩 2 格 (宁可整台停下), 记一次 WARN。
     */
    public static boolean actorMayAct(@Nullable Level level, @Nullable Object movementContext, AABB entityBounds) {
        try {
            GuardView view = active(level);
            if (view == null) {
                return true;
            }
            Vec3 position = movementContext == null ? null : CreateReflection.contextPosition(movementContext);
            if (position == null) {
                if (ACTOR_FALLBACK_WARNED.compareAndSet(false, true)) {
                    LOGGER.warn("[miningdim] district: cannot read MovementContext.position; contraption actors "
                            + "near districts are judged by the whole contraption's bounding box instead");
                }
                return actorMayActIn(view, level, entityBounds.inflate(ACTOR_MARGIN));
            }
            return actorMayActAt(level, position);
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("create isActorActive", failure);
            return true;
        }
    }

    /** C5 的纯判定: 部件位置外扩 {@link #ACTOR_MARGIN} 格的方块框碰不碰任何在用自管区。 */
    public static boolean actorMayActAt(Level level, Vec3 position) {
        GuardView view = active(level);
        if (view == null) {
            return true;
        }
        int x = Mth.floor(position.x);
        int z = Mth.floor(position.z);
        return !view.zones().touchesDistrict(level, x - ACTOR_MARGIN, z - ACTOR_MARGIN, x + ACTOR_MARGIN,
                z + ACTOR_MARGIN);
    }

    private static boolean actorMayActIn(GuardView view, Level level, AABB box) {
        return !view.zones().touchesDistrict(level, Mth.floor(box.minX), Mth.floor(box.minZ), Mth.floor(box.maxX),
                Mth.floor(box.maxZ));
    }

    /** C6 收割机、C7 压路机: 位置受保护就跳过 (世界取 MovementContext.world)。 */
    public static boolean actorMayTouch(@Nullable Object movementContext, @Nullable BlockPos pos) {
        try {
            Level level = movementContext == null ? null : CreateReflection.contextWorld(movementContext);
            return mayBreak(level, pos);
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("create actor", failure);
            return true;
        }
    }

    /** C8 ContraptionCollider.isCollidingWithWorld: 受保护的格子报"没加载" (没加载的区块本来就算碰撞)。 */
    public static boolean colliderMayEnter(@Nullable Level level, @Nullable BlockPos pos) {
        return mayBreak(level, pos);
    }

    /** C9 Contraption.addBlocksToWorld: 落点受保护, 或落点在外围 8 格内且是机器 → 这一格不放, 掉成物品。 */
    public static boolean contraptionMayPlace(@Nullable LevelAccessor level, @Nullable BlockPos pos,
                                              @Nullable BlockState state) {
        try {
            if (!(level instanceof Level world)) {
                return true;
            }
            GuardView view = active(world);
            if (view == null || pos == null) {
                return true;
            }
            return !placementBlocked(view, world, pos, state);
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("create addBlocksToWorld", failure);
            return true;
        }
    }

    /**
     * C9 被拦下的那一格: 照抄机械动力自己"被挡住"那一支: 在最低建筑高度时上移一格, 播放 2001 事件, 除非
     * noDropWhenContraptionReplaceBlocks 否则掉落; 原地的方块不动。
     */
    public static void dropBlockedContraptionBlock(LevelAccessor level, BlockPos pos, BlockState state) {
        try {
            if (!(level instanceof Level world)) {
                return;
            }
            BlockPos at = pos.getY() == world.getMinBuildHeight() ? pos.above() : pos;
            world.levelEvent(2001, at, Block.getId(state));
            if (!CreateReflection.noDropWhenContraptionReplaceBlocks()) {
                Block.dropResources(state, world, at, null);
            }
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("create contraption drop", failure);
        }
    }

    /** C10 BlockMovementChecksImpl.isMovementAllowed: 受保护的方块不可移动 → 整个装置组装失败。 */
    public static boolean mayMoveBlock(@Nullable Level level, @Nullable BlockPos pos) {
        return mayBreak(level, pos);
    }

    /** C11 软管滑轮抽液、C12 灌液、C13 开口管道: 受保护的格子当不存在。 */
    public static boolean fluidMayReach(@Nullable Level level, @Nullable BlockPos pos) {
        return mayBreak(level, pos);
    }

    // ================================================================
    // C14 轨道
    // ================================================================

    /** 轨道 (C14) 与传送带 (C15) 一次放置的结论。 */
    public enum TrackVerdict {
        ALLOW,
        /** 碰到外围 8 格 (区外)。 */
        BUFFER,
        /** 碰到自管区本身。 */
        DISTRICT
    }

    /** 这一次 tryConnect 里 placeTracks(simulate = true) 收到的 PlacementInfo (服务端线程)。 */
    private static final ThreadLocal<Object> TRACK_INFO = new ThreadLocal<>();

    /** C14: tryConnect 的 HEAD, 清掉上一次留下的。 */
    public static void trackConnectStarted() {
        TRACK_INFO.remove();
    }

    /**
     * C14: placeTracks 的 HEAD。tryConnect 在算完两端、延伸段与曲线之后、开始扣物品之前, 以 simulate = true 调它一次;
     * 这里把那一次的 PlacementInfo 记下来 (只在服务端)。
     */
    public static void trackInfoComputed(@Nullable Level level, @Nullable Object info, boolean simulate) {
        if (simulate && level != null && !level.isClientSide && info != null) {
            TRACK_INFO.set(info);
        }
    }

    /**
     * C14 TrackPlacement.tryConnect (INVOKE Player.isCreative: tryConnect 里只出现这一次, 在扣物品与真正铺轨之前): 服务端、
     * 玩家不是 OP 例外、要铺的范围碰到禁放区 → 把这次的 PlacementInfo 置为无效并发动作栏提示, 返回它 (调用方以它为返回值
     * 提前结束; TrackBlockItem 见 valid 为假就回 FAIL)。放行时返回 null。
     */
    @Nullable
    public static Object trackPlacementBlocked(@Nullable Level level, @Nullable Player player) {
        Object info = TRACK_INFO.get();
        TRACK_INFO.remove();
        try {
            GuardView view = active(level);
            if (view == null || player == null || info == null || GuardActors.isExemptOp(player)) {
                return null;
            }
            CreateReflection.TrackInfo track = CreateReflection.trackInfo(info);
            if (track == null) {
                return null;
            }
            TrackVerdict verdict = trackVerdict(level, track.pos1(), track.axis1(), track.end1Extent(), track.pos2(),
                    track.axis2(), track.end2Extent(), track.curveBounds());
            if (verdict == TrackVerdict.ALLOW || !CreateReflection.invalidate(info)) {
                return null;
            }
            GuardEvents.tellActionBar(player, verdict == TrackVerdict.DISTRICT
                    ? GuardEvents.KEY_PLACE_DENIED : GuardEvents.KEY_PLACE_DENIED_BUFFER);
            return info;
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("create tryConnect", failure);
            return null;
        }
    }

    /**
     * C14 的纯判定: 两端 pos1、pos2, 两端的直线延伸段 (沿 axis 逐格, 各 extent 格, 有曲线时多一格, 与 placeTracks
     * 同口径), 再加曲线的包围框。碰到自管区本身为 DISTRICT, 只碰到外围 8 格为 BUFFER。
     */
    public static TrackVerdict trackVerdict(Level level, BlockPos pos1, @Nullable Vec3 axis1, int extent1,
                                            BlockPos pos2, @Nullable Vec3 axis2, int extent2,
                                            @Nullable AABB curveBounds) {
        GuardView view = DistrictWorldGuards.view();
        if (!view.banActive()) {
            return TrackVerdict.ALLOW;
        }
        DistrictZoneSnapshot zones = view.zones();
        TrackVerdict verdict = TrackVerdict.ALLOW;
        int curveStep = curveBounds != null ? 1 : 0;
        verdict = worst(verdict, segment(zones, level, pos1, axis1, extent1 + curveStep));
        verdict = worst(verdict, segment(zones, level, pos2, axis2, extent2 + curveStep));
        verdict = worst(verdict, column(zones, level, pos1.getX(), pos1.getZ()));
        verdict = worst(verdict, column(zones, level, pos2.getX(), pos2.getZ()));
        if (curveBounds != null) {
            int x0 = Mth.floor(curveBounds.minX);
            int z0 = Mth.floor(curveBounds.minZ);
            int x1 = Mth.floor(curveBounds.maxX);
            int z1 = Mth.floor(curveBounds.maxZ);
            if (zones.touchesDistrict(level, x0, z0, x1, z1)) {
                verdict = TrackVerdict.DISTRICT;
            } else if (zones.touchesBanArea(level, x0, z0, x1, z1)) {
                verdict = worst(verdict, TrackVerdict.BUFFER);
            }
        }
        return verdict;
    }

    private static TrackVerdict segment(DistrictZoneSnapshot zones, Level level, BlockPos start, @Nullable Vec3 axis,
                                        int extent) {
        TrackVerdict verdict = TrackVerdict.ALLOW;
        if (axis == null) {
            return verdict;
        }
        for (int i = 0; i < extent && verdict != TrackVerdict.DISTRICT; i++) {
            BlockPos at = start.offset(BlockPos.containing(axis.scale(i)));
            verdict = worst(verdict, column(zones, level, at.getX(), at.getZ()));
        }
        return verdict;
    }

    private static TrackVerdict column(DistrictZoneSnapshot zones, Level level, int x, int z) {
        if (zones.zoneAt(level, x, z) != 0) {
            return TrackVerdict.DISTRICT;
        }
        return zones.inBanArea(level, x, z) ? TrackVerdict.BUFFER : TrackVerdict.ALLOW;
    }

    private static TrackVerdict worst(TrackVerdict a, TrackVerdict b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    // ================================================================
    // C15 传送带
    // ================================================================

    /** 物品 NBT 里记着第一根传动杆的键 (BeltConnectorItem 自己的写法)。 */
    public static final String BELT_FIRST_PULLEY = "FirstPulley";

    /** OP 亲手连的这一条 (useOn 放行时记下, createBelts 按同一个世界、同样的两端认)。 */
    private record BeltExemption(Level level, BlockPos start, BlockPos end) {
    }

    private static final ThreadLocal<BeltExemption> BELT_EXEMPT = new ThreadLocal<>();

    /** C15: useOn 的 HEAD, 清掉上一次留下的。 */
    public static void beltUseStarted() {
        BELT_EXEMPT.remove();
    }

    /**
     * C15 BeltConnectorItem.useOn 里唯一一次 canConnect 之前 (服务端, 扣物品与铺传送带之前): 玩家不是 OP 例外、这一条
     * 传送带 (第一根传动杆到这一根的框) 碰到禁放区 → 拦下 (调用方回 FAIL, 不扣物品, 物品上记着的第一根传动杆不动) 并发
     * 动作栏提示。OP 放行, 并记下这一条, 让 createBelts 认出来。
     *
     * @return 拦下为 true
     */
    public static boolean beltConnectBlocked(@Nullable Level level, @Nullable Player player, @Nullable ItemStack stack,
                                             @Nullable BlockPos clicked) {
        try {
            GuardView view = active(level);
            if (view == null || player == null || stack == null || clicked == null) {
                return false;
            }
            CompoundTag tag = stack.getTag();
            if (tag == null || !tag.contains(BELT_FIRST_PULLEY, Tag.TAG_COMPOUND)) {
                return false;
            }
            BlockPos first = NbtUtils.readBlockPos(tag.getCompound(BELT_FIRST_PULLEY));
            if (GuardActors.isExemptOp(player)) {
                BELT_EXEMPT.set(new BeltExemption(level, first, clicked));
                return false;
            }
            TrackVerdict verdict = boxVerdict(view.zones(), level, first, clicked);
            if (verdict == TrackVerdict.ALLOW) {
                return false;
            }
            GuardEvents.tellActionBar(player, verdict == TrackVerdict.DISTRICT
                    ? GuardEvents.KEY_PLACE_DENIED : GuardEvents.KEY_PLACE_DENIED_BUFFER);
            return true;
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("create belt connector", failure);
            return false;
        }
    }

    /**
     * C15 BeltConnectorItem.createBelts 的 HEAD: 传送带从 start 铺到 end, 沿途不可替换的方块一律 destroyBlock, 不发任何
     * 事件。这一条的框碰到禁放区就整条不铺 (蓝图炮打印的传送带长度来自蓝图里的方块实体数据, 最长可到 1000 格); OP 在
     * useOn 里放行过的同一条除外。
     *
     * @return 可以铺为 true
     */
    public static boolean beltMayCreate(@Nullable Level level, @Nullable BlockPos start, @Nullable BlockPos end) {
        BeltExemption exempt = BELT_EXEMPT.get();
        BELT_EXEMPT.remove();
        try {
            GuardView view = active(level);
            if (view == null || start == null || end == null) {
                return true;
            }
            if (exempt != null && exempt.level() == level && exempt.start().equals(start) && exempt.end().equals(end)) {
                return true;
            }
            return !view.zones().touchesBanArea(level, start.getX(), start.getZ(), end.getX(), end.getZ());
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("create createBelts", failure);
            return true;
        }
    }

    /** 两个端点围成的框: 碰到自管区本身为 DISTRICT, 只碰到外围 8 格为 BUFFER。 */
    public static TrackVerdict boxVerdict(DistrictZoneSnapshot zones, Level level, BlockPos a, BlockPos b) {
        if (zones.touchesDistrict(level, a.getX(), a.getZ(), b.getX(), b.getZ())) {
            return TrackVerdict.DISTRICT;
        }
        return zones.touchesBanArea(level, a.getX(), a.getZ(), b.getX(), b.getZ())
                ? TrackVerdict.BUFFER : TrackVerdict.ALLOW;
    }

    // ================================================================
    // C16 对称之杖
    // ================================================================

    /**
     * C16 SymmetryWandItem.remove / apply 里的 {@code Map.keySet()} (镜像出来的全部位置): 去掉落到别的区域的位置 (单向口径:
     * 镜像位置属于某个在用自管区, 且与玩家这一下所在的格子 origin 不同区域), OP 例外。拿掉的位置随之既不拆 (也不掉落)、
     * 也不放。没有要拿掉的时原样返回。
     */
    public static <V> Set<BlockPos> symmetryTargets(Map<BlockPos, V> positions, @Nullable Level level,
                                                    @Nullable Player player, @Nullable BlockPos origin) {
        Set<BlockPos> all = positions.keySet();
        try {
            GuardView view = active(level);
            if (view == null || origin == null || GuardActors.isExemptOp(player)) {
                return all;
            }
            DistrictZoneSnapshot zones = view.zones();
            Set<BlockPos> kept = new LinkedHashSet<>();
            for (BlockPos pos : all) {
                if (pos.equals(origin) || !DistrictWorldGuards.oneWayBlocked(zones, level, origin.getX(), origin.getZ(),
                        pos.getX(), pos.getZ())) {
                    kept.add(pos);
                }
            }
            return kept.size() == all.size() ? all : kept;
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("create symmetry wand", failure);
            return all;
        }
    }

    // ================================================================
    // C17 机械臂
    // ================================================================

    /** 交互点 NBT 里相对机械臂的位置 (ArmInteractionPoint.serialize 的写法)。 */
    public static final String ARM_POINT_POS = "Pos";

    /**
     * C17 ArmInteractionPoint.deserialize 的 HEAD: 交互点 = anchor + tag.Pos (与 deserialize 同口径)。按单向口径, 交互点
     * 属于某个在用自管区且与机械臂不同区域 → 这个点读不出来 (返回 null, 机械臂跳过它)。网络包 (ArmPlacementPacket 不查距离
     * 与归属)、蓝图炮打印的机械臂、存档里读出来的都经过这里。
     *
     * @return 可以用这个点为 true
     */
    public static boolean armPointMayReach(@Nullable Level level, @Nullable BlockPos anchor,
                                           @Nullable CompoundTag tag) {
        try {
            GuardView view = active(level);
            if (view == null || anchor == null || tag == null || !tag.contains(ARM_POINT_POS, Tag.TAG_COMPOUND)) {
                return true;
            }
            BlockPos point = NbtUtils.readBlockPos(tag.getCompound(ARM_POINT_POS)).offset(anchor);
            return armPointMayReach(level, anchor, point);
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("create arm point", failure);
            return true;
        }
    }

    /** C17 的纯判定 (单向口径, 机械臂 → 交互点)。 */
    public static boolean armPointMayReach(Level level, BlockPos arm, BlockPos point) {
        GuardView view = active(level);
        return view == null || !DistrictWorldGuards.oneWayBlocked(view.zones(), level, arm.getX(), arm.getZ(),
                point.getX(), point.getZ());
    }

    // ================================================================
    // C18 显示链接
    // ================================================================

    /**
     * C18 DisplayLinkBlockEntity.updateGatheredData 的 HEAD: 目标 (要写的告示牌、讲台等) 或来源 (要读的方块) 属于某个在用
     * 自管区且与显示链接不同区域 (单向口径) → 这一次不读不写。目标偏移可以来自蓝图 (writeSafe 带着它), 服务端也不查距离。
     *
     * @return 可以更新为 true
     */
    public static boolean displayLinkMayUpdate(@Nullable Level level, @Nullable BlockPos link, @Nullable BlockPos source,
                                               @Nullable BlockPos target) {
        try {
            GuardView view = active(level);
            if (view == null || link == null) {
                return true;
            }
            DistrictZoneSnapshot zones = view.zones();
            return (target == null || !DistrictWorldGuards.oneWayBlocked(zones, level, link.getX(), link.getZ(),
                    target.getX(), target.getZ()))
                    && (source == null || !DistrictWorldGuards.oneWayBlocked(zones, level, link.getX(), link.getZ(),
                    source.getX(), source.getZ()));
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("create display link", failure);
            return true;
        }
    }

    // ================================================================
    // C19 土豆加农炮
    // ================================================================

    /**
     * C19 PotatoCannonProjectileType.onBlockHit 的 HEAD ("受保护"口径, 同 C1 ~ C13): 被打中的方块或它被打中那一面的邻格受
     * 保护 → 不执行打中方块的动作 (返回 false: 射弹照原版掉落或回收物品)。放方块 (南瓜、西瓜) 与种作物都落在这两格里。
     * Flan 的射弹规则只管少数几种方块, 对别的方块一律放行, 所以不能交给它。
     *
     * @return 可以执行打中方块的动作为 true
     */
    public static boolean potatoMayHitBlock(@Nullable LevelAccessor level, @Nullable BlockHitResult hit) {
        try {
            if (!(level instanceof Level world) || hit == null) {
                return true;
            }
            GuardView view = active(world);
            if (view == null) {
                return true;
            }
            BlockPos pos = hit.getBlockPos();
            return !protectedAt(view, world, pos) && !protectedAt(view, world, pos.relative(hit.getDirection()));
        } catch (RuntimeException failure) {
            DistrictWorldGuards.failOpen("create potato cannon", failure);
            return true;
        }
    }
}
