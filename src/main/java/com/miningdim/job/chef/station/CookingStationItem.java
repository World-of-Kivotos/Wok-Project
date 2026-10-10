package com.miningdim.job.chef.station;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 烹饪台的物品形态, 在物品提示里写清这台是什么、怎么摆:
 *  - 功能种类 (炸锅 / 烤炉 / 备餐台) 并说明三种外观功能相同: 九台名字各不相同 (吊炉、砖砌烤炉、铸铁烤箱灶
 *    都是烤炉), 只看名字认不出来;
 *  - 炉上油锅要下方热源 (否则只有开火失败时才在动作栏提示一次);
 *  - 模型高出方块顶的台子提醒上方留空 (见 {@link CookingStationBlock#decorRisesAbove()});
 *  - 烹饪功能尚未开放 (本阶段只有方块, 配方也暂时关着; 接入烹饪逻辑时连同这行一起去掉)。
 * 文案键都在 chef.station.tooltip.* 下。
 */
public final class CookingStationItem extends BlockItem {

    public static final String WIP_KEY = "chef.station.tooltip.wip";
    public static final String NEEDS_HEAT_KEY = "chef.station.tooltip.needs_heat";
    public static final String KEEP_ABOVE_CLEAR_KEY = "chef.station.tooltip.keep_above_clear";

    public CookingStationItem(CookingStationBlock block, Properties properties) {
        super(block, properties);
    }

    public static String kindKey(CookingStationBlock.Kind kind) {
        return "chef.station.tooltip.kind." + kind.id();
    }

    /** 这台的提示文案键, 按显示顺序排列; GameTest 用它核对语言文件。 */
    public List<String> tooltipKeys() {
        CookingStationBlock station = (CookingStationBlock) getBlock();
        List<String> keys = new ArrayList<>();
        keys.add(kindKey(station.kind()));
        if (station instanceof StovetopFryerBlock) {
            keys.add(NEEDS_HEAT_KEY);
        }
        if (station.decorRisesAbove()) {
            keys.add(KEEP_ABOVE_CLEAR_KEY);
        }
        keys.add(WIP_KEY);
        return keys;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        for (String key : tooltipKeys()) {
            tooltip.add(Component.translatable(key)
                    .withStyle(WIP_KEY.equals(key) ? ChatFormatting.DARK_GRAY : ChatFormatting.GRAY));
        }
    }
}
