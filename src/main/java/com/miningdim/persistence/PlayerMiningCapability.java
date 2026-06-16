package com.miningdim.persistence;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;

/**
 * PlayerMiningData 的 Capability 句柄与注册入口 (1.20.1 Forge, 设计文档 12.5)。
 *
 * 1.20.1 强制写法: 用 CapabilityManager.get(new CapabilityToken<>(){}) 取得带泛型的 Capability 句柄
 * (匿名子类是 Forge 反射读出泛型实参的唯一手段); 在 RegisterCapabilitiesEvent (mod bus) 上
 * event.register(PlayerMiningData.class) 注册具体类型。两步缺一不可。
 */
public final class PlayerMiningCapability {

    private PlayerMiningCapability() {
    }

    /** AttachCapabilitiesEvent attach 时用的 ResourceLocation 键 (全 mod 唯一)。 */
    public static final ResourceLocation ID = new ResourceLocation(MiningConstants.MODID, "player_mining_data");

    /**
     * 强类型 Capability 句柄。匿名 CapabilityToken 子类是 1.20.1 取泛型 Capability 的强制写法,
     * 不可省略大括号 (省略则擦除泛型, Forge 无法匹配类型)。
     */
    public static final Capability<PlayerMiningData> CAPABILITY =
            CapabilityManager.get(new CapabilityToken<>() {
            });

    /** 在 mod bus 的 RegisterCapabilitiesEvent 内注册具体承载类型 (InstanceSystem.register 接线)。 */
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.register(PlayerMiningData.class);
    }
}
