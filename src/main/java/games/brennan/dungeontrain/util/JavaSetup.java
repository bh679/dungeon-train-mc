package games.brennan.dungeontrain.util;

import java.util.Locale;

/**
 * Who installed the Java this game runs on, so the Java security warning can say exactly how to
 * update it. Pure: reads nothing itself; {@link #current()} feeds it the live system properties.
 *
 * <p>A launcher that downloads Java for the player keeps it in its own folder, so the install path
 * ({@code java.home}) names the launcher. Anything else is a Java the player installed, and the
 * vendor ({@code java.vendor}) says where to get a newer one.</p>
 */
public final class JavaSetup {

    /** Who manages this Java. {@link #OWN} means the player installed it. */
    public enum Manager { MINECRAFT_LAUNCHER, CURSEFORGE, MODRINTH, PRISM, ATLAUNCHER, OWN }

    /** Java vendors with a public download page. */
    public enum Vendor {
        ADOPTIUM("Eclipse Temurin", "https://adoptium.net/temurin/releases/?version="),
        MICROSOFT("Microsoft", "https://learn.microsoft.com/java/openjdk/download"),
        ORACLE("Oracle", "https://www.oracle.com/java/technologies/downloads/"),
        AZUL("Azul Zulu", "https://www.azul.com/downloads/"),
        AMAZON("Amazon Corretto", "https://aws.amazon.com/corretto/"),
        OTHER("Eclipse Temurin", "https://adoptium.net/temurin/releases/?version=");

        /** Product name shown to the player. A proper noun, never translated. */
        public final String displayName;
        private final String url;

        Vendor(String displayName, String url) {
            this.displayName = displayName;
            this.url = url;
        }

        /** Download page; Temurin's opens on the right major version. */
        public String downloadUrl(int feature) {
            return url.endsWith("?version=") ? url + feature : url;
        }
    }

    public record Setup(Manager manager, Vendor vendor, int feature) {}

    private JavaSetup() {}

    public static Setup current() {
        return new Setup(
            manager(System.getProperty("java.home", "")),
            vendor(System.getProperty("java.vendor", "") + " " + System.getProperty("java.vm.vendor", "")),
            Runtime.version().feature());
    }

    /** Which launcher, if any, keeps the Java at {@code javaHome}. */
    public static Manager manager(String javaHome) {
        if (javaHome == null) return Manager.OWN;
        String p = javaHome.replace('\\', '/').toLowerCase(Locale.ROOT);
        // Launchers that also download Mojang's runtimes are checked before the Mojang folder name.
        if (p.contains("/modrinthapp/") || p.contains("com.modrinth.theseus")) return Manager.MODRINTH;
        if (p.contains("prismlauncher")) return Manager.PRISM;
        if (p.contains("atlauncher")) return Manager.ATLAUNCHER;
        if (p.contains("curseforge")) return Manager.CURSEFORGE;
        if (p.contains("/runtime/java-runtime") || p.contains("microsoft.4297127d64ec6")) return Manager.MINECRAFT_LAUNCHER;
        return Manager.OWN;
    }

    /** The vendor named in {@code java.vendor} / {@code java.vm.vendor}. */
    public static Vendor vendor(String vendorText) {
        if (vendorText == null) return Vendor.OTHER;
        String v = vendorText.toLowerCase(Locale.ROOT);
        if (v.contains("adoptium") || v.contains("adoptopenjdk") || v.contains("temurin")) return Vendor.ADOPTIUM;
        if (v.contains("microsoft")) return Vendor.MICROSOFT;
        if (v.contains("azul")) return Vendor.AZUL;
        if (v.contains("amazon")) return Vendor.AMAZON;
        if (v.contains("oracle")) return Vendor.ORACLE;
        return Vendor.OTHER;
    }
}
