package games.brennan.dungeontrain.event;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Optional;

/**
 * Which vanilla dimension change a portal trip along the ride stands for, so the band counts as the
 * real Nether for advancements. Vanilla awards <i>We Need to Go Deeper</i> and the Nether tab's root
 * from a single {@code minecraft:changed_dimension} criterion ({@code to: the_nether}); DT's portal trip
 * is a same-level teleport, so {@link NetherPortalBandJump} fires that criterion itself with the pair
 * this class names. Nether → Nether and overworld → overworld trips are no crossing at all.
 */
public final class NetherPortalAdvancements {

    /** A trip that counts as a vanilla dimension change. */
    public enum Crossing {
        INTO_NETHER(Level.OVERWORLD, Level.NETHER),
        OUT_OF_NETHER(Level.NETHER, Level.OVERWORLD);

        private final ResourceKey<Level> from;
        private final ResourceKey<Level> to;

        Crossing(ResourceKey<Level> from, ResourceKey<Level> to) {
            this.from = from;
            this.to = to;
        }

        public ResourceKey<Level> from() {
            return from;
        }

        public ResourceKey<Level> to() {
            return to;
        }
    }

    private NetherPortalAdvancements() {}

    /** The crossing a trip from a Nether (or not) column to a Nether (or not) column stands for, if any. */
    public static Optional<Crossing> crossing(boolean fromNether, boolean toNether) {
        if (fromNether == toNether) return Optional.empty();
        return Optional.of(toNether ? Crossing.INTO_NETHER : Crossing.OUT_OF_NETHER);
    }
}
