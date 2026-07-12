package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.PackageListClient;
import games.brennan.dungeontrain.client.menu.plot.EditorPlotTeleport;
import games.brennan.dungeontrain.net.PackageContents;
import games.brennan.dungeontrain.net.PackageListSyncPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Side panel for {@link PackageListScreen}. Renders the active package's
 * content listing, grouped by kind.
 *
 * <p>Each leaf is a {@link CommandMenuEntry.Run} whose command teleports
 * the player into the corresponding editor plot. The command is built
 * via {@link EditorPlotTeleport#commandFor(String, String, String)} —
 * the same dispatcher used by the in-world plot panels and the variant
 * menus. Kinds that lack a teleport target (e.g. block-variant prefabs)
 * render as informational labels.</p>
 *
 * <p>Renders from {@link PackageListClient} only — no direct filesystem
 * access. That keeps dedicated-server installs working (the client
 * shows whatever the server's last sync said) and lets the listing
 * stay consistent with the package list above it.</p>
 */
public final class PackageContentsScreen implements MenuScreen {

    private static final int MAX_ROWS = 60;

    @Override public String title() {
        return PackageListClient.activeEntry()
            .map(e -> e.name() + " contents")
            .orElse("Contents");
    }

    @Override public List<CommandMenuEntry> entries() {
        PackageListSyncPacket.Entry active = PackageListClient.activeEntry().orElse(null);
        if (active == null) {
            return List.of(new CommandMenuEntry.Label("No active package."));
        }

        List<CommandMenuEntry> out = new ArrayList<>();
        Map<String, List<String>> contents = active.contentsBySubdir();
        int rowsEmitted = 0;
        boolean anyContent = false;

        for (PackageContents.Section section : PackageContents.SECTIONS) {
            List<String> names = contents.getOrDefault(section.subdir(), List.of());
            if (names.isEmpty()) continue;
            anyContent = true;

            out.add(new CommandMenuEntry.Label(section.label() + " (" + names.size() + ")"));
            for (String name : names) {
                if (rowsEmitted >= MAX_ROWS) {
                    out.add(new CommandMenuEntry.Label("... (more truncated)"));
                    return out;
                }
                out.add(rowFor(section, name));
                rowsEmitted++;
            }
        }

        if (!anyContent) {
            out.add(new CommandMenuEntry.Label("(empty)"));
            out.add(new CommandMenuEntry.Label("Save in the editor to populate."));
        }
        return out;
    }

    /** Build a single clickable row. Falls back to an informational label
     *  for kinds without a teleport mapping. */
    private static CommandMenuEntry rowFor(PackageContents.Section section, String name) {
        if (section.category() == null) {
            // Containers, loot prefabs, block-variant prefabs — these aren't
            // edited via a teleportable plot. Show as plain labels.
            return new CommandMenuEntry.Label("  " + name);
        }

        TeleportTarget target = teleportTargetFor(section, name);
        if (target == null) {
            return new CommandMenuEntry.Label("  " + name);
        }
        String cmd = EditorPlotTeleport.commandFor(target.category, target.modelId, target.modelName);
        if (cmd == null) {
            return new CommandMenuEntry.Label("  " + name);
        }
        return new CommandMenuEntry.Run("  " + name, cmd);
    }

    private record TeleportTarget(String category, String modelId, String modelName) {}

    /**
     * Map a {@code (section, basename)} pair to the
     * {@code (category, modelId, modelName)} tuple
     * {@link EditorPlotTeleport#commandFor} understands.
     *
     * <p>Most kinds: {@code modelId == modelName == basename}.
     * For nested kinds (parts/pillars/tunnels) the name is encoded as
     * {@code <kind>:<basename>} (the {@link PackageListRequestPacket}
     * builder joined them with a colon when enumerating). Split it back
     * apart so the teleport dispatcher gets the kind + name it expects.</p>
     */
    private static TeleportTarget teleportTargetFor(PackageContents.Section section, String name) {
        String category = section.category();
        if (category == null) return null;

        // Pillars and tunnels: the on-disk layout is pillars/<section>/<name>.nbt
        // and tunnels/<variant>/<name>.nbt. The packet builder produced
        // "<section>:<name>" entries. Teleport for pillars expects
        // modelId="pillar_<section>" (matching EditorPlotTeleport's stripping
        // logic). Tunnels expect modelId="tunnel_<variant>".
        if ("pillars".equals(section.subdir())) {
            int sep = name.indexOf(':');
            if (sep <= 0) return null;
            String pillarSection = name.substring(0, sep);
            return new TeleportTarget("TRACKS", "pillar_" + pillarSection, name);
        }
        if ("tunnels".equals(section.subdir())) {
            int sep = name.indexOf(':');
            if (sep <= 0) return null;
            String tunnelVariant = name.substring(0, sep);
            return new TeleportTarget("TRACKS", "tunnel_" + tunnelVariant, name);
        }
        if ("parts".equals(section.subdir())) {
            int sep = name.indexOf(':');
            if (sep <= 0) return null;
            String partKind = name.substring(0, sep);
            String partName = name.substring(sep + 1);
            return new TeleportTarget("PARTS", partKind, partName);
        }
        if ("tracks".equals(section.subdir())) {
            // Track variants: basename is the variant name; modelId can be the
            // bare track kind ("track") which EditorPlotTeleport.trackTeleportCommand
            // accepts as "/dt editor track enter". Without per-track-kind
            // differentiation we fall through to the generic teleport.
            return new TeleportTarget("TRACKS", "track", name);
        }
        // Carriages, Contents — modelId == basename.
        return new TeleportTarget(category, name, name);
    }
}
