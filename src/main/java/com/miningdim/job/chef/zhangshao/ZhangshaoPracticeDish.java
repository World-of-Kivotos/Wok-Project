package com.miningdim.job.chef.zhangshao;

import java.util.List;

/**
 * 掌勺练习界面的四台示例菜。这里只提供演示数据，不注册物品或配方。
 */
public record ZhangshaoPracticeDish(
        String id,
        String name,
        int star,
        int motion,
        int flips,
        int beats,
        boolean drink
) {
    /**
     * 返回指定台子的七道练习示例：0 厨锅，1 炸锅，2 烤炉，3 备餐台。
     */
    public static List<ZhangshaoPracticeDish> forStation(int station) {
        return switch (station) {
            case 0 -> List.of(
                    new ZhangshaoPracticeDish("farmersdelight:bone_broth", "大骨汤", 1, 2, 0, 0, false),
                    new ZhangshaoPracticeDish("farmersdelight:beef_stew", "牛肉炖", 2, 2, 0, 0, false),
                    new ZhangshaoPracticeDish("farmersdelight:fried_rice", "炒饭", 3, 0, 0, 0, false),
                    new ZhangshaoPracticeDish("dumplings_delight:pork_cabbage_wonton", "白菜猪肉馄饨", 4, 3, 0, 0, false),
                    new ZhangshaoPracticeDish("crabbersdelight:clam_bake", "蛤蜊大杂烩", 5, 0, 0, 0, false),
                    new ZhangshaoPracticeDish("culturaldelights:spicy_curry", "火辣咖喱", 6, 0, 0, 0, false),
                    new ZhangshaoPracticeDish("brewinandchewin:fiery_fondue_pot", "烈焰奶酪火锅", 7, 2, 0, 0, false)
            );
            case 1 -> List.of(
                    new ZhangshaoPracticeDish("oceansdelight:honey_fried_kelp", "蜂蜜炸海带", 1, 4, 0, 0, false),
                    new ZhangshaoPracticeDish("casualness_delight:spring_roll", "春卷", 2, 4, 0, 0, false),
                    new ZhangshaoPracticeDish("casualness_delight:fried_dumpling", "大炸饺", 3, 4, 0, 0, false),
                    new ZhangshaoPracticeDish("casualness_delight:plate_of_fried_dumpling", "盘装大炸饺", 4, 4, 0, 0, false),
                    new ZhangshaoPracticeDish("casualness_delight:spring_roll_medley", "春卷拼盘", 5, 4, 0, 0, false),
                    new ZhangshaoPracticeDish("delightful:crab_rangoon", "炸蟹角", 6, 1, 0, 0, false),
                    new ZhangshaoPracticeDish("culturaldelights:empanada", "恩潘纳达炸饺", 7, 4, 0, 0, false)
            );
            case 2 -> List.of(
                    new ZhangshaoPracticeDish("farmersdelight:sweet_berry_cookie", "甜浆果曲奇", 1, 0, 1, 0, false),
                    new ZhangshaoPracticeDish("farmersdelight:barbecue_stick", "烧烤串", 2, 0, 3, 0, false),
                    new ZhangshaoPracticeDish("farmersdelight:roasted_mutton_chops", "烤羊排", 3, 3, 1, 0, false),
                    new ZhangshaoPracticeDish("farmersdelight:apple_pie", "苹果派", 4, 2, 2, 0, false),
                    new ZhangshaoPracticeDish("farmersdelight:roast_chicken_block", "烤鸡", 5, 3, 2, 0, false),
                    new ZhangshaoPracticeDish("brewinandchewin:pizza", "披萨", 6, 2, 2, 0, false),
                    new ZhangshaoPracticeDish("delightful:baklava", "巴克拉瓦", 7, 0, 2, 0, false)
            );
            case 3 -> List.of(
                    new ZhangshaoPracticeDish("farmersdelight:egg_sandwich", "鸡蛋三明治", 1, 0, 0, 3, false),
                    new ZhangshaoPracticeDish("farmersdelight:salmon_roll", "鲑鱼寿司", 2, 1, 0, 3, false),
                    new ZhangshaoPracticeDish("delightful:salmonberry_milkshake", "大树莓奶昔", 3, 4, 0, 6, true),
                    new ZhangshaoPracticeDish("culturaldelights:hearty_salad", "丰盛色拉", 4, 2, 0, 5, false),
                    new ZhangshaoPracticeDish("moredelight:loaded_hamburger", "豪华汉堡", 5, 0, 0, 8, false),
                    new ZhangshaoPracticeDish("culturaldelights:chicken_taco", "墨西哥鸡肉卷", 6, 0, 0, 7, false),
                    new ZhangshaoPracticeDish("culturaldelights:exotic_roll_medley", "热带寿司拼盘", 7, 1, 0, 7, false)
            );
            default -> throw new IllegalArgumentException("未知掌勺台: " + station);
        };
    }
}
