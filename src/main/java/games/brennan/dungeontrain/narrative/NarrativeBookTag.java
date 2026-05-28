package games.brennan.dungeontrain.narrative;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Optional;

/**
 * Stamps and reads the {@code (story, letter)} identifier on narrative books
 * via {@link DataComponents#CUSTOM_DATA}. The identifier is what the
 * read-event handler uses to mark progression: when a player opens a book
 * (lectern right-click or held-item right-click), the handler calls
 * {@link #read(ItemStack)} on the involved stack.
 *
 * <p>Co-exists with other systems that put root-level keys in
 * {@code CUSTOM_DATA} (e.g.
 * {@code dt_bv_prefab_id} from
 * {@link games.brennan.dungeontrain.event.PrefabUseHandler}) by reserving its
 * own {@code dt_narrative_*} prefix.</p>
 */
public final class NarrativeBookTag {

    /** Story basename, e.g. {@code "augustus_park"}. */
    public static final String NBT_STORY = "dt_narrative_story";
    /** Letter index, 1-based. */
    public static final String NBT_LETTER = "dt_narrative_letter";
    /** Variant index, 0-based. Added with the per-variant tracking work; old books missing this tag decode as -1. */
    public static final String NBT_VARIANT = "dt_narrative_variant";

    /** Sentinel returned by {@link #read} when the book pre-dates variant stamping. */
    public static final int VARIANT_UNKNOWN = -1;

    private NarrativeBookTag() {}

    /** Stamp the identifier onto {@code stack} (creates / merges into CUSTOM_DATA). */
    public static void stamp(ItemStack stack, String storyBasename, int letterIndex, int variantIndex) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putString(NBT_STORY, storyBasename);
            tag.putInt(NBT_LETTER, letterIndex);
            tag.putInt(NBT_VARIANT, variantIndex);
        });
    }

    /**
     * Decode the identifier from {@code stack}'s CUSTOM_DATA. Empty when the
     * stack has no narrative tag (vanilla written book, foreign book, etc.).
     *
     * <p>Old books stamped before the variant-tracking field landed will
     * decode with {@code variantIndex == }{@link #VARIANT_UNKNOWN}; callers
     * should treat that as "don't mark a variant".</p>
     */
    public static Optional<NarrativeIdentity> read(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return Optional.empty();
        CompoundTag tag = cd.copyTag();
        if (!tag.contains(NBT_STORY, Tag.TAG_STRING) || !tag.contains(NBT_LETTER, Tag.TAG_INT)) {
            return Optional.empty();
        }
        int variantIndex = tag.contains(NBT_VARIANT, Tag.TAG_INT) ? tag.getInt(NBT_VARIANT) : VARIANT_UNKNOWN;
        return Optional.of(new NarrativeIdentity(tag.getString(NBT_STORY), tag.getInt(NBT_LETTER), variantIndex));
    }

    /** {@code (storyBasename, letterIndex, variantIndex)} triple as stamped on a book. */
    public record NarrativeIdentity(String storyBasename, int letterIndex, int variantIndex) {}
}
