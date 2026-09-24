package games.brennan.dungeontrain.client.menu.editorscreen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UploadStatusBookTest {

    private static final String KEY = UploadStatusBook.key("CARRIAGE", "camel");

    @Test
    @DisplayName("nothing is shown for a template that never uploaded")
    void emptyShowsNothing() {
        assertNull(new UploadStatusBook().shown(KEY, 0L));
    }

    @Test
    @DisplayName("a started upload shows Uploading until it settles")
    void startedThenDone() {
        UploadStatusBook book = new UploadStatusBook();
        book.started(KEY, 1_000L);
        assertEquals(UploadStatusBook.Shown.UPLOADING, book.shown(KEY, 5_000L));
        book.finished(KEY, true, 6_000L);
        assertEquals(UploadStatusBook.Shown.UPLOADED, book.shown(KEY, 6_000L));
    }

    @Test
    @DisplayName("Uploaded fades after its moment; a failure stays up longer")
    void finishedExpires() {
        UploadStatusBook book = new UploadStatusBook();
        book.finished(KEY, true, 0L);
        assertNull(book.shown(KEY, UploadStatusBook.UPLOADED_SHOWN_MS));

        book.finished(KEY, false, 0L);
        assertEquals(UploadStatusBook.Shown.FAILED, book.shown(KEY, UploadStatusBook.UPLOADED_SHOWN_MS));
        assertNull(book.shown(KEY, UploadStatusBook.FAILED_SHOWN_MS));
    }

    @Test
    @DisplayName("an upload that never reports back is given up on rather than shown forever")
    void startedTimesOut() {
        UploadStatusBook book = new UploadStatusBook();
        book.started(KEY, 0L);
        assertEquals(UploadStatusBook.Shown.UPLOADING, book.shown(KEY, UploadStatusBook.STARTED_TIMEOUT_MS - 1));
        assertNull(book.shown(KEY, UploadStatusBook.STARTED_TIMEOUT_MS));
    }

    @Test
    @DisplayName("templates are kept apart by kind and id")
    void keyedPerTemplate() {
        UploadStatusBook book = new UploadStatusBook();
        book.started(KEY, 0L);
        assertNull(book.shown(UploadStatusBook.key("CONTENTS", "camel"), 0L));
        assertNull(book.shown(UploadStatusBook.key("CARRIAGE", "horse"), 0L));
    }
}
