package com.miningdim.stacking;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 主动产出: 击杀堆叠倍增 (需求规格 FR-2; AC-3 / AC-4)。挂 {@link LivingDeathEvent}。
 *
 * 语义与避重策略 (FR-2 "避免与原版掉落重复" 的明确二选一):
 *  - 原版死亡链 ({@code LivingEntity.die -> dropAllDeathLoot}) 在本事件后照常执行, 即原版会按 "1 个个体" 自然掉落
 *    1 份战利品 + 1 份经验 (走原版完整 looting/luck/ForgeHooks.onLivingDrops 上下文)。
 *  - 本 handler 只补 "其余 N-1 个虚拟个体": 对 N-1 个虚拟个体【分别独立】跑原版 LootTable roll (FR-2.2 概率正确性,
 *    严禁 base*N), 经验按 (N-1) x 单个体经验累加 (FR-2.4)。N==1 时不补, 退化为纯原版掉落。
 *  - death_mode=INSTANT_ALL: 整堆一次结算全部 N (本 handler 补 N-1, 原版掉 1, 合计 N), 实体随原版死亡移除 (FR-2.5)。
 *  - death_mode=ONE_PER_KILL: 仅剥离 1 个个体 —— 即只让原版掉的那 1 份生效, 不补任何额外份; 堆叠数 -1 后让实体存活
 *    (撤销原版的移除), 直至剥到 1 再正常死亡 (FR-2.5)。
 *  - 环境致死 (岩浆/跌落/凋灵): 同样命中本事件, 按上述 INSTANT_ALL 结算全部 N 份 (FR-2.6); 这些来源 lastHurtByPlayer
 *    为空, per_individual roll 的 LootContext 不带 LAST_DAMAGE_PLAYER, 自然不含抢夺加成, 与原版环境致死一致。
 *
 * loot_roll_mode (FR-2.2):
 *  - PER_INDIVIDUAL: 对每个虚拟个体用实体自身 loot table + 重建的 {@link LootParams} 跑一次独立 roll, 稀有掉落/抢夺
 *    概率统计正确 (AC-4)。
 *  - MULTIPLY_BASE: 单次 roll 结果 x (N-1) (规格标注不推荐, 仅兼容保留; 概率不正确)。
 *
 * 掉落物落地: 全部 ItemStack 合并按物品最大堆叠 (通常 64) 分批生成 {@link ItemEntity} (FR-2.3), 避免单物品超量时一次
 * 生成上千个 ItemEntity。经验用 {@link ExperienceOrb#award} 在死亡点投放 (与原版 dropExperience 同 API)。
 *
 * 线程 (NFR-5): LivingDeathEvent 在服务端主线程触发, 所有 spawn/award 同线程, 无并发。
 *
 * 纯核心 ({@link #rollStackedLoot} / {@link #spawnBatchedDrops}) 与事件 handler 分离, 便于 GameTest 直接驱动断言
 * (AC-3 总数落在 N 次独立 roll 区间 / ItemEntity 按 <=maxStack 分批; AC-4 N 次独立 LootContext 概率正确)。
 */
public final class StackDeath {

    /** 由 {@link StackingSystem#register} 实例化并注册到 forge bus (package-private: 仅本子系统装配)。 */
    StackDeath() {
    }

    /**
     * 击杀堆叠实体的倍增结算 (FR-2 主入口)。仅对带堆叠标记且 N>1 的可堆叠 LivingEntity 生效; 普通实体 (N==1) 直接
     * 放行走原版。事件不取消 —— 原版死亡链负责掉那 "1 份" 与移除实体; 本 handler 视 death_mode 补差额。
     */
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        // 命名/驯服/Boss/blacklist 不参与堆叠, 其死亡也不倍增 (与合并侧排除一致; canStack 在 isAlive 上判定,
        // 死亡事件中实体仍 isAlive=true, 故此处判定有效)。
        if (!StackMerge.canStack(entity)) {
            return;
        }
        int stackSize = StackData.getStackSize(entity);
        if (stackSize <= 1) {
            return;
        }

        StackingConfig.DeathMode mode = StackingConfig.DROPS_DEATH_MODE.get();
        if (mode == StackingConfig.DeathMode.ONE_PER_KILL) {
            handleOnePerKill(event, entity);
            return;
        }
        handleInstantAll(level, entity, event.getSource(), stackSize);
    }

    /**
     * INSTANT_ALL (FR-2.5): 原版掉 1 份并移除实体, 本方法补其余 N-1 份掉落 + (N-1)x 经验。整堆一次清空。
     */
    private void handleInstantAll(ServerLevel level, LivingEntity entity, DamageSource source, int stackSize) {
        int extraIndividuals = stackSize - 1;
        List<ItemStack> extraDrops = rollStackedLoot(level, entity, source, extraIndividuals);
        spawnBatchedDrops(level, entity.blockPosition(), extraDrops);
        awardStackedExperience(level, entity, extraIndividuals);
    }

    /**
     * ONE_PER_KILL (FR-2.5): 每次击杀只剥离 1 个个体。原版那 1 份掉落/经验已足够 (恰是 "1 个个体"), 故不补任何额外份;
     * 仅把堆叠数 -1 并撤销原版的实体移除 (取消死亡事件), 让幸存堆叠继续存在。剥到堆叠数 1 时不再取消, 实体正常死亡。
     */
    private void handleOnePerKill(LivingDeathEvent event, LivingEntity entity) {
        // 取消死亡 -> 实体不被移除。但取消死亡会让原版 die() 不执行, 原版掉落也不发生 —— 故 ONE_PER_KILL 下需本方法
        // 自行掉那 1 份。否则 "击杀剥 1 但什么都不掉" 违反 FR-2.5。
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        List<ItemStack> oneDrop = rollStackedLoot(level, entity, event.getSource(), 1);
        spawnBatchedDrops(level, entity.blockPosition(), oneDrop);
        awardStackedExperience(level, entity, 1);

        // incr(-1): 入口已保证 stackSize>1, 故剥 1 后 >=1; 若越界 incr 自身抛 IllegalArgumentException (异常必痛)。
        StackData.incr(entity, -1);
        StackMerge.applyLabel(entity);
        // 撤销原版移除: 恢复满血并取消死亡事件, 使幸存堆叠继续存活。
        entity.setHealth(entity.getMaxHealth());
        entity.setLastHurtByMob(null);
        event.setCanceled(true);
    }

    /**
     * 对 {@code count} 个虚拟个体执行战利品 roll (FR-2.1 / FR-2.2)。返回所有产出 ItemStack 的扁平列表 (未分批未落地)。
     *
     * PER_INDIVIDUAL: 用实体自身 loot table + 死亡上下文重建的 {@link LootParams}, 跑 {@code count} 次独立 roll
     * (每次新随机种子, 概率独立); 这是稀有/抢夺统计正确性的核心 (AC-4)。
     * MULTIPLY_BASE: 跑 1 次 roll 再把结果 x count (概率不正确, 规格不推荐, 仅 config 显式选中时走)。
     *
     * @param count 虚拟个体数 (须 >=1)
     */
    public static List<ItemStack> rollStackedLoot(ServerLevel level, LivingEntity entity, DamageSource source, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("loot roll count must be >= 1, got " + count);
        }
        ResourceLocation lootTableId = entity.getLootTable();
        LootTable table = level.getServer().getLootData().getLootTable(lootTableId);

        StackingConfig.LootRollMode rollMode = StackingConfig.DROPS_LOOT_ROLL_MODE.get();
        List<ItemStack> out = new ArrayList<>();
        if (rollMode == StackingConfig.LootRollMode.MULTIPLY_BASE) {
            // base*N: 单次 roll, 结果按 count 复制 (概率不独立, 规格不推荐)。
            List<ItemStack> base = table.getRandomItems(buildLootParams(level, entity, source));
            for (int i = 0; i < count; i++) {
                for (ItemStack stack : base) {
                    out.add(stack.copy());
                }
            }
            return out;
        }
        // PER_INDIVIDUAL: count 次独立 roll, 每次新 LootParams (其内随机源独立播种), 概率统计正确 (FR-2.2)。
        for (int i = 0; i < count; i++) {
            List<ItemStack> rolled = table.getRandomItems(buildLootParams(level, entity, source));
            out.addAll(rolled);
        }
        return out;
    }

    /**
     * 重建实体死亡掉落的 {@link LootParams} (照搬 {@code LivingEntity.dropFromLootTable} 的参数装配): THIS_ENTITY /
     * ORIGIN / DAMAGE_SOURCE / KILLER / DIRECT_KILLER + 若有玩家击杀者则 LAST_DAMAGE_PLAYER + luck (供抢夺/幸运
     * 修饰生效)。每个虚拟个体取一份新 Builder, 保证 roll 间随机独立。
     */
    private static LootParams buildLootParams(ServerLevel level, LivingEntity entity, DamageSource source) {
        LootParams.Builder builder = new LootParams.Builder(level)
                .withParameter(LootContextParams.THIS_ENTITY, entity)
                .withParameter(LootContextParams.ORIGIN, entity.position())
                .withParameter(LootContextParams.DAMAGE_SOURCE, source)
                .withOptionalParameter(LootContextParams.KILLER_ENTITY, source.getEntity())
                .withOptionalParameter(LootContextParams.DIRECT_KILLER_ENTITY, source.getDirectEntity());
        Player killer = entity.getKillCredit() instanceof Player p ? p : null;
        if (killer != null) {
            builder = builder.withParameter(LootContextParams.LAST_DAMAGE_PLAYER, killer)
                    .withLuck(killer.getLuck());
        }
        return builder.create(LootContextParamSets.ENTITY);
    }

    /**
     * 把扁平 ItemStack 列表按物品最大堆叠分批生成 {@link ItemEntity} 落地 (FR-2.3)。同种物品先归并数量, 再按
     * {@code maxStackSize} 切块, 每块一个 ItemEntity —— 避免某物品累计上千个时生成上千 ItemEntity。
     *
     * @return 实际生成的 ItemEntity 数 (供测试断言分批)
     */
    public static int spawnBatchedDrops(ServerLevel level, BlockPos pos, List<ItemStack> drops) {
        int spawned = 0;
        for (ItemStack stack : drops) {
            if (stack.isEmpty()) {
                continue;
            }
            int remaining = stack.getCount();
            int max = stack.getMaxStackSize();
            while (remaining > 0) {
                int batch = Math.min(remaining, max);
                ItemStack chunk = stack.copy();
                chunk.setCount(batch);
                ItemEntity item = new ItemEntity(level, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, chunk);
                item.setDefaultPickUpDelay();
                level.addFreshEntity(item);
                spawned++;
                remaining -= batch;
            }
        }
        return spawned;
    }

    /**
     * 投放 {@code count} 个虚拟个体的经验 (FR-2.4)。受 drops.multiply_xp 控制: 关闭时不补任何额外经验 (原版那 1 份仍在)。
     *
     * 同时复刻原版 {@code dropExperience} 的投放前置条件, 防止造出经验 faucet:
     *  - 仅玩家击杀 (getKillCredit 为 Player) 才补经验 —— 环境致死 (岩浆/跌落) 原版本就不掉经验 (FR-2.6 掉落按 N,
     *    但经验保持原版 "无玩家击杀不掉经验" 语义, 不凭空多产), 故此处也不补。
     *  - shouldDropExperience() 复刻 (幼年原版不掉经验)。
     *
     * 经验值 = sum(各虚拟个体 getExperienceReward) —— 逐个调 getExperienceReward 取其随机经验 (与原版每只独立同分布)。
     */
    private static void awardStackedExperience(ServerLevel level, LivingEntity entity, int count) {
        if (!StackingConfig.DROPS_MULTIPLY_XP.get()) {
            return;
        }
        // 无玩家击杀 / 幼年: 与原版一致不补经验 (否则环境致死整堆会凭空产 N-1 经验, 经济 faucet)。
        if (!(entity.getKillCredit() instanceof net.minecraft.world.entity.player.Player) || !entity.shouldDropExperience()) {
            return;
        }
        int total = 0;
        for (int i = 0; i < count; i++) {
            total += entity.getExperienceReward();
        }
        if (total > 0) {
            ExperienceOrb.award(level, entity.position(), total);
        }
    }

    /** 把一个堆叠实体当 {@code count} 个虚拟个体, 累加其经验 (供 GameTest 直接断言 sum, 不投放实体)。 */
    public static int sumExperience(LivingEntity entity, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("xp count must be >= 1, got " + count);
        }
        int total = 0;
        for (int i = 0; i < count; i++) {
            total += entity.getExperienceReward();
        }
        return total;
    }
}
