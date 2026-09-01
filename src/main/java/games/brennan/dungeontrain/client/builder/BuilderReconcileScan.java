package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderTemplateSource;
import games.brennan.dungeontrain.data.PlayerDataBackup;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import games.brennan.dungeontrain.editor.UserContentPaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What this install has, what a backup remembers it having, and which of those the relay has lost.
 *
 * <p>Client-side and world-free, so the offer can be made at the title screen — the relay answers a
 * plain HTTP call from the menu, exactly as the translation tools already do. The in-world check
 * reads the world's own upload records; this one has no world, so it works from what is on disk:</p>
 *
 * <ul>
 *   <li><b>on disk</b> — a build in the user tier that the relay does not list. Restricted to
 *       {@link UserContentPaths.Provenance#USER}: a build that came in with somebody else's dtpack
 *       is theirs, and offering to upload it to your profile would be wrong.</li>
 *   <li><b>in a backup</b> — a build that is in a backup archive, not on disk, and not on the relay.
 *       That combination is only reachable by having existed here once; it is exactly the case the
 *       player asked about, and it is offered separately because a file can also be missing because
 *       they deleted it.</li>
 * </ul>
 *
 * <p>A build that is in no backup and not on disk cannot be seen from here at all — nothing records
 * it. That is the gap the in-world check covers, since the world remembers what it uploaded.</p>
 */
public final class BuilderReconcileScan {

    private static final String EXT = ".nbt";

    private BuilderReconcileScan() {}

    /** One build, addressed the way the relay addresses it. */
    public record Build(BuilderPhotoPaths.Kind kind, String subKind, String id) {
        /** The identity the relay listing is matched on: kind, sub kind, and the build's name. */
        String key() {
            return kind.id() + '\0' + (subKind == null ? "" : subKind) + '\0' + id;
        }
    }

    /**
     * What a scan found. Both lists are already filtered against the relay.
     *
     * @param profileUsed how many profile slots the relay already holds for this player — what a
     *                    restore has to stay under, since going over makes the relay delete their
     *                    oldest builds to make room
     */
    public record Result(List<Build> onDisk, List<Build> inBackups, int profileUsed) {
        public boolean isEmpty() {
            return onDisk.isEmpty() && inBackups.isEmpty();
        }

        public int total() {
            return onDisk.size() + inBackups.size();
        }
    }

    /**
     * Sort what this install has against what the relay still lists.
     *
     * @param relayKeys the {@code kind\0subKind\0buildName} of every build the relay holds for this
     *                  player — pure input, so the sorting can be tested without a relay
     */
    public static Result compare(List<Build> onDisk, List<Build> inBackups, Set<String> relayKeys,
                                 int profileUsed) {
        List<Build> missingOnDisk = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Build build : onDisk) {
            if (relayKeys.contains(build.key()) || !seen.add(build.key())) continue;
            missingOnDisk.add(build);
        }
        List<Build> missingInBackups = new ArrayList<>();
        for (Build build : inBackups) {
            // Only builds with no copy on disk at all: one that is both places is already covered by
            // the first tier, and offering it twice would upload it twice.
            if (relayKeys.contains(build.key()) || !seen.add(build.key())) continue;
            missingInBackups.add(build);
        }
        return new Result(List.copyOf(missingOnDisk), List.copyOf(missingInBackups), profileUsed);
    }

    /** Every build authored on this install, across every store directory. */
    public static List<Build> localBuilds() {
        List<Build> out = new ArrayList<>();
        for (BuilderTemplateSource.Slug slug : BuilderTemplateSource.slugs()) {
            for (String basename : UserContentPaths.listBasenamesAcrossSearchDirs(slug.subSlug(), EXT)) {
                if (UserContentPaths.provenanceOf(slug.subSlug(), basename + EXT)
                        != UserContentPaths.Provenance.USER) {
                    continue;   // bundled, or somebody else's imported pack — not this player's to upload
                }
                out.add(new Build(slug.kind(), slug.subKind(), basename));
            }
        }
        return List.copyOf(out);
    }

    /**
     * Every build a backup archive remembers, that is not on disk now.
     *
     * <p>Read from entry names alone — no archive is opened past its directory — so this stays cheap
     * enough for the title screen however many restore points have piled up.</p>
     */
    public static List<Build> backupBuilds(Set<String> onDiskKeys) {
        List<Build> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Path archive : archives()) {
            for (String entry : PlayerDataBackup.listEntries(archive)) {
                Build build = buildOf(entry);
                if (build == null) continue;
                if (onDiskKeys.contains(build.key()) || !seen.add(build.key())) continue;
                out.add(build);
            }
        }
        return List.copyOf(out);
    }

    /**
     * The build an archive entry names, or null when it names something else.
     *
     * <p>Entries are {@code "<label>/<relative path>"}. A build is one under the data root's user
     * tier or inside a bundled dtpack — {@code dungeontrain/user/<slug>/<id>.nbt} or
     * {@code dtpacks/<pack>/<slug>/<id>.nbt} — and the slug is what says which store, and therefore
     * which kind, it belonged to.</p>
     */
    static Build buildOf(String entryName) {
        if (entryName == null || !entryName.endsWith(EXT)) return null;
        String path = entryName;
        if (path.startsWith(PlayerDataPaths.ROOT_DIR + "/" + PlayerDataPaths.USER + "/")) {
            path = path.substring((PlayerDataPaths.ROOT_DIR + "/" + PlayerDataPaths.USER + "/").length());
        } else if (path.startsWith("dtpacks/")) {
            // dtpacks/<pack>/<slug>/<id>.nbt — drop the label and the pack name.
            int packEnd = path.indexOf('/', "dtpacks/".length());
            if (packEnd < 0) return null;
            path = path.substring(packEnd + 1);
        } else {
            return null;
        }
        for (BuilderTemplateSource.Slug slug : BuilderTemplateSource.slugs()) {
            String prefix = slug.subSlug() + "/";
            if (!path.startsWith(prefix)) continue;
            String id = path.substring(prefix.length(), path.length() - EXT.length());
            // A nested path is not this slug's build — parts and tracks have deeper slugs of their
            // own, and the loop reaches them on their own terms.
            if (id.isEmpty() || id.contains("/")) continue;
            return new Build(slug.kind(), slug.subKind(), id);
        }
        return null;
    }

    /** Every backup archive, newest first, in-instance before the out-of-instance mirror. */
    private static List<Path> archives() {
        List<Path> all = new ArrayList<>(PlayerDataBackup.listArchives(PlayerDataPaths.backupsRoot()));
        PlayerDataPaths.externalBackupsRoot()
                .ifPresent(root -> all.addAll(PlayerDataBackup.listArchives(root)));
        return all;
    }

    /** The identity keys of a list of builds — what {@link #backupBuilds} is filtered against. */
    public static Set<String> keysOf(List<Build> builds) {
        Set<String> keys = new LinkedHashSet<>();
        for (Build build : builds) keys.add(build.key());
        return keys;
    }
}
