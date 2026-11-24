package games.brennan.dungeontrain.compat.photo;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotoReportReasonTest {

    @Test
    void acceptsEveryPickerReason() {
        assertTrue(PhotoReportReason.isValid("hate_symbol"));
        for (String topic : PhotoReportReason.TOPICS) {
            assertTrue(PhotoReportReason.isValid("text:" + topic), topic);
            assertTrue(PhotoReportReason.isValid("other:" + topic), topic);
            assertTrue(("other:" + topic).length() <= PhotoReportReason.MAX_CHARS, topic);
        }
    }

    @Test
    void rejectsAnythingElse() {
        assertFalse(PhotoReportReason.isValid(null));
        assertFalse(PhotoReportReason.isValid(""));
        assertFalse(PhotoReportReason.isValid("text:"));
        assertFalse(PhotoReportReason.isValid("text:spam"));
        assertFalse(PhotoReportReason.isValid("other:csam"));
        assertFalse(PhotoReportReason.isValid("hate_symbol:sexual"));
        assertFalse(PhotoReportReason.isValid("sexual"));
        assertFalse(PhotoReportReason.isValid("TEXT:sexual"));
    }

    @Test
    void spamIsNotAPhotoTopic() {
        assertFalse(PhotoReportReason.TOPICS.contains("spam"));
        assertEquals(8, PhotoReportReason.TOPICS.size());
    }

    @Test
    void payloadCarriesIdReasonAndKey() {
        JsonObject body = PhotoReports.buildPayload("abc", 42, "text:hate", "k1");
        assertEquals("abc", body.get("uuid").getAsString());
        assertEquals(42, body.get("photoId").getAsInt());
        assertEquals("text:hate", body.get("reason").getAsString());
        assertEquals("k1", body.get("key").getAsString());
    }
}
