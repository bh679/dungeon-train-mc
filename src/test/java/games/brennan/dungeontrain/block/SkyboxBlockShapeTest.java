package games.brennan.dungeontrain.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * <b>{@link SkyboxBlock} must keep overriding its collision and occlusion shapes.</b>
 *
 * <p>{@code getShape} deliberately returns an empty shape unless the raycasting entity is a
 * creative player, so hovering a skybox block in survival draws no selection outline across the
 * sky. On its own that is a silent double regression, because {@code BlockBehaviour} derives
 * both other shapes from it:</p>
 *
 * <pre>
 *   getCollisionShape(...)  →  hasCollision ? state.getShape(level, pos) : empty
 *   getOcclusionShape(...)  →  state.getShape(level, pos)
 * </pre>
 *
 * <p>Delete either override and the block silently becomes <b>walk-through</b> and stops
 * <b>occluding</b> — and occlusion is what culls the neighbouring faces and the chunk sections
 * behind the hole, so every sky window would grow a border of stone edges. Neither failure is
 * visible until someone walks into a wall or looks at one.</p>
 *
 * <p><b>Why this is a structural check and not a behavioural one.</b> It asserts the overrides
 * are <em>declared on {@code SkyboxBlock}</em> rather than calling them, because a block cannot
 * be instantiated in this project's test JVM at all: {@code Block}'s constructor claims an
 * intrusive holder in {@code BuiltInRegistries.BLOCK}, FML has already frozen the registries by
 * the time tests run, and DT's own blocks are never registered there (they go through
 * {@code DeferredRegister} on the mod bus). Looking one up is equally impossible. So this
 * guards the deletion — which is the regression that would actually happen — and the shapes
 * themselves are covered by the in-game pass.</p>
 */
final class SkyboxBlockShapeTest {

    @Test
    @DisplayName("collision shape is overridden, so the block cannot become walk-through")
    void collisionShapeIsOverridden() throws NoSuchMethodException {
        Method method = SkyboxBlock.class.getDeclaredMethod(
            "getCollisionShape", BlockState.class, BlockGetter.class, BlockPos.class, CollisionContext.class);
        assertNotNull(method);
        assertEquals(SkyboxBlock.class, method.getDeclaringClass(),
            "SkyboxBlock must declare getCollisionShape. BlockBehaviour's default derives it from "
                + "getShape, which is empty in survival — the block would be walk-through.");
    }

    @Test
    @DisplayName("occlusion shape is overridden, so sky windows keep culling what is behind them")
    void occlusionShapeIsOverridden() throws NoSuchMethodException {
        Method method = SkyboxBlock.class.getDeclaredMethod(
            "getOcclusionShape", BlockState.class, BlockGetter.class, BlockPos.class);
        assertNotNull(method);
        assertEquals(SkyboxBlock.class, method.getDeclaringClass(),
            "SkyboxBlock must declare getOcclusionShape. BlockBehaviour's default derives it from "
                + "getShape, which is empty in survival — neighbour faces and the sections behind "
                + "the hole would stop being culled.");
    }

    @Test
    @DisplayName("the survival-invisible outline shape is still overridden")
    void outlineShapeIsOverridden() throws NoSuchMethodException {
        Method method = SkyboxBlock.class.getDeclaredMethod(
            "getShape", BlockState.class, BlockGetter.class, BlockPos.class, CollisionContext.class);
        assertNotNull(method);
        assertEquals(SkyboxBlock.class, method.getDeclaringClass(),
            "SkyboxBlock must declare getShape — without it the block is targetable in survival "
                + "and the selection outline breaks the illusion.");
    }

    /** Sanity: the class under test really does extend the behaviour whose defaults we are guarding. */
    @Test
    @DisplayName("SkyboxBlock still inherits the BlockBehaviour defaults being guarded")
    void stillExtendsBlockBehaviour() {
        assertEquals(true, BlockBehaviour.class.isAssignableFrom(SkyboxBlock.class),
            "if SkyboxBlock no longer extends BlockBehaviour, this whole guard is meaningless");
    }
}
