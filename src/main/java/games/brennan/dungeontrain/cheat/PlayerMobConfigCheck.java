package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.PlayerMobConfig;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PlayerMob's balance settings, held to their defaults as part of {@link DtConfigIntegrity}.
 *
 * <p>PlayerMob ({@code playermob.properties}) decides how the train's player-shaped mobs fight and
 * scavenge — TNT, End crystals, arrows, engage ranges, looting chests. A world running with those
 * changed is not the game DT balanced, so a deviation joins DT's own config deviations and inherits
 * everything built for them: session Free Play, the login notice, the title-screen prompt and
 * {@code /fixconfig}.</p>
 *
 * <p><b>Live values, not the file.</b> PlayerMob reads its file once at game launch and applies its
 * own parse rules (clamping, fallbacks, list syntax), so comparing its getters against its public
 * {@code DEFAULT_*} constants is exact and needs no parser copy. A file edit therefore only counts
 * once the game restarts — which is also when it starts to matter. PlayerMob's in-game override
 * commands already taint through {@link CommandAllowlist}.</p>
 *
 * <p>Cosmetic and diagnostic keys (skins, naming, debug logging, order-failure chat) are deliberately
 * not governed. {@code naturalSpawnScale.*} only applies while {@code naturalSpawnEnabled} is on,
 * which is.</p>
 */
final class PlayerMobConfigCheck {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Rendered value of a list setting that holds entries — the entries themselves don't matter. */
    static final String CUSTOM_LIST = "custom";

    private static volatile boolean warned = false;

    private PlayerMobConfigCheck() {}

    /**
     * Deviations from PlayerMob's defaults, e.g. {@code playermob: tntCombat=false (expected true)}.
     * Fails open: if PlayerMob's API ever moves, log once and report nothing — an integrity check
     * must never be the thing that stops the game starting.
     */
    static List<String> deviations() {
        try {
            return compare(actual(), expected());
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                LOGGER.warn("[DungeonTrain] Could not read PlayerMob's config ({}) — not checking it", t.toString());
            }
            return List.of();
        }
    }

    /**
     * Pure: every key in {@code expected} whose value in {@code actual} differs, in key order.
     * Package-visible for tests.
     */
    static List<String> compare(Map<String, Object> actual, Map<String, Object> expected) {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            Object value = actual.get(entry.getKey());
            if (value == null || value.equals(entry.getValue())) continue;
            found.add("playermob: " + entry.getKey() + "=" + display(value)
                + " (expected " + display(entry.getValue()) + ")");
        }
        return List.copyOf(found);
    }

    private static String display(Object value) {
        if ("".equals(value)) return "empty";
        return value instanceof Enum<?> e ? e.name() : String.valueOf(value);
    }

    private static Map<String, Object> actual() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("echoFriendChance", PlayerMobConfig.echoFriendChance());
        m.put("reincarnationDifficultyIsolation", PlayerMobConfig.reincarnationDifficultyIsolation());
        m.put("trainDigThrough", PlayerMobConfig.trainDigThrough());
        m.put("trainFollowLovedPlayer", PlayerMobConfig.trainFollowLovedPlayer());
        m.put("naturalSpawnEnabled", PlayerMobConfig.naturalSpawnEnabled());
        m.put("requireArrows", PlayerMobConfig.requireArrows());
        m.put("seekArrowsWhenEmpty", PlayerMobConfig.seekArrowsWhenEmpty());
        m.put("rangedEngageDistance", PlayerMobConfig.rangedEngageDistance());
        m.put("meleeEngageDistance", PlayerMobConfig.meleeEngageDistance());
        m.put("tntCombat", PlayerMobConfig.tntCombat());
        m.put("endCrystalCombat", PlayerMobConfig.endCrystalCombat());
        m.put("flintAndSteelCombat", PlayerMobConfig.flintAndSteelCombat());
        m.put("extinguishWithBucket", PlayerMobConfig.extinguishWithBucket());
        m.put("douseFires", PlayerMobConfig.douseFires());
        m.put("huntForFood", PlayerMobConfig.huntForFood());
        m.put("searchContainers", PlayerMobConfig.searchContainers());
        m.put("searchArmorStands", PlayerMobConfig.searchArmorStands());
        m.put("collectFloorItems", PlayerMobConfig.collectFloorItems());
        m.put("extraPickupItems", PlayerMobConfig.extraPickups().isEmpty() ? "" : CUSTOM_LIST);
        m.put("moddedRangedWeapons", PlayerMobConfig.moddedRanged().isEmpty() ? "" : CUSTOM_LIST);
        return m;
    }

    private static Map<String, Object> expected() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("echoFriendChance", PlayerMobConfig.DEFAULT_ECHO_FRIEND_CHANCE);
        m.put("reincarnationDifficultyIsolation", PlayerMobConfig.DEFAULT_REINCARNATION_DIFFICULTY_ISOLATION);
        m.put("trainDigThrough", PlayerMobConfig.DEFAULT_TRAIN_DIG_THROUGH);
        m.put("trainFollowLovedPlayer", PlayerMobConfig.DEFAULT_TRAIN_FOLLOW_LOVED_PLAYER);
        m.put("naturalSpawnEnabled", PlayerMobConfig.DEFAULT_NATURAL_SPAWN_ENABLED);
        m.put("requireArrows", PlayerMobConfig.DEFAULT_REQUIRE_ARROWS);
        m.put("seekArrowsWhenEmpty", PlayerMobConfig.DEFAULT_SEEK_ARROWS_WHEN_EMPTY);
        m.put("rangedEngageDistance", PlayerMobConfig.DEFAULT_RANGED_ENGAGE_DISTANCE);
        m.put("meleeEngageDistance", PlayerMobConfig.DEFAULT_MELEE_ENGAGE_DISTANCE);
        m.put("tntCombat", PlayerMobConfig.DEFAULT_TNT_COMBAT);
        m.put("endCrystalCombat", PlayerMobConfig.DEFAULT_END_CRYSTAL_COMBAT);
        m.put("flintAndSteelCombat", PlayerMobConfig.DEFAULT_FLINT_AND_STEEL_COMBAT);
        m.put("extinguishWithBucket", PlayerMobConfig.DEFAULT_EXTINGUISH_WITH_BUCKET);
        m.put("douseFires", PlayerMobConfig.DEFAULT_DOUSE_FIRES);
        m.put("huntForFood", PlayerMobConfig.DEFAULT_HUNT_FOR_FOOD);
        m.put("searchContainers", PlayerMobConfig.DEFAULT_SEARCH_CONTAINERS);
        m.put("searchArmorStands", PlayerMobConfig.DEFAULT_SEARCH_ARMOR_STANDS);
        m.put("collectFloorItems", PlayerMobConfig.DEFAULT_COLLECT_FLOOR_ITEMS);
        m.put("extraPickupItems", "");
        m.put("moddedRangedWeapons", "");
        return m;
    }
}
