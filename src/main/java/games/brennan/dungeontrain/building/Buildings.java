package games.brennan.dungeontrain.building;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The fixed facts about buildings — the Lost City / WWOO buildings a player can edit and add to.
 *
 * <p>Two kinds of building share one editor, told apart by name:</p>
 * <ul>
 *   <li><b>Shipped</b> — DT's own Lost City buildings, {@code data/dungeontrain/structure/lost_city/<name>.nbt}.
 *       Each has its own structure, pool and processor lists, so an edit keeps the building's place in the
 *       roster and its designs; the player's copy is loaded in place of the jar's
 *       ({@code StructureTemplateManagerBuildingMixin}). Big Lost City's buildings are never here: that mod is
 *       All Rights Reserved and DT never edits its templates.</li>
 *   <li><b>New</b> — anything else. Placed as built, with no processors, from the one
 *       {@link #PLAYER_STRUCTURE} slot in the Lost City structure set ({@link PlayerBuildings}).</li>
 * </ul>
 */
public final class Buildings {

    /** Sub-folder of each content package that holds building files. */
    public static final String SUBDIR = "buildings";

    /** The editor model id every building row carries — buildings have no kinds. */
    public static final String MODEL_ID = "building";

    /** The same name rule the other named templates use. */
    public static final Pattern NAME = Pattern.compile("^[a-z0-9_]{1,32}$");

    /**
     * Largest box a building may be: the biggest building the Lost City places, per axis — the power
     * plant's 63 wide, the tall skyscraper's 159 high, the skyscrapers' 64 deep. 64 across also keeps every
     * building inside vanilla's 128-block chunk-reference radius, so none is clipped at a chunk edge.
     */
    public static final Vec3i MAX_SIZE = new Vec3i(64, 159, 64);
    /** Smallest box — a pad and something standing on it. */
    public static final Vec3i MIN_SIZE = new Vec3i(4, 4, 4);
    /** What "New building" starts from. */
    public static final Vec3i DEFAULT_SIZE = new Vec3i(16, 24, 16);

    /** Template path prefix of the shipped buildings. */
    public static final String SHIPPED_PREFIX = "lost_city/";
    /** Template path prefix of new buildings — resolved from the player's files, then the jar. */
    public static final String PLAYER_PREFIX = "buildings/";

    /** The structure (and its pool) that places new buildings. */
    public static final ResourceLocation PLAYER_STRUCTURE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "lost_city/player_building");

    private Buildings() {}

    /** The structure-template id of shipped building {@code name}. */
    public static ResourceLocation shippedTemplateId(String name) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, SHIPPED_PREFIX + name);
    }

    /** The structure-template id of new building {@code name}. */
    public static ResourceLocation playerTemplateId(String name) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, PLAYER_PREFIX + name);
    }

    /** The template id {@code name} places from — shipped or new. */
    public static ResourceLocation templateId(String name) {
        return BuildingStore.isShipped(name) ? shippedTemplateId(name) : playerTemplateId(name);
    }

    /**
     * The building {@code templateId} names, or empty when it is not a building template: DT's namespace,
     * one of the two prefixes, and a valid name after it.
     */
    public static Optional<String> nameOf(ResourceLocation templateId) {
        if (templateId == null || !DungeonTrain.MOD_ID.equals(templateId.getNamespace())) return Optional.empty();
        String path = templateId.getPath();
        String name;
        if (path.startsWith(SHIPPED_PREFIX)) name = path.substring(SHIPPED_PREFIX.length());
        else if (path.startsWith(PLAYER_PREFIX)) name = path.substring(PLAYER_PREFIX.length());
        else return Optional.empty();
        return NAME.matcher(name).matches() ? Optional.of(name) : Optional.empty();
    }

    /** {@code size} clamped into {@link #MIN_SIZE}..{@link #MAX_SIZE} on every axis. */
    public static Vec3i clamp(Vec3i size) {
        return new Vec3i(
            Math.max(MIN_SIZE.getX(), Math.min(MAX_SIZE.getX(), size.getX())),
            Math.max(MIN_SIZE.getY(), Math.min(MAX_SIZE.getY(), size.getY())),
            Math.max(MIN_SIZE.getZ(), Math.min(MAX_SIZE.getZ(), size.getZ())));
    }
}
