package games.brennan.dungeontrain.net;

import java.util.List;

/**
 * Shared definition of how a package's working folder is grouped into
 * UI sections. Used by both the server-side packet builder
 * ({@link PackageListRequestPacket}) and the client-side contents pane
 * so the two stay in lockstep — adding a new content kind here makes it
 * appear in both the wire payload and the menu.
 *
 * <p>The {@code subdir} string is the layout slug under a package's
 * working folder (e.g. {@code "templates"} → {@code dtpacks/<pkg>/templates/}).
 * The {@code label} is the human-friendly section header.
 * The {@code category} string maps to the slash-command argument set
 * understood by {@link games.brennan.dungeontrain.client.menu.plot.EditorPlotTeleport#commandFor}
 * so a click on a row in the contents pane can teleport the player into
 * the corresponding editor plot.</p>
 */
public final class PackageContents {

    /**
     * One row group in the contents pane.
     *
     * @param label        human-friendly section header
     * @param subdir       layout slug under a package's working folder
     * @param category     slash-command argument set for teleporting into the editor plot,
     *                     or {@code null} when the section has no editor plot
     * @param legacySubdir slug this section used in packages authored by an older build, or
     *                     {@code null} when the slug has never changed. Packages are shared
     *                     between players as zips, so one can arrive at any time carrying the
     *                     old layout — readers check this as a fallback. Writers only ever
     *                     emit {@link #subdir()}.
     */
    public record Section(String label, String subdir, String category, String legacySubdir) {

        /** A section whose slug has never changed. */
        public Section(String label, String subdir, String category) {
            this(label, subdir, category, null);
        }

        /** True when {@code candidate} names this section under either the current or old slug. */
        public boolean matches(String candidate) {
            return subdir.equals(candidate)
                || (legacySubdir != null && legacySubdir.equals(candidate));
        }
    }

    /**
     * Ordered list of sections in the contents pane. Matches the legacy
     * user-templates layout one-for-one so the pane feels familiar —
     * Carriages at the top, prefabs at the bottom.
     */
    public static final List<Section> SECTIONS = List.of(
        new Section("Carriages",             "templates",                    "CARRIAGES"),
        new Section("Contents",              "contents",                     "CONTENTS"),
        new Section("Parts",                 "parts",                        "PARTS"),
        new Section("Containers",            "containers",                   null),
        new Section("Tracks",                "tracks",                       "TRACKS"),
        new Section("Pillars",               "pillars",                      "TRACKS"),
        new Section("Tunnels",               "tunnels",                      "TRACKS"),
        new Section("Dimensional Carriages", "portals/dimensional_carriage", "PORTALS", "portals/room"),
        new Section("Loot Prefabs",          "prefabs/loot",                 null),
        new Section("Block-Variant Prefabs", "prefabs/block_variants",       null)
    );

    private PackageContents() {}
}
