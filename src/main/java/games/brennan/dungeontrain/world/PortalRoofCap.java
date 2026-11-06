package games.brennan.dungeontrain.world;

import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * The highest Y an exit portal may stand at during one portal trip ({@code event/NetherPortalBandJump}). A Nether
 * core has its own bedrock roof, but vanilla's {@code PortalForcer} only knows the overworld's height: it reuses or
 * builds a frame wherever there is room, the open roof above the core included.
 * {@code mixin/PortalForcerRoofCapMixin} reads this cap. Portal trips run on the server thread, so a plain field does.
 */
public final class PortalRoofCap {

    private static final int NONE = Integer.MAX_VALUE;

    private static int topY = NONE;

    private PortalRoofCap() {}

    /** The cap in force, or {@link Integer#MAX_VALUE} outside a capped trip. */
    public static int topY() {
        return topY;
    }

    /** Runs {@code trip} with exit portals held to {@code roofTop}, when there is one. */
    public static <T> T under(OptionalInt roofTop, Supplier<T> trip) {
        int previous = topY;
        topY = roofTop.orElse(NONE);
        try {
            return trip.get();
        } finally {
            topY = previous;
        }
    }
}
