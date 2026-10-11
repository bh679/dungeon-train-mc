package games.brennan.dungeontrain.cheat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * What a jar's code <em>looks like</em>: the distinct top two segments of every {@code .class}
 * path ({@code mezz.jei}, {@code net.xray}), embedded jars included, shaded libraries and loader
 * classes dropped, sorted, at most {@link #MAX}.
 *
 * <p>This is the second tier of the file check. A hash says "this exact published file"; it
 * cannot vouch for a release that is an hour old, a GitHub build, or a store's slightly different
 * upload. The packages can: those are still the mod's own code. A cheat jar that merely rewrote
 * its {@code mods.toml} to claim an approved id is not — its packages share nothing with that
 * mod's known set — and that is what makes it suspect. See
 * {@link UnapprovedModIntegrity#tierOf}.</p>
 *
 * <p>Twins: {@code scripts/modpack/check-mod-ids.py} {@code jar_packages} (what the catalogue
 * records) and {@code gradle/approved-mods.gradle} {@code jarPackages} (the embedded jars). All
 * three must agree key for key, or an honest jar reads as suspect; {@code PackageFingerprintTest}
 * pins the rule with the same fixtures.</p>
 */
public final class PackageFingerprint {

    /** Cap on keys per jar — a sorted prefix, so both sides cut the same way. */
    public static final int MAX = 64;
    /** Embedded-jar recursion depth. */
    static final int MAX_DEPTH = 2;

    /**
     * Libraries mods commonly shade, and classes every jar on the classpath shares. A match on one
     * of these says nothing about which mod a jar is, so they never count — in either direction.
     */
    static final String[] DROP = {
        "org.apache", "org.slf4j", "org.jetbrains", "org.intellij", "org.objectweb", "org.lwjgl", "org.joml",
        "org.spongepowered", "org.yaml", "org.json", "org.checkerframework", "org.jspecify", "org.codehaus",
        "com.google", "com.mojang", "com.sun", "com.electronwill", "com.fasterxml", "io.netty", "kotlin",
        "kotlinx", "javax", "jakarta", "java", "sun", "net.minecraft", "net.neoforged", "net.minecraftforge",
        "net.fabricmc", "scala", "groovy", "it.unimi", "cpw.mods", "mixin", "META-INF",
    };
    /** Hosting-style roots where the second segment is the host, not the author: take three. */
    static final String[] HOSTS = {"com.github", "io.github", "org.github", "dev.github", "com.gitlab", "io.gitlab"};
    private static final Pattern KEY = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*){1,2}");

    private PackageFingerprint() {}

    /** The package key for one zip entry name, or null when it does not count. Pure. */
    static String keyOf(String entryName) {
        if (entryName == null || !entryName.endsWith(".class") || entryName.startsWith("META-INF/")) return null;
        String[] parts = entryName.split("/");
        int dirs = parts.length - 1;
        if (dirs < 2) return null; // default package, or a single-segment package: too generic
        String key = parts[0] + "." + parts[1];
        if (dirs >= 3) {
            for (String h : HOSTS) {
                if (key.equals(h)) { key = key + "." + parts[2]; break; }
            }
        }
        for (String d : DROP) {
            if (key.equals(d) || key.startsWith(d + ".")) return null;
        }
        return KEY.matcher(key).matches() ? key : null;
    }

    /** Is this a key {@link #keyOf} could have produced? (Relay/baked input validation.) */
    public static boolean isValid(String key) {
        return key != null && key.length() <= 120 && KEY.matcher(key).matches();
    }

    /** The fingerprint of a jar on disk, embedded jars included. */
    public static List<String> ofJar(Path jar) throws IOException {
        TreeSet<String> out = new TreeSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String key = keyOf(e.getName());
                if (key != null) {
                    out.add(key);
                } else if (isEmbeddedJar(e.getName())) {
                    try (InputStream in = zip.getInputStream(e)) {
                        collect(in, 1, out);
                    } catch (IOException ignored) {
                        // a broken embedded jar contributes nothing
                    }
                }
            }
        }
        return cap(out);
    }

    /** The fingerprint of a jar's bytes (tests, and the embedded-jar recursion). */
    public static List<String> ofBytes(byte[] data) throws IOException {
        TreeSet<String> out = new TreeSet<>();
        collect(new java.io.ByteArrayInputStream(data), 0, out);
        return cap(out);
    }

    private static void collect(InputStream in, int depth, TreeSet<String> out) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
            String key = keyOf(e.getName());
            if (key != null) {
                out.add(key);
            } else if (depth < MAX_DEPTH && isEmbeddedJar(e.getName())) {
                try {
                    collect(new java.io.ByteArrayInputStream(zip.readAllBytes()), depth + 1, out);
                } catch (IOException ignored) {
                    // a broken embedded jar contributes nothing
                }
            }
        }
    }

    private static boolean isEmbeddedJar(String name) {
        return name.startsWith("META-INF/jarjar/") && name.endsWith(".jar");
    }

    private static List<String> cap(TreeSet<String> keys) {
        List<String> out = new ArrayList<>(Math.min(MAX, keys.size()));
        for (String k : keys) {
            if (out.size() >= MAX) break;
            out.add(k);
        }
        return List.copyOf(out);
    }
}
