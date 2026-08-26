package games.brennan.dungeontrain.builder.structure;

import java.util.Locale;
import java.util.Optional;

/**
 * When the structures around a build re-read the template they are made of.
 *
 * <p>Most of what {@link BuilderStructureNeeds} declares is resolved from a <em>template</em> — the
 * carriages either side of you are the carriage you are editing, the run either side of a track tile
 * rolls tiles that can be it. That template has two versions at once: the one on disk, and the one in
 * front of you. This chooses which the scenery shows.</p>
 *
 * <ul>
 *   <li>{@link #INSTANT} — the open build itself, re-read every sweep. Place a block and the copies
 *       gain it. The default, and what a room's tiled copies have always done.</li>
 *   <li>{@link #ON_SAVE} — the stored template, re-read when you Save. The scenery is then always a
 *       state you actually committed, which is the useful property when you are comparing.</li>
 *   <li>{@link #NEVER} — the stored template as it was when the build was opened, kept. For holding
 *       the version you started from beside the one you are making; note this is deliberately
 *       <em>stale</em>, and gets further from the truth the longer you work.</li>
 * </ul>
 *
 * <h2>Why this is world state and not a client preference</h2>
 *
 * <p>Same reason as {@link BuilderStructureMode}, which it sits under: in
 * {@link BuilderStructureMode#SOLID} the scenery is real blocks, so <em>when</em> it is refreshed is
 * the server rewriting the world rather than a client redrawing a ghost.</p>
 *
 * <h2>Instant is not "what Save will write"</h2>
 *
 * <p>A capture mirrors the build before it reads it, so with mirroring on, the stored template is the
 * reflected whole and the open build is the half you placed by hand. Instant shows what is actually
 * there, which is the honest answer for a preview, but the two are not the same picture.</p>
 *
 * <p>Deliberately free of client-only imports so it stays unit-testable.</p>
 */
public enum BuilderStructureRefresh {

    INSTANT("instant"),
    ON_SAVE("on_save"),
    NEVER("never");

    /**
     * What a world that has never been told gets.
     *
     * <p>{@link #INSTANT}, because it is what the room kinds already did before there was a control:
     * a world saved before this existed was live, and loading it must not quietly stop being.</p>
     */
    public static final BuilderStructureRefresh DEFAULT = INSTANT;

    private final String id;

    BuilderStructureRefresh(String id) {
        this.id = id;
    }

    /** Stable lower-case token — used in world data, packets and logs. */
    public String id() {
        return id;
    }

    /**
     * Translation key for the pause-menu button's label in this state.
     *
     * <p>Under its own prefix rather than {@code builder.structures.}, because this is a second
     * control sitting next to that one and sharing a key prefix would read as one setting.</p>
     */
    public String labelKey() {
        return "gui.dungeontrain.builder.structure_update." + id;
    }

    /**
     * Whether the open build's own blocks stand in for the template they came from.
     *
     * <p><b>The one question both sides ask.</b> The client sweep and the server materialiser each
     * build their own resolver inputs, and they have to agree — the client's {@code solidCells} is
     * what the out-of-bounds wash reads to know which real blocks are scenery, so a client resolving
     * live against a server that stamped from disk would paint real structure red and skip cells that
     * aren't there. Both call this rather than each testing the enum, so neither can decide alone.</p>
     *
     * <p><b>Solid included.</b> Solid follows your edits too: the client resolves live for the wash,
     * and {@code BuilderStructureRestampTicker} re-stamps the world off the same live read. The two
     * run on different cadences, so for the second or so between a change and the re-stamp the wash
     * can still be describing the previous stamp. That is a tint being briefly wrong about blocks
     * that are about to be rewritten, and it settles itself.</p>
     */
    public boolean substitutes() {
        return this == INSTANT;
    }

    /** Whether a Save should drop what was read from the store. False only for {@link #NEVER}. */
    public boolean refreshesOnSave() {
        return this != NEVER;
    }

    /** The next state on the pause-menu button, cycling in the order the enum declares. */
    public BuilderStructureRefresh next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public static Optional<BuilderStructureRefresh> fromId(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String needle = raw.trim().toLowerCase(Locale.ROOT);
        for (BuilderStructureRefresh refresh : values()) {
            if (refresh.id.equals(needle)) {
                return Optional.of(refresh);
            }
        }
        return Optional.empty();
    }

    /** As {@link #fromId}, falling back to {@link #DEFAULT} — for a world saved before this existed. */
    public static BuilderStructureRefresh orDefault(String raw) {
        return fromId(raw).orElse(DEFAULT);
    }
}
