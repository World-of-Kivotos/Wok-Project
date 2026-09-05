package com.miningdim.job.fisher.journal;

import net.minecraft.resources.ResourceLocation;

/** 图鉴元数据与物品注册 ID 分离；文本沿用资源包翻译，不复制 Tide 的客户端类。 */
public record FishingJournalEntry(ResourceLocation itemId, String category,
                                  String descriptionKey, String habitatKey, String conditionsKey) {
}
