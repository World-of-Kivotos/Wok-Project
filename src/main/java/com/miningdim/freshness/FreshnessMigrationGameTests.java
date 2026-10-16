package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 开服迁移 ({@link FreshnessMigration} / {@link FreshnessMigrationData}) 的契约 GameTest。纯函数与存档形状,
 * 区块与实体那两条钩子由 {@code FreshnessMigrationHooks} 接线 (需要真存档, 列入测试服实机清单)。
 *
 * 这一片坏掉的方式全都很安静, 逐条钉住:
 * <ol>
 *   <li><b>算错批次</b>: 老货一开服就整箱烂掉 (算少了) 或者永远不烂 (算多了) —— 前者劝退, 后者等于没做;</li>
 *   <li><b>不认"已经盖过章"</b>: 把玩家已经处理过的货再盖一遍, 等于凭空续命;</li>
 *   <li><b>只认容器形状</b>: 换个容器 (抽屉、保险库、嵌套潜影盒) 就漏, 而漏掉的那种正是走私者用的那种;</li>
 *   <li><b>脏存档条目把区块加载搞崩</b>: 这段代码跑在区块加载路径上, 抛出去就是整个区块读不出来;</li>
 *   <li><b>重启把启用日往后推</b>: 每重启一次老货就多活 6 小时 (时间胶囊);</li>
 *   <li><b>名单记错</b>: 新玩家被当成老玩家 (凭空得 6 小时), 或老玩家没进名单 (整箱烂掉)。</li>
 * </ol>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessMigrationGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_migration";

    /** 用例里的启用时刻 (分钟序号); 取一个够大的数, 免得与"未定值 0"混在一起。 */
    private static final long ENABLED_AT = 29_000_000L;

    private static final int GRACE_HOURS = FreshnessConstants.MIGRATION_GRACE_HOURS;
    private static final int STOP_CAP = FreshnessConstants.STOP_CAP_HOURS;

    // ============================================================
    // 1. 迁移章本身
    // ============================================================

    /**
     * 迁移章: 启用那一刻正好剩 {@link FreshnessConstants#MIGRATION_GRACE_HOURS} 小时, 停表额度记满,
     * 且带迁移标记 (永不和新货合并)。老货照常随时间变少 (不是"冻结")。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void migratedStampGivesGraceHoursAtTheEnableInstantAndDecaysNormally(GameTestHelper helper) {
        ItemStack bread = new ItemStack(Items.BREAD, 1);
        helper.assertTrue(FreshnessMigration.stampMigrated(bread, ENABLED_AT, GRACE_HOURS, STOP_CAP),
                "没章的易腐品应当被迁移");
        helper.assertTrue(FreshnessData.isMigrated(bread), "迁移货必须带 m 标记 (永不和新货合并)");
        helper.assertTrue(FreshnessData.isStamped(bread), "迁移也要盖章");
        helper.assertTrue(FreshnessData.stoppedHours(bread) == STOP_CAP,
                "停表额度记满 (" + STOP_CAP + "), 实得 " + FreshnessData.stoppedHours(bread));
        helper.assertTrue(FreshnessCheck.remainingMinutes(bread, ENABLED_AT) == (long) GRACE_HOURS * 60L,
                "启用那一刻必须正好剩 " + GRACE_HOURS + " 小时, 实得 "
                        + FreshnessCheck.remainingMinutes(bread, ENABLED_AT));

        // 两小时后: 老货照常变少 (迁移只是给一次宽限, 不是停表)。
        long twoHoursLater = ENABLED_AT + 120L;
        helper.assertTrue(FreshnessCheck.remainingMinutes(bread, twoHoursLater) == (long) (GRACE_HOURS - 2) * 60L,
                "两小时后应当只剩 " + (GRACE_HOURS - 2) + " 小时, 实得 "
                        + FreshnessCheck.remainingMinutes(bread, twoHoursLater));

        // 不该动的三类: 已经盖过章的老货、不腐烂的东西、豁免品。
        ItemStack alreadyStamped = new ItemStack(Items.BREAD, 1);
        FreshnessData.stamp(alreadyStamped, ENABLED_AT - 5_000L);
        long batchBefore = FreshnessData.batchMinute(alreadyStamped);
        helper.assertTrue(!FreshnessMigration.stampMigrated(alreadyStamped, ENABLED_AT, GRACE_HOURS, STOP_CAP),
                "已经盖过章的货不许再迁移一次 (那等于凭空续命)");
        helper.assertTrue(FreshnessData.batchMinute(alreadyStamped) == batchBefore,
                "被跳过时章必须原样不动, 实得 " + FreshnessData.batchMinute(alreadyStamped));
        helper.assertTrue(!FreshnessData.isMigrated(alreadyStamped), "也不该被打上迁移标记");

        helper.assertTrue(!FreshnessMigration.stampMigrated(new ItemStack(Items.STONE, 64), ENABLED_AT,
                GRACE_HOURS, STOP_CAP), "石头不腐烂, 不迁移");
        ItemStack exempt = new ItemStack(Items.ROTTEN_FLESH, 1);
        helper.assertTrue(!FreshnessCategories.spoils(exempt) || FreshnessCategories.isExempt(exempt),
                "前提: 腐肉是豁免品 (本用例靠它代表「不该动」的那一类)");
        helper.assertTrue(!FreshnessMigration.stampMigrated(exempt, ENABLED_AT, GRACE_HOURS, STOP_CAP),
                "豁免品不迁移");
        helper.succeed();
    }

    // ============================================================
    // 2. 存档 NBT 下钻
    // ============================================================

    /**
     * 区块存档那一棵树: 认"有 id 与 Count"的条目而不是认容器形状, 于是箱子、嵌套潜影盒一次覆盖;
     * 跑第二遍不再改动任何东西 (幂等)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void migrateTagWalksNestedContainerDataAndIsIdempotent(GameTestHelper helper) {
        CompoundTag nestedBox = new CompoundTag();
        ListTag nestedItems = new ListTag();
        nestedItems.add(itemEntry("minecraft:bread", 1, itemTag(new ItemStack(Items.BREAD, 1))));
        CompoundTag boxBlockEntity = new CompoundTag();
        boxBlockEntity.put("Items", nestedItems);
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.getOrCreateTag().put("BlockEntityTag", boxBlockEntity);

        ListTag chestItems = new ListTag();
        chestItems.add(itemEntry("minecraft:bread", 5, new CompoundTag()));
        chestItems.add(itemEntry("minecraft:stone", 64, new CompoundTag()));
        chestItems.add(itemEntry("minecraft:shulker_box", 1, itemTag(box)));
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putInt("x", 3);
        chest.putInt("y", 64);
        chest.putInt("z", -7);
        chest.put("Items", chestItems);
        ListTag blockEntities = new ListTag();
        blockEntities.add(chest);
        CompoundTag chunkData = new CompoundTag();
        chunkData.put("block_entities", blockEntities);

        int migrated = FreshnessMigration.migrateTag(chunkData, ENABLED_AT, GRACE_HOURS, STOP_CAP);
        helper.assertTrue(migrated == 2,
                "箱子里的面包 + 潜影盒里的面包 = 2 件 (石头与盒子本身不迁移), 实得 " + migrated);

        CompoundTag migratedBread = chestItems.getCompound(0);
        helper.assertTrue(migratedBread.contains("tag", net.minecraft.nbt.Tag.TAG_COMPOUND)
                        && migratedBread.getCompound("tag").contains(FreshnessConstants.ROOT),
                "箱子里的面包必须被写上章, 实得 " + migratedBread);
        helper.assertTrue(migratedBread.getCompound("tag").getCompound(FreshnessConstants.ROOT)
                        .getBoolean(FreshnessConstants.KEY_MIGRATED),
                "迁移章要带 m 标记");
        helper.assertTrue(chestItems.getCompound(1).getCompound("tag").isEmpty(),
                "石头一个字都不该被写 (它不是易腐品), 实得 " + chestItems.getCompound(1).getCompound("tag"));
        CompoundTag nestedBread = chestItems.getCompound(2).getCompound("tag")
                .getCompound("BlockEntityTag").getList("Items", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .getCompound(0);
        helper.assertTrue(nestedBread.contains("tag", net.minecraft.nbt.Tag.TAG_COMPOUND)
                        && nestedBread.getCompound("tag").contains(FreshnessConstants.ROOT),
                "潜影盒里的面包也要迁移 (任何层嵌套都要走到), 实得 " + nestedBread);

        // 幂等: 第二遍没有可迁移的条目, 且已迁移的值一分不变。
        long before = FreshnessData.batchMinute(ItemStack.of(migratedBread));
        helper.assertTrue(FreshnessMigration.migrateTag(chunkData, ENABLED_AT, GRACE_HOURS, STOP_CAP) == 0,
                "第二遍不该再迁移任何东西");
        helper.assertTrue(FreshnessData.batchMinute(ItemStack.of(chestItems.getCompound(0))) == before,
                "第二遍也不许改已迁移条目的章");
        helper.succeed();
    }

    /** 畸形条目一律跳过而不抛: 这段代码跑在区块加载路径上, 抛出去就是整个区块读不出来。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void malformedEntriesAreSkippedInsteadOfThrowing(GameTestHelper helper) {
        ListTag items = new ListTag();
        CompoundTag badId = new CompoundTag();
        badId.putString("id", "not a valid id !!");
        badId.putByte("Count", (byte) 1);
        items.add(badId);
        CompoundTag noId = new CompoundTag();
        noId.putByte("Count", (byte) 1);
        items.add(noId);
        CompoundTag noCount = new CompoundTag();
        noCount.putString("id", "minecraft:bread");
        items.add(noCount);
        CompoundTag idNotString = new CompoundTag();
        idNotString.putInt("id", 5);
        idNotString.putByte("Count", (byte) 1);
        items.add(idNotString);
        items.add(itemEntry("minecraft:bread", 1, new CompoundTag()));

        CompoundTag chunkData = new CompoundTag();
        chunkData.put("Items", items);
        int migrated = FreshnessMigration.migrateTag(chunkData, ENABLED_AT, GRACE_HOURS, STOP_CAP);
        helper.assertTrue(migrated == 1,
                "四行坏数据 + 一行好的 = 只迁移那一行 (也不许抛), 实得 " + migrated);
        helper.succeed();
    }

    // ============================================================
    // 3. 容器与玩家存档
    // ============================================================

    /** 容器每一格都走一遍 (玩家背包与末影箱走的就是这条路), 且第二次调用不再改动。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void containerMigrationTouchesEachSlotOnce(GameTestHelper helper) {
        SimpleContainer container = new SimpleContainer(3);
        container.setItem(0, new ItemStack(Items.BREAD, 4));
        container.setItem(1, new ItemStack(Items.STONE, 8));
        container.setItem(2, new ItemStack(Items.COOKED_BEEF, 2));

        helper.assertTrue(FreshnessMigration.migrateContainer(container, ENABLED_AT, GRACE_HOURS, STOP_CAP) == 2,
                "两格易腐品 + 一格石头 = 迁移 2 件");
        helper.assertTrue(FreshnessData.isMigrated(container.getItem(0))
                        && FreshnessData.isMigrated(container.getItem(2)),
                "两格易腐品都要带上迁移标记");
        helper.assertTrue(container.getItem(1).getTag() == null, "石头不许被写任何数据");
        helper.assertTrue(FreshnessMigration.migrateContainer(container, ENABLED_AT, GRACE_HOURS, STOP_CAP) == 0,
                "第二次调用不该再迁移 (章已经在)");
        helper.succeed();
    }

    /**
     * 启用时刻与名单: 已经定过的启用时刻不许被重启推后 (每重启一次老货多活 6 小时); 名单只记一次;
     * 存档往返之后三者 (启用时刻 / 名单 / 已迁移的人) 都要还在。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void enableInstantRosterAndMigratedPlayersSurviveASaveLoad(GameTestHelper helper) {
        FreshnessMigrationData data = new FreshnessMigrationData();
        helper.assertTrue(data.enabledAtMinute() == FreshnessMigrationData.UNSET_MINUTE,
                "出厂状态是未定 (哨兵 0)");
        helper.assertTrue(data.resolveEnabledAt(ENABLED_AT, FreshnessMigrationData.UNSET_MINUTE) == ENABLED_AT,
                "没配启用时刻时用第一次启动的现在");
        helper.assertTrue(data.resolveEnabledAt(ENABLED_AT + 10_000L, FreshnessMigrationData.UNSET_MINUTE)
                        == ENABLED_AT,
                "重启不许把启用时刻往后推 (推一次老货就多活一次宽限)");

        FreshnessMigrationData configured = new FreshnessMigrationData();
        helper.assertTrue(configured.resolveEnabledAt(ENABLED_AT, 12_345L) == 12_345L,
                "配置给了启用时刻就用配置");

        UUID veteran = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID newcomer = UUID.randomUUID();
        helper.assertTrue(data.captureRosterIfNeeded(List.of(veteran, second)), "第一次数名单要生效");
        helper.assertTrue(data.rosterSize() == 2, "名单 2 人, 实得 " + data.rosterSize());
        helper.assertTrue(!data.captureRosterIfNeeded(List.of(newcomer)),
                "名单只记一次: 第二次调用不许把新玩家补进来");
        helper.assertTrue(data.isInRoster(veteran) && !data.isInRoster(newcomer),
                "新玩家不在名单里 (他们按现在盖章, 不该拿到宽限)");
        helper.assertTrue(data.markPlayerMigrated(veteran), "第一次标记要生效");
        helper.assertTrue(!data.markPlayerMigrated(veteran), "同一个人只迁移一次");
        helper.assertTrue(data.isPlayerMigrated(veteran) && !data.isPlayerMigrated(second),
                "只记这一个");

        FreshnessMigrationData reloaded = FreshnessMigrationData.load(data.save(new CompoundTag()));
        helper.assertTrue(reloaded.enabledAtMinute() == ENABLED_AT, "启用时刻要能存读");
        helper.assertTrue(reloaded.rosterCaptured() && reloaded.rosterSize() == 2, "名单与「已数过」标记要能存读");
        helper.assertTrue(reloaded.isInRoster(veteran) && !reloaded.isInRoster(newcomer), "名单内容要能存读");
        helper.assertTrue(reloaded.isPlayerMigrated(veteran) && !reloaded.isPlayerMigrated(second),
                "已迁移集合要能存读 (否则每次重启都白迁移一遍)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void saveLoadIgnoresGarbageUuids(GameTestHelper helper) {
        CompoundTag tag = new CompoundTag();
        ListTag roster = new ListTag();
        roster.add(net.minecraft.nbt.StringTag.valueOf("not-a-uuid"));
        roster.add(net.minecraft.nbt.StringTag.valueOf(UUID.randomUUID().toString()));
        tag.put("Roster", roster);
        tag.putBoolean("RosterCaptured", true);
        FreshnessMigrationData data = FreshnessMigrationData.load(tag);
        helper.assertTrue(data.rosterSize() == 1,
                "非 UUID 的脏数据跳过, 剩下那一条照读, 实得 " + data.rosterSize());
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 一个物品条目 (与原版容器存档同形状: id + Count + tag)。 */
    private static CompoundTag itemEntry(String id, int count, CompoundTag tag) {
        CompoundTag entry = new CompoundTag();
        entry.putString("id", id);
        entry.putByte("Count", (byte) count);
        entry.put("tag", tag);
        return entry;
    }

    /** 物品自己的 NBT (潜影盒装东西就活在这一层里)。 */
    private static CompoundTag itemTag(ItemStack stack) {
        return stack.getOrCreateTag();
    }
}
