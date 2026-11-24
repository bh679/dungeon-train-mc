package games.brennan.dungeontrain.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * Forced Air: an editor block that is air everywhere a player can tell, and is written as plain
 * {@code minecraft:air} when a template carrying it is stamped in a live run.
 *
 * <p>Plain air in a template means "leave what is there" on the stamp paths that lay a template
 * over terrain (a chunk frame over a sampled chunk dimension, for one), so an author had no way to
 * say "air here, even under the sea". Forced Air says it. The stamp rule lives in {@link ForcedAir}.</p>
 *
 * <p>In the editor it behaves like vanilla's light block: invisible, no collision, replaceable by
 * any block placed into it, and only outlined (and marked with its bubble icon, see
 * {@code ClientLevelForcedAirMarkerMixin}) while the player holds it. It keeps water out — it is a
 * {@link LiquidBlockContainer} that refuses every fluid — so an air pocket stays a pocket while it
 * is being built.</p>
 */
public class ForcedAirBlock extends Block implements LiquidBlockContainer {

    public static final MapCodec<ForcedAirBlock> CODEC = simpleCodec(ForcedAirBlock::new);

    public ForcedAirBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return context.isHoldingItem(asItem()) ? Shapes.block() : Shapes.empty();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }

    @Override
    public boolean canPlaceLiquid(Player player, BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        return false;
    }

    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState) {
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("block.dungeontrain.forced_air.tooltip").withStyle(ChatFormatting.GRAY));
    }
}
