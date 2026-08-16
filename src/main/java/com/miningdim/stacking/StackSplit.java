package com.miningdim.stacking;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 拆分与拴绳 (需求规格 FR-5; F066)。从零实现 —— 白名单化前 stacking 包对 leash/split/unstack 全部零命中。
 *
 * FR-5.1 (拆分): {@link #splitOne} 从堆叠数 N&gt;1 的实体拆出 1 个独立个体, 原堆叠数 -1, 新个体带
 * {@link StackData#NO_MERGE_UNTIL_KEY} 保护期 (splitGraceTicks) 防止刚拆出就被下一轮扫描立刻并回去。
 *
 * FR-5.2 (拴绳语义二选一, config interaction.leashMode): SPLIT_ONE 时对堆叠用拴绳 = 拆出 1 个体并单独拴住它;
 * WHOLE_STACK 时拴绳直接作用于整堆实体本身 (原版行为), 被拴住的堆叠因 {@link StackMerge#canMerge} 的
 * {@code isLeashed} 闸不再被合并 (语义自洽, 见 {@link #handleLead}); 但它仍是 {@link StackMerge#canStack} 认可
 * 的合法堆叠个体, 死亡/被动产出/繁殖/拆分照常结算, 不因被拴住而丢失 (findings 1/3/5 回归修复)。
 *
 * 本类的 {@link #onEntityInteract} handler 【不】受 {@link StackingConfig#ENABLED} 门控: 总开关关停只应停止
 * "发生新的合并", 不应连带锁死拆分交互 —— 否则总开关关停期间, 已存在的堆叠会失去唯一能把动物牵走 (脱离堆叠)
 * 的手段, 动物被永久锁死在堆叠里, 这比 "继续能合并" 更糟。
 */
public final class StackSplit {

    /** 由 {@link StackingSystem#register} 实例化并注册到 forge bus (package-private: 仅本子系统装配)。 */
    StackSplit() {
    }

    /**
     * 从堆叠 {@code source} 拆出 1 个独立个体 (FR-5.1): 新建同类型实体, 复制位置/朝向/年龄 (+ 羊的色与已剪态),
     * 落地为独立 stack=1 实体并置拆分保护期; 原堆叠数 -1 并刷新显示名。
     *
     * 只显式复制位置/朝向/年龄 (+ 羊变体) —— 严禁走 saveWithoutId/load 全量 NBT 克隆: 那会把 UUID/Leash/Passengers
     * 一并复制, UUID 撞车会让 {@link net.minecraft.world.level.entity.EntityLookup#add} 打 "Duplicate entity UUID"
     * 警告且不注册新实体 (byId/byUuid 两张表都不写入), 实体虽仍 addFreshEntity 却在服务端查找体系里形同丢失
     * (已核对 EntityLookup.java:33-41)。白名单只有四种动物, 其变体维度就是羊的色与已剪态 + 年龄, 显式拷贝是
     * 完备的 (YAGNI, 不为不存在的第五种变体预留扩展点)。
     *
     * @throws IllegalStateException 当 source 堆叠数 &lt;=1 (调用方必须先自行判定, 拆分要求确有可拆的堆叠),
     *                                或新建实体的类型不是 Animal (理论上不可能发生, 白名单四种均为 Animal, 出现
     *                                即说明白名单被破坏, 异常必痛不掩盖)
     */
    public static Animal splitOne(ServerLevel level, Animal source) {
        int size = StackData.getStackSize(source);
        if (size <= 1) {
            throw new IllegalStateException("split requires a stack size > 1, got " + size);
        }
        Entity created = source.getType().create(level);
        if (!(created instanceof Animal single)) {
            throw new IllegalStateException("EntityType.create for " + source.getType()
                    + " did not produce an Animal, got " + created);
        }
        single.moveTo(source.getX(), source.getY(), source.getZ(), source.getYRot(), source.getXRot());
        single.setAge(source.getAge());
        if (single instanceof Sheep out && source instanceof Sheep in) {
            out.setColor(in.getColor());
            out.setSheared(in.isSheared());
        }
        StackData.setStackSize(single, 1);
        StackData.setNoMergeUntil(single, level.getGameTime() + StackingConfig.SPLIT_GRACE_TICKS.get());
        level.addFreshEntity(single);

        StackData.incr(source, -1);
        StackMerge.applyLabel(source);
        return single;
    }

    /**
     * 处理对堆叠使用拴绳 (FR-5.2)。返回 true 表示本系统已接管这次拴绳 (调用方据此取消事件并扣道具);
     * 返回 false 表示放行原版处理。
     *
     * WHOLE_STACK 模式直接返回 false —— 这不是空壳, 而是该模式的完整语义: 拴绳应作用于整堆实体本身 (原版行为),
     * 让原版 {@code Mob.mobInteract} 的拴绳逻辑接管; 被拴住的堆叠随后因 {@link StackMerge#canMerge} 里的
     * {@code isLeashed} 闸不再吸收他者, 语义自洽收敛, 无需本类额外处理。
     */
    public static boolean handleLead(ServerLevel level, Player player, Animal target) {
        if (StackingConfig.LEASH_MODE.get() == StackingConfig.LeashMode.WHOLE_STACK) {
            return false;
        }
        if (!target.canBeLeashed(player)) {
            return false;
        }
        Animal single = splitOne(level, target);
        single.setLeashedTo(player, true);
        return true;
    }

    /**
     * FR-5 交互入口: 空手 + 潜行右键堆叠 = 拆出 1 只 (FR-5.1); 手持拴绳右键堆叠 = 按 leashMode 结算 (FR-5.2)。
     * 两条前置互斥, 且都要求 target 已是堆叠数 &gt;1 的可堆叠白名单动物, 故不会抢占 {@link StackPassive} 的
     * 剪毛/挤奶与 {@link StackBreed} 的投喂交互 (它们各自的前置条件 —— 剪刀/空桶/繁殖材料 —— 与本类的
     * "空手潜行" 或 "拴绳" 互斥)。
     */
    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) {
            return;
        }
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            // 仅主手处理一次, 防止双手各触发一次事件时重复拆分/拴绳。
            return;
        }
        if (!(event.getTarget() instanceof Animal animal)) {
            return;
        }
        if (StackData.getStackSize(animal) <= 1 || !StackMerge.canStack(animal)) {
            return;
        }
        if (!(animal.level() instanceof ServerLevel level)) {
            return;
        }

        ItemStack held = event.getItemStack();
        if (held.is(Items.LEAD)) {
            if (!handleLead(level, player, animal)) {
                return;
            }
            if (!player.getAbilities().instabuild) {
                held.shrink(1);
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        if (held.isEmpty() && player.isShiftKeyDown()) {
            splitOne(level, animal);
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }
}
