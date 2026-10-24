package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.RepoPaths;
import games.brennan.dungeontrain.event.PlayerActivityTracker;
import games.brennan.dungeontrain.event.PlayerActivityTracker.Reason;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The AFK rule's exemption list: every entry is a real advancement, and only those are let through. */
final class AfkAdvancementGateTest {

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath("dungeontrain", path);
    }

    @Test
    @DisplayName("every exempt id is a shipped advancement, so a rename can't silently drop one")
    void everyExemptIdExists() {
        Path dir = RepoPaths.advancements();
        for (ResourceLocation id : AfkAdvancementGate.EXEMPT) {
            assertEquals("dungeontrain", id.getNamespace(), id.toString());
            assertTrue(Files.isRegularFile(dir.resolve(id.getPath() + ".json")),
                "no advancement file for exempt id " + id);
        }
    }

    @Test
    @DisplayName("advancements that arrive on their own are exempt")
    void passiveAdvancementsAreExempt() {
        assertTrue(AfkAdvancementGate.isExempt(rl("dungeon_train/the_creator_answers")));
        assertTrue(AfkAdvancementGate.isExempt(rl("enchiridion/called_it")));
        assertTrue(AfkAdvancementGate.isExempt(rl("enchiridion/candid")));
    }

    @Test
    @DisplayName("screen-earned advancements need no exemption: the activity tracker sees the player")
    void screenEarnedAdvancementsAreNotExempt() {
        assertFalse(AfkAdvancementGate.isExempt(rl("dungeon_train/changed_engine_volume")));
        assertFalse(AfkAdvancementGate.isExempt(rl("dungeon_train/the_enchiridion")));
        assertFalse(AfkAdvancementGate.isExempt(rl("dungeon_train/signed_shared_book")));
        assertFalse(AfkAdvancementGate.isExempt(rl("enchiridion/found_footage")));
    }

    @Test
    @DisplayName("a photo_subject criterion reaches a player exploring off the train")
    void photoSubjectReachesOffTrainPlayer() throws Exception {
        // Nature Documentary is displayed and not exempt, so the AFK rule judges it ...
        ResourceLocation id = rl("enchiridion/nature_documentary");
        assertFalse(AfkAdvancementGate.isExempt(id));
        JsonObject json = JsonParser.parseString(
            Files.readString(RepoPaths.advancements().resolve(id.getPath() + ".json"))).getAsJsonObject();
        assertTrue(json.has("display"));
        for (var criterion : json.getAsJsonObject("criteria").entrySet()) {
            assertEquals("dungeontrain:photo_subject",
                criterion.getValue().getAsJsonObject().get("trigger").getAsString(), criterion.getKey());
        }
        // ... and that rule lets an off-train, present player (NO_PROGRESS) through, idle players not.
        assertFalse(PlayerActivityTracker.isAfk(Reason.NO_PROGRESS));
        assertTrue(PlayerActivityTracker.isAfk(Reason.MOUSE_IDLE));
        assertTrue(PlayerActivityTracker.isAfk(Reason.INPUT_IDLE));
        assertTrue(PlayerActivityTracker.isAfk(Reason.PAUSED));
    }

    @Test
    @DisplayName("only Dungeon Train's own advancements are judged; vanilla and other mods' pass while AFK")
    void onlyDungeonTrainAdvancementsAreJudged() {
        assertFalse(AfkAdvancementGate.isDungeonTrains(ResourceLocation.parse("minecraft:adventure/adventuring_time")));
        assertFalse(AfkAdvancementGate.isDungeonTrains(ResourceLocation.parse("exposure:take_photo")));
        assertTrue(AfkAdvancementGate.isDungeonTrains(rl("enchiridion/nature_documentary")));
        assertTrue(AfkAdvancementGate.isDungeonTrains(rl("dungeon_train/reached_nether")));
    }

    @Test
    @DisplayName("re-firing scan advancements stay gated")
    void scanAdvancementsAreNotExempt() {
        assertFalse(AfkAdvancementGate.isExempt(rl("dungeon_train/reached_nether")));
        assertFalse(AfkAdvancementGate.isExempt(rl("dungeon_train/root")));
    }
}
