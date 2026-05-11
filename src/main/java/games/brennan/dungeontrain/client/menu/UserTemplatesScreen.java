package games.brennan.dungeontrain.client.menu;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.UserContentPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Side-panel companion for {@link PackageMenuScreen}. Lists every user-
 * authored template grouped by kind so the player can see at a glance what
 * a fresh export would contain. Walks {@link UserContentPaths#root()} on
 * the client filesystem each tick rather than caching, so saves made while
 * the menu is open show up on the next rebuild.
 *
 * <p>Single-player only in practice — the menu is client-side and reads the
 * local install's config dir. On a dedicated server the player's local
 * {@code config/dungeontrain/user/} is typically empty (the server's copy
 * is what gets exported by the slash command), so the list will be empty.
 * That mirrors the existing client/server split of the editor's other
 * file-touching flows.</p>
 *
 * <p>Rows are {@link CommandMenuEntry.Label} so they're informational
 * only — clicking does nothing, hover does not highlight. Drilling into
 * individual templates is a follow-up.</p>
 */
public final class UserTemplatesScreen implements MenuScreen {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Section labels paired with the kind-subdir under
     * {@link UserContentPaths#root()}. Order matches the on-disk layout
     * roughly so a player scanning the panel sees related kinds together
     * (carriage-related at the top, prefabs at the bottom).
     */
    private static final List<Section> SECTIONS = List.of(
        new Section("Carriages", "templates"),
        new Section("Contents", "contents"),
        new Section("Parts", "parts"),
        new Section("Containers", "containers"),
        new Section("Tracks", "tracks"),
        new Section("Pillars", "pillars"),
        new Section("Tunnels", "tunnels"),
        new Section("Loot Prefabs", "prefabs/loot"),
        new Section("Block-Variant Prefabs", "prefabs/block_variants")
    );

    @Override public String title() { return "User Templates"; }

    @Override public List<CommandMenuEntry> entries() {
        List<CommandMenuEntry> out = new ArrayList<>();
        int totalRows = 0;
        for (Section s : SECTIONS) {
            List<String> names = collectNames(s.subdir());
            if (names.isEmpty()) continue;
            out.add(new CommandMenuEntry.Label(s.label() + " (" + names.size() + ")"));
            for (String name : names) {
                out.add(new CommandMenuEntry.Label("  " + name));
                totalRows++;
                if (totalRows >= MAX_ROWS) {
                    out.add(new CommandMenuEntry.Label("..."));
                    return out;
                }
            }
        }
        if (out.isEmpty()) {
            out.add(new CommandMenuEntry.Label("No saved templates yet."));
            out.add(new CommandMenuEntry.Label("Save in the editor to populate."));
        }
        return out;
    }

    /**
     * Soft cap on side-panel rows. The shared layout doesn't scroll, so a
     * runaway listing would push the panel taller than the world view —
     * clamp and show an ellipsis instead.
     */
    private static final int MAX_ROWS = 40;

    /**
     * Enumerate the basenames (no extension) of regular files directly
     * under {@code <user-root>/<subdir>/}. Skips non-regular files (so the
     * containers' nested {@code <plotKey>.contents.json} files still show
     * up — they're at the top level of {@code containers/} — but a
     * sub-kind directory like {@code parts/floor/} is enumerated by its
     * subdirectories rather than its contents).
     *
     * <p>The two kinds that nest deeper — {@code parts/<kind>/} and
     * {@code pillars/<section>/} — are listed by the immediate
     * subdirectory name plus the count of NBTs inside, since the row format
     * here is intentionally compact ("Parts (8)" with eight sub-rows is
     * less informative for the player than "floor:simple", "walls:plain",
     * etc.).</p>
     */
    private static List<String> collectNames(String subdir) {
        Path dir = UserContentPaths.dir(subdir);
        if (!Files.isDirectory(dir)) return Collections.emptyList();

        List<String> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    // parts/<kind>/, pillars/<section>/, tunnels/<variant>/ —
                    // descend one level and list each NBT inline as
                    // "<kind>:<name>" so a player browsing parts sees individual
                    // user-authored part templates rather than just a kind count.
                    String kind = entry.getFileName().toString();
                    listNbtBasenames(entry).forEach(name -> out.add(kind + ":" + name));
                } else if (Files.isRegularFile(entry)) {
                    String filename = entry.getFileName().toString();
                    String stripped = stripExt(filename);
                    if (stripped != null) out.add(stripped);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] User-templates side panel: failed to list {}: {}",
                dir, e.toString());
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static List<String> listNbtBasenames(Path dir) {
        List<String> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) continue;
                String stripped = stripExt(file.getFileName().toString());
                if (stripped != null) out.add(stripped);
            }
        } catch (IOException ignored) {
            // Best-effort enumeration — a single unreadable kind dir doesn't
            // poison the rest of the panel.
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /**
     * Strip the trailing extension off {@code filename}, returning null for
     * files that don't have one (avoids dropping {@code .gitkeep} sentinels
     * or unrelated files into the listing).
     */
    private static String stripExt(String filename) {
        int dot = filename.indexOf('.');
        if (dot <= 0) return null;
        return filename.substring(0, dot).toLowerCase(Locale.ROOT);
    }

    private record Section(String label, String subdir) {}
}
