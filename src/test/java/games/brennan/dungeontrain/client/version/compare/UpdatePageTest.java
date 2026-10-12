package games.brennan.dungeontrain.client.version.compare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpdatePageTest {

    @Test
    @DisplayName("carries the installed version and the launcher, lower-case, as the page reads them")
    void withVersion() {
        assertEquals("https://brennan.games/dungeontrain/update/?v=0.927.0&from=curseforge",
                UpdatePage.url(FullSemver.parse("0.927.0"), Platform.CURSEFORGE));
        assertEquals("https://brennan.games/dungeontrain/update/?v=0.1104.2&from=modrinth",
                UpdatePage.url(FullSemver.parse("0.1104.2"), Platform.MODRINTH));
    }

    @Test
    @DisplayName("an unknown installed version leaves v off rather than sending junk")
    void withoutVersion() {
        assertEquals("https://brennan.games/dungeontrain/update/?from=modrinth",
                UpdatePage.url(Optional.empty(), Platform.MODRINTH));
    }
}
