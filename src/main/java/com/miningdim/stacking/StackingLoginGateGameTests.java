package com.miningdim.stacking;

import com.miningdim.core.MiningConstants;
import com.miningdim.core.auth.PlayerLoginGate;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 堆叠交互的登录门回归: {@link StackSplit} / {@link StackPassive} / {@link StackBreed} 挂在 EntityInteract 的 NORMAL
 * 优先级; 未登录玩家的交互由登录门在 HIGHEST 统一取消 (core.auth.LoginGateSubsystem), 所以这里走真实的 Forge
 * 事件总线, 用"空手潜行右键 = 拆出一只"这条最直接的路径, 断言堆叠数不变。
 *
 * 范式同 {@link StackingInteractionGameTests} (首行 {@link StackingConfig#ensureLoadedForTest}, template = "empty")。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class StackingLoginGateGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "stacking_login_gate";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void stackedAnimalsIgnorePlayersWhoHaveNotLoggedIn(GameTestHelper helper) {
        StackingConfig.ensureLoadedForTest();
        BlockPos origin = new BlockPos(1, 2, 1);
        Cow cow = helper.spawn(EntityType.COW, origin);
        StackData.setStackSize(cow, 3);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setShiftKeyDown(true);
        int cowsBefore = helper.getEntities(EntityType.COW, origin, 8.0).size();

        try (PlayerLoginGate.ForcedVerdict ignored =
                     PlayerLoginGate.forceVerdictForTest(player.getUUID(), PlayerLoginGate.Verdict.NOT_LOGGED_IN)) {
            PlayerInteractEvent.EntityInteract split =
                    new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, cow);
            MinecraftForge.EVENT_BUS.post(split);
            helper.assertTrue(split.isCanceled(), "the interaction of a player who has not /login-ed must be cancelled");
            helper.assertTrue(StackData.getStackSize(cow) == 3,
                    "no stacking listener may act for a player who has not logged in: stack must stay 3, got "
                            + StackData.getStackSize(cow));
            helper.assertTrue(helper.getEntities(EntityType.COW, origin, 8.0).size() == cowsBefore,
                    "no cow may be split off for a player who has not logged in");
        }

        // 对照: 登录后同一个事件照常拆出一只 (证明上面的"不变"不是因为这条路径本来就走不通)。
        PlayerInteractEvent.EntityInteract afterLogin =
                new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, cow);
        MinecraftForge.EVENT_BUS.post(afterLogin);
        helper.assertTrue(StackData.getStackSize(cow) == 2,
                "after login the sneak-split must peel one cow off: stack must be 2, got " + StackData.getStackSize(cow));
        helper.assertTrue(helper.getEntities(EntityType.COW, origin, 8.0).size() == cowsBefore + 1,
                "after login exactly one cow must be split off");
        helper.succeed();
    }
}
