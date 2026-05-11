package games.brennan.dungeontrain.editor;

import java.nio.file.Path;

/**
 * Immutable identity of one Dungeon Train content package.
 *
 * <p>Packages live under {@code <gameDir>/dtpacks/}:
 * <ul>
 *   <li>{@code dtpacks/<name>/} — working folder. Mirrors the legacy
 *       {@code user/} layout one-for-one ({@code templates/},
 *       {@code contents/}, {@code parts/...}, etc.). This is where edits
 *       land when the package is active.</li>
 *   <li>{@code dtpacks/<name>.zip} — archived snapshot, present once the
 *       player has Saved the package at least once. Updated only on
 *       explicit Save (never auto-zipped on edit).</li>
 * </ul>
 *
 * <p>The pseudo-package returned by {@link #unsaved(Path)} represents
 * in-progress content that has not yet been saved as a named .zip. Its
 * working folder is the legacy {@code <config>/dungeontrain/user/} so
 * pre-V2 saves continue to work without any file moves until the user
 * clicks Save.</p>
 *
 * <p>{@link PackageRegistry} owns the canonical list and issues new
 * instances when state changes; callers should treat returned records as
 * frozen snapshots.</p>
 */
public record PackageInfo(String name, Path workingDir, Path zipPath, Provenance provenance) {

    /**
     * Reserved id for the unsaved pseudo-package. Treated specially by the
     * registry: it's always present, always enabled, and represents
     * {@code user/} rather than a folder under {@code dtpacks/}.
     */
    public static final String UNSAVED_NAME = "(unsaved)";

    /** Factory for the unsaved pseudo-package — {@code workingDir = user/}, no zip. */
    public static PackageInfo unsaved(Path userRoot) {
        return new PackageInfo(UNSAVED_NAME, userRoot, null, Provenance.UNSAVED);
    }

    public boolean isUnsaved() { return provenance == Provenance.UNSAVED; }
    public boolean hasZip() { return zipPath != null; }

    /**
     * Classifies how a package came to be on disk.
     *
     * <ul>
     *   <li>{@link #UNSAVED} — the synthetic "(unsaved)" package backed by
     *       {@code <config>/dungeontrain/user/}. Only one of these ever
     *       exists.</li>
     *   <li>{@link #SAVED} — a package the player has explicitly Saved at
     *       least once. Has a paired {@code .zip} archive alongside its
     *       working folder.</li>
     *   <li>{@link #IMPORTED} — a folder under {@code dtpacks/} with no
     *       paired zip (e.g. extracted from an external zip drop or
     *       hand-placed). Behaves identically to {@link #SAVED} at runtime;
     *       the distinction is purely advisory for UI tinting.</li>
     * </ul>
     */
    public enum Provenance {
        UNSAVED,
        SAVED,
        IMPORTED
    }
}
