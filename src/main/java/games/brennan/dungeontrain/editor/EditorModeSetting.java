package games.brennan.dungeontrain.editor;

/**
 * The editor Settings that keep one value for <b>editing</b> and another for <b>testing</b>
 * ({@link EditorSettingMode}): an author can have redstone cut while wiring a plot and live in the
 * Test-the-Carriage copy, and changing one never touches the other.
 *
 * <p>Each constant names the world-data tags its two values persist under. The editor tags are the
 * ones each setting used before it had a test value, and {@code testMobsLive} is the old Test Mobs
 * setting, so existing worlds keep what they had. A value is only written while it differs from
 * that mode's default.</p>
 */
public enum EditorModeSetting {
    /** Off keeps every observer quiet; see {@link EditorObservers}. */
    OBSERVERS("observers", "editorObserversOn", "testObserversOn", true, true),
    /** Off cuts every redstone signal; see {@link EditorRedstone}. */
    REDSTONE("redstone", "editorRedstoneOn", "testRedstoneOn", true, true),
    /** Off makes TNT fizzle; see {@link EditorTnt}. */
    TNT("tnt", "editorTntOn", "testTntOn", true, true),
    /** Off keeps placed water and lava from spreading; see {@link EditorFluids}. Still by default while editing. */
    FLUID_FLOW("fluids", "editorFluidFlowOn", "testFluidFlowOn", false, true),
    /** On: falling below the plots' frame starts flying; see {@link EditorAutoFly}. */
    AUTO_FLY("autofly", "editorAutoFlyOn", "testAutoFlyOn", true, true),
    /**
     * Mobs: Blocks ({@code false}) or Live ({@code true}). While editing, a spawn egg places a frozen
     * mob unless Live; in a test copy, Live also wakes the frozen mobs the template carries
     * ({@link FrozenMobs}, {@link TestCopyMobs}).
     */
    MOBS_LIVE("mobs", "editorMobsLive", "testMobsLive", false, false);

    private final String id;
    private final String editorTag;
    private final String testTag;
    private final boolean editorDefault;
    private final boolean testDefault;

    EditorModeSetting(String id, String editorTag, String testTag, boolean editorDefault, boolean testDefault) {
        this.id = id;
        this.editorTag = editorTag;
        this.testTag = testTag;
        this.editorDefault = editorDefault;
        this.testDefault = testDefault;
    }

    /** The command literal: {@code dungeontrain editor <id> …}. */
    public String id() {
        return id;
    }

    /** The world-data tag this setting's value in {@code mode} persists under. */
    public String tag(EditorSettingMode mode) {
        return mode == EditorSettingMode.TEST ? testTag : editorTag;
    }

    /** The value a world has until someone changes it. */
    public boolean defaultValue(EditorSettingMode mode) {
        return mode == EditorSettingMode.TEST ? testDefault : editorDefault;
    }
}
