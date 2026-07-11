package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;

/**
 * Stamps and reads the {@code (seriesId, letterIndex)} identifier on a served player-written narrative
 * book via {@link DataComponents#CUSTOM_DATA}. The lectern read-credit handler uses {@link #read} to
 * advance the world's player-series progress ({@code NarrativeProgressData.markPlayerLetterRead}).
 *
 * <p>The twin of {@link NarrativeBookTag} for the DISCOVERY half, on its own reserved
 * {@code dt_pnarr_*} keys so exactly one of the two tags is ever present on a book — a mod-story book
 * carries {@code dt_narrative_*} and routes into canon progress/advancements; a player-narrative book
 * carries {@code dt_pnarr_*} and routes only into the world-local player-series store, never touching
 * {@code GlobalNarrativeProgress} or advancements. There is no variant field (player letters have a
 * single page list).</p>
 */
public final class PlayerNarrativeBookTag {

    /** Opaque per-life series id (a dash-stripped uuid). */
    public static final String NBT_SERIES = "dt_pnarr_series";
    /** Letter index within the series, 1-based. */
    public static final String NBT_LETTER = "dt_pnarr_letter";

    private PlayerNarrativeBookTag() {}

    /** Stamp the identifier onto {@code stack} (creates / merges into CUSTOM_DATA). */
    public static void stamp(ItemStack stack, String seriesId, int letterIndex) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putString(NBT_SERIES, seriesId);
            tag.putInt(NBT_LETTER, letterIndex);
        });
    }

    /**
     * Decode the identifier from {@code stack}'s CUSTOM_DATA. Empty when the stack has no player-narrative
     * tag (vanilla book, mod-story book, foreign book, etc.).
     */
    public static Optional<PlayerNarrativeIdentity> read(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return Optional.empty();
        CompoundTag tag = cd.copyTag();
        if (!tag.contains(NBT_SERIES, Tag.TAG_STRING) || !tag.contains(NBT_LETTER, Tag.TAG_INT)) {
            return Optional.empty();
        }
        String seriesId = tag.getString(NBT_SERIES);
        if (seriesId.isEmpty()) return Optional.empty();
        return Optional.of(new PlayerNarrativeIdentity(seriesId, tag.getInt(NBT_LETTER)));
    }

    /** {@code (seriesId, letterIndex)} pair as stamped on a served player-narrative book. */
    public record PlayerNarrativeIdentity(String seriesId, int letterIndex) {}
}
