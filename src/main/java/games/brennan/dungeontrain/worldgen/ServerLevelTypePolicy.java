package games.brennan.dungeontrain.worldgen;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Decides which world preset a dedicated server should actually create, given the
 * {@code level-type} its {@code server.properties} asked for and the one that reached
 * {@code DedicatedServerProperties.WorldDimensionData} after other mods had their say.
 *
 * <p>Why this exists: WorldWeaver (BCLib / BetterNether / BetterEnd's library, a hard DT
 * dependency) ships {@code config/wover/main.json → server.force_default_world_preset = true}
 * by default, and with it on it replaces <em>every</em> server {@code level-type} with its own
 * default preset ({@code wover:normal}, whose Nether/End use {@code wover:betterx}
 * generators). On a DT server that silently dropped the Dungeon
 * Train preset — no raised floor, no DT bedrock layer, and none of the floor presets
 * ({@code dungeon_train_y80} etc.) could be chosen.</p>
 *
 * <p>Policy:</p>
 * <ul>
 *   <li>A {@code dungeontrain:*} level-type is honoured as written.</li>
 *   <li>An unconfigured server — blank, {@code minecraft:normal}, the legacy
 *       {@code normal}/{@code default} names, or {@code wover:normal} — gets
 *       {@link #DEFAULT_PRESET}, the same world singleplayer creates. When server.properties
 *       has no {@code level-type}, WorldWeaver fills in its own default ({@code wover:normal})
 *       and writes it back to the file before any mod can see the gap, so a fresh DT server
 *       always reaches us as {@code wover:normal}.</li>
 *   <li>Any other explicit preset is left to whatever the mods decided.</li>
 * </ul>
 */
public final class ServerLevelTypePolicy {

    public static final String DT_NAMESPACE = "dungeontrain";
    public static final String DEFAULT_PRESET = DT_NAMESPACE + ":dungeon_train";

    /** WorldWeaver's default preset ({@code WorldPresetsManagerImpl.getDefault()}). */
    static final String WOVER_DEFAULT_PRESET = "wover:normal";

    private static final Set<String> UNCONFIGURED = Set.of(
            "", "minecraft:normal", "normal", "default", WOVER_DEFAULT_PRESET);

    private ServerLevelTypePolicy() {}

    /**
     * @param requested the raw {@code level-type} from server.properties (may be null)
     * @param applied   the level-type the server is about to create
     * @return the level-type to use instead, or empty to keep {@code applied}
     */
    public static Optional<String> override(String requested, String applied) {
        String wanted = resolve(requested);
        if (wanted == null || wanted.equals(applied)) {
            return Optional.empty();
        }
        return Optional.of(wanted);
    }

    private static String resolve(String requested) {
        String normalized = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith(DT_NAMESPACE + ":")) {
            return normalized;
        }
        if (UNCONFIGURED.contains(normalized)) {
            return DEFAULT_PRESET;
        }
        return null;
    }
}
