package com.miningdim.trap;

import com.miningdim.core.Difficulty;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningServices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态陷阱运行期引擎 (设计文档 9.6 - 9.8, 19.2)。由 {@link TrapSystem} 在 LevelTickEvent(END, 仅矿山维度)
 * 按 danger 评估周期驱动。本类只做"决策" (在 tick 线程读 danger / 算位置 / 判节流), 所有世界写
 * (刷怪/setBlock/落沙/爆炸) 经 server.execute() 回主线程 (TR-5/D8/19.2 末)。
 *
 * 三类动态陷阱:
 *  - 身后刷苦力怕 (9.7): danger >= DANGER_THRESH_CREEPER 且玩家背向有合法点, 8-20 格、视锥外、可达、非陷阱区;
 *    计入 InstanceState.liveMobs (与第十章单实例 mob 硬上限共享计数, 9.7 末)。
 *  - 局部坍塌 (9.6): danger 概率 tick, 预警 10 tick 后 setBlock 把头顶若干列方块换成下落态/空气 (19.2 默认 setBlock,
 *    不批量 spawn FallingBlock; 仅视觉关键点少量真实 FallingBlock), 累计伤害封顶 6.0。
 *  - 岩浆喷发 (9.6): danger 概率 tick, 预警 20 tick 后有限步岩浆填充, RECYCLE_TICKS 后回收; 每实例同时至多 1 处。
 *
 * 节流 (9.8): 每实例每评估周期触发动态陷阱次数 <= DYNAMIC_TRIGGERS_PER_EVAL; 各陷阱另有独立冷却。
 * 引擎状态 (冷却时间戳) 为运行期瞬态, 不持久化 (实例卸载即弃)。
 */
public final class DynamicTrapEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/DynamicTrapEngine");

    /** 每实例运行期节流状态: 各陷阱上次触发的 server game time。 */
    private final Map<Long, InstanceThrottle> throttleByInstance = new ConcurrentHashMap<>();

    /** danger 供给 (由压力子系统注入; 未注入则 danger=0)。 */
    private volatile DangerSource dangerSource = (player, instanceId) -> 0.0f;

    /** 标记是否已就 danger 源缺失告警过一次 (避免每周期刷屏)。 */
    private volatile boolean warnedNoDanger = false;

    void setDangerSource(DangerSource source) {
        if (source == null) {
            throw new IllegalArgumentException("DangerSource must not be null");
        }
        this.dangerSource = source;
        this.warnedNoDanger = false;
    }

    /**
     * 读当前注入的 danger 源对某玩家的取值 (与 evaluateInstance 内门控读同一字段)。
     * 供陷阱/压力接线回归测试断言 "注入后 danger > 0" (反向防再退化成恒 0 stub)。
     */
    float injectedDangerOf(ServerPlayer player, long instanceId) {
        return dangerSource.dangerOf(player, instanceId);
    }

    /**
     * 对单个活跃实例做一次动态陷阱评估 (调用方已确保: 该实例在矿山维度、有在线玩家、到了评估周期)。
     * 在 tick 线程执行, 内部世界写经 server.execute 提交。
     */
    void evaluateInstance(ServerLevel level, InstanceState instance, MinecraftServer server, long gameTime) {
        if (!MiningServices.config().trapDynamicEnabled()) {
            return; // 16.2.5 trap.dynamicEnabled=false 时全关
        }
        InstanceThrottle throttle = throttleByInstance.computeIfAbsent(instance.instanceId(), id -> new InstanceThrottle());
        int triggersThisEval = 0;

        for (UUID playerId : instance.playerSet()) {
            if (triggersThisEval >= TrapParams.DYNAMIC_TRIGGERS_PER_EVAL) {
                break; // 9.8 每实例每周期触发上限
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || player.serverLevel() != level) {
                continue;
            }
            float danger = dangerSource.dangerOf(player, instance.instanceId());
            if (danger <= 0.0f && !warnedNoDanger) {
                // 优雅降级声明 (不掩盖): danger 恒 0 通常意味压力子系统尚未注入 DangerSource。
                LOGGER.info("[miningdim] dynamic trap eval sees danger=0 (DangerSource not injected yet); danger-gated traps idle");
                warnedNoDanger = true;
            }

            // 优先级: 岩浆喷发 (高危, 阈值最高) > 局部坍塌 > 身后刷怪。每周期至多触发一类。
            // F035 配套: 致死类 (岩浆喷发/身后刷苦力怕) 在 Easy 区恒不触发, 与 TrapParams.difficultyFactor(EASY)=0
            // (新手区无致死陷阱) 对齐; 非致死的局部坍塌不受此门约束。
            if (danger >= TrapParams.DANGER_THRESH_LAVA
                    && lethalDynamicAllowed(instance.difficulty())
                    && throttle.canTriggerLava(gameTime)
                    && tryLavaBurst(level, instance, player, server, gameTime)) {
                throttle.markLava(gameTime);
                triggersThisEval++;
                continue;
            }
            if (danger >= TrapParams.DANGER_THRESH_COLLAPSE
                    && throttle.canTriggerCollapse(playerId, gameTime)
                    && tryLocalCollapse(level, instance, player, server, gameTime)) {
                throttle.markCollapse(playerId, gameTime);
                triggersThisEval++;
                continue;
            }
            if (danger >= TrapParams.DANGER_THRESH_CREEPER
                    && lethalDynamicAllowed(instance.difficulty())
                    && throttle.canTriggerCreeper(playerId, gameTime)
                    && tryBehindCreeper(level, instance, player, server)) {
                throttle.markCreeper(playerId, gameTime);
                triggersThisEval++;
            }
        }
    }

    /** 实例卸载/重置时清节流状态。 */
    void onInstanceReleased(long instanceId) {
        throttleByInstance.remove(instanceId);
    }

    // ---- 身后刷苦力怕 (9.6/9.7) ----

    private boolean tryBehindCreeper(ServerLevel level, InstanceState instance, ServerPlayer player,
                                     MinecraftServer server) {
        // 9.7 计入实例 mob 预算: 与第十章单实例硬上限共享 liveMobs 计数。
        int hardCap = MiningServices.config().mobMaxPerInstance();
        if (instance.liveMobs().size() >= hardCap) {
            return false;
        }
        BlockPos spawnPos = findBehindSpawn(level, instance, player);
        if (spawnPos == null) {
            return false; // 无合法身后点: 放弃本次 (19.2 末: 刷怪非关键, 从简)
        }
        UUID mobId = UUID.randomUUID();
        float yRot = player.getYRot();
        // 世界写回主线程 (TR-5/D8)。
        server.execute(() -> {
            if (instance.liveMobs().size() >= hardCap) {
                return; // 主线程二次校验, 防并发周期内超额
            }
            Creeper creeper = new Creeper(EntityType.CREEPER, level);
            creeper.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, yRot, 0.0f);
            creeper.setPersistenceRequired();
            // DG-6: 合法生成校验, 避免穿墙/非法点。
            if (!creeper.checkSpawnRules(level, MobSpawnType.EVENT)) {
                return;
            }
            creeper.setUUID(mobId);
            creeper.setTarget(player);
            if (level.addFreshEntity(creeper)) {
                instance.liveMobs().add(mobId);
                // TR-1: 生成即播方位提示音, 给玩家回头反应窗口。
                level.playSound(null, spawnPos.getX() + 0.5, spawnPos.getY() + 0.5, spawnPos.getZ() + 0.5,
                        SoundEvents.CREEPER_PRIMED, SoundSource.HOSTILE, 1.0f, 1.0f);
                level.sendParticles(ParticleTypes.SMOKE, spawnPos.getX() + 0.5, spawnPos.getY() + 1.0,
                        spawnPos.getZ() + 0.5, 6, 0.2, 0.3, 0.2, 0.01);
            }
        });
        return true;
    }

    /**
     * 在玩家身后 8-20 格找一个合法站立点 (9.7): 视锥外 (dot(look,dir) < cos70) + 脚下固体 + 头顶 2 格净空 +
     * 邻域无岩浆 (见 {@link #findStandableColumn})。复用本类站立点谓词 (与 11.2 同口径的运行期方块态版本)。
     */
    private BlockPos findBehindSpawn(ServerLevel level, InstanceState instance, ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        BlockPos base = player.blockPosition();
        var random = level.getRandom();
        double lookHorizLen = Math.sqrt(look.x * look.x + look.z * look.z);
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            int dist = TrapParams.MOB_MIN_SPAWN_DIST
                    + random.nextInt(TrapParams.MOB_MAX_SPAWN_DIST - TrapParams.MOB_MIN_SPAWN_DIST + 1);
            double dirX = Math.cos(angle);
            double dirZ = Math.sin(angle);
            // 视锥外判定: 水平方向与玩家朝向水平分量夹角。
            if (lookHorizLen > 1.0e-4) {
                double dot = (dirX * look.x + dirZ * look.z) / lookHorizLen;
                if (dot >= TrapParams.MOB_BEHIND_COS) {
                    continue; // 在视锥内, 不算身后
                }
            }
            int wx = base.getX() + (int) Math.round(dirX * dist);
            int wz = base.getZ() + (int) Math.round(dirZ * dist);
            BlockPos found = findStandableColumn(level, instance, wx, base.getY(), wz);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * 在 (wx,wz) 列以玩家 Y 为中心上下搜一个合法站立点 (脚下固体 + 头顶 2 格净空 + 邻域无岩浆 + 在 region 内)。
     * isStandable 提供 level.isLoaded 前置守卫与几何判据 (脚下固体/头顶净空); isSafe (core ISpawnService,
     * 唯一被允许的跨子系统口径) 补上"邻域无岩浆"这条真实危害判据, 取代已随离线静态陷阱布点表一起删除的
     * 致死禁区判据 (原判据依赖的离线布点表恒空已判废, 见 F033)。
     */
    private BlockPos findStandableColumn(ServerLevel level, InstanceState instance, int wx, int centerY, int wz) {
        if (!instance.regionBox().contains(wx, wz)) {
            return null; // 越 region 不刷
        }
        for (int dy = -6; dy <= 6; dy++) {
            int y = centerY + dy;
            if (!instance.regionBox().containsWorld(wx, y, wz)) {
                continue;
            }
            BlockPos feet = new BlockPos(wx, y, wz);
            if (isStandable(level, feet) && MiningServices.spawnService().isSafe(level, feet, instance)) {
                return feet;
            }
        }
        return null;
    }

    /** 运行期站立点谓词 (11.2 方块态版本): 脚下固体非岩浆、脚部与头顶为空气。 */
    private boolean isStandable(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        BlockPos head = feet.above();
        if (!level.isLoaded(feet) || !level.isLoaded(below) || !level.isLoaded(head)) {
            return false;
        }
        BlockState floor = level.getBlockState(below);
        if (floor.isAir() || !floor.getFluidState().isEmpty()) {
            return false; // 脚下须固体, 非流体 (岩浆/水)
        }
        return level.getBlockState(feet).isAir() && level.getBlockState(head).isAir();
    }

    // ---- 局部坍塌 (9.6, 19.2 setBlock 实现) ----

    private boolean tryLocalCollapse(ServerLevel level, InstanceState instance, ServerPlayer player,
                                     MinecraftServer server, long gameTime) {
        var random = level.getRandom();
        int columns = TrapParams.COLLAPSE_MIN_COLUMNS
                + random.nextInt(TrapParams.COLLAPSE_MAX_COLUMNS - TrapParams.COLLAPSE_MIN_COLUMNS + 1);
        BlockPos playerPos = player.blockPosition();
        // 选玩家头顶上方、作用半径内、有实心承重的列做坍塌。
        ArrayList<BlockPos> targets = new ArrayList<>(columns);
        for (int i = 0; i < columns; i++) {
            int ox = random.nextInt(TrapParams.DYNAMIC_EFFECT_RADIUS * 2 + 1) - TrapParams.DYNAMIC_EFFECT_RADIUS;
            int oz = random.nextInt(TrapParams.DYNAMIC_EFFECT_RADIUS * 2 + 1) - TrapParams.DYNAMIC_EFFECT_RADIUS;
            BlockPos ceiling = findCeilingBlock(level, instance, playerPos.getX() + ox,
                    playerPos.getY(), playerPos.getZ() + oz);
            if (ceiling != null) {
                targets.add(ceiling);
            }
        }
        if (targets.isEmpty()) {
            return false;
        }
        // F033 复核修正: 预警一响起就登记活跃危害坐标 (早于真正落地成方块态那一刻), 让陷阱探测在
        // reactionWindow 内有机会提前示警这处即将坍塌的列 (见 WorldHazards 类注释的完整取舍记录)。
        for (BlockPos t : targets) {
            WorldHazards.markActive(t, TrapType.LOCAL_COLLAPSE);
        }
        // TR-1: 先发预警 (粒子 + 落沙音), reactionWindow 10 tick 后再落方块。
        server.execute(() -> {
            for (BlockPos t : targets) {
                level.sendParticles(ParticleTypes.SMOKE, t.getX() + 0.5, t.getY() - 0.5, t.getZ() + 0.5,
                        4, 0.2, 0.1, 0.2, 0.0);
            }
            level.playSound(null, playerPos, SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 1.0f, 0.8f);
        });
        // 调度延迟落方块: 延迟到 reactionWindow 后 (在 TrapSystem 的延迟队列里主线程执行)。
        TrapSystem.get().scheduleDelayed(gameTime + TrapType.LOCAL_COLLAPSE.reactionWindowTicks(), () -> {
            for (BlockPos t : targets) {
                dropColumn(level, t);
                WorldHazards.clearActive(t); // 危害已处理完毕 (落方块或因二次校验放弃), 注销登记。
            }
        });
        return true;
    }

    /**
     * 从玩家头顶向上找第一块实心承重方块 (作为坍塌源)。无则 null。
     *
     * F036 修复: 第一块非空气/非流体方块若不属 {@link #isCollapsible} 白名单 (即是矿石或其他非承重方块),
     * 直接放弃这一列 (不继续往上找)。这保证任何情况下都不会删掉玩家头顶的矿, 也不会让落块穿过矿石 ——
     * 旧实现无差别把找到的第一块方块换成砂砾, 会静默吞掉玩家头顶的矿石并成为零成本砂砾产出。
     */
    private BlockPos findCeilingBlock(ServerLevel level, InstanceState instance, int wx, int feetY, int wz) {
        for (int dy = 2; dy <= 6; dy++) {
            int y = feetY + dy;
            if (!instance.regionBox().containsWorld(wx, y, wz)) {
                break;
            }
            BlockPos p = new BlockPos(wx, y, wz);
            if (!level.isLoaded(p)) {
                return null;
            }
            BlockState bs = level.getBlockState(p);
            if (bs.isAir() || !bs.getFluidState().isEmpty()) {
                continue;
            }
            return isCollapsible(bs) ? p : null;
        }
        return null;
    }

    /**
     * 承重白名单判据 (公开静态纯函数, 供同包 GameTest 锁死回归, 与本文件已有的 {@link #cooldownAllows} 同范式):
     * 只认原版基岩类石材 (石/花岗岩/闪长岩/安山岩/凝灰岩/深板岩) 或本身就是 {@link FallingBlock} (砂砾/沙/
     * 混凝土粉)。用白名单而非黑名单, 是因为 trap 子系统不得 import 矿物子系统实现 (铁律 2): 用基岩类标签
     * 天然把一切矿石排除在外, 无需知道矿物子系统认哪些方块是矿。
     */
    public static boolean isCollapsible(BlockState bs) {
        return bs.is(BlockTags.BASE_STONE_OVERWORLD) || bs.getBlock() instanceof FallingBlock;
    }

    /**
     * 坍塌单列 (19.2 DECIDED, F036 修复后): 读到承重块的真实方块态生成 1 个真实 FallingBlockEntity 做表现
     * (视觉关键点少量真实落块, 19.2 visualFallingBudget), 伤害封顶 COLLAPSE_DAMAGE_CAP。不批量 spawn 实体。
     * 由 private 改为包内可见, 供同包 GameTest 断言坍塌保留原方块态 (而非无差别写成砂砾)。
     *
     * 不手写 setBlock(source, AIR) 清空源格: 官方映射 sources (FallingBlockEntity.java:78-83) 里
     * {@link FallingBlockEntity#fall} 自身就会 setBlock(pos, fluidState.createLegacyBlock(), 3) 并
     * addFreshEntity, 手写那行是纯冗余 (且会与 fall 内部的 setBlock 竞争同一 tick 内的两次方块更新广播)。
     */
    void dropColumn(ServerLevel level, BlockPos source) {
        if (!level.isLoaded(source)) {
            return;
        }
        BlockState bs = level.getBlockState(source);
        // 延迟窗口 (reactionWindow) 内地形可能已被玩家挖掉或被上一次坍塌改写, 此处二次校验。
        if (bs.isAir() || !isCollapsible(bs)) {
            return;
        }
        FallingBlockEntity fb = FallingBlockEntity.fall(level, source, bs);
        // setHurtsEntities(perBlockDamage, maxDamage): 单块伤害 + 累计封顶 (9.4/9.6)。
        fb.setHurtsEntities(TrapType.LOCAL_COLLAPSE.damage(), (int) TrapParams.COLLAPSE_DAMAGE_CAP);
    }

    // ---- 岩浆喷发 (9.6, 19.2 有限步填充) ----

    private boolean tryLavaBurst(ServerLevel level, InstanceState instance, ServerPlayer player,
                                 MinecraftServer server, long gameTime) {
        BlockPos floor = findLavaBurstFloor(level, instance, player.blockPosition());
        if (floor == null) {
            return false;
        }
        // F033 复核修正: 预警一响起就登记活跃危害坐标, 覆盖预警窗口 + 存续窗口的整段时间 (而不仅是
        // fillLava 之后那 5 tick 的真实 LAVA 方块态窗口), 见 WorldHazards 类注释的完整取舍记录。
        WorldHazards.markActive(floor, TrapType.LAVA_BURST);
        // TR-1: 预警 20 tick (地面裂纹粒子 + 红光 + LAVA_POP), 之后喷出有限岩浆, RECYCLE_TICKS 后回收。
        server.execute(() -> {
            level.sendParticles(ParticleTypes.LAVA, floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5,
                    8, 0.3, 0.1, 0.3, 0.0);
            level.sendParticles(ParticleTypes.FLAME, floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5,
                    6, 0.2, 0.1, 0.2, 0.0);
            level.playSound(null, floor, SoundEvents.LAVA_POP, SoundSource.BLOCKS, 1.0f, 1.0f);
        });
        int reaction = TrapType.LAVA_BURST.reactionWindowTicks();
        TrapSystem trapSystem = TrapSystem.get();
        // 反应窗口后填充岩浆。
        trapSystem.scheduleDelayed(gameTime + reaction, () -> fillLava(level, floor));
        // 再过 RECYCLE_TICKS 回收 (9.6: 5 tick 后自动回收)。
        trapSystem.scheduleDelayed(gameTime + reaction + TrapParams.LAVA_BURST_RECYCLE_TICKS,
                () -> recycleLava(level, floor));
        return true;
    }

    /** 找玩家脚下附近一块可喷岩浆的地面 (实心地板上方为空气)。 */
    private BlockPos findLavaBurstFloor(ServerLevel level, InstanceState instance, BlockPos playerPos) {
        var random = level.getRandom();
        for (int attempt = 0; attempt < 8; attempt++) {
            int ox = random.nextInt(7) - 3;
            int oz = random.nextInt(7) - 3;
            int wx = playerPos.getX() + ox;
            int wz = playerPos.getZ() + oz;
            for (int dy = -2; dy <= 1; dy++) {
                int y = playerPos.getY() + dy;
                if (!instance.regionBox().containsWorld(wx, y, wz)) {
                    continue;
                }
                BlockPos cell = new BlockPos(wx, y, wz);
                BlockPos below = cell.below();
                if (!level.isLoaded(cell) || !level.isLoaded(below)) {
                    continue;
                }
                if (level.getBlockState(cell).isAir()
                        && !level.getBlockState(below).isAir()
                        && level.getBlockState(below).getFluidState().isEmpty()) {
                    return cell;
                }
            }
        }
        return null;
    }

    /** 有限步岩浆填充 (19.2: 预定义填充而非原版无限扩散); 单格岩浆源, 体积受 9.4 "<=2x2x2" 约束这里取 1 格。 */
    private void fillLava(ServerLevel level, BlockPos pos) {
        if (level.isLoaded(pos) && level.getBlockState(pos).isAir()) {
            level.setBlock(pos, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /** 回收岩浆 (9.6: 5 tick 后自动回收), 只清掉我们放下的那格岩浆。 */
    private void recycleLava(ServerLevel level, BlockPos pos) {
        if (level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.LAVA)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        WorldHazards.clearActive(pos); // 危害存续窗口结束, 注销登记 (无论回收前是否已被玩家自行处理)。
    }

    /**
     * 冷却放行判据 (公开静态纯函数, 供 GameTest 锁死回归): {@code lastTick == Long.MIN_VALUE} 表示"从未触发"直接放行;
     * 否则比较经过时间是否到冷却。【关键】严禁写 {@code now - lastTick >= cooldown} 不带哨兵判: lastTick 为
     * Long.MIN_VALUE 时 {@code now - Long.MIN_VALUE} 对任何现实正 gameTime 整数溢出成大负数 -> 恒 < 冷却 -> 首次
     * 永不放行 -> 时间戳永停哨兵 -> 三类动态陷阱永久死锁 (红队确认: 本 session 注入 danger 复活陷阱后才暴露)。
     */
    public static boolean cooldownAllows(long lastTick, long now, long cooldownTicks) {
        return lastTick == Long.MIN_VALUE || now - lastTick >= cooldownTicks;
    }

    /**
     * F035 配套门控 (公开静态纯函数, 供 GameTest 锁死回归): Easy 区恒不触发致死类动态陷阱 (岩浆喷发 / 身后
     * 刷苦力怕), 与 {@link TrapParams#difficultyFactor} 对 EASY 取 0 (新手区无致死陷阱) 的口径对齐。
     * 非致死的局部坍塌不受此门约束 (evaluateInstance 里 tryLocalCollapse 分支不接此判据)。
     */
    public static boolean lethalDynamicAllowed(Difficulty difficulty) {
        return difficulty != Difficulty.EASY;
    }

    // ---- 实例级节流状态 ----

    private static final class InstanceThrottle {
        private long lastLavaTick = Long.MIN_VALUE;
        private final Map<UUID, Long> lastCollapseByPlayer = new ConcurrentHashMap<>();
        private final Map<UUID, Long> lastCreeperByPlayer = new ConcurrentHashMap<>();

        boolean canTriggerLava(long now) {
            return cooldownAllows(lastLavaTick, now, TrapParams.LAVA_BURST_COOLDOWN_TICKS);
        }

        void markLava(long now) {
            lastLavaTick = now;
        }

        boolean canTriggerCollapse(UUID player, long now) {
            return cooldownAllows(lastCollapseByPlayer.getOrDefault(player, Long.MIN_VALUE),
                    now, TrapParams.COLLAPSE_PER_PLAYER_COOLDOWN_TICKS);
        }

        void markCollapse(UUID player, long now) {
            lastCollapseByPlayer.put(player, now);
        }

        boolean canTriggerCreeper(UUID player, long now) {
            return cooldownAllows(lastCreeperByPlayer.getOrDefault(player, Long.MIN_VALUE),
                    now, TrapParams.MOB_BEHIND_COOLDOWN_TICKS);
        }

        void markCreeper(UUID player, long now) {
            lastCreeperByPlayer.put(player, now);
        }
    }
}
