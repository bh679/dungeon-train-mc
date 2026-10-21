package games.brennan.dungeontrain.discord;

import games.brennan.dungeontrain.advancement.CompletionistAdvancement;
import games.brennan.dungeontrain.advancement.StartAgainAdvancement;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which advancements reach the passenger log, and what the post says. */
class MilestoneAdvancementReporterTest {

    @Test
    @DisplayName("the burrito, the one after it, the 250k run and The Challenge are the milestones — nothing else")
    void milestoneSet() {
        assertTrue(MilestoneAdvancementReporter.isMilestone(CompletionistAdvancement.ID));
        assertTrue(MilestoneAdvancementReporter.isMilestone(StartAgainAdvancement.ID));
        assertTrue(MilestoneAdvancementReporter.isMilestone(
                ResourceLocation.fromNamespaceAndPath("dungeontrain", "dungeon_train/the_long_run")));
        assertTrue(MilestoneAdvancementReporter.isMilestone(
                ResourceLocation.fromNamespaceAndPath("dungeontrain", "dungeon_train/the_challenge")));
        assertFalse(MilestoneAdvancementReporter.isMilestone(
                ResourceLocation.fromNamespaceAndPath("dungeontrain", "dungeon_train/all_at_once")));
        assertFalse(MilestoneAdvancementReporter.isMilestone(
                ResourceLocation.fromNamespaceAndPath("dungeontrain", "dungeon_train/cross_country")));
        assertFalse(MilestoneAdvancementReporter.isMilestone(
                ResourceLocation.fromNamespaceAndPath("minecraft", "story/root")));
    }

    @Test
    @DisplayName("the hint key follows the advancements lang layout")
    void hintKey() {
        assertEquals("advancements.dungeontrain.dungeon_train.the_long_run.hint",
                MilestoneAdvancementReporter.hintKey(
                        ResourceLocation.fromNamespaceAndPath("dungeontrain", "dungeon_train/the_long_run")));
        assertTrue(MilestoneAdvancementReporter.isMilestone(
                ResourceLocation.fromNamespaceAndPath("dungeontrain", "dungeon_train/the_challenge")));
        assertFalse(MilestoneAdvancementReporter.isMilestone(
                ResourceLocation.fromNamespaceAndPath("dungeontrain", "dungeon_train/all_at_once")));
    }

    @Test
    @DisplayName("title names the player and the advancement")
    void titleShape() {
        assertEquals("🏆 Steve earned Everything Burrito",
                MilestoneAdvancementReporter.title("Steve", "Everything Burrito"));
    }

    @Test
    @DisplayName("description is the advancement hint alone — no carriage / difficulty line")
    void descriptionShape() {
        assertEquals("Clear them all. Start Again.",
                MilestoneAdvancementReporter.description("  Clear them all. Start Again.\n"));
        assertEquals("", MilestoneAdvancementReporter.description(""));
        assertEquals("", MilestoneAdvancementReporter.description(null));
    }
}
