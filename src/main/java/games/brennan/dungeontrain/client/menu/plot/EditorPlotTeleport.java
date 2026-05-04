package games.brennan.dungeontrain.client.menu.plot;

/**
 * Shared client-side dispatcher: maps a panel entry's {@code (category,
 * modelId, modelName)} tuple to the slash command that teleports the player
 * into that template's plot, or that bumps its weight.
 *
 * <p>Single source of truth for both
 * {@link EditorPlotPanelInputHandler} (per-plot panel — name row click,
 * weight arrows) and {@link EditorTypeMenuInputHandler} (template-type menu
 * — variant row name + weight cells). Keeping the routing in one place means
 * fixing a command shape (e.g. the {@code pillar_*} → bare-name strip)
 * fixes both call sites at once.</p>
 */
public final class EditorPlotTeleport {

    private EditorPlotTeleport() {}

    /**
     * Build the slash command (no leading slash, suitable for
     * {@link games.brennan.dungeontrain.client.menu.CommandRunner#run})
     * that teleports the player into the plot identified by
     * {@code (category, modelId, modelName)}, or {@code null} if no
     * teleport target exists for the entry.
     */
    public static String commandFor(String category, String modelId, String modelName) {
        return switch (category) {
            case "CARRIAGES" -> "dungeontrain editor enter " + modelId;
            case "CONTENTS" -> "dungeontrain editor contents enter " + modelId;
            case "TRACKS" -> trackTeleportCommand(modelId);
            // Parts: modelId is the kind tag ("floor"/"walls"/"roof"/"doors"),
            // modelName is the variant name. Server command is
            // {@code /dt editor part enter <kind> <name>}.
            case "PARTS" -> "dungeontrain editor part enter " + modelId + " " + modelName;
            default -> null;
        };
    }

    /**
     * Build the slash command that bumps the weight of the template
     * identified by {@code (category, modelId, modelName)} in {@code dir}
     * ({@code "inc"} or {@code "dec"}). Returns {@code null} for categories
     * without a weight pool (currently {@code PARTS}).
     */
    public static String weightCommandFor(String category, String modelId, String modelName, String dir) {
        return switch (category) {
            case "CARRIAGES" -> "dungeontrain editor weight " + modelId + " " + dir;
            case "CONTENTS" -> "dungeontrain editor contents weight " + modelId + " " + dir;
            case "TRACKS" -> "dungeontrain editor tracks weight " + modelId + " " + modelName + " " + dir;
            default -> null;
        };
    }

    private static String trackTeleportCommand(String modelId) {
        // The track tile's per-plot label uses {@code TrackKind.TILE.id() == "tile"};
        // {@code Template.TrackModel.id()} (used by the keyboard menu) returns "track".
        // Accept both so dispatch works regardless of which side built the entry.
        if ("track".equals(modelId) || "tile".equals(modelId)) return "dungeontrain editor track enter";
        // The {@code dungeontrain editor pillar enter <target>} parser uses
        // {@code PillarSection.valueOf(...) / PillarAdjunct.valueOf(...)} which
        // accepts only the bare enum name ({@code top, middle, bottom, stairs}).
        // {@link Template.PillarModel#id()} returns the {@code pillar_<section>}
        // form for HUD dedup, so strip the prefix here.
        if (modelId.startsWith("pillar_")) {
            return "dungeontrain editor pillar enter " + modelId.substring("pillar_".length());
        }
        if (modelId.startsWith("adjunct_")) {
            return "dungeontrain editor pillar enter " + modelId.substring("adjunct_".length());
        }
        // Tunnels: the top-level {@code editor enter <target>} command's
        // {@code isTunnelInput} branch accepts the {@code tunnel_} prefix
        // directly, so the modelId can pass through unchanged.
        if (modelId.startsWith("tunnel_")) {
            return "dungeontrain editor enter " + modelId;
        }
        return null;
    }
}
