package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which grants count as getting somewhere: hand-driven triggers yes, passive thresholds never. */
final class ProgressCreditingGrantsTest {

    private static ResourceLocation rl(String id) {
        return ResourceLocation.parse(id);
    }

    /** Every trigger type a shipped Dungeon Train advancement criterion names. */
    private static Set<ResourceLocation> triggersInUse() throws IOException {
        Set<ResourceLocation> found = new HashSet<>();
        try (Stream<Path> files = Files.walk(RepoPaths.advancements())) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".json"))::iterator) {
                JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (!json.has("criteria")) continue;
                for (var criterion : json.getAsJsonObject("criteria").entrySet()) {
                    found.add(rl(criterion.getValue().getAsJsonObject().get("trigger").getAsString()));
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("every trigger type the shipped advancements use is decided one way or the other")
    void everyUsedTriggerIsClassified() throws IOException {
        for (ResourceLocation trigger : triggersInUse()) {
            boolean action = ProgressCreditingGrants.PLAYER_ACTION_TRIGGERS.contains(trigger);
            boolean passive = ProgressCreditingGrants.PASSIVE_OR_MIXED_TRIGGERS.contains(trigger);
            assertTrue(action || passive, "undecided trigger type " + trigger
                + ": add it to PLAYER_ACTION_TRIGGERS or PASSIVE_OR_MIXED_TRIGGERS");
            assertFalse(action && passive, "trigger type on both lists: " + trigger);
        }
    }

    @Test
    @DisplayName("time, distance, band, code and stand-still grants never restart the progress window")
    void passiveGrantsDoNotCount() {
        for (String id : new String[] {"dungeontrain:train_time", "dungeontrain:run_train_time",
                "dungeontrain:run_distance", "dungeontrain:lifetime_distance", "dungeontrain:biomes_visited",
                "dungeontrain:carts_in_run", "dungeontrain:code_granted", "dungeontrain:gameplay_action",
                "minecraft:impossible", "minecraft:location", "minecraft:tick",
                // Doable standing in one spot: not movement.
                "minecraft:placed_block", "minecraft:item_used_on_block", "minecraft:consume_item",
                "dungeontrain:editor_action", "dungeontrain:random_books_read", "dungeontrain:books_burned_unread",
                "dungeontrain:gave_playermob_unrequited", "dungeontrain:pushed_playermob_off_train"}) {
            assertFalse(ProgressCreditingGrants.proves(rl(id), true, false), id);
        }
    }

    @Test
    @DisplayName("a kill, a new chest or a befriended PlayerMob counts; recipes and exempt one-shots do not")
    void handDrivenGrantsCount() {
        assertTrue(ProgressCreditingGrants.proves(rl("minecraft:player_killed_entity"), true, false));
        assertTrue(ProgressCreditingGrants.proves(rl("dungeontrain:befriended_playermob"), true, false));
        assertTrue(ProgressCreditingGrants.proves(rl("dungeontrain:unique_chests_opened"), true, false));
        // Display-less (recipe) and exempt advancements never count, whatever the trigger.
        assertFalse(ProgressCreditingGrants.proves(rl("minecraft:player_killed_entity"), false, false));
        assertFalse(ProgressCreditingGrants.proves(rl("minecraft:player_killed_entity"), true, true));
        assertFalse(ProgressCreditingGrants.proves(null, true, false));
    }

    @Test
    @DisplayName("photo triggers are left to the camera hook, which credits the shot itself")
    void photoTriggersAreNotDoubleCounted() {
        assertFalse(ProgressCreditingGrants.proves(rl("dungeontrain:photo_subject"), true, false));
        assertFalse(ProgressCreditingGrants.proves(rl("dungeontrain:photo_count"), true, false));
    }
}
