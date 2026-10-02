package games.brennan.dungeontrain.compat.photo;

import games.brennan.dungeontrain.DungeonTrain;
import io.github.mortuusars.exposure.world.photograph.PhotographType;
import net.minecraft.resources.ResourceLocation;

/**
 * A found photo's paper wears with every pair of hands it passes through. Exposure draws a
 * photograph on the paper of its {@code photograph_type}; these are DT's worn papers, chosen by
 * how many views the photo has left. A photo with all its views has clean, unbroken edges.
 */
public final class WornPhotographs {

    public static final PhotographType LIGHT = type("worn_1");
    public static final PhotographType MEDIUM = type("worn_2");
    public static final PhotographType HEAVY = type("worn_3");

    private WornPhotographs() {}

    private static PhotographType type(String name) {
        return new PhotographType(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, name));
    }

    /** The paper for a photo with {@code viewsLeft} of {@link SharedPhotos#VIEWS_MAX} views remaining. */
    public static PhotographType forViewsLeft(int viewsLeft) {
        if (viewsLeft >= SharedPhotos.VIEWS_MAX) return PhotographType.REGULAR;
        int third = Math.max(1, SharedPhotos.VIEWS_MAX / 3);
        if (viewsLeft > 2 * third) return LIGHT;
        return viewsLeft > third ? MEDIUM : HEAVY;
    }
}
