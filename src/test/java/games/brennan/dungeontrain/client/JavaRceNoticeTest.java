package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.client.version.LauncherDetector.Launcher;
import games.brennan.dungeontrain.util.JavaRceCheck.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaRceNoticeTest {

    @Test
    @DisplayName("At risk and never dismissed: shown")
    void atRisk_shown() {
        assertTrue(JavaRceNotice.shouldShow(Verdict.AT_RISK, "21.0.13+11-LTS", "", false));
        assertTrue(JavaRceNotice.shouldShow(Verdict.AT_RISK, "21.0.13+11-LTS", null, false));
    }

    @Test
    @DisplayName("Dismissed for this Java: hidden; a different Java: shown again")
    void dismissal_isPerRuntime() {
        assertFalse(JavaRceNotice.shouldShow(Verdict.AT_RISK, "21.0.13+11-LTS", "21.0.13+11-LTS", false));
        assertTrue(JavaRceNotice.shouldShow(Verdict.AT_RISK, "21.0.14+7-LTS", "21.0.13+11-LTS", false));
    }

    @Test
    @DisplayName("Not at risk: never shown unless forced")
    void notAtRisk_hidden() {
        for (Verdict v : new Verdict[] {Verdict.SAFE, Verdict.SAFE_WORKAROUND, Verdict.UNKNOWN}) {
            assertFalse(JavaRceNotice.shouldShow(v, "21.0.13", "", false));
        }
        assertTrue(JavaRceNotice.shouldShow(Verdict.SAFE, "21.0.13", "21.0.13", true));
    }

    @Test
    @DisplayName("Every launcher has its own directions key")
    void whereKey_perLauncher() {
        assertEquals("gui.dungeontrain.java_rce.where.curseforge", JavaRceNotice.whereKey(Launcher.CURSEFORGE));
        for (Launcher l : Launcher.values()) {
            assertTrue(JavaRceNotice.whereKey(l).startsWith("gui.dungeontrain.java_rce.where."));
        }
    }
}
