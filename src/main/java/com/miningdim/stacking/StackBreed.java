package com.miningdim.stacking;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 繁殖 (需求规格 FR-4)。挂 {@link PlayerInteractEvent.EntityInteract}: 对堆叠 N 的成年 {@link Animal} 投喂繁殖材料时,
 * 明确语义为 "每次投喂触发 1 对个体繁殖、产 1 个幼崽、消耗对应材料" (FR-4.1)。
 *
 * 与原版繁殖的差异 (堆叠语义下的明确取舍):
 *  - 原版需两只独立成年动物各自被喂到 inLove 才配对生 1 崽。堆叠后 N 只挤成一个实体, "找另一只" 的原版 AI 配对失效。
 *    故本系统接管: 玩家对堆叠成年动物投喂 1 份材料 = 该堆叠内 "1 对个体" 繁殖, 直接产 1 幼崽 + 消耗 1 份材料
 *    (FR-4.1)。堆叠数 N 不因生育而减 (堆叠代表的是 "存栏成年数", 生崽是新增个体, 不消耗母体)。
 *  - 幼崽是独立新实体, setBaby(true), age 隔离 (FR-4.2): 它带 baby 年龄, {@link StackMatchKey} 的 baby 维度使其
 *    只能并入 "幼年堆叠", 不会并进成年母堆叠 (复用阶段 1 年龄隔离, 无需本类额外处理)。
 *  - max_stack 约束 (FR-4.3): 幼崽以 stack=1 落地, 自身不触碰成年堆叠计数, 故成年堆叠不会因繁殖突破上限; 幼崽侧
 *    后续若被周期扫描并入幼年堆叠, 也走 {@link StackMerge} 的 max_stack 封顶 (溢出另起新堆叠), 同样不破上限。
 *
 * 仅服务端结算 (NFR-5); 客户端放行不改状态。本类不接管原版双只配对 (两只独立未堆叠成年动物仍走原版 AI 繁殖,
 * canStack 把它们排除在 "堆叠" 之外的判定不影响原版 mobInteract —— 本类只在 target 已是 N>1 堆叠时介入)。
 */
public final class StackBreed {

    /** 由 {@link StackingSystem#register} 实例化并注册到 forge bus (package-private: 仅本子系统装配)。 */
    StackBreed() {
    }

    @SubscribeEvent
    public void onEntityInteractBreed(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) {
            return;
        }
        if (!(event.getTarget() instanceof Animal animal)) {
            return;
        }
        int stackSize = StackData.getStackSize(animal);
        if (stackSize <= 1 || !StackMerge.canStack(animal)) {
            return;
        }
        ItemStack held = event.getItemStack();
        if (!animal.isFood(held)) {
            return; // 非该动物的繁殖材料 -> 放行 (可能是挤奶/剪毛等其它交互, 由对应 handler 或原版处理)。
        }
        if (!(animal.level() instanceof ServerLevel level)) {
            return;
        }
        // 受控繁殖 (FR-4.1 每次投喂 1 对产 1 崽): feedBreed 内判 age==0 (成年且非繁殖冷却) 才产崽并置冷却。
        // 幼年/冷却中/不可育 不接管 (放行: 幼年喂食 = 原版加速成长)。
        if (!feedBreed(level, animal)) {
            return;
        }
        // 消耗 1 份材料 (创造模式不耗, 与原版 usePlayerItem 一致)。仅繁殖成功才消耗。
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /**
     * 受控喂食繁殖 (FR-4.1): 仅 {@code age==0} 的成年 (非幼年 age&lt;0 / 非繁殖冷却 age&gt;0) 才产 1 崽并置 6000t 冷却。
     * 关键: 用 {@link net.minecraft.world.entity.AgeableMob#setAge(int)} 正向冷却而非 {@code setInLove} —— setInLove
     * 会激活 vanilla BreedGoal, 使邻近多只被喂的堆叠动物互相繁殖产【额外】幼崽 (破 FR-4.1 "每次投喂 1 崽" 受控语义);
     * 正向 age 冷却只防连喂复刷, 既不触 vanilla AI 繁殖, 也使 age 每 tick 自然回 0 后可再繁殖 (与原版繁殖冷却同值)。
     *
     * @return true 已产崽并置冷却; false 幼年/冷却中/不可育 (调用方据此放行)
     */
    public static boolean feedBreed(ServerLevel level, Animal animal) {
        if (animal.isBaby() || animal.getAge() != 0) {
            return false; // 幼年(age<0) 或 繁殖冷却中(age>0): 不接管。
        }
        if (!breedOnce(level, animal)) {
            return false; // getBreedOffspring 返回 null (不可育)。
        }
        animal.setAge(6000); // 繁殖冷却 (vanilla 同值): 6000t 后 age 自然回 0 才能再喂繁殖。
        return true;
    }

    /**
     * 在堆叠母体处生 1 个幼崽 (FR-4.1)。幼崽 setBaby(true) 并落地为独立 stack=1 新实体 (FR-4.2 年龄隔离 / FR-4.3
     * 不破成年堆叠上限)。
     *
     * @return true 表示成功产崽; false 表示该动物不可育 (getBreedOffspring 返回 null)
     */
    public static boolean breedOnce(ServerLevel level, Animal parent) {
        AgeableMob child = parent.getBreedOffspring(level, parent);
        if (child == null) {
            return false;
        }
        child.setBaby(true);
        child.moveTo(parent.getX(), parent.getY(), parent.getZ(), 0.0F, 0.0F);
        // 幼崽不写堆叠键 -> 默认 stack 1 (StackData.getStackSize 缺键返回 1)。年龄隔离与 max_stack 由后续合并扫描自然处理。
        level.addFreshEntity(child);
        return true;
    }
}
