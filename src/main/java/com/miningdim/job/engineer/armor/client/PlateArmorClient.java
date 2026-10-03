package com.miningdim.job.engineer.armor.client;

import com.miningdim.job.engineer.armor.item.PlateArmorItem;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.NotNull;

/**
 * 插板护甲的客户端扩展：胸甲槽穿戴时改用该外观的烘焙网格模型（{@link PlateArmorBakedModel}），
 * 网格缺失或损坏时退回原版胸甲模型（{@link PlateArmorMeshCache} 已打过一次警告）。
 * 贴图仍由 PlateArmorItem.getArmorTexture 提供，与网格同名。
 *
 * <p>注意退回后的样子：贴图已是按网格逐面打包的图集，不再是原版 64×32 的箱式 uv 排布，原版模型拿它去取色，
 * 画出来是一件贴图错乱的原版胸甲，而不是干净的原版外观。这是有意保留的"出错一眼可见、但不崩渲染"的退路；
 * 排查时看到错乱的原版胸甲，先去日志里找那条网格警告。</p>
 */
public final class PlateArmorClient implements IClientItemExtensions {

    private final PlateArmorItem armorItem;
    /** 当前网格对应的模型；资源重载换了网格对象后按新网格重建一次。 */
    private PlateArmorBakedModel model;

    private PlateArmorClient(PlateArmorItem armorItem) {
        this.armorItem = armorItem;
    }

    public static IClientItemExtensions forItem(PlateArmorItem armorItem) {
        return new PlateArmorClient(armorItem);
    }

    @Override
    @NotNull
    public HumanoidModel<?> getHumanoidArmorModel(LivingEntity livingEntity, ItemStack itemStack,
                                                   EquipmentSlot equipmentSlot, HumanoidModel<?> original) {
        if (equipmentSlot != EquipmentSlot.CHEST || itemStack.getItem() != armorItem) {
            return original;
        }
        PlateArmorMesh mesh = PlateArmorMeshCache.get(armorItem.variant());
        if (mesh == null) {
            return original;
        }
        PlateArmorBakedModel current = model;
        if (current == null || current.mesh() != mesh) {
            current = new PlateArmorBakedModel(mesh);
            model = current;
        }
        return current;
    }
}
