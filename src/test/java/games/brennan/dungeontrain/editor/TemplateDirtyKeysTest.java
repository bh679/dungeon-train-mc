package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.tunnel.TunnelPlacer.TunnelVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TemplateDirtyKeys} is the inverse of {@link Template#dirtyKey()}: what the scan emits, it reads back. */
final class TemplateDirtyKeysTest {

    @Test
    @DisplayName("every scanned kind round-trips through its own dirty key")
    void roundTrips() {
        Template[] scanned = {
            new Template.Track("sleeperless"),
            new Template.Pillar(PillarSection.MIDDLE, "oak"),
            new Template.Adjunct(PillarAdjunct.STAIRS, "wide"),
            new Template.Tunnel(TunnelVariant.SECTION, "brick"),
            new Template.PortalRoom("library"),
            new Template.ChunkFrame("ivy"),
            new Template.Building("tower"),
            new Template.Staged("s1"),
        };
        for (Template t : scanned) {
            String key = t.dirtyKey().orElseThrow();
            assertEquals(Optional.of(t), TemplateDirtyKeys.parse(t.plotCategory().id(), key), key);
        }
    }

    @Test
    @DisplayName("a frame resolves under chunk_frames with the chunk_frame.<name> token")
    void frameToken() {
        assertEquals(Optional.of(new Template.ChunkFrame("ivy")),
                TemplateDirtyKeys.parse("chunk_frames", "chunk_frame.ivy"));
        assertTrue(TemplateDirtyKeys.parse("chunk_frames", "frame.ivy").isEmpty(), "wrong kind token");
        assertTrue(TemplateDirtyKeys.parse("portals", "chunk_frame.ivy").isEmpty(), "wrong category");
    }

    @Test
    @DisplayName("rows the scan never emits, and malformed ids, parse to nothing")
    void refusals() {
        assertTrue(TemplateDirtyKeys.parse("parts", "floor").isEmpty());
        assertTrue(TemplateDirtyKeys.parse("lost_city", "lost_city.bank").isEmpty());
        assertTrue(TemplateDirtyKeys.parse("tracks", "nodot").isEmpty());
        assertTrue(TemplateDirtyKeys.parse("tracks", "pillar_sideways.x").isEmpty());
        assertTrue(TemplateDirtyKeys.parse("tracks", ".x").isEmpty());
        assertTrue(TemplateDirtyKeys.parse("tracks", "track.").isEmpty());
        assertTrue(TemplateDirtyKeys.parse("stages", "x").isEmpty(), "the stages sentinel is not a category");
        assertTrue(TemplateDirtyKeys.parse((String) null, "x").isEmpty());
        assertTrue(TemplateDirtyKeys.parse("tracks", null).isEmpty());
    }
}
