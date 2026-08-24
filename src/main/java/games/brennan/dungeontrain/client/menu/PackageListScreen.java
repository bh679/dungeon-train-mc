package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.PackageListClient;
import games.brennan.dungeontrain.editor.PackageInfo;
import games.brennan.dungeontrain.net.PackageListSyncPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Drilldown from {@link EditorMenuScreen} via the "Package" entry.
 *
 * <p>Top row: {@code Reload | Open Packages}.
 *
 * <p>Per-package {@link CommandMenuEntry.Quad} row:
 * {@code Name | Save | Open | Enable}.
 * <ul>
 *   <li><b>Name</b> — clicking activates the package (server-side mutation
 *       through {@code /dungeontrain package activate <name>}).</li>
 *   <li><b>Save</b> — shown on the <i>active</i> row only, because the
 *       save always applies to the active package. Typing field pre-filled
 *       with that package's current name (empty for the unsaved
 *       pseudo-package). On submit, dispatches
 *       {@code /dungeontrain package save <name>}. Typing a name other
 *       than the active package's saves a <i>copy</i> under that name and
 *       leaves the original package on disk untouched. Inactive rows
 *       render a "—" label.</li>
 *   <li><b>Open</b> — opens the package's working folder in the OS file
 *       manager. Client-side only.</li>
 *   <li><b>Enable</b> — toggles via {@code /dungeontrain package enable}
 *       or {@code disable}. Label flips between "Enable" / "Disable"
 *       depending on current state. Hidden (rendered as a "—" label) for
 *       the unsaved pseudo-package, which is always enabled.</li>
 * </ul>
 *
 * <p>Side panel: {@link PackageContentsScreen} — shows the active
 * package's contents (Carriages, Contents, Parts, ...) with clickable
 * rows that teleport into the corresponding editor plot.</p>
 *
 * <p>Refreshes the {@link PackageListClient} cache on every rebuild
 * (throttled to ~250ms) so server-side mutations propagate to the
 * client menu within one or two ticks.</p>
 */
public final class PackageListScreen implements MenuScreen {

    /**
     * Wider than the default. Each row is a Quad
     * (Name | Save | Open | Enable) and package names can be 32 chars —
     * the standard 1.6-block width leaves the name cell uncomfortably
     * tight. 3.6 gives the name cell ~1.98 blocks of horizontal space —
     * enough to render a full 32-char id at the default text scale
     * without truncation.
     */
    @Override public double panelWidth() { return 3.6; }

    @Override public String title() { return "Packages"; }

    @Override public List<CommandMenuEntry> entries() {
        // Cache may be stale or absent. Request a fresh snapshot but don't
        // block — the menu rebuilds every tick, so the response will land
        // before the next render frame in practice.
        PackageListClient.requestRefreshThrottled();

        List<CommandMenuEntry> out = new ArrayList<>();

        out.add(new CommandMenuEntry.Split(
            new CommandMenuEntry.Stay("Reload", "dungeontrain editor import"),
            new CommandMenuEntry.ClientAction("Open Packages", PackageMenuActions::openDtpacksFolder),
            0.55
        ));

        List<PackageListSyncPacket.Entry> packages = PackageListClient.entries();
        if (packages.isEmpty()) {
            out.add(new CommandMenuEntry.Label("Loading..."));
        } else {
            for (PackageListSyncPacket.Entry entry : packages) {
                out.add(buildRow(entry));
            }
        }

        out.add(new CommandMenuEntry.Back("< Back"));
        return out;
    }

    @Override public MenuScreen sidePanel() {
        return new PackageContentsScreen();
    }

    private CommandMenuEntry buildRow(PackageListSyncPacket.Entry entry) {
        boolean isUnsaved = PackageInfo.UNSAVED_NAME.equals(entry.name());
        boolean isActive = entry.isActive();
        boolean enabled = entry.enabled();

        String activateLabel = (isActive ? "● " : "  ") + displayName(entry.name());
        // Stay (not Run) so clicking a package name flips the active marker
        // without dismissing the menu — the player typically wants to keep
        // browsing the contents pane or click a different package next.
        CommandMenuEntry activate = new CommandMenuEntry.Stay(
            activateLabel,
            buildActivateCommand(entry.name()),
            isActive
        );

        // Save acts on the ACTIVE package, whatever row it's clicked from, so it
        // only appears on the active row. Drawn on every row it read as "save
        // this package" and instead saved a different one — which is how players
        // ended up overwriting the wrong pack.
        CommandMenuEntry save;
        if (isActive) {
            String saveInitial = isUnsaved ? "" : entry.name();
            save = new CommandMenuEntry.TypeArg(
                "Save",
                "name",
                "dungeontrain package save",
                "",
                saveInitial
            );
        } else {
            save = new CommandMenuEntry.Label("\u2014");
        }

        final String packageName = entry.name();
        CommandMenuEntry open = new CommandMenuEntry.ClientAction(
            "Open",
            () -> PackageMenuActions.openWorkingFolder(packageName)
        );

        CommandMenuEntry enableCell;
        if (isUnsaved) {
            // Unsaved is the working folder for in-progress content — there's
            // no enable/disable concept for it. Render an inert placeholder
            // so the row stays visually aligned.
            enableCell = new CommandMenuEntry.Label("—");
        } else {
            String enableLabel = enabled ? "Disable" : "Enable";
            String enableCmd = "dungeontrain package "
                + (enabled ? "disable " : "enable ")
                + packageName;
            // Stay so the player can toggle multiple packages in a row
            // without re-opening the menu between clicks.
            enableCell = new CommandMenuEntry.Stay(enableLabel, enableCmd);
        }

        // Boundaries: name(0..0.55) | save(0.55..0.72) | open(0.72..0.86) | enable(0.86..1.0).
        // Name gets the widest cell because it carries variable-length text;
        // the action cells are short fixed labels.
        return new CommandMenuEntry.Quad(activate, save, open, enableCell, 0.55, 0.72, 0.86);
    }

    /** Slash command for activating a package. The unsaved sentinel needs special quoting because it contains parens. */
    private static String buildActivateCommand(String name) {
        return "dungeontrain package activate " + name;
    }

    private static String displayName(String name) {
        // Show "(unsaved)" verbatim, otherwise just the name.
        return name;
    }
}
