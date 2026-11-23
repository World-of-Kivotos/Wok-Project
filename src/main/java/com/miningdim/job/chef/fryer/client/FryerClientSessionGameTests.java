package com.miningdim.job.chef.fryer.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.zhangshao.ZhangshaoConfig;
import com.miningdim.job.chef.zhangshao.ZhangshaoKernel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FryerClientSessionGameTests {
    private static final long STEP = 50_000_000L;

    private FryerClientSessionGameTests() {}

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer_client")
    public static void shortPressIsLatchedAndBatchedAtTwentyTicks(GameTestHelper helper) {
        RecordingTransport transport = new RecordingTransport();
        FryerClientSession session = new FryerClientSession(transport);
        long now = 10_000L;
        session.startSession(41L, 17, fryerParams(), new ItemStack(Items.COOKED_COD), ZhangshaoKernel.FRYER, now);
        for (int tick = 0; tick < 20; tick++) session.advanceClock(now += STEP, true);
        helper.assertTrue(session.countdown() == 20 && session.state().tick == 0 && transport.inputs.isEmpty(),
                "倒计时期间不推进掌勺模拟，不向服务端提交输入");
        session.keyPressed(GLFW.GLFW_KEY_SPACE, now);
        session.keyReleased(GLFW.GLFW_KEY_SPACE);
        for (int tick = 0; tick < 20; tick++) session.advanceClock(now += STEP, true);
        helper.assertTrue(transport.inputs.size() == 1, "20 个真实 tick 应提交一包输入");
        RecordingTransport.Batch batch = transport.inputs.get(0);
        helper.assertTrue(batch.round() == 41L && batch.sequence() == 0 && batch.firstTick() == 0
                        && !batch.terminal() && batch.hash() == ZhangshaoKernel.hash(session.state()),
                "首包须关联本局，且能与服务端按相同输入回放的状态核对");
        byte[] expected = new byte[20];
        expected[0] = 1;
        helper.assertTrue(java.util.Arrays.equals(batch.inputs(), expected),
                "倒计时结束后的短按必须锁存为第一个控条输入");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer_client")
    public static void doubleEscapeAbortsOnlyOnSecondPress(GameTestHelper helper) {
        RecordingTransport transport = new RecordingTransport();
        FryerClientSession session = new FryerClientSession(transport);
        long now = 20_000L;
        session.startSession(42L, 23, fryerParams(), new ItemStack(Items.COOKED_COD), ZhangshaoKernel.FRYER, now);
        helper.assertTrue(session.keyPressed(GLFW.GLFW_KEY_ESCAPE, now), "首个 Esc 由会话接管");
        session.keyPressed(GLFW.GLFW_KEY_ESCAPE, now + 1);
        helper.assertTrue(transport.aborts == 0, "按住 Esc 时系统的按键重复不能触发撂勺");
        session.keyReleased(GLFW.GLFW_KEY_ESCAPE);
        helper.assertTrue(transport.aborts == 0 && session.active(), "首个 Esc 只进入确认窗口");
        helper.assertTrue(session.keyPressed(GLFW.GLFW_KEY_ESCAPE, now + 100_000_000L), "第二个 Esc 由会话接管");
        helper.assertTrue(transport.aborts == 1 && session.waiting(), "确认窗口内第二个 Esc 必须只发送一次撂勺包");
        helper.succeed();
    }

    private static int[] fryerParams() {
        ZhangshaoConfig.Options options = new ZhangshaoConfig.Options();
        options.station = ZhangshaoKernel.FRYER;
        options.star = 1;
        options.level = 1;
        options.motion = ZhangshaoKernel.FLOAT;
        return new ZhangshaoConfig().buildParams(options);
    }

    private static final class RecordingTransport implements FryerClientSession.Transport {
        private record Batch(long round, int sequence, int firstTick, byte[] inputs, int hash, boolean terminal) {}

        private final List<Batch> inputs = new ArrayList<>();
        private int aborts;

        @Override
        public void inputs(long round, int sequence, int firstTick, byte[] inputs, int hash, boolean terminal) {
            this.inputs.add(new Batch(round, sequence, firstTick, inputs, hash, terminal));
        }

        @Override
        public void abort(long round) {
            aborts++;
        }
    }
}
