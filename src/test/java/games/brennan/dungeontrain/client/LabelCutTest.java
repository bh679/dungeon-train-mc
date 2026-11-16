package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.Test;

import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabelCutTest {

    /** A fixed-width font: 6 px a character, like vanilla's digits. */
    private static final ToIntFunction<String> SIX = s -> s.length() * 6;

    @Test
    void aLabelThatFitsIsLeftAlone() {
        assertEquals("System default", LabelCut.fit("System default", SIX, 92));
        assertEquals("", LabelCut.fit("", SIX, 10));
    }

    @Test
    void aLongDeviceNameIsCutToTheButtonWithAnEllipsis() {
        String name = "Brennan's Galaxy S5 Microphone";
        int avail = DungeonTrainClientOptionsScreen.MIC_DEVICE_LABEL_W;
        String cut = LabelCut.fit(name, SIX, avail);
        assertTrue(cut.endsWith(LabelCut.ELLIPSIS), cut);
        assertTrue(SIX.applyAsInt(cut) <= avail, "measured with the font, it fits: " + cut);
        assertTrue(name.startsWith(cut.substring(0, cut.length() - 1)), cut);
    }

    @Test
    void theCutNeverEndsInASpace() {
        // 6 px a character: "Galaxy " would end on the space before the ellipsis.
        assertEquals("Galaxy" + LabelCut.ELLIPSIS, LabelCut.fit("Galaxy S5 Microphone", SIX, 7 * 6 + 6));
    }

    @Test
    void aWideFontMeansFewerCharactersFit() {
        ToIntFunction<String> wide = s -> s.length() * 12;
        String narrow = LabelCut.fit("Microsoft Teams Audio", SIX, 60);
        String broad = LabelCut.fit("Microsoft Teams Audio", wide, 60);
        assertTrue(broad.length() < narrow.length(), narrow + " vs " + broad);
    }

    @Test
    void whenNotEvenTheEllipsisFitsTheBareCutIsReturned() {
        assertEquals("M", LabelCut.fit("Mic", SIX, 6));
    }
}
