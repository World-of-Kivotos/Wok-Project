package com.miningdim.core.auth;

/**
 * 登录门运行模式 (服务端配置 {@code security.loginGate}, 真源 {@code MiningServerConfig.LOGIN_GATE_MODE})。
 *
 * 三档而不是一个布尔, 是因为"没装 AccessHub"在两种场合含义相反: dev / GameTest / 单人存档里它本来就不存在,
 * 放行才是对的; 正式服上它缺失则说明部署出了问题, 此时应当关门, 而不是悄悄停用登录校验。
 *
 * 只在专用服务器上生效: AccessHub 自己也只在专用服务器上启动 (onServerStarting 里 isDedicatedServer 为假
 * 直接返回, 2026-09 核实), 单人 / 局域网 / GameTest 服务端上根本没有"登录"这一步可等。
 */
public enum LoginGateMode {

    /** 装了 AccessHub 就强制要求 /login (API 对不上则一律拒绝); 没装就放行。默认值。 */
    AUTO,

    /** 同 AUTO, 但专用服务器上没装 (或装了却对不上) AccessHub 时一律拒绝。正式服应设此档。 */
    REQUIRED,

    /** 完全不检查。仅供 AccessHub 升级导致误拒全员时的应急开关, 开着期间本 mod 不校验任何人的登录态。 */
    OFF
}
