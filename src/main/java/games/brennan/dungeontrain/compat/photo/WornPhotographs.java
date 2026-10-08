package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.DungeonTrain;
import io.github.mortuusars.exposure.world.photograph.PhotographType;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * A found photo's paper wears with every pair of hands it passes through. Exposure draws a
 * photograph on the paper of its type; these are DT's worn papers, chosen by how many views the
 * photo has left. A photo with all its views has clean, unbroken edges.
 *
 * <p>Medium and heavy wear come in several papers so photos do not all wear alike. Which one a
 * photo gets is fixed by its id, and each heavy paper grows out of the medium paper the same photo
 * would have had, so a photo only ever gains wear.</p>
 */
public final class WornPhotographs {

    public static final PhotographType LIGHT = type("worn_1");
    public static final List<PhotographType> MEDIUM = List.of(type("worn_2a"), type("worn_2b"));
    public static final List<PhotographType> HEAVY = List.of(type("worn_3a"), type("worn_3b"), type("worn_3c"));

    private WornPhotographs() {}

    private static PhotographType type(String name) {
        return new PhotographType(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, name));
    }

    /** The paper for photo {@code photoId} with {@code viewsLeft} of {@code maxViews} views remaining. */
    public static PhotographType forViewsLeft(int viewsLeft, int maxViews, int photoId) {
        if (viewsLeft >= maxViews) return PhotographType.REGULAR;
        int third = Math.max(1, maxViews / 3);
        if (viewsLeft > 2 * third) return LIGHT;
        int heavy = Math.floorMod(photoId, HEAVY.size());
        // Heavy paper h is drawn on top of medium paper h % 2 (see the paper generator).
        return viewsLeft > third ? MEDIUM.get(heavy % MEDIUM.size()) : HEAVY.get(heavy);
    }
}
