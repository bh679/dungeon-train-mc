package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The fixed order of the advancement tabs: {@code advancement_tabs.json} → {@code order} (Dungeon Train, Train
 * Explorer, Challenges, Others, The Enchiridion, The Darkroom, The Secret Menu, Editor), then any other Dungeon
 * Train tab, then vanilla's and other mods' tabs in the order they arrived. Applied to the advancement tree's
 * roots as they are added ({@code AdvancementTreeOrderMixin}), which is the order both advancements screens
 * build their tab strip in.
 */
public final class TabOrder {

    private TabOrder() {}

    /** A tab root's place: its index in {@code order}, or past the listed ones (ours first, then everyone else). */
    static int rank(List<String> order, ResourceLocation root) {
        int i = order.indexOf(root.toString());
        if (i >= 0) return i;
        return DungeonTrain.MOD_ID.equals(root.getNamespace()) ? order.size() : order.size() + 1;
    }

    /** {@code roots} in the fixed order; ties keep their arrival order (the sort is stable). */
    public static <T> List<T> sorted(List<String> order, List<T> roots, java.util.function.Function<T, ResourceLocation> idOf) {
        List<T> out = new ArrayList<>(roots);
        out.sort(Comparator.comparingInt(r -> rank(order, idOf.apply(r))));
        return out;
    }
}
