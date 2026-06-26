package com.miningdim.trap;

import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningServices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
            if (danger >= TrapParams.DANGER_THRESH_LAVA
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
     * 不在静态致死陷阱半径内。复用本类站立点谓词 (与 11.2 同口径的运行期方块态版本)。
     */
    private BlockPos findBehindSpawn(ServerLevel level, InstanceState instance, ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        BlockPos base = player.blockPosition();
        var random = level.getRandom();
        StaticTrapPlacement statics = TrapSystem.get().staticPlacement(instance.instanceId());
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
            BlockPos found = findStandableColumn(level, instance, statics, wx, base.getY(), wz);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 在 (wx,wz) 列以玩家 Y 为中心上下搜一个合法站立点 (脚下固体 + 头顶 2 格净空 + 非致死陷阱区 + 在 region 内)。 */
    private BlockPos findStandableColumn(ServerLevel level, InstanceState instance, StaticTrapPlacement statics,
                                         int wx, int centerY, int wz) {
        if (!instance.regionBox().contains(wx, wz)) {
            return null; // 越 region 不刷
        }
        for (int dy = -6; dy <= 6; dy++) {
            int y = centerY + dy;
            if (!instance.regionBox().containsWorld(wx, y, wz)) {
                continue;
            }
            BlockPos feet = new BlockPos(wx, y, wz);
            if (isStandable(level, feet) && !statics.inLethalTrapRadius(wx, y, wz)) {
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
            }
        });
        return true;
    }

    /** 从玩家头顶向上找第一块实心承重方块 (作为坍塌源)。无则 null。 */
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
            if (!bs.isAir() && bs.getFluidState().isEmpty()) {
                return p;
            }
        }
        return null;
    }

    /**
     * 坍塌单列 (19.2 DECIDED): 默认 setBlock 把承重块换成空气并在原位生成 1 个真实 FallingBlockEntity 做表现
     * (视觉关键点少量真实落沙, 19.2 visualFallingBudget), 伤害封顶 COLLAPSE_DAMAGE_CAP。不批量 spawn 实体。
     */
    private void dropColumn(ServerLevel level, BlockPos source) {
        if (!level.isLoaded(source)) {
            return;
        }
        BlockState bs = level.getBlockState(source);
        if (bs.isAir()) {
            return;
        }
        // 用砂砾作坍塌坠落态 (与崩塌矿道线索一致); 把源方块清空, 生成一个会砸下来的 FallingBlock。
        BlockState falling = Blocks.GRAVEL.defaultBlockState();
        level.setBlock(source, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        FallingBlockEntity fb = FallingBlockEntity.fall(level, source, falling);
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
    }

    // ---- 实例级节流状态 ----

    private static final class InstanceThrottle {
        private long lastLavaTick = Long.MIN_VALUE;
        private final Map<UUID, Long> lastCollapseByPlayer = new ConcurrentHashMap<>();
        private final Map<UUID, Long> lastCreeperByPlayer = new ConcurrentHashMap<>();

        boolean canTriggerLava(long now) {
            return now - lastLavaTick >= TrapParams.LAVA_BURST_COOLDOWN_TICKS;
        }

        void markLava(long now) {
            lastLavaTick = now;
        }

        boolean canTriggerCollapse(UUID player, long now) {
            return now - lastCollapseByPlayer.getOrDefault(player, Long.MIN_VALUE)
                    >= TrapParams.COLLAPSE_PER_PLAYER_COOLDOWN_TICKS;
        }

        void markCollapse(UUID player, long now) {
            lastCollapseByPlayer.put(player, now);
        }

        boolean canTriggerCreeper(UUID player, long now) {
            return now - lastCreeperByPlayer.getOrDefault(player, Long.MIN_VALUE)
                    >= TrapParams.MOB_BEHIND_COOLDOWN_TICKS;
        }

        void markCreeper(UUID player, long now) {
            lastCreeperByPlayer.put(player, now);
        }
    }
}
