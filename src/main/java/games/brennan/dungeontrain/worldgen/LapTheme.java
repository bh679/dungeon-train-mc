package games.brennan.dungeontrain.worldgen;

import java.util.Locale;

/**
 * The look a whole <b>theme lap</b> wears — one group of themed slots in {@link CycleLayout} (the
 * overworld stretch, Nether band, overworld stretch and End band that make up Lap 1 or Lap 2 of a
 * cycle). Every slot of the group takes its style from the one theme, so a lap never mixes looks:
 *
 * <table>
 *   <tr><th>Theme</th><th>Overworld</th><th>Nether</th><th>End</th></tr>
 *   <tr><td>{@link #VANILLA}</td><td>vanilla</td><td>vanilla</td><td>vanilla</td></tr>
 *   <tr><td>{@link #BOP}</td><td>Biomes O' Plenty</td><td>vanilla + BoP Nether</td><td>vanilla + BoP End</td></tr>
 *   <tr><td>{@link #BETTER}</td><td>WWOO</td><td>BetterNether</td><td>BetterEnd</td></tr>
 * </table>
 *
 * <p>Which theme a lap gets is decided once per world ({@link LapThemePicker}) and saved. Pure — no
 * Minecraft types — so the mapping is unit-tested directly.</p>
 */
public enum LapTheme {
    VANILLA,
    BOP,
    BETTER;

    /** The style a slot of type {@code type} wears under this theme (bands without a themed look stay vanilla). */
    public CycleLayout.Style styleFor(CycleLayout.Type type) {
        if (this == VANILLA) return CycleLayout.Style.VANILLA;
        return switch (type) {
            case OVERWORLD -> this == BOP ? CycleLayout.Style.BOP : CycleLayout.Style.WWOO;
            case NETHER, END -> this == BOP ? CycleLayout.Style.BOP : CycleLayout.Style.BETTER;
            default -> CycleLayout.Style.VANILLA;
        };
    }

    /** Lower-case id used in saved data, the progress file and debug output. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The theme for {@code id} ({@link #id}), or {@code null} if unknown. */
    public static LapTheme byId(String id) {
        if (id == null) return null;
        for (LapTheme t : values()) {
            if (t.id().equals(id.trim().toLowerCase(Locale.ROOT))) return t;
        }
        return null;
    }
}
