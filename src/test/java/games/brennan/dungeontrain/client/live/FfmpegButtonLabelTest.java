package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegButtonLabelTest {

    private static final String KEY = "gui.dungeontrain.options.ffmpeg.";

    @Test
    void pressableOnlyWhenMissing() {
        for (FfmpegSupport.State state : new FfmpegSupport.State[] {FfmpegSupport.State.OFF, FfmpegSupport.State.FAILED}) {
            FfmpegButtonLabel label = FfmpegButtonLabel.of(state, false, -1);
            assertEquals(KEY + "download", label.key());
            assertTrue(label.active());
        }
    }

    @Test
    void installedIsGreyedOut() {
        FfmpegButtonLabel label = FfmpegButtonLabel.of(FfmpegSupport.State.READY, false, 100);
        assertEquals(KEY + "installed", label.key());
        assertFalse(label.active());
    }

    @Test
    void downloadingCountsUpAndIsGreyedOut() {
        FfmpegButtonLabel known = FfmpegButtonLabel.of(FfmpegSupport.State.DOWNLOADING, false, 42);
        assertEquals(KEY + "downloading_percent", known.key());
        assertEquals(42, known.percent());
        assertFalse(known.active());

        FfmpegButtonLabel unknown = FfmpegButtonLabel.of(FfmpegSupport.State.DOWNLOADING, false, -1);
        assertEquals(KEY + "downloading", unknown.key());
        assertFalse(unknown.active());
    }

    @Test
    void dtInstallRunningCountsAsDownloadingEvenWhileVistaSaysOff() {
        FfmpegButtonLabel label = FfmpegButtonLabel.of(FfmpegSupport.State.OFF, true, 7);
        assertEquals(KEY + "downloading_percent", label.key());
        assertFalse(label.active());
    }
}
