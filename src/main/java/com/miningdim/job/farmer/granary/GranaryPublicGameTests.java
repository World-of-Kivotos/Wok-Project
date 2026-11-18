package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryPublicGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_public";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dayRollsOverAtBeijingEightAndUsageRoundTrips(GameTestHelper helper) {
        // 挂钟是 epoch 分钟 (UTC): 北京 08:00 恰好是 UTC 00:00, 所以日序号就是 floorDiv(分钟, 1440)。
        helper.assertTrue(GranaryPublic.dayOf(0L) == 0L, "UTC 00:00 是新的一天的开头");
        helper.assertTrue(GranaryPublic.dayOf(GranaryPublic.MINUTES_PER_DAY - 1L) == 0L,
                "北京 07:59 还是前一天");
        helper.assertTrue(GranaryPublic.dayOf(GranaryPublic.MINUTES_PER_DAY) == 1L,
                "北京 08:00 翻日 (UTC 00:00)");
        helper.assertTrue(GranaryPublic.clampGroups(-3) == 0
                        && GranaryPublic.clampGroups(99) == GranaryPublic.MAX_GROUPS,
                "配额夹在 0..9");

        GranaryPublic.Settings settings = new GranaryPublic.Settings(true, 3, true, 2);
        GranaryPublic.Settings loaded = GranaryPublic.Settings.load(settings.save());
        helper.assertTrue(loaded.equals(settings), "设置原样存读, 实得 " + loaded);
        helper.assertTrue(GranaryPublic.Settings.load(new net.minecraft.nbt.CompoundTag())
                .equals(GranaryPublic.Settings.OFF), "老档没有这个键就是全关");

        UUID player = UUID.randomUUID();
        List<GranaryPublic.Usage> usage = List.of(new GranaryPublic.Usage(player, 7L, 1, 2));
        List<GranaryPublic.Usage> loadedUsage = GranaryPublic.loadUsage(GranaryPublic.saveUsage(usage));
        helper.assertTrue(loadedUsage.equals(usage), "用量表原样存读, 实得 " + loadedUsage);
        GranaryPublic.Usage today = GranaryPublic.usageOf(loadedUsage, player, 7L);
        helper.assertTrue(GranaryPublic.remainingDonations(settings, today) == 2,
                "捐粮还差 2 组 (3 - 1)");
        helper.assertTrue(GranaryPublic.remainingTakes(settings, today) == 0,
                "取用已经用完 (2 - 2)");
        helper.assertTrue(GranaryPublic.usageOf(loadedUsage, player, 8L).stored() == 0,
                "翻日之后重新从 0 开始算");
        helper.succeed();
    }
}
