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
    @DisplayName("the burrito, the one after it, and the 250k run are the milestones — nothing else")
    void milestoneSet() {
        assertTrue(MilestoneAdvancementReporter.isMilestone(CompletionistAdvancement.ID));
        assertTrue(MilestoneAdvancementReporter.isMilestone(StartAgainAdvancement.ID));
        assertTrue(MilestoneAdvancementReporter.isMilestone(
                ResourceLocation.fromNamespaceAndPath("dungeontrain", "dungeon_train/the_long_run")));
        assertFalse(MilestoneAdvancementReporter.isMilestone(
                ResourceLocation.fromNamespaceAndPath("dungeontrain", "dungeon_train/cross_country")));
        assertFalse(MilestoneAdvancementReporter.isMilestone(
                ResourceLocation.fromNamespaceAndPath("minecraft", "story/root")));
    }

    @Test
    @DisplayName("title names the player and the advancement")
    void titleShape() {
        assertEquals("🏆 Steve earned Everything Burrito",
                MilestoneAdvancementReporter.title("Steve", "Everything Burrito"));
    }

    @Test
    @DisplayName("description is the advancement text, then the carriage/difficulty line")
    void descriptionShape() {
        assertEquals("Clear them all. Start Again.\n\nCarriage +7 · Difficulty Level 3",
                MilestoneAdvancementReporter.description("Clear them all. Start Again.",
                        "Carriage +7 · Difficulty Level 3"));
        assertEquals("Clear them all. Start Again.",
                MilestoneAdvancementReporter.description("Clear them all. Start Again.", ""));
        assertEquals("Difficulty Level 1", MilestoneAdvancementReporter.description(null, "Difficulty Level 1"));
    }
}
