package com.miningdim.config;

import com.miningdim.core.MiningConstants;
import com.miningdim.core.auth.LoginGateMode;
import com.miningdim.core.auth.PlayerLoginGate;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * security.loginGate 的出厂值与注入链路 (登录门判定本身的回归网在 core.auth.PlayerLoginGateGameTests)。
 *
 * 放在 config 包而不是 core.auth: core 是最底层, 不引用 config 包; 由 ConfigSystem 把 getter 注入给登录门,
 * 验证这条注入的用例自然归注入方。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class LoginGateConfigGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "login_gate_config";

    /**
     * 出厂默认必须是 AUTO (不装 AccessHub 的服务器、dev 与 GameTest 行为零变化的前提), 且门每次都读配置的现值。
     *
     * 出厂值读 spec 的 getDefault() 而不是 get(): 后者是本次运行所在存档 serverconfig 里的现值, 有人手改过
     * run/world 的 toml, 断言测的就不再是"出厂值"。
     *
     * 现值那半边真改一次配置再读: 只比 mode() == get() 的话, 注入一个启动时抓下来的常量也能过。删掉 ConfigSystem
     * 里那行注入, mode() 直接抛。改的是本批唯一一条用例独占的窗口 (批与批顺序执行), 且 GameTest 服务端不是专用
     * 服务器, 哪一档都不影响别处的判定; finally 里原样放回。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void loginGateShipsAsAutoAndIsReadLive(GameTestHelper helper) {
        helper.assertTrue(MiningServerConfig.LOGIN_GATE_MODE.getDefault() == LoginGateMode.AUTO,
                "the shipped default of security.loginGate must be AUTO, got "
                        + MiningServerConfig.LOGIN_GATE_MODE.getDefault());

        LoginGateMode original = MiningServerConfig.LOGIN_GATE_MODE.get();
        LoginGateMode probe = original == LoginGateMode.REQUIRED ? LoginGateMode.OFF : LoginGateMode.REQUIRED;
        try {
            MiningServerConfig.LOGIN_GATE_MODE.set(probe);
            helper.assertTrue(PlayerLoginGate.mode() == probe,
                    "the login gate must read security.loginGate live through the getter ConfigSystem injected; "
                            + "after setting " + probe + " it still reads " + PlayerLoginGate.mode());
        } finally {
            MiningServerConfig.LOGIN_GATE_MODE.set(original);
        }
        helper.assertTrue(PlayerLoginGate.mode() == original,
                "restoring the config must be visible to the gate as well, got " + PlayerLoginGate.mode());
        helper.succeed();
    }
}
