package com.miningdim.district.guard.create;

import java.util.List;

/**
 * 机械动力注入点的常量表 (设计文档 22.6、22.14): mixin 注解里的类名、方法描述符、INVOKE 目标全部引用这里的
 * {@code static final String}; 离线核对 ({@code CreateMixinTargetGameTests}) 读同一张 {@link #HOOKS} 表, 两边不会各写一份。
 *
 * <p>全部对整合包里的 Create 6.0.8 (本地重编译的 {@code create_consblocks.jar}) 用 javap 核对过。机械动力自己的类名、方法名
 * 没有混淆 (选择器 remap = false); INVOKE 指向 Minecraft 方法时, 注解里写开发环境的名字 (remap = true, 由 refmap 换成
 * SRG 名), 离线核对按 jar 里实际出现的 SRG 名找。
 */
public final class CreateHookTargets {

    private CreateHookTargets() {
    }

    // ---- 类名 ----

    public static final String BLOCK_HELPER = "com.simibubi.create.foundation.utility.BlockHelper";
    public static final String SCHEMATICANNON = "com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity";
    public static final String BREAKING_BLOCK_ENTITY = "com.simibubi.create.content.kinetics.base.BlockBreakingKineticBlockEntity";
    public static final String BREAKING_MOVEMENT = "com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour";
    public static final String CONTRAPTION_ENTITY = "com.simibubi.create.content.contraptions.AbstractContraptionEntity";
    public static final String HARVESTER = "com.simibubi.create.content.contraptions.actors.harvester.HarvesterMovementBehaviour";
    public static final String ROLLER = "com.simibubi.create.content.contraptions.actors.roller.RollerMovementBehaviour";
    public static final String COLLIDER = "com.simibubi.create.content.contraptions.ContraptionCollider";
    public static final String CONTRAPTION = "com.simibubi.create.content.contraptions.Contraption";
    public static final String MOVEMENT_CHECKS = "com.simibubi.create.impl.contraption.BlockMovementChecksImpl";
    public static final String FLUID_MANIPULATION = "com.simibubi.create.content.fluids.transfer.FluidManipulationBehaviour";
    public static final String FLUID_FILLING = "com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour";
    public static final String OPEN_ENDED_PIPE = "com.simibubi.create.content.fluids.OpenEndedPipe";
    public static final String TRACK_PLACEMENT = "com.simibubi.create.content.trains.track.TrackPlacement";
    public static final String BELT_CONNECTOR = "com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem";
    public static final String SYMMETRY_WAND = "com.simibubi.create.content.equipment.symmetryWand.SymmetryWandItem";
    public static final String ARM_POINT = "com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint";
    public static final String DISPLAY_LINK = "com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity";
    public static final String POTATO_TYPE = "com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType";

    public static final String MOVEMENT_CONTEXT = "com.simibubi.create.content.contraptions.behaviour.MovementContext";
    public static final String PAVE_RESULT = ROLLER + "$PaveResult";
    public static final String SPACE_TYPE = FLUID_FILLING + "$SpaceType";
    public static final String PLACEMENT_INFO = TRACK_PLACEMENT + "$PlacementInfo";
    public static final String BEZIER_CONNECTION = "com.simibubi.create.content.trains.track.BezierConnection";
    public static final String ALL_CONFIGS = "com.simibubi.create.infrastructure.config.AllConfigs";

    // ---- 描述符里常用的类型 ----

    private static final String LEVEL = "Lnet/minecraft/world/level/Level;";
    private static final String LEVEL_ACCESSOR = "Lnet/minecraft/world/level/LevelAccessor;";
    private static final String POS = "Lnet/minecraft/core/BlockPos;";
    private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String PLAYER = "Lnet/minecraft/world/entity/player/Player;";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String FLUID = "Lnet/minecraft/world/level/material/Fluid;";
    private static final String FLUID_STACK = "Lnet/minecraftforge/fluids/FluidStack;";
    private static final String CTX = "Lcom/simibubi/create/content/contraptions/behaviour/MovementContext;";

    // ---- 方法选择器 (名字 + 描述符) ----

    /** C1 BlockHelper.destroyBlockAs (static)。 */
    public static final String DESTROY_BLOCK_AS = "destroyBlockAs(" + LEVEL + POS + PLAYER + STACK
            + "FLjava/util/function/Consumer;)V";
    /** C2 SchematicannonBlockEntity.shouldPlace (protected)。 */
    public static final String SHOULD_PLACE = "shouldPlace(" + POS + STATE
            + "Lnet/minecraft/world/level/block/entity/BlockEntity;" + STATE + STATE + "Z)Z";
    /** C3 BlockBreakingKineticBlockEntity.canBreak。 */
    public static final String BE_CAN_BREAK = "canBreak(" + STATE + "F)Z";
    /** C4 BlockBreakingMovementBehaviour.canBreak。 */
    public static final String MOVEMENT_CAN_BREAK = "canBreak(" + LEVEL + POS + STATE + ")Z";
    /** C5 AbstractContraptionEntity.isActorActive (protected)。 */
    public static final String IS_ACTOR_ACTIVE = "isActorActive(" + CTX
            + "Lcom/simibubi/create/api/behaviour/movement/MovementBehaviour;)Z";
    /** C6 HarvesterMovementBehaviour.visitNewPosition。 */
    public static final String VISIT_NEW_POSITION = "visitNewPosition(" + CTX + POS + ")V";
    /** C7 RollerMovementBehaviour.tryFill (protected, 返回包内可见的 PaveResult)。 */
    public static final String TRY_FILL = "tryFill(" + CTX + POS + STATE
            + ")Lcom/simibubi/create/content/contraptions/actors/roller/RollerMovementBehaviour$PaveResult;";
    /** C8 ContraptionCollider.isCollidingWithWorld (static)。 */
    public static final String IS_COLLIDING_WITH_WORLD = "isCollidingWithWorld(" + LEVEL
            + "Lcom/simibubi/create/content/contraptions/TranslatingContraption;" + POS
            + "Lnet/minecraft/core/Direction;)Z";
    /** C9 Contraption.addBlocksToWorld。 */
    public static final String ADD_BLOCKS_TO_WORLD = "addBlocksToWorld(" + LEVEL
            + "Lcom/simibubi/create/content/contraptions/StructureTransform;)V";
    /** C10 BlockMovementChecksImpl.isMovementAllowed (static)。 */
    public static final String IS_MOVEMENT_ALLOWED = "isMovementAllowed(" + STATE + LEVEL + POS + ")Z";
    /** C11 FluidManipulationBehaviour.search (protected)。 */
    public static final String SEARCH = "search(" + FLUID
            + "Ljava/util/List;Ljava/util/Set;Ljava/util/function/BiConsumer;Z)" + FLUID;
    /** C12 FluidFillingBehaviour.getAtPos (protected, 返回包内可见的 SpaceType)。 */
    public static final String GET_AT_POS = "getAtPos(" + LEVEL + POS + FLUID
            + ")Lcom/simibubi/create/content/fluids/transfer/FluidFillingBehaviour$SpaceType;";
    /** C13 OpenEndedPipe.provideFluidToSpace (private)。 */
    public static final String PROVIDE_FLUID_TO_SPACE = "provideFluidToSpace(" + FLUID_STACK + "Z)Z";
    /** C13 OpenEndedPipe.removeFluidFromSpace (private)。 */
    public static final String REMOVE_FLUID_FROM_SPACE = "removeFluidFromSpace(Z)" + FLUID_STACK;
    /** C14 TrackPlacement.tryConnect (static)。 */
    public static final String TRY_CONNECT = "tryConnect(" + LEVEL + PLAYER + POS + STATE + STACK
            + "ZZ)Lcom/simibubi/create/content/trains/track/TrackPlacement$PlacementInfo;";
    /** C14 TrackPlacement.placeTracks (private static): tryConnect 以 simulate = true 调它一次, 把 PlacementInfo 交出来。 */
    public static final String PLACE_TRACKS = "placeTracks(" + LEVEL
            + "Lcom/simibubi/create/content/trains/track/TrackPlacement$PlacementInfo;" + STATE + STATE + POS + POS
            + "Z)Lcom/simibubi/create/content/trains/track/TrackPlacement$PlacementInfo;";
    /**
     * C15 BeltConnectorItem.useOn: 覆写的是 Minecraft 的 Item.useOn, 机械动力的 jar 里是 SRG 名 m_6225_ (正式服就是这个
     * 名字; 开发运行时没有机械动力, 这个 mixin 不会应用), 所以选择器直接写 SRG 名、remap = false。
     */
    public static final String BELT_USE_ON = "m_6225_(Lnet/minecraft/world/item/context/UseOnContext;)"
            + "Lnet/minecraft/world/InteractionResult;";
    /** C15 BeltConnectorItem.createBelts (static): 玩家连接与蓝图炮打印传送带都走它, 逐格拆掉挡路的方块再放传送带。 */
    public static final String CREATE_BELTS = "createBelts(" + LEVEL + POS + POS + ")V";
    /** C16 SymmetryWandItem.remove (static): 镜像拆, 不发 BreakEvent。 */
    public static final String SYMMETRY_REMOVE = "remove(" + LEVEL + STACK + PLAYER + POS + ")V";
    /** C16 SymmetryWandItem.apply (static): 镜像放, 创造模式那一支不发放置事件。 */
    public static final String SYMMETRY_APPLY = "apply(" + LEVEL + STACK + PLAYER + POS + STATE + ")V";
    /** C17 ArmInteractionPoint.deserialize (static): 机械臂的交互点从 NBT 读出来的唯一入口 (网络包、蓝图、存档)。 */
    public static final String ARM_DESERIALIZE = "deserialize(Lnet/minecraft/nbt/CompoundTag;" + LEVEL + POS
            + ")Lcom/simibubi/create/content/kinetics/mechanicalArm/ArmInteractionPoint;";
    /** C18 DisplayLinkBlockEntity.updateGatheredData: tickSource、失去信号、改配置的网络包都走它, 再 transferData 写目标。 */
    public static final String DISPLAY_UPDATE = "updateGatheredData()V";
    /** C19 PotatoCannonProjectileType.onBlockHit (record 的方法): 射弹打中方块时的数据驱动动作 (放方块、种作物)。 */
    public static final String POTATO_ON_BLOCK_HIT = "onBlockHit(" + LEVEL_ACCESSOR + STACK
            + "Lnet/minecraft/world/phys/BlockHitResult;)Z";

    // ---- INVOKE 目标 (开发环境名字, remap = true) ----

    /** C8: Level.isLoaded(BlockPos), 正式服 SRG 名 m_46749_。 */
    public static final String LEVEL_IS_LOADED = LEVEL + "isLoaded(" + POS + ")Z";
    /** C11: Level.getFluidState(BlockPos), 正式服 SRG 名 m_6425_。 */
    public static final String LEVEL_GET_FLUID_STATE = LEVEL + "getFluidState(" + POS
            + ")Lnet/minecraft/world/level/material/FluidState;";
    /** C14: Player.isCreative(), 正式服 SRG 名 m_7500_; tryConnect 里只出现一次, 在开始扣物品、真正铺轨之前。 */
    public static final String PLAYER_IS_CREATIVE = PLAYER + "isCreative()Z";
    /** C9: Contraption.customBlockPlacement (机械动力自己的名字, remap = false)。 */
    public static final String CUSTOM_BLOCK_PLACEMENT = "Lcom/simibubi/create/content/contraptions/Contraption;"
            + "customBlockPlacement(" + LEVEL_ACCESSOR + POS + STATE + ")Z";
    /** C15: useOn 里唯一一次调 canConnect (机械动力自己的名字, remap = false), 在扣物品、铺传送带之前。 */
    public static final String BELT_CAN_CONNECT = "Lcom/simibubi/create/content/kinetics/belt/item/BeltConnectorItem;"
            + "canConnect(" + LEVEL + POS + POS + ")Z";
    /** C16: remove、apply 里各有唯一一次 Map.keySet() (镜像出来的全部位置), remap = false。 */
    public static final String MAP_KEY_SET = "Ljava/util/Map;keySet()Ljava/util/Set;";

    // ================================================================
    // 离线核对用的表 (22.14)
    // ================================================================

    /** 一个方法: 名字与描述符。 */
    public record Member(String name, String desc) {

        static Member of(String selector) {
            int paren = selector.indexOf('(');
            return new Member(selector.substring(0, paren), selector.substring(paren));
        }
    }

    /**
     * INVOKE 目标: 在哪个方法里、调的是谁 (jar 里的 owner 与 SRG 名或机械动力自己的名字)、描述符、应出现几次。
     */
    public record Invoke(Member in, String owner, String name, String desc, int count) {
    }

    /** 一个字段 (影子字段或反射读写的字段): owner 的内部名、名字、描述符。 */
    public record Field(String owner, String name, String desc) {
    }

    /**
     * 一个注入点。
     *
     * @param id      C1 ~ C19
     * @param mixin   mixin 的简名 (miningdim.district.create.mixins.json 里的名字)
     * @param target  目标类 (点分全名)
     * @param methods 注入的方法
     * @param invokes INVOKE 目标
     * @param shadows 影子字段
     * @param covers  失效时少了什么 (给 /district status 与开服 ERROR 用)
     */
    public record Hook(String id, String mixin, String target, List<Member> methods, List<Invoke> invokes,
                       List<Field> shadows, String covers) {

        public String targetSimpleName() {
            return simpleName(target);
        }
    }

    public static final List<Hook> HOOKS = List.of(
            new Hook("C1", "BlockHelperMixin", BLOCK_HELPER, List.of(Member.of(DESTROY_BLOCK_AS)), List.of(),
                    List.of(), "机器拆方块的总闸 (钻头、锯、压路机、收割机拆方块, 锯伐树)"),
            new Hook("C2", "SchematicannonMixin", SCHEMATICANNON, List.of(Member.of(SHOULD_PLACE)), List.of(),
                    List.of(), "蓝图炮"),
            new Hook("C3", "BlockBreakingBlockEntityMixin", BREAKING_BLOCK_ENTITY, List.of(Member.of(BE_CAN_BREAK)),
                    List.of(), List.of(new Field(internal(BREAKING_BLOCK_ENTITY), "breakingPos", POS)),
                    "固定的钻头、锯开始拆之前"),
            new Hook("C4", "BlockBreakingMovementMixin", BREAKING_MOVEMENT, List.of(Member.of(MOVEMENT_CAN_BREAK)),
                    List.of(), List.of(), "装置上的钻头、锯、压路机把区内方块当墙"),
            new Hook("C5", "ContraptionActorMixin", CONTRAPTION_ENTITY, List.of(Member.of(IS_ACTOR_ACTIVE)),
                    List.of(), List.of(), "装置部件的总网 (装置上的发射器、钻头与锯伤实体、火车上的部件)"),
            new Hook("C6", "HarvesterMixin", HARVESTER, List.of(Member.of(VISIT_NEW_POSITION)), List.of(), List.of(),
                    "收割机重置区内作物"),
            new Hook("C7", "RollerMixin", ROLLER, List.of(Member.of(TRY_FILL)), List.of(), List.of(),
                    "压路机铺路"),
            new Hook("C8", "ContraptionColliderMixin", COLLIDER, List.of(Member.of(IS_COLLIDING_WITH_WORLD)),
                    List.of(new Invoke(Member.of(IS_COLLIDING_WITH_WORLD), "net/minecraft/world/level/Level",
                            "m_46749_", "(" + POS + ")Z", 1)),
                    List.of(), "活塞、滑轮、龙门平移进区内"),
            new Hook("C9", "ContraptionDisassemblyMixin", CONTRAPTION, List.of(Member.of(ADD_BLOCKS_TO_WORLD)),
                    List.of(new Invoke(Member.of(ADD_BLOCKS_TO_WORLD), internal(CONTRAPTION), "customBlockPlacement",
                            "(" + LEVEL_ACCESSOR + POS + STATE + ")Z", 1)),
                    List.of(), "装置拆装时砸掉区内方块"),
            new Hook("C10", "BlockMovementChecksMixin", MOVEMENT_CHECKS, List.of(Member.of(IS_MOVEMENT_ALLOWED)),
                    List.of(), List.of(), "装置带着区内方块组装"),
            new Hook("C11", "FluidSearchMixin", FLUID_MANIPULATION, List.of(Member.of(SEARCH)),
                    List.of(new Invoke(Member.of(SEARCH), "net/minecraft/world/level/Level", "m_6425_",
                            "(" + POS + ")Lnet/minecraft/world/level/material/FluidState;", 2)),
                    List.of(), "软管滑轮抽区内的液体"),
            new Hook("C12", "FluidFillingMixin", FLUID_FILLING, List.of(Member.of(GET_AT_POS)), List.of(), List.of(),
                    "软管滑轮往区内灌液"),
            new Hook("C13", "OpenEndedPipeMixin", OPEN_ENDED_PIPE,
                    List.of(Member.of(PROVIDE_FLUID_TO_SPACE), Member.of(REMOVE_FLUID_FROM_SPACE)), List.of(),
                    List.of(new Field(internal(OPEN_ENDED_PIPE), "world", LEVEL),
                            new Field(internal(OPEN_ENDED_PIPE), "outputPos", POS)),
                    "开口管道往区内放液、从区内吸液"),
            new Hook("C14", "TrackPlacementMixin", TRACK_PLACEMENT,
                    List.of(Member.of(TRY_CONNECT), Member.of(PLACE_TRACKS)),
                    List.of(new Invoke(Member.of(TRY_CONNECT), "net/minecraft/world/entity/player/Player", "m_7500_",
                            "()Z", 1),
                            new Invoke(Member.of(TRY_CONNECT), internal(TRACK_PLACEMENT), "placeTracks",
                                    PLACE_TRACKS.substring(PLACE_TRACKS.indexOf('(')), 2)),
                    List.of(), "轨道弯道、长直道铺进禁放区"),
            new Hook("C15", "BeltConnectorMixin", BELT_CONNECTOR,
                    List.of(Member.of(BELT_USE_ON), Member.of(CREATE_BELTS)),
                    List.of(new Invoke(Member.of(BELT_USE_ON), internal(BELT_CONNECTOR), "canConnect",
                            "(" + LEVEL + POS + POS + ")Z", 1)),
                    List.of(), "传送带连进禁放区 (玩家连接; 蓝图炮打印的传送带逐格拆掉区内方块)"),
            new Hook("C16", "SymmetryWandMixin", SYMMETRY_WAND,
                    List.of(Member.of(SYMMETRY_REMOVE), Member.of(SYMMETRY_APPLY)),
                    List.of(new Invoke(Member.of(SYMMETRY_REMOVE), "java/util/Map", "keySet", "()Ljava/util/Set;", 1),
                            new Invoke(Member.of(SYMMETRY_APPLY), "java/util/Map", "keySet", "()Ljava/util/Set;", 1)),
                    List.of(), "对称之杖把镜像的拆、放做到别的区域 (镜像拆不发 BreakEvent)"),
            new Hook("C17", "ArmInteractionPointMixin", ARM_POINT, List.of(Member.of(ARM_DESERIALIZE)), List.of(),
                    List.of(), "机械臂从别的区域取放物品 (服务端不查交互点的距离与归属)"),
            new Hook("C18", "DisplayLinkMixin", DISPLAY_LINK, List.of(Member.of(DISPLAY_UPDATE)), List.of(), List.of(),
                    "显示链接读写别的区域的方块 (告示牌、讲台的字)"),
            new Hook("C19", "PotatoProjectileTypeMixin", POTATO_TYPE, List.of(Member.of(POTATO_ON_BLOCK_HIT)),
                    List.of(), List.of(), "土豆加农炮的射弹往区内放方块、种作物"));

    /** 反射读写的字段 (CreateReflection), 离线核对一并看。 */
    public static final List<Field> REFLECTED_FIELDS = List.of(
            new Field(internal(MOVEMENT_CONTEXT), "position", "Lnet/minecraft/world/phys/Vec3;"),
            new Field(internal(MOVEMENT_CONTEXT), "world", LEVEL),
            new Field(internal(PLACEMENT_INFO), "valid", "Z"),
            new Field(internal(PLACEMENT_INFO), "pos1", POS),
            new Field(internal(PLACEMENT_INFO), "pos2", POS),
            new Field(internal(PLACEMENT_INFO), "end1Extent", "I"),
            new Field(internal(PLACEMENT_INFO), "end2Extent", "I"),
            new Field(internal(PLACEMENT_INFO), "axis1", "Lnet/minecraft/world/phys/Vec3;"),
            new Field(internal(PLACEMENT_INFO), "axis2", "Lnet/minecraft/world/phys/Vec3;"),
            new Field(internal(PLACEMENT_INFO), "curve", "L" + internal(BEZIER_CONNECTION) + ";"),
            new Field(internal(PAVE_RESULT), "FAIL", "L" + internal(PAVE_RESULT) + ";"),
            new Field(internal(SPACE_TYPE), "BLOCKING", "L" + internal(SPACE_TYPE) + ";"));

    /** 反射调用的方法。 */
    public static final List<Invoke> REFLECTED_METHODS = List.of(
            new Invoke(new Member("getBounds", "()Lnet/minecraft/world/phys/AABB;"), internal(BEZIER_CONNECTION),
                    "getBounds", "()Lnet/minecraft/world/phys/AABB;", 1),
            new Invoke(new Member("server", "()Lcom/simibubi/create/infrastructure/config/CServer;"),
                    internal(ALL_CONFIGS), "server", "()Lcom/simibubi/create/infrastructure/config/CServer;", 1));

    public static String internal(String dotted) {
        return dotted.replace('.', '/');
    }

    public static String simpleName(String dotted) {
        return dotted.substring(dotted.lastIndexOf('.') + 1);
    }
}
