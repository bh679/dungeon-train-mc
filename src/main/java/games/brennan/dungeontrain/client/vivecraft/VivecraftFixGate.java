package games.brennan.dungeontrain.client.vivecraft;

/**
 * Decides whether Dungeon Train should apply its own copy of the VR melee fix.
 *
 * <p>Kept OUT of the mixin package for the same reason as {@link SwingAabbClamp}: Mixin's transformer
 * claims everything under {@code games.brennan.dungeontrain.mixin.*}, so a class there cannot be
 * referenced directly by a unit test. {@code VivecraftMixinPlugin} reads the loader state and
 * delegates the decision here, where it is a plain boolean function anyone can test.</p>
 *
 * <p><b>Why DT has a copy at all.</b> Both VR fixes moved out to the standalone
 * <a href="https://github.com/bh679/vivecraft-sable-compat">Vivecraft Sable Compat</a> addon in
 * #1261, because neither bug is Dungeon Train specific. That left a regression window: a VR player
 * who updates DT before installing the addon loses working melee on the train. DT's copy is the
 * stopgap, and it stands down as soon as the addon is present.</p>
 *
 * <table>
 *   <tr><th>Vivecraft</th><th>Addon</th><th>DT's melee fix</th></tr>
 *   <tr><td>absent</td>  <td>—</td>      <td>skipped — the target class isn't there</td></tr>
 *   <tr><td>present</td> <td>absent</td> <td><b>applied</b> — DT is the only fix present</td></tr>
 *   <tr><td>present</td> <td>present</td><td>skipped — the addon owns it</td></tr>
 * </table>
 *
 * <p>DT only ever carried the <em>melee</em> half; the addon also fixes VR teleport dropping the
 * player into the void, which DT cannot substitute for. So installing the addon remains the better
 * outcome, and this gate gets out of its way rather than competing with it.</p>
 *
 * <p><b>TEMPORARY.</b> Delete this class, {@code VivecraftMixinPlugin}, {@link SwingAabbClamp}, the
 * mixin it gates and the {@code [[mixins]]} entry in {@code neoforge.mods.toml} once the addon is
 * live on both platforms and bundled in the Dungeon Train modpack.</p>
 */
public final class VivecraftFixGate {

    /** Vivecraft — the mod whose class DT's melee mixin targets. */
    public static final String VIVECRAFT_MODID = "vivecraft";

    /**
     * Vivecraft Sable Compat — the addon that owns both VR fixes.
     *
     * <p>This is its {@code modId} as declared in its own {@code neoforge.mods.toml} (underscores),
     * NOT its Modrinth/CurseForge slug {@code vivecraft-sable-compat} (hyphens). Using the slug
     * would fail open: the addon would never be detected and DT would keep applying its copy
     * alongside it.</p>
     */
    public static final String COMPAT_MODID = "vivecraft_sable_compat";

    private VivecraftFixGate() {}

    /**
     * Should DT apply its own melee fix?
     *
     * @param vivecraftLoaded is Vivecraft installed? Without it the mixin's target class does not exist.
     * @param compatModLoaded is Vivecraft Sable Compat installed? If so, it owns the fix.
     */
    public static boolean shouldApply(boolean vivecraftLoaded, boolean compatModLoaded) {
        return vivecraftLoaded && !compatModLoaded;
    }
}
