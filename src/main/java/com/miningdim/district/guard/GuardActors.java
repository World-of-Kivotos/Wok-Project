package com.miningdim.district.guard;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

/**
 * 守卫认人 (设计文档 22.5)。
 *
 * <ul>
 *   <li>OP 例外 = {@code hasPermissions(2) && !(player instanceof FakePlayer)}: 机械手的假玩家 (DeployerFakePlayer) 的
 *       {@code getUUID()} 是主人的 UUID, OP 放的机械手本身就能通过 hasPermissions(2), 所以必须按类排除假玩家, 不能按
 *       UUID。1 级 OP 不例外。</li>
 *   <li>机械动力的假玩家 = FakePlayer 且类名以 {@code com.simibubi.create.} 开头 (机械手的 DeployerFakePlayer、犁的
 *       PloughBlock$PloughFakePlayer)。只按类名认, 绝不按 UUID。别的 mod 的假玩家交给 Flan 的 flan:fake_player。</li>
 * </ul>
 */
public final class GuardActors {

    /** 机械动力的类名前缀。 */
    public static final String CREATE_PACKAGE_PREFIX = "com.simibubi.create.";

    /** GameTest 登记的"机械动力的假玩家"类 (测试专用入口)。 */
    @Nullable
    private static volatile Class<?> testCreateFakePlayer;

    private GuardActors() {
    }

    /** OP 例外: 2 级权限的真玩家。 */
    public static boolean isExemptOp(@Nullable Player player) {
        return player != null && !(player instanceof FakePlayer) && player.hasPermissions(2);
    }

    /** 真玩家 (不是任何 mod 的假玩家)。 */
    public static boolean isRealPlayer(@Nullable Player player) {
        return player != null && !(player instanceof FakePlayer);
    }

    /** 机械动力的假玩家 (按类名)。 */
    public static boolean isCreateFakePlayer(@Nullable Player player) {
        if (!(player instanceof FakePlayer)) {
            return false;
        }
        Class<?> registered = testCreateFakePlayer;
        if (registered != null && registered.isInstance(player)) {
            return true;
        }
        return player.getClass().getName().startsWith(CREATE_PACKAGE_PREFIX);
    }

    /**
     * 只供 GameTest: 把一个 FakePlayer 子类登记成"机械动力的假玩家", 返回复原用的句柄。只在 GameTest 服务端上可用
     * (门同 DistrictFeature.forceForTest)。
     */
    public static AutoCloseable registerTestCreateFakePlayer(Class<? extends FakePlayer> type) {
        if (!(ServerLifecycleHooks.getCurrentServer() instanceof GameTestServer)) {
            throw new IllegalStateException("GuardActors.registerTestCreateFakePlayer is only available on the "
                    + "GameTest server");
        }
        Class<?> previous = testCreateFakePlayer;
        testCreateFakePlayer = type;
        return () -> testCreateFakePlayer = previous;
    }
}
