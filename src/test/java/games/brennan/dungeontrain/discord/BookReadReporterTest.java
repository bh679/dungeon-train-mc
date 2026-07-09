package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.net.BookReadClosedPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assembly tests for {@link BookReadReporter#buildPayload}. Pure — no running server or Minecraft
 * bootstrap needed. Covers the {@code variantIndex} inclusion rule, which is the one piece of
 * bookType-specific branching in an otherwise generic payload builder: {@code random} and
 * {@code starting} books carry a known text variant and report it when resolved ({@code >= 0});
 * every other bookType (and an unresolved {@code -1}) omits the field entirely.
 */
class BookReadReporterTest {

    private static BookReadClosedPacket packet(String bookType, int variantIndex) {
        return new BookReadClosedPacket(bookType, "some_book", "Title", "Author",
                5, 5, 4, true, 12_000L, List.of(1, 2, 3, 4, 5), "", 0, variantIndex);
    }

    @Test
    @DisplayName("starting book with a resolved variant includes variantIndex")
    void startingBookIncludesVariantIndex() {
        JsonObject out = BookReadReporter.buildPayload("uuid", "Notch", packet("starting", 2),
                null, 0, 0, false);
        assertTrue(out.has("variantIndex"));
        assertEquals(2, out.get("variantIndex").getAsInt());
    }

    @Test
    @DisplayName("starting book with an unresolved variant (-1) omits variantIndex")
    void startingBookUnresolvedVariantOmitted() {
        JsonObject out = BookReadReporter.buildPayload("uuid", "Notch", packet("starting", -1),
                null, 0, 0, false);
        assertFalse(out.has("variantIndex"));
    }

    @Test
    @DisplayName("random book with a resolved variant still includes variantIndex (unchanged behaviour)")
    void randomBookIncludesVariantIndex() {
        JsonObject out = BookReadReporter.buildPayload("uuid", "Notch", packet("random", 1),
                null, 0, 0, false);
        assertTrue(out.has("variantIndex"));
        assertEquals(1, out.get("variantIndex").getAsInt());
    }

    @Test
    @DisplayName("shared and narrative books never carry variantIndex, even if the field is non-negative")
    void nonVariantBookTypesOmitVariantIndex() {
        JsonObject shared = BookReadReporter.buildPayload("uuid", "Notch", packet("shared", 3),
                null, 0, 0, false);
        assertFalse(shared.has("variantIndex"));

        JsonObject narrative = BookReadReporter.buildPayload("uuid", "Notch", packet("narrative", 3),
                "brennan_intro", 1, 4, false);
        assertFalse(narrative.has("variantIndex"));
    }

    @Test
    @DisplayName("starting book payload carries the same identity/metadata fields as any other bookType")
    void startingBookCarriesCoreFields() {
        JsonObject out = BookReadReporter.buildPayload("uuid", "Notch", packet("starting", 0),
                null, 0, 0, false);
        assertEquals("starting", out.get("bookType").getAsString());
        assertEquals("some_book", out.get("bookId").getAsString());
        assertEquals(5, out.get("pageCount").getAsInt());
        assertTrue(out.get("completed").getAsBoolean());
        assertFalse(out.has("story"), "starting books are not narrative — no story enrichment fields");
    }
}
