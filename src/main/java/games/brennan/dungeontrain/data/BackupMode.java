package games.brennan.dungeontrain.data;

/**
 * Where Dungeon Train keeps its restore points — the player's choice, in
 * Options → Dungeon Train.
 *
 * <p>The three values are a ladder of how much loss they survive, and the default is the top of
 * it. {@link #EXTERNAL} is the only one that survives an instance being deleted and reinstalled,
 * which launchers do and which takes {@code saves/} and any in-instance backup with it.</p>
 *
 * <p>Lives in {@code data} rather than next to the client config because
 * {@link PlayerDataBackupHook} reads it on the server thread, on dedicated servers too, where no
 * client config exists.</p>
 */
public enum BackupMode {

    /**
     * Back up outside the instance folder as well as inside it. The only mode that survives the
     * instance being deleted, reset or reinstalled, and the only one nothing a launcher does to the
     * instance can reach.
     */
    EXTERNAL,

    /**
     * Back up inside the instance only.
     *
     * <p><b>Likely</b> safe from a modpack update, but not guaranteed: the data root is outside the
     * folder a pack definitely replaces ({@code config/}), yet a pack can ship
     * {@code overrides/dungeontrain/} straight into it, and launchers have deleted whole instance
     * trees. Certainly lost if the instance is deleted or reset.</p>
     */
    INSTANCE,

    /** No backups at all. */
    OFF;

    /** The value a fresh install gets, and the fallback whenever the setting can't be read. */
    public static final BackupMode DEFAULT = EXTERNAL;

    public boolean writesAnything() {
        return this != OFF;
    }

    public boolean writesOutsideTheInstance() {
        return this == EXTERNAL;
    }

    /**
     * Parse a stored or operator-supplied name, falling back to {@link #DEFAULT} rather than
     * throwing.
     *
     * <p>Accepts the enum names and the words the options screen shows, because a server operator
     * setting {@code -Ddungeontrain.backups=on} is copying what they saw in the UI, not the
     * constant name: {@code on} = {@link #EXTERNAL}, {@code instanced} = {@link #INSTANCE}.</p>
     *
     * <p>An unrecognised value resolves to the default rather than to {@link #OFF} — a typo in a
     * launch flag must not silently disable backups.</p>
     */
    public static BackupMode parse(String name) {
        if (name == null) return DEFAULT;
        String value = name.trim();
        if (value.isEmpty()) return DEFAULT;
        if (value.equalsIgnoreCase("on")) return EXTERNAL;
        if (value.equalsIgnoreCase("instanced")) return INSTANCE;
        for (BackupMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) return mode;
        }
        return DEFAULT;
    }

    /**
     * Resolve an operator override from a system property and an environment variable, or
     * {@code null} when neither is set.
     *
     * <p>Split out as a pure function of its two inputs so it is unit-testable — reading the real
     * JVM environment in a test makes the result depend on the machine.</p>
     */
    public static BackupMode overrideFrom(String property, String env) {
        String raw = (property != null && !property.isBlank()) ? property : env;
        if (raw == null || raw.isBlank()) return null;
        return parse(raw);
    }
}
