package games.brennan.dungeontrain.util;

import games.brennan.dungeontrain.util.JavaRceCheck.JvmFacts;
import games.brennan.dungeontrain.util.JavaRceCheck.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaRceCheckTest {

    private static Verdict x86(List<Integer> version, Boolean intrinsics, Integer avx) {
        return JavaRceCheck.evaluate(new JvmFacts("amd64", version, intrinsics, avx));
    }

    @Test
    @DisplayName("Launcher Java 21.0.13 on an AVX-512 PC with the intrinsic on is at risk")
    void vulnerable21_atRisk() {
        assertEquals(Verdict.AT_RISK, x86(List.of(21, 0, 13), true, 3));
        assertEquals(Verdict.AT_RISK, x86(List.of(21, 0, 7), true, 3));
    }

    @Test
    @DisplayName("Every affected line warns below its fix")
    void everyLine_belowFix_atRisk() {
        assertEquals(Verdict.AT_RISK, x86(List.of(17, 0, 21), true, 3));
        assertEquals(Verdict.AT_RISK, x86(List.of(25, 0, 5), true, 3));
        assertEquals(Verdict.AT_RISK, x86(List.of(27, 0, 1), true, 3));
        assertEquals(Verdict.AT_RISK, x86(List.of(25), true, 3));
    }

    @Test
    @DisplayName("Fixed builds and later are safe")
    void fixedVersions_safe() {
        assertEquals(Verdict.SAFE, x86(List.of(17, 0, 21, 1), true, 3));
        assertEquals(Verdict.SAFE, x86(List.of(21, 0, 13, 1), true, 3));
        assertEquals(Verdict.SAFE, x86(List.of(21, 0, 14), true, 3));
        assertEquals(Verdict.SAFE, x86(List.of(25, 0, 5, 1), true, 3));
        assertEquals(Verdict.SAFE, x86(List.of(27, 0, 1, 1), true, 3));
    }

    @Test
    @DisplayName("Java 17 before 17.0.6, and 8/11/22, are not affected")
    void unaffectedVersions_safe() {
        assertEquals(Verdict.SAFE, x86(List.of(17, 0, 5), true, 3));
        assertEquals(Verdict.AT_RISK, x86(List.of(17, 0, 6), true, 3));
        assertEquals(Verdict.SAFE, x86(List.of(11, 0, 25), true, 3));
        assertEquals(Verdict.SAFE, x86(List.of(8), true, 3));
        assertEquals(Verdict.SAFE, x86(List.of(22, 0, 2), true, 3));
        assertEquals(Verdict.SAFE, x86(List.of(), true, 3));
    }

    @Test
    @DisplayName("The -XX:-UseBASE64Intrinsics workaround is recognised")
    void workaround_recognised() {
        assertEquals(Verdict.SAFE_WORKAROUND, x86(List.of(21, 0, 13), false, 3));
    }

    @Test
    @DisplayName("No AVX-512 (UseAVX below 3) is safe")
    void noAvx512_safe() {
        assertEquals(Verdict.SAFE, x86(List.of(21, 0, 13), true, 2));
        assertEquals(Verdict.SAFE, x86(List.of(21, 0, 13), true, 0));
    }

    @Test
    @DisplayName("ARM and Apple silicon are safe even with unreadable flags")
    void arm_safe() {
        assertEquals(Verdict.SAFE, JavaRceCheck.evaluate(new JvmFacts("aarch64", List.of(21, 0, 13), null, null)));
        assertEquals(Verdict.SAFE, JavaRceCheck.evaluate(new JvmFacts("arm64", List.of(21, 0, 13), true, 3)));
        assertEquals(Verdict.SAFE, JavaRceCheck.evaluate(new JvmFacts(null, List.of(21, 0, 13), true, 3)));
    }

    @Test
    @DisplayName("Unreadable flags on an affected x86 Java are unknown, never a warning")
    void unreadableFlags_unknown() {
        assertEquals(Verdict.UNKNOWN, x86(List.of(21, 0, 13), null, 3));
        assertEquals(Verdict.UNKNOWN, x86(List.of(21, 0, 13), true, null));
    }

    @Test
    @DisplayName("x86-64 arch names")
    void archNames() {
        assertEquals(Verdict.AT_RISK, JavaRceCheck.evaluate(new JvmFacts("x86_64", List.of(21, 0, 13), true, 3)));
        assertEquals(Verdict.AT_RISK, JavaRceCheck.evaluate(new JvmFacts("AMD64", List.of(21, 0, 13), true, 3)));
        assertEquals(Verdict.SAFE, JavaRceCheck.evaluate(new JvmFacts("x86", List.of(21, 0, 13), true, 3)));
    }

    @Test
    @DisplayName("Fixed version is named per line, defaulting to 21")
    void fixedVersionFor_line() {
        assertEquals("17.0.21.1", JavaRceCheck.fixedVersionFor(17));
        assertEquals("21.0.13.1", JavaRceCheck.fixedVersionFor(21));
        assertEquals("25.0.5.1", JavaRceCheck.fixedVersionFor(25));
        assertEquals("27.0.1.1", JavaRceCheck.fixedVersionFor(27));
        assertEquals("21.0.13.1", JavaRceCheck.fixedVersionFor(22));
    }

    @Test
    @DisplayName("The live JVM gets a verdict and a log line without throwing")
    void liveJvm_noThrow() {
        Verdict v = JavaRceCheck.current();
        assertNotNull(v);
        assertNotNull(JavaRceCheck.describe(v));
    }
}
