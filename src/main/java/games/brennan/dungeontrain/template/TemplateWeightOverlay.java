package games.brennan.dungeontrain.template;

import java.util.HashMap;
import java.util.Map;

/**
 * What the per-install {@code weights.json} overlay should hold, and when it should be read.
 *
 * <p>Shared by the three weight stores ({@code CarriageWeights}, {@code CarriageContentsWeights},
 * {@code TrackVariantWeights}). Each keeps a <b>merged</b> view in memory — the bundled catalogue
 * with the player's config laid over it per key — and every setter mutates that view and writes it
 * back. Writing the merged view <i>whole</i> was the bug this class exists to close: one edit to one
 * room froze every other room's weight, as shipped that day, into the player's overlay, and the
 * overlay wins on every later reload. A room the mod later retired to weight 0 kept spawning for
 * anyone who had ever touched the editor, and no bundled retune reached them again.</p>
 *
 * <p>{@link #diff} keeps the overlay down to what the player actually changed. Entries equal to the
 * bundled record are redundant — the bundled tier already supplies them — so they are dropped, which
 * also self-heals an old snapshot on its next write.</p>
 */
public final class TemplateWeightOverlay {

    private TemplateWeightOverlay() {}

    /**
     * The entries of {@code merged} that differ from {@code bundled} — the overlay to persist.
     *
     * <p>Structural {@link TemplateMeta#equals} equality: weight, gate, Stage link, mode, flip,
     * label and builder credit all count. A key present in {@code bundled} but absent from
     * {@code merged} is simply not written — on the next reload the bundled entry stands again,
     * which is what {@code unset} has always meant after a restart.</p>
     */
    public static Map<String, TemplateMeta> diff(Map<String, TemplateMeta> merged,
                                                 Map<String, TemplateMeta> bundled) {
        Map<String, TemplateMeta> out = new HashMap<>();
        for (Map.Entry<String, TemplateMeta> e : merged.entrySet()) {
            TemplateMeta shipped = bundled.get(e.getKey());
            if (e.getValue() == null) continue;
            if (!e.getValue().equals(shipped)) out.put(e.getKey(), e.getValue());
        }
        return Map.copyOf(out);
    }

    /**
     * May the per-install overlay be read for the current world?
     *
     * <p>{@code false} while the world has disabled custom Train Editor content
     * ({@link games.brennan.dungeontrain.cheat.EditorContentIntegrity#isSuppressed}). Every other
     * store already falls through to the bundled tier under suppression via
     * {@code UserContentPaths.searchDirs}; the weight stores resolve their file directly and used to
     * skip that check, so a disabled world — a real, non-Free-Play run — still spawned from a stale
     * overlay. The reload barrier re-runs every store's {@code reload()} when the choice flips, so
     * honouring it here is all that is needed.</p>
     */
    public static boolean overlayReadable() {
        return !games.brennan.dungeontrain.cheat.EditorContentIntegrity.isSuppressed();
    }
}
