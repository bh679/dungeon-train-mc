package games.brennan.dungeontrain.util;

import com.sun.management.HotSpotDiagnosticMXBean;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Whether the running JVM is exposed to CVE-2026-114938 ("Java4Shell"), the heap overflow in
 * HotSpot's AVX-512 VBMI Base64 decode intrinsic. {@link StrictBase64} keeps DT's own decodes off
 * that intrinsic, but vanilla and other mods still use the JDK decoder (a multiplayer server's skin
 * {@code textures} property, for one), so the player's game is only safe once their JVM is.
 *
 * <p>Reads the JVM, never feeds it a trigger string. {@link Verdict#AT_RISK} needs every
 * precondition the advisories list (TuxCare, OX Security, Equixly; Nov 2026):</p>
 * <ol>
 *   <li>an x86-64 CPU. ARM, Apple silicon included, has no such intrinsic;</li>
 *   <li>{@code UseBASE64Intrinsics} on. The workaround {@code -XX:-UseBASE64Intrinsics} turns it
 *       off, so a player who applied the fix stops being warned without doing anything else;</li>
 *   <li>{@code UseAVX} 3, which HotSpot only picks on an AVX-512 CPU. Java cannot see VBMI itself,
 *       so an AVX-512 CPU without it (Skylake-X, Cascade Lake) is still warned: the fix costs
 *       nothing, and a missed warning costs more than a spare one;</li>
 *   <li>a Java version inside the affected range of its line (below the fixed one).</li>
 * </ol>
 *
 * <p>Fixed and vulnerable vendor builds can share a version string, so a version at or above the
 * fix is believed rather than proven. That errs quiet only for builds that claim the fix.</p>
 */
public final class JavaRceCheck {

    /** The JVM argument that closes the hole on any build. Shown to players verbatim. */
    public static final String JVM_ARGUMENT = "-XX:-UseBASE64Intrinsics";

    /** Advisory linked from the warning: affected versions, fixes, workaround. */
    public static final String ADVISORY_URL = "https://tuxcare.com/blog/java4shell-cve-2026-114938/";

    public static final String CVE = "CVE-2026-114938";

    /** First fixed version per affected feature line. */
    static final Map<Integer, List<Integer>> FIXED = Map.of(
        17, List.of(17, 0, 21, 1),
        21, List.of(21, 0, 13, 1),
        25, List.of(25, 0, 5, 1),
        27, List.of(27, 0, 1, 1));

    /** Java 17 picked the intrinsic up in 17.0.6; earlier 17 builds never had it. */
    static final List<Integer> FIRST_AFFECTED_17 = List.of(17, 0, 6);

    /** Smallest {@code UseAVX} that enables the AVX-512 code paths. */
    static final int AVX512_LEVEL = 3;

    public enum Verdict {
        /** Every precondition holds: warn. */
        AT_RISK,
        /** Would be at risk, but {@link #JVM_ARGUMENT} is in effect. */
        SAFE_WORKAROUND,
        /** A precondition is missing (ARM, no AVX-512, unaffected or fixed version). */
        SAFE,
        /** The flags couldn't be read (not HotSpot). Never warned on. */
        UNKNOWN
    }

    /**
     * What the check needs to know about a JVM. {@code base64Intrinsics} and {@code useAvx} are
     * {@code null} when unreadable.
     */
    public record JvmFacts(String osArch, List<Integer> version, Boolean base64Intrinsics, Integer useAvx) {
        public JvmFacts {
            version = List.copyOf(version);
        }
    }

    private JavaRceCheck() {}

    private static volatile Verdict cached;

    /** The verdict for this JVM, computed once. Never throws. */
    public static Verdict current() {
        Verdict v = cached;
        if (v == null) {
            v = evaluate(readFacts());
            cached = v;
        }
        return v;
    }

    /** Pure decision over {@code facts}. */
    public static Verdict evaluate(JvmFacts facts) {
        if (!isX86_64(facts.osArch())) return Verdict.SAFE;
        if (!isAffectedVersion(facts.version())) return Verdict.SAFE;
        if (facts.base64Intrinsics() == null || facts.useAvx() == null) return Verdict.UNKNOWN;
        if (facts.useAvx() < AVX512_LEVEL) return Verdict.SAFE;
        return facts.base64Intrinsics() ? Verdict.AT_RISK : Verdict.SAFE_WORKAROUND;
    }

    static boolean isX86_64(String osArch) {
        if (osArch == null) return false;
        String arch = osArch.toLowerCase(Locale.ROOT);
        return arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64");
    }

    static boolean isAffectedVersion(List<Integer> version) {
        if (version.isEmpty()) return false;
        List<Integer> fixed = FIXED.get(version.get(0));
        if (fixed == null) return false;
        if (version.get(0) == 17 && compare(version, FIRST_AFFECTED_17) < 0) return false;
        return compare(version, fixed) < 0;
    }

    /** Compare version lists component-wise; a missing component counts as 0. */
    static int compare(List<Integer> a, List<Integer> b) {
        int n = Math.max(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.size() ? b.get(i) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    /**
     * The first fixed version on the running JVM's feature line, e.g. {@code "21.0.13.1"}, or
     * {@code "21.0.13.1"} when the line isn't one of the affected ones.
     */
    public static String fixedVersionFor(int feature) {
        List<Integer> fixed = FIXED.getOrDefault(feature, FIXED.get(21));
        StringBuilder sb = new StringBuilder();
        for (int part : fixed) {
            if (!sb.isEmpty()) sb.append('.');
            sb.append(part);
        }
        return sb.toString();
    }

    /** One line for logs and bug reports. */
    public static String describe(Verdict verdict) {
        String java = System.getProperty("java.runtime.version", "?")
            + " (" + System.getProperty("java.vendor", "?") + ", " + System.getProperty("os.arch", "?") + ")";
        return switch (verdict) {
            case AT_RISK -> "[DungeonTrain] " + CVE + " (Java4Shell): this Java is affected: " + java
                + ". Add the JVM argument " + JVM_ARGUMENT + " or update Java. See " + ADVISORY_URL;
            case SAFE_WORKAROUND -> "[DungeonTrain] " + CVE + ": workaround " + JVM_ARGUMENT + " is active on " + java;
            case SAFE -> "[DungeonTrain] " + CVE + ": not affected: " + java;
            case UNKNOWN -> "[DungeonTrain] " + CVE + ": couldn't read the JVM flags of " + java + "; not checked";
        };
    }

    static JvmFacts readFacts() {
        String arch = System.getProperty("os.arch", "");
        List<Integer> version;
        try {
            version = Runtime.version().version();
        } catch (Throwable t) {
            version = List.of();
        }
        Boolean intrinsics = null;
        Integer avx = null;
        try {
            HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            if (bean != null) {
                intrinsics = Boolean.parseBoolean(bean.getVMOption("UseBASE64Intrinsics").getValue());
                avx = Integer.parseInt(bean.getVMOption("UseAVX").getValue().trim());
            }
        } catch (Throwable t) {
            // Not HotSpot, or the flag doesn't exist on this build (e.g. ARM has no UseAVX): unknown.
            intrinsics = null;
            avx = null;
        }
        return new JvmFacts(arch, version, intrinsics, avx);
    }
}
