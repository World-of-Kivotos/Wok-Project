package com.miningdim.district.flan;

import com.miningdim.district.DistrictFeature;
import com.miningdim.district.DistrictLimits;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.flan.real.FlanCompat;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * 开服选网关 (设计文档 20.2), 写成只依赖注入参数的函数以便 GameTest 核对每一个分支:
 * <ol>
 *   <li>JVM 系统属性 {@code -Dminingdim.district.flanGateway=recording} 且当前是 GameTest 服务端 → 记录型网关
 *       (专用服务器与单人存档里忽略这个属性, 记 WARN, 照常往下选);</li>
 *   <li>Flan 没有加载 → Disabled, 原因"Flan 没有安装";</li>
 *   <li>版本不是核对过的那一个 → Disabled, 原因"Flan 版本 {v} 未经核对";</li>
 *   <li>自检 ({@link FlanCompat}) 不通过 → Disabled, 原因"Flan 自检未通过"; 另记一条 ERROR, 每个问题一行;</li>
 *   <li>都通过 → 真网关 (工厂此时才第一次被调用, 也就是才第一次碰引用 Flan 的类)。</li>
 * </ol>
 * 不通过时一次写入都不做: 真网关根本没有被造出来。
 */
public final class GatewaySelector {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");

    /** {@link DistrictLimits#GATEWAY_PROPERTY} 要求记录型网关时的取值。 */
    public static final String RECORDING = "recording";

    /** 选择的结果。 */
    public record Selection(FlanGateway gateway, DistrictFeature.State state, @Nullable String reason,
                            @Nullable String flanVersion, List<String> problems) {

        public Selection {
            problems = List.copyOf(problems);
        }
    }

    /** 真网关的工厂 (FlanBridge::create)。 */
    @FunctionalInterface
    public interface RealFactory {
        FlanGateway create() throws ReflectiveOperationException;
    }

    private GatewaySelector() {
    }

    /**
     * @param server      当前服务端 (判断是否 GameTest 服务端)
     * @param flanLoaded  ModList 是否报告 Flan 已加载
     * @param flanVersion Flan 模组容器的版本 (没装为 null)
     * @param selfCheck   自检 (只在前面几步都过了才调用)
     * @param factory     真网关工厂 (只在自检通过后调用)
     */
    public static Selection select(@Nullable MinecraftServer server, boolean flanLoaded, @Nullable String flanVersion,
                                   Supplier<FlanCompat.Result> selfCheck, RealFactory factory) {
        if (RECORDING.equals(System.getProperty(DistrictLimits.GATEWAY_PROPERTY))) {
            if (server instanceof GameTestServer) {
                return new Selection(new RecordingFlanGateway(), DistrictFeature.State.LIVE, null, flanVersion,
                        List.of());
            }
            LOGGER.warn("[miningdim] -D{}={} is ignored: the recording Flan gateway is a GameTest fake and is only "
                    + "available on the GameTest server", DistrictLimits.GATEWAY_PROPERTY, RECORDING);
        }
        if (!flanLoaded) {
            return degraded(DistrictTexts.FLAN_REASON_MISSING, null, List.of());
        }
        if (!FlanCompat.VERIFIED_VERSION.equals(flanVersion)) {
            String version = String.valueOf(flanVersion);
            LOGGER.error("[miningdim] district: Flan {} is installed but only {} has been verified; the district "
                    + "feature runs degraded (no Flan writes)", version, FlanCompat.VERIFIED_VERSION);
            return degraded(DistrictTexts.flanReasonVersion(version), flanVersion, List.of());
        }
        FlanCompat.Result check = selfCheck.get();
        if (!check.ok()) {
            LOGGER.error("[miningdim] district: the Flan self-check failed; the district feature runs degraded (no "
                    + "Flan writes):\n - {}", String.join("\n - ", check.problems()));
            return degraded(DistrictTexts.FLAN_REASON_SELF_CHECK, flanVersion, check.problems());
        }
        try {
            return new Selection(factory.create(), DistrictFeature.State.LIVE, null, flanVersion, List.of());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            LOGGER.error("[miningdim] district: building the Flan gateway failed; the district feature runs degraded",
                    failure);
            return degraded(DistrictTexts.FLAN_REASON_SELF_CHECK, flanVersion, List.of(String.valueOf(failure)));
        }
    }

    /**
     * 不是 Flan 的原因也要降级时 (库里缺 district_notice 表, 22.19): Disabled 网关带上原因, 真网关不造。
     */
    public static Selection degradedFor(String reason, @Nullable String flanVersion) {
        return degraded(reason, flanVersion, List.of());
    }

    private static Selection degraded(String reason, @Nullable String version, List<String> problems) {
        return new Selection(new DisabledFlanGateway(reason), DistrictFeature.State.DEGRADED, reason, version,
                problems);
    }
}
