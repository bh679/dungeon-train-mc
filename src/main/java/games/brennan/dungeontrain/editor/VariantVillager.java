package games.brennan.dungeontrain.editor;

import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Per-entry <b>villager job mode</b> for a villager mob row, shown in the Z menu as a J / R / N pill:
 *
 * <ul>
 *   <li>{@link Mode#JOB} (default, never written): spawns jobless and claims the nearest job block,
 *       as every train villager always has ({@code VillagerTrainSpawnEvents}).</li>
 *   <li>{@link Mode#RANDOM}: spawns with a random job profession ({@code VillagerJobSiteAssigner#JOB_PROFESSIONS}).</li>
 *   <li>{@link Mode#NITWIT}: spawns as a nitwit, with no trades.</li>
 * </ul>
 *
 * <p>Sidecar: {@code "villager": "random"} / {@code "nitwit"}; NBT / wire: the mode ordinal.
 * Applied in {@code CarriageContentsPlacer#spawnVariantMobEntity}.</p>
 */
public final class VariantVillager {

    public static final String ID_VILLAGER = "minecraft:villager";

    public enum Mode {
        JOB, RANDOM, NITWIT;

        /** Lower-case JSON token ({@code "random"} / {@code "nitwit"}; Job is never written). */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean isDefault() {
            return this == JOB;
        }

        /** The next mode in the pill's click cycle: J → R → N → J. */
        public Mode next() {
            Mode[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        /** The mode for a JSON token, or {@code null} when unrecognised. */
        @Nullable
        public static Mode parse(@Nullable String token) {
            if (token == null) return null;
            String t = token.trim().toLowerCase(Locale.ROOT);
            for (Mode m : values()) {
                if (m.id().equals(t)) return m;
            }
            return null;
        }

        /** The mode for a wire / NBT ordinal, falling back to {@link #JOB} when out of range. */
        public static Mode fromOrdinal(int ordinal) {
            Mode[] all = values();
            return ordinal >= 0 && ordinal < all.length ? all[ordinal] : JOB;
        }
    }

    private VariantVillager() {}

    /** True for a mob id whose row shows the J / R / N option. */
    public static boolean isVillager(@Nullable ResourceLocation entityId) {
        return entityId != null && isVillager(entityId.toString());
    }

    public static boolean isVillager(@Nullable String entityId) {
        return ID_VILLAGER.equals(entityId);
    }
}
