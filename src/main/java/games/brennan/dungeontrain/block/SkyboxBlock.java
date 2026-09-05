package games.brennan.dungeontrain.block;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A solid, unmineable full cube that reads as a hole punched through the world:
 * look at it and you see the skybox, even when there is terrain — or a carriage
 * wall — directly behind it.
 *
 * <p>The block itself contributes <em>no</em> chunk geometry
 * ({@link RenderShape#INVISIBLE}); the sky effect is produced entirely on the
 * client by
 * {@link games.brennan.dungeontrain.client.skybox.SkyboxPunchRenderer}, which
 * writes the cube's faces into the depth buffer (colour writes masked off) in the
 * gap between vanilla drawing the sky and drawing terrain. Everything behind the
 * cube then fails the depth test and the already-painted sky survives; everything
 * in front draws normally.</p>
 *
 * <p>Note what is deliberately <b>not</b> set on the block properties in
 * {@link games.brennan.dungeontrain.registry.ModBlocks}: {@code noOcclusion()}.
 * Unlike a barrier we <em>want</em> vanilla to treat this as opaque, so it culls
 * neighbouring block faces and whole chunk sections behind it — that culling is
 * free correctness on top of the depth punch, not a conflict with it.</p>
 */
public class SkyboxBlock extends Block {

    public static final MapCodec<SkyboxBlock> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
            SkyboxSky.CODEC.fieldOf("sky").forGetter(SkyboxBlock::sky),
            propertiesCodec()
        ).apply(instance, SkyboxBlock::new)
    );

    private final SkyboxSky sky;

    public SkyboxBlock(SkyboxSky sky, BlockBehaviour.Properties properties) {
        super(properties);
        this.sky = sky;
    }

    /** Which sky this block's hole shows. Drives its stencil ref at render time. */
    public SkyboxSky sky() {
        return sky;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /** No baked model — the depth punch is the entire visual. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    /**
     * The outline / raycast shape — <b>creative only</b>. In survival the ray passes straight
     * through, so hovering a skybox block draws no selection box: the black outline across a
     * patch of sky is precisely the tell that breaks the illusion.
     *
     * <p>Same shape vanilla's {@code LightBlock} uses to hide itself unless you hold a light.
     * Note the consequence: with no outline shape the survival ray continues past the block
     * and hits whatever is behind it, which the depth punch has hidden — so a player can
     * interact with a block they cannot see, within reach. Accepted deliberately in favour of
     * an unbroken sky.</p>
     */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return isCreativePlayer(context) && dungeontrainSkyboxesOn(context)
            ? Shapes.block() : Shapes.empty();
    }

    /**
     * Always a full cube.
     *
     * <p>Must be stated explicitly and <b>must not</b> be left to default: {@code
     * BlockBehaviour}'s default collision shape is {@code state.getShape(level, pos)}, which is
     * empty in survival — the block would silently become walk-through.</p>
     */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // Switched off, the block is not there: no collision, so an author can walk out through a
        // sky wall to see it from behind. Client-side, and in single player that IS the server, so
        // the walk-through is real; a dedicated server answers with its own config and keeps them
        // solid — see ClientDisplayConfig.areSkyboxBlocksOn().
        return dungeontrainSkyboxesOn(context) ? Shapes.block() : Shapes.empty();
    }

    /**
     * Always a full cube, for the same reason as {@link #getCollisionShape} — the default is
     * also {@code getShape}.
     *
     * <p>This one is load-bearing for the feature rather than merely correct: occlusion is what
     * culls the neighbouring block faces and the chunk sections behind the hole. Let it fall
     * through to the survival-empty shape and every sky window grows a border of stone edges.</p>
     */
    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        // Off, it must not cull its neighbours either: an invisible block that still hides the wall
        // behind it leaves a hole in the room, which is the opposite of what turning it off is for.
        // No context to ask who is looking, so the test override is "is anybody testing" — see
        // PortalTestSession.anyActive.
        return ClientDisplayConfig.areSkyboxBlocksOn()
            || games.brennan.dungeontrain.portal.PortalTestSession.anyActive()
            ? Shapes.block() : Shapes.empty();
    }

    /**
     * {@code CollisionContext.empty()} is an {@link EntityCollisionContext} carrying a null
     * entity, so every context-free {@code getShape} call answers "not creative" — which is
     * why the two overrides above exist.
     */
    /**
     * Whether Skybox Blocks are there for whoever is asking.
     *
     * <p>The author's switch, <b>except inside a test</b>. Turning them off is a way to build — walk
     * out through a sky wall and see it from behind — and Test the Carriage is the one place in the
     * editor that promises the room as a player will meet it. A wall you can stroll through there is
     * the test lying about the build, so a test session puts them back however the switch is set,
     * and takes them away again on the way out.</p>
     */
    private static boolean dungeontrainSkyboxesOn(CollisionContext context) {
        if (ClientDisplayConfig.areSkyboxBlocksOn()) return true;
        return context instanceof EntityCollisionContext entityContext
            && entityContext.getEntity() instanceof Player player
            && games.brennan.dungeontrain.portal.PortalTestSession.has(player.getUUID());
    }

    private static boolean isCreativePlayer(CollisionContext context) {
        return context instanceof EntityCollisionContext entityContext
            && entityContext.getEntity() instanceof Player player
            && player.isCreative();
    }

    /**
     * Full brightness, so the block casts no ambient-occlusion darkening onto the
     * blocks around the hole. Vanilla {@code BarrierBlock} does the same.
     */
    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }

    /**
     * Costs light exactly what air costs it — <b>light is the one exception</b> to the occlusion
     * this block otherwise keeps.
     *
     * <p>A hole punched through to the sky that then casts a hard shadow underneath itself is the
     * illusion contradicting itself. {@link #getShadeBrightness} already stopped the block darkening
     * its neighbours; this is what lets light actually pass. Default for a solid-render block is
     * full opacity, and the light engine reads this through {@code LightEngine.getOpacity}.</p>
     *
     * <p>Occlusion itself ({@code canOcclude}) stays on and costs the light engine nothing:
     * {@code LightEngine.isEmptyShape} skips shape-based light occlusion for any block that does not
     * set {@code useShapeForLightOcclusion}, which this does not. So the face and section culling the
     * class javadoc calls load-bearing is untouched.</p>
     *
     * <p>Block light passes too, not only skylight — a torch behind a skybox wall lights what is in
     * front of it. Accepted: it is the same "there is nothing there" the depth punch already claims
     * to the eye, and splitting the two would mean a directional occlusion shape for a block whose
     * whole premise is that it is a hole.</p>
     */
    @Override
    protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return 0;
    }

    /**
     * Lets a skylight column fall straight through at full strength, which is what stops a skybox
     * ceiling shadowing the floor below it. Defaults to false for a full collision cube, which every
     * skybox block is — see {@link #getCollisionShape}.
     *
     * <p>Both this and {@link #getLightBlock} are read once per block state into
     * {@code BlockBehaviour$BlockStateBase.Cache}, so neither is on any per-tick path.</p>
     */
    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}
