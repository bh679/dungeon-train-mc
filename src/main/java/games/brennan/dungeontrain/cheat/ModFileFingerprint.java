package games.brennan.dungeontrain.cheat;

import java.util.List;

/**
 * One installed mod <b>file</b> as the hash-verified whitelist sees it: the jar's name and size,
 * its SHA-1 (the identity the relay and Modrinth key on), its CurseForge fingerprint, and every mod
 * id that file declares — its own {@code [[mods]]} plus those of any jar it embeds under
 * {@code META-INF/jarjar/}, since NeoForge loads those as mods but the player installed exactly one
 * file.
 *
 * <p>DT's own thin copy of the engine's record: produced by the optional {@code dtmodverify}
 * sibling and converted in {@link games.brennan.dungeontrain.compat.DtModVerifyBridge} (this record
 * never references the engine's classes), sent to the relay through the bridge by
 * {@link ApprovedModListFetcher} and judged by {@link UnapprovedModIntegrity}.</p>
 *
 * @param fileName the jar's file name, e.g. {@code jei-1.21.1-neoforge-19.21.0.247.jar}
 * @param size     bytes on disk
 * @param sha1     lowercase 40-hex SHA-1 of the file
 * @param murmur2  CurseForge fingerprint, unsigned 32-bit
 * @param modIds   lowercase mod ids the file declares, in discovery order
 * @param packages the jar's package fingerprint (top-level package names, outer jar and embedded
 *                 jars together, sorted)
 */
public record ModFileFingerprint(String fileName, long size, String sha1, long murmur2, List<String> modIds,
                                 List<String> packages) {

    public ModFileFingerprint {
        modIds = List.copyOf(modIds);
        packages = packages == null ? List.of() : List.copyOf(packages);
    }

    /** The pre-package shape, for tests and callers that only have a hash. */
    public ModFileFingerprint(String fileName, long size, String sha1, long murmur2, List<String> modIds) {
        this(fileName, size, sha1, murmur2, modIds, List.of());
    }

    /** {@code "<file> (id1, id2)"} — how the login notice and the cause tooltip name this file. */
    public String display() {
        return fileName + " (" + String.join(", ", modIds) + ")";
    }
}
