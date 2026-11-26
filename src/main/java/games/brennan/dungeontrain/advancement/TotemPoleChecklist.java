package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceLocation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Totem Pole's checklist: one slot per Dungeon Totem its criteria name (criterion {@code x_totem} is the item
 * {@code dungeontotems:x_totem}). The totems that have saved the player come first, in the order they were
 * found; then one slot per totem still to use, which the screen hides as ???. Read on the client from the
 * advancement's own progress, so it needs no packet.
 */
public final class TotemPoleChecklist {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/totem_pole");
    public static final String TOTEMS_NAMESPACE = "dungeontotems";

    /** One slot: the totem item, and whether it has saved the player yet. */
    public record Slot(ResourceLocation item, boolean used) {}

    private TotemPoleChecklist() {}

    /**
     * Every slot, found ones first by when they were found (oldest first), the rest after in {@code criteria}
     * order. {@code obtained} gives a criterion's time, or null while it is still to earn.
     */
    public static List<Slot> slots(List<String> criteria, Function<String, Instant> obtained) {
        record Found(int index, String criterion, Instant at) {}
        List<Found> found = new ArrayList<>();
        List<Slot> missing = new ArrayList<>();
        for (int i = 0; i < criteria.size(); i++) {
            String criterion = criteria.get(i);
            Instant at = obtained.apply(criterion);
            if (at != null) found.add(new Found(i, criterion, at));
            else missing.add(new Slot(itemOf(criterion), false));
        }
        found.sort(Comparator.comparing(Found::at).thenComparingInt(Found::index));
        List<Slot> out = new ArrayList<>(criteria.size());
        for (Found f : found) out.add(new Slot(itemOf(f.criterion()), true));
        out.addAll(missing);
        return List.copyOf(out);
    }

    /** The totem item a criterion names. */
    public static ResourceLocation itemOf(String criterion) {
        return ResourceLocation.fromNamespaceAndPath(TOTEMS_NAMESPACE, criterion);
    }
}
