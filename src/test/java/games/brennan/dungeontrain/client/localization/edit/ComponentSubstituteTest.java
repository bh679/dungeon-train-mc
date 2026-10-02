package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A recorded message or tooltip, rebuilt with one key's text swapped for the translator's. */
class ComponentSubstituteTest {

    @Test
    @DisplayName("the key's text is swapped, its own arguments still fill it, and its style stays")
    void swapsKeepingArgsAndStyle() {
        Component recorded = Component.translatable("chat.dungeontrain.found", Component.literal("12"))
            .withStyle(ChatFormatting.GOLD);
        Component out = ComponentSubstitute.replace(recorded, "chat.dungeontrain.found", "Gefunden: %s");

        TranslatableContents contents = (TranslatableContents) out.getContents();
        assertEquals("Gefunden: %s", contents.getFallback());
        assertEquals("12", ((Component) contents.getArgs()[0]).getString());
        assertEquals(ChatFormatting.GOLD.getColor(), out.getStyle().getColor().getValue());
    }

    @Test
    @DisplayName("a key nested as an argument or a sibling is found and swapped too")
    void nested() {
        Component recorded = Component.literal("> ")
            .append(Component.translatable("chat.type.text", Component.translatable("gui.dungeontrain.name")));
        assertTrue(ComponentSubstitute.contains(recorded, "gui.dungeontrain.name"));
        assertFalse(ComponentSubstitute.contains(recorded, "gui.dungeontrain.other"));

        Component out = ComponentSubstitute.replace(recorded, "gui.dungeontrain.name", "Zug");
        TranslatableContents outer = (TranslatableContents) out.getSiblings().get(0).getContents();
        TranslatableContents inner = (TranslatableContents) ((Component) outer.getArgs()[0]).getContents();
        assertEquals("Zug", inner.getFallback());
        // Other keys are left as the game resolves them.
        assertEquals("chat.type.text", outer.getKey());
    }
}
