package games.brennan.dungeontrain.client.wind;

import games.brennan.dungeontrain.DungeonTrain;

/**
 * Which "you are being pushed" effects play while the wind moves the local player. A test harness: dev builds
 * cycle through the combos with a hotkey ({@link WindFeelHotkey}) and show which one is on
 * ({@link WindFeelOverlay}); release builds stay on {@link #DEFAULT}.
 * <ul>
 *   <li><b>A</b> louder when pushed ({@link WindSound})</li>
 *   <li><b>B</b> ear buffet, a low fluttering rumble ({@link WindSound})</li>
 *   <li><b>C</b> near streaks across the screen edges ({@link WindFeelOverlay})</li>
 *   <li><b>D</b> upwind edge chill ({@link WindFeelOverlay})</li>
 *   <li><b>E</b> camera lean ({@link WindFeelCamera})</li>
 *   <li><b>F</b> push chevrons beside the crosshair ({@link WindFeelOverlay})</li>
 * </ul>
 */
public enum WindFeelMode {
    A("A", "louder when pushed", false, false, false, false, false),
    AB("A+B", "ear buffet", true, false, false, false, false),
    ABC("A+B+C", "near streaks", true, true, false, false, false),
    ABD("A+B+D", "upwind edge chill", true, false, true, false, false),
    ABE("A+B+E", "camera lean", true, false, false, true, false),
    ABF("A+B+F", "push chevrons", true, false, false, false, true);

    public static final WindFeelMode DEFAULT = A;

    private static volatile WindFeelMode current = DEFAULT;

    private final String code;
    private final String what;
    public final boolean buffet;
    public final boolean streaks;
    public final boolean chill;
    public final boolean lean;
    public final boolean chevrons;

    WindFeelMode(String code, String what, boolean buffet, boolean streaks, boolean chill, boolean lean,
                 boolean chevrons) {
        this.code = code;
        this.what = what;
        this.buffet = buffet;
        this.streaks = streaks;
        this.chill = chill;
        this.lean = lean;
        this.chevrons = chevrons;
    }

    public static WindFeelMode current() {
        return current;
    }

    /** Step to the next combo (wrapping); dev builds only. */
    static WindFeelMode cycle() {
        if (!DungeonTrain.isDevBuild()) return current;
        WindFeelMode[] all = values();
        current = all[(current.ordinal() + 1) % all.length];
        return current;
    }

    /** {@code Wind FX 5/6: A+B+E (camera lean)}. Untranslated: a dev label. */
    public String label() {
        return "Wind FX " + (ordinal() + 1) + "/" + values().length + ": " + code + " (" + what + ")";
    }

    public String shortLabel() {
        return "Wind FX " + (ordinal() + 1) + "/" + values().length + ": " + code;
    }
}
