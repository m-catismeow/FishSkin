package dev.mcatismeow.fishskin.runtime.block;

import com.mojang.serialization.MapCodec;
import dev.mcatismeow.fishskin.runtime.SkinConfig;
import dev.mcatismeow.fishskin.runtime.SkinMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** The optional blocks: the skin station (opens the wardrobe) and the skin zone marker (creative only). */
public final class SkinBlocks {
    public static final ResourceLocation STATION_ID = SkinMod.id("skin_station");
    public static final ResourceLocation ZONE_ID = SkinMod.id("skin_zone");

    public static Block STATION;
    public static Block ZONE;
    public static Item STATION_ITEM;
    public static Item ZONE_ITEM;
    public static BlockEntityType<ZoneBlockEntity> ZONE_BE;

    /** A block of our own that opens the wardrobe (a Block node with "make my own block"). */
    public record Custom(ResourceLocation id, Block block, Item item) {}

    public static final List<Custom> CUSTOM = new ArrayList<>();
    /** Game blocks that open the wardrobe when right-clicked, for example minecraft:crafting_table. */
    public static final Set<ResourceLocation> VANILLA = ConcurrentHashMap.newKeySet();
    /** Items that open the wardrobe when used / right-clicked. */
    public static final Set<ResourceLocation> ITEMS = ConcurrentHashMap.newKeySet();
    /** Quick O(1) path matching without namespace. */
    public static final Set<String> PATHS = ConcurrentHashMap.newKeySet();

    /** Set by the client side; the common block code only calls these on the logical client. */
    public static Runnable openWardrobe = () -> {};
    public static Consumer<ZoneBlockEntity> openZoneEditor = be -> {};

    private static boolean created;

    private SkinBlocks() {}

    private static void registerMatch(ResourceLocation rl) {
        if (rl == null) return;
        VANILLA.add(rl);
        ITEMS.add(rl);
        PATHS.add(rl.getPath().toLowerCase());
    }

    /**
     * Builds the blocks and items the mod needs; each loader then registers them under the ids above.
     * Call it from inside the loader block registration (Forge refuses new blocks once registries are frozen).
     */
    public static void create() {
        if (created) return;
        created = true;
        // the standalone mod always has both blocks, so client and server registries match whatever the config says
        if (SkinConfig.userConfig() || SkinConfig.builtinStation()) {
            STATION = new StationBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, STATION_ID)).strength(2.0f).sound(SoundType.WOOD));
            STATION_ITEM = new BlockItem(STATION, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, STATION_ID)).useBlockDescriptionPrefix());
        }
        if (SkinConfig.userConfig() || SkinConfig.zoneEnabled()) {
            // like the barrier: unbreakable by hand, creative players can still break it
            ZONE = new ZoneBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, ZONE_ID)).strength(-1.0f, 3600000.0f).noLootTable().noCollission().noOcclusion().pushReaction(PushReaction.BLOCK));
            ZONE_ITEM = new ZoneItem(ZONE, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, ZONE_ID)).useBlockDescriptionPrefix());
            ZONE_BE = SkinMod.platform.blockEntityType(ZoneBlockEntity::new, ZONE);
        }
        reloadMatches();
    }

    /** Rebuilds which game blocks / items open the wardrobe (at start, and when a server sends its settings). */
    public static synchronized void reloadMatches() {
        VANILLA.clear();
        ITEMS.clear();
        PATHS.clear();
        for (SkinConfig.BlockEntry e : SkinConfig.blocks()) {
            String v = e.vanilla().trim().toLowerCase();
            String idStr = e.id().trim().toLowerCase();
            if (!v.isEmpty()) {
                registerMatch(ResourceLocation.tryParse(v));
                if (!v.contains(":")) registerMatch(ResourceLocation.tryParse("minecraft:" + v));
            }
            if (!idStr.isEmpty()) {
                registerMatch(ResourceLocation.tryParse(idStr));
                if (!idStr.contains(":")) registerMatch(ResourceLocation.tryParse("minecraft:" + idStr));
            }
        }
        for (String itemStr : SkinConfig.items()) {
            String s = itemStr.trim().toLowerCase();
            registerMatch(ResourceLocation.tryParse(s));
            if (!s.contains(":")) registerMatch(ResourceLocation.tryParse("minecraft:" + s));
        }
    }

    public static boolean matchesBlockOrItem(ResourceLocation loc) {
        if (loc == null) return false;
        if (VANILLA.contains(loc) || ITEMS.contains(loc)) return true;
        return PATHS.contains(loc.getPath().toLowerCase());
    }

    /** Is this one of the blocks that open the wardrobe (ours, or a game block chosen in the launcher)? */
    public static boolean isSkinBlock(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof StationBlock) return SkinConfig.builtinStation();
        if (!SkinConfig.blockEnabled()) return false;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
        if (matchesBlockOrItem(id)) return true;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(b.asItem());
        return matchesBlockOrItem(itemId);
    }

    /**
     * Right-click on a block that was turned into a skin block. Called on both sides: the client opens the
     * window, and both sides swallow the click so the block's own screen (a crafting table, say) does not open too.
     * Sneaking still places blocks as usual.
     */
    public static boolean handleUse(Level level, Player player, BlockPos pos) {
        if (player.isShiftKeyDown()) return false;
        Block block = level.getBlockState(pos).getBlock();
        if (block instanceof StationBlock) {
            if (!SkinConfig.builtinStation()) return false;
            if (level.isClientSide) openWardrobe.run();
            return true;
        }
        if (!SkinConfig.blockEnabled()) return false;
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
        if (matchesBlockOrItem(blockId)) {
            if (level.isClientSide) openWardrobe.run();
            return true;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(block.asItem());
        if (matchesBlockOrItem(itemId)) {
            if (level.isClientSide) openWardrobe.run();
            return true;
        }
        return false;
    }

    /**
     * Wardrobe can only be opened by interacting with placed blocks in world, not by holding items.
     */
    public static boolean handleUseItem(Level level, Player player, net.minecraft.world.item.ItemStack stack) {
        return false;
    }

    static final class StationBlock extends Block {
        StationBlock(Properties properties) { super(properties); }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (level.isClientSide && SkinConfig.builtinStation()) openWardrobe.run();
            return InteractionResult.SUCCESS;
        }

        @Override
        protected InteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
            if (level.isClientSide && SkinConfig.builtinStation()) openWardrobe.run();
            return InteractionResult.SUCCESS;
        }
    }

    static final class ZoneItem extends BlockItem {
        ZoneItem(Block block, Properties properties) { super(block, properties); }

        @Override
        protected boolean canPlace(BlockPlaceContext ctx, BlockState state) {
            Player p = ctx.getPlayer();
            return p != null && p.isCreative() && super.canPlace(ctx, state);
        }
    }

    static final class ZoneBlock extends BaseEntityBlock {
        private static final MapCodec<ZoneBlock> CODEC = simpleCodec(ZoneBlock::new);

        ZoneBlock(Properties properties) { super(properties); }

        @Override
        protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ZoneBlockEntity(pos, state); }

        @Override
        protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            // like the light block: it cannot be targeted (or even seen) unless you hold the item
            return ZONE_ITEM != null && ctx.isHoldingItem(ZONE_ITEM) ? Shapes.block() : Shapes.empty();
        }

        @Override
        protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) { return Shapes.empty(); }

        @Override
        protected boolean propagatesSkylightDown(BlockState state) { return true; }

        @Override
        protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) { return 1.0f; }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (!player.isCreative()) return InteractionResult.PASS;
            if (level.isClientSide && level.getBlockEntity(pos) instanceof ZoneBlockEntity be) openZoneEditor.accept(be);
            return InteractionResult.SUCCESS;
        }
    }
}
