package games.brennan.dungeontrain.event;

import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveMomentEventsTest {

    private static Optional<DisplayInfo> display(boolean announce) {
        return Optional.of(new DisplayInfo(ItemStack.EMPTY, Component.literal("t"), Component.literal("d"),
            Optional.empty(), AdvancementType.TASK, true, announce, false));
    }

    @Test
    @DisplayName("an advancement is shared when vanilla would announce it, never an editor one or a recipe")
    void sharesAdvancement() {
        assertTrue(LiveMomentEvents.sharesAdvancement(ResourceLocation.parse("minecraft:story/mine_stone"), display(true)));
        assertTrue(LiveMomentEvents.sharesAdvancement(ResourceLocation.parse("dungeontrain:ride/first"), display(true)));
        assertFalse(LiveMomentEvents.sharesAdvancement(ResourceLocation.parse("minecraft:story/root"), display(false)));
        assertFalse(LiveMomentEvents.sharesAdvancement(ResourceLocation.parse("minecraft:recipes/misc/x"), Optional.empty()));
        assertFalse(LiveMomentEvents.sharesAdvancement(ResourceLocation.parse("dungeontrain:editor/x"), display(true)));
    }

    @Test
    @DisplayName("a book's excerpt: first non-blank page, one line, cut at a word")
    void excerpt() {
        assertEquals("", LiveMomentEvents.excerpt(null));
        assertEquals("", LiveMomentEvents.excerpt(List.of()));
        assertEquals("Day one. The train", LiveMomentEvents.excerpt(List.of("  ", "Day one.\n\nThe train", "later")));
        String word = "word ";
        String longPage = word.repeat(100);
        String cut = LiveMomentEvents.excerpt(List.of(longPage));
        assertTrue(cut.endsWith("word…"), cut);
        assertTrue(cut.length() <= LiveMomentEvents.EXCERPT_CHARS + 1, cut);
        char[] solid = new char[300];
        Arrays.fill(solid, 'x');
        assertEquals(LiveMomentEvents.EXCERPT_CHARS + 1, LiveMomentEvents.excerpt(List.of(new String(solid))).length());
    }
}
