package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/**
 * Hidden collection advancements whose tooltip must not print its {@code x/y} criteria count — that
 * would tell the player how many are needed. The tooltip's title bar still fills with progress (both
 * screens draw it from {@code AdvancementProgress.getPercent}), so how far along shows without the
 * target. Shared by the vanilla and Better Advancements widget mixins.
 */
public final class HiddenAdvancementCount {

    private static final Set<ResourceLocation> COUNT_HIDDEN = Set.of(
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/faulthursts_favourite"));

    private HiddenAdvancementCount() {}

    /** True for an advancement whose {@code x/y} count must not be drawn. */
    public static boolean hidesCount(ResourceLocation id) {
        return id != null && COUNT_HIDDEN.contains(id);
    }
}
