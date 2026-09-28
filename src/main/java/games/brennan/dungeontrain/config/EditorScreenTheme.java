package games.brennan.dungeontrain.config;

/**
 * How the inventory-style editor screen is painted.
 *
 * <p>Two looks, one layout. {@link #LIGHT} is the creative-inventory grey the screen was designed
 * in; {@link #DARK} is the translucent black every other Dungeon Train menu draws over the world.
 * Every painter takes its fills from here, so the two cannot drift into different layouts.</p>
 *
 * <p>Stored by name in {@link ClientDisplayConfig}: a client-scope setting, so the choice
 * follows the player between worlds and servers.</p>
 */
public enum EditorScreenTheme {

    /** Creative-inventory grey with a white / dark-grey bevel; dark sub-panels. */
    LIGHT(0xFFC6C6C6, 0xFFFFFFFF, 0xFF555555, 0xFF000000, 0xD0000000,
          0xFF6D6D6D, 0xFF8A8A8A, 0xFFC6C6C6, 0xFFDDDDDD, 0xFF000000, 0xFF3F3F3F),

    /** The translucent black of the other menus; sub-panels a shade darker again. */
    DARK(0xD0000000, 0xFF3A3A3A, 0xFF000000, 0xFF000000, 0xC0101010,
         0xFF2B2B2B, 0xFF4A4A4A, 0xFF6E6E6E, 0xFFDDDDDD, 0xFFFFFFFF, 0xFFFFEEBB);

    private final int panel;
    private final int bevelLight;
    private final int bevelDark;
    private final int outline;
    private final int subPanel;
    private final int tabIdle;
    private final int tabHover;
    private final int tabActive;
    private final int tabText;
    private final int tabTextActive;
    private final int panelText;

    EditorScreenTheme(int panel, int bevelLight, int bevelDark, int outline, int subPanel,
                      int tabIdle, int tabHover, int tabActive, int tabText, int tabTextActive,
                      int panelText) {
        this.panel = panel;
        this.bevelLight = bevelLight;
        this.bevelDark = bevelDark;
        this.outline = outline;
        this.subPanel = subPanel;
        this.tabIdle = tabIdle;
        this.tabHover = tabHover;
        this.tabActive = tabActive;
        this.tabText = tabText;
        this.tabTextActive = tabTextActive;
        this.panelText = panelText;
    }

    /** The main panel body. */
    public int panel() { return panel; }
    /** Top and left bevel edge. */
    public int bevelLight() { return bevelLight; }
    /** Bottom and right bevel edge. */
    public int bevelDark() { return bevelDark; }
    /** The one-pixel outline around the panel. */
    public int outline() { return outline; }
    /** Background of the dark sub-panels that hold rows, grids and the preview. */
    public int subPanel() { return subPanel; }
    public int tabIdle() { return tabIdle; }
    public int tabHover() { return tabHover; }
    public int tabActive() { return tabActive; }
    public int tabText() { return tabText; }
    public int tabTextActive() { return tabTextActive; }
    /** Text drawn directly on the panel body (not inside a sub-panel). */
    public int panelText() { return panelText; }

    public boolean isLight() {
        return this == LIGHT;
    }

    /** The other theme — what the Settings row switches to. */
    public EditorScreenTheme toggled() {
        return this == LIGHT ? DARK : LIGHT;
    }
}
