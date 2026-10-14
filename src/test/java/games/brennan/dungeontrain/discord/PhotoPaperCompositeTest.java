package games.brennan.dungeontrain.discord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A tributed photo laid on its paper for the passenger log. */
class PhotoPaperCompositeTest {

    private static final int SIZE = PhotoPaperComposite.PAPER_SIZE;
    private static final int PAPER = 0xFFEEEEDD;
    private static final int PHOTO = 0xFF336699;

    private static int[] cleanPaper() {
        int[] paper = new int[SIZE * SIZE];
        Arrays.fill(paper, PAPER);
        return paper;
    }

    private static int[] photo(int width, int height) {
        int[] picture = new int[width * height];
        Arrays.fill(picture, PHOTO);
        return picture;
    }

    @Test
    @DisplayName("the picture is inset by 4 of 64 texels each side, every pixel kept")
    void insetAndExact() {
        int[] picture = photo(112, 112);
        picture[0] = 0xFF010203;
        picture[112 * 112 - 1] = 0xFF040506;
        PhotoPaperComposite.Composite out = PhotoPaperComposite.compose(cleanPaper(), picture, 112, 112);
        int border = PhotoPaperComposite.border(112);
        assertEquals(8, border);
        assertEquals(128, out.width());
        assertEquals(128, out.height());
        assertEquals(PAPER, out.argb()[0]);
        assertEquals(PAPER, out.argb()[(border - 1) * 128 + border]);
        assertEquals(0xFF010203, out.argb()[border * 128 + border]);
        assertEquals(0xFF040506, out.argb()[(border + 111) * 128 + border + 111]);
        assertEquals(PAPER, out.argb()[127 * 128 + 127]);
    }

    @Test
    @DisplayName("a tear in the paper cuts through the border and the picture alike")
    void tearsAreTransparent() {
        int[] paper = cleanPaper();
        paper[0] = 0;                              // corner of the border
        paper[(SIZE / 2) * SIZE + SIZE / 2] = 0;   // middle of the picture
        PhotoPaperComposite.Composite out = PhotoPaperComposite.compose(paper, photo(112, 112), 112, 112);
        assertEquals(0, out.argb()[0]);
        // Texel 32 sits over picture pixels (32 - 4) * 2 = 56..57, i.e. output 64..65.
        assertEquals(0, out.argb()[64 * 128 + 64]);
        assertEquals(PHOTO, out.argb()[62 * 128 + 62]);
    }

    @Test
    @DisplayName("a non-square photo gets its own border on each axis")
    void nonSquare() {
        PhotoPaperComposite.Composite out = PhotoPaperComposite.compose(cleanPaper(), photo(224, 112), 224, 112);
        assertEquals(224 + 2 * 16, out.width());
        assertEquals(112 + 2 * 8, out.height());
    }

    @Test
    @DisplayName("bad sizes are refused rather than guessed at")
    void refusesBadInput() {
        assertThrows(IllegalArgumentException.class, () -> PhotoPaperComposite.compose(new int[10], photo(4, 4), 4, 4));
        assertThrows(IllegalArgumentException.class, () -> PhotoPaperComposite.compose(cleanPaper(), new int[15], 4, 4));
    }
}
