package com.miningdim.entry;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 玩家 Capability 提供者 GameTest: 验证 {@link MiningPlayerDataProvider#invalidate()} 后句柄【重建】——
 * 跨维度 invalidateCaps 后 cap 仍可取且底层 data 不丢。回归锁: 修复"进矿洞后 /mining leave 退不出"
 * (旧实现 handle final + 永久 invalidate -> 换维度后 get() 永空)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MiningCapabilitiesGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "entry";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void capHandleRevivesAfterInvalidate(GameTestHelper helper) {
        MiningPlayerDataProvider provider = new MiningPlayerDataProvider();

        IMiningPlayerData before = provider.getCapability(MiningCapabilities.PLAYER_DATA, null)
                .resolve().orElse(null);
        helper.assertTrue(before != null, "cap resolvable before invalidate");
        before.setCurrentInstanceId(42L);

        // 模拟玩家跨维度时 Forge 触发的 invalidateCaps。
        provider.invalidate();

        IMiningPlayerData after = provider.getCapability(MiningCapabilities.PLAYER_DATA, null)
                .resolve().orElse(null);
        helper.assertTrue(after != null, "cap STILL resolvable after invalidate (handle revived) -- 修复退不出");
        helper.assertTrue(after.currentInstanceId() == 42L, "underlying data survives invalidate (same instance)");
        helper.succeed();
    }
}
