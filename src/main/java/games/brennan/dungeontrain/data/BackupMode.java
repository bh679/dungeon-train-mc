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
     * Back up outside the instance folder as well as inside it. Survives a modpack update, an
     * instance reset, and a delete-and-reinstall.
     */
    EXTERNAL,

    /**
     * Back up inside the instance only. Survives a modpack update — which is the loss that
     * prompted all of this — but not the instance being deleted or reset.
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

    /** Parse a stored name, falling back to {@link #DEFAULT} rather than throwing. */
    public static BackupMode parse(String name) {
        if (name == null) return DEFAULT;
        for (BackupMode mode : values()) {
            if (mode.name().equalsIgnoreCase(name.trim())) return mode;
        }
        return DEFAULT;
    }
}
