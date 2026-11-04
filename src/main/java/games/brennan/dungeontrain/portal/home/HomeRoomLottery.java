package games.brennan.dungeontrain.portal.home;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Random;

/**
 * Whose room a pair that rolled {@value HomeRooms#TEMPLATE} stands: a seeded draw among the rooms this
 * server has saved, so the same pair in the same world answers the same owner every time it is
 * stamped, and a relocation never swaps the room out from under somebody.
 *
 * <p>Pure: the candidates go in sorted by the caller, the pick comes out.</p>
 */
public final class HomeRoomLottery {

    private HomeRoomLottery() {}

    /** The owner (dashless uuid) for {@code pairKey}, or null when there is nobody to draw. */
    @Nullable
    public static String pick(long worldSeed, int pairKey, List<String> owners) {
        if (owners.isEmpty()) return null;
        long seed = worldSeed ^ (pairKey * 0x9E3779B97F4A7C15L) ^ 0x484F4D45L;
        return owners.get(new Random(seed).nextInt(owners.size()));
    }
}
