package games.brennan.dungeontrain.compat.vista;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Recognises a Vista Television by block id, without touching a Vista class, so code outside the
 * Vista mixin set can ask safely.
 *
 * <p>A TV's block entity is Moonlight's {@code ItemDisplayTile}, a {@code RandomizableContainerBlockEntity}
 * with one slot. That slot is the TV's tuner (it holds the Live Feed Cassette), never storage: DT's loot
 * roller and break spill both skip a TV on this test.</p>
 */
public final class TvBlocks {

    static final ResourceLocation TV_ID = ResourceLocation.fromNamespaceAndPath("vista", "television");

    private TvBlocks() {}

    /** Id-level rule, split out so it is testable without a bootstrapped registry. */
    static boolean isTelevision(ResourceLocation id) {
        return TV_ID.equals(id);
    }

    public static boolean isTelevision(BlockState state) {
        return state != null && isTelevision(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }
}
