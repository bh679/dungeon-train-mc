package games.brennan.dungeontrain.perks;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.UUID;

/**
 * Where a player's constellation window stands ({@link ConstellationWindowPlacement}): {@code subLevel}
 * empty for the main world, else the carriage whose plot holds {@code pos}. {@code spent} once a perk
 * point has gone in since it was placed; {@code replaced} the small block it stands in for.
 */
public record ConstellationWindowSpot(boolean placed, Optional<UUID> subLevel, long pos, boolean spent,
                                      Optional<BlockState> replaced) {

    public static final ConstellationWindowSpot NONE =
        new ConstellationWindowSpot(false, Optional.empty(), 0L, false, Optional.empty());

    public static final Codec<ConstellationWindowSpot> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.BOOL.fieldOf("placed").forGetter(ConstellationWindowSpot::placed),
        UUIDUtil.CODEC.optionalFieldOf("sub_level").forGetter(ConstellationWindowSpot::subLevel),
        Codec.LONG.fieldOf("pos").forGetter(ConstellationWindowSpot::pos),
        Codec.BOOL.fieldOf("spent").forGetter(ConstellationWindowSpot::spent),
        BlockState.CODEC.optionalFieldOf("replaced").forGetter(ConstellationWindowSpot::replaced)
    ).apply(i, ConstellationWindowSpot::new));

    public static ConstellationWindowSpot at(UUID subLevel, long pos) {
        return at(subLevel, pos, null);
    }

    /** {@code replaced}: the small block the window took the place of, put back when it goes; null for none. */
    public static ConstellationWindowSpot at(UUID subLevel, long pos, BlockState replaced) {
        return new ConstellationWindowSpot(true, Optional.ofNullable(subLevel), pos, false, Optional.ofNullable(replaced));
    }

    public ConstellationWindowSpot withSpent() {
        return new ConstellationWindowSpot(placed, subLevel, pos, true, replaced);
    }

    /** Gone once a point has gone in and the player's body has left the cell. */
    public static boolean shouldRemove(ConstellationWindowSpot spot, boolean playerInCell) {
        return spot.placed() && spot.spent() && !playerInCell;
    }
}
