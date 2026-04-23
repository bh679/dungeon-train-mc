package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.tunnel.TunnelTemplate.TunnelVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Top-level grouping for the editor. Each category exposes an ordered list of
 * {@link EditorModel}s so commands like {@code /dt editor <category>} and
 * {@code /dt save all} can iterate without caring about the underlying
 * storage split between carriages, pillars, and tunnels.
 *
 * <ul>
 *   <li>{@link #CARRIAGES} — every registered {@link CarriageVariant}.</li>
 *   <li>{@link #TRACKS} — pillars ({@code bottom → middle → top}), then
 *       tunnels ({@code section → portal}).</li>
 *   <li>{@link #ARCHITECTURE} — placeholder, no models yet (walls, floor,
 *       roof coming later).</li>
 * </ul>
 */
public enum EditorCategory {
    CARRIAGES("Carriages"),
    TRACKS("Tracks"),
    ARCHITECTURE("Architecture");

    private final String displayName;

    EditorCategory(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Stable lower-case token used in commands ({@code /dt editor tracks}). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Every model in this category, in the order a player walks through them. */
    public List<EditorModel> models() {
        return switch (this) {
            case CARRIAGES -> carriageModels();
            case TRACKS -> trackModels();
            case ARCHITECTURE -> List.of();
        };
    }

    /** The landing model when a player runs {@code /dt editor <category>}. */
    public Optional<EditorModel> firstModel() {
        List<EditorModel> models = models();
        return models.isEmpty() ? Optional.empty() : Optional.of(models.get(0));
    }

    /** Parse a command argument back to a category. Case-insensitive. */
    public static Optional<EditorCategory> fromId(String raw) {
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(EditorCategory.valueOf(raw.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Resolve which category + model the player's block position falls inside.
     * Checks carriage plots first, then pillars, then tunnels.
     */
    public static Optional<Located> locate(ServerPlayer player, CarriageDims dims) {
        BlockPos pos = player.blockPosition();
        CarriageVariant carriage = CarriageEditor.plotContaining(pos, dims);
        if (carriage != null) {
            return Optional.of(new Located(CARRIAGES, new EditorModel.CarriageModel(carriage)));
        }
        PillarSection pillar = PillarEditor.plotContaining(pos, dims);
        if (pillar != null) {
            return Optional.of(new Located(TRACKS, new EditorModel.PillarModel(pillar)));
        }
        TunnelVariant tunnel = TunnelEditor.plotContaining(pos);
        if (tunnel != null) {
            return Optional.of(new Located(TRACKS, new EditorModel.TunnelModel(tunnel)));
        }
        return Optional.empty();
    }

    private static List<EditorModel> carriageModels() {
        List<CarriageVariant> variants = CarriageVariantRegistry.allVariants();
        List<EditorModel> out = new ArrayList<>(variants.size());
        for (CarriageVariant v : variants) {
            out.add(new EditorModel.CarriageModel(v));
        }
        return out;
    }

    private static List<EditorModel> trackModels() {
        List<EditorModel> out = new ArrayList<>(PillarSection.values().length + TunnelVariant.values().length);
        // Ground-up pillar ordering mirrors physical stacking.
        out.add(new EditorModel.PillarModel(PillarSection.BOTTOM));
        out.add(new EditorModel.PillarModel(PillarSection.MIDDLE));
        out.add(new EditorModel.PillarModel(PillarSection.TOP));
        for (TunnelVariant v : TunnelVariant.values()) {
            out.add(new EditorModel.TunnelModel(v));
        }
        return out;
    }

    /** A category + which specific model the player is standing in. */
    public record Located(EditorCategory category, EditorModel model) {}

    /**
     * Erase every known editor plot in every category — footprints + barrier
     * cages all go back to air. Called when the player exits the editor and
     * when switching categories so stale models don't pile up at Y=250. Cheap
     * — the total is a handful of plots ({@code CarriageVariantRegistry} size
     * + 3 pillars + 2 tunnels).
     */
    public static void clearAllPlots(ServerLevel overworld, CarriageDims dims) {
        for (CarriageVariant v : CarriageVariantRegistry.allVariants()) {
            CarriageEditor.clearPlot(overworld, v, dims);
        }
        for (PillarSection s : PillarSection.values()) {
            PillarEditor.clearPlot(overworld, s, dims);
        }
        for (TunnelVariant t : TunnelVariant.values()) {
            TunnelEditor.clearPlot(overworld, t);
        }
    }
}
