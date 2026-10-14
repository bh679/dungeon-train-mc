package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegSupportTest {

    @Test
    void findsTheNativeAacEncoder() {
        String out = """
            Encoders:
             V..... = Video
             ------
             V....D libx264              libx264 H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10 (codec h264)
             A....D aac                  AAC (Advanced Audio Coding)
             A..... aac_at               aac (AudioToolbox) (codec aac)
            """;
        assertTrue(FfmpegSupport.listsAacEncoder(out));
    }

    @Test
    void anotherAacNamedEncoderIsNotEnough() {
        String out = """
             V....D libx264              libx264 H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10 (codec h264)
             A..... aac_at               aac (AudioToolbox) (codec aac)
             A....D libopus              libopus Opus
            """;
        assertFalse(FfmpegSupport.listsAacEncoder(out));
        assertFalse(FfmpegSupport.listsAacEncoder(""));
    }

    @Test
    void findsReadrateCatchupInLongHelp() {
        String v8 = "-re <>              read input at native frame rate; equivalent to -readrate 1\n"
            + "-readrate <speed>   read input at specified rate\n"
            + "-readrate_catchup <speed>  Temporary readrate used to catch up if an input lags behind\n";
        assertTrue(FfmpegSupport.listsReadrateCatchup(v8));
        assertFalse(FfmpegSupport.listsReadrateCatchup("-re <>  read input at native frame rate\n-readrate <speed>  read input\n"),
            "ffmpeg 6: -readrate but no catch-up");
    }
}
