package games.brennan.dungeontrain.cheat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * One installed mod <b>file</b> as the hash-verified whitelist sees it: the jar's name and size,
 * its SHA-1 (the identity the relay and Modrinth key on), its CurseForge fingerprint
 * ({@link CurseForgeFingerprint}), and every mod id that file declares — its own {@code [[mods]]}
 * plus those of any jar it embeds under {@code META-INF/jarjar/}, since NeoForge loads those as
 * mods but the player installed exactly one file.
 *
 * <p>Immutable; produced by {@link ModFileScanner}, sent to the relay by
 * {@link ApprovedModListFetcher} and judged by {@link UnapprovedModIntegrity}.</p>
 *
 * @param fileName the jar's file name, e.g. {@code jei-1.21.1-neoforge-19.21.0.247.jar}
 * @param size     bytes on disk
 * @param sha1     lowercase 40-hex SHA-1 of the file
 * @param murmur2  CurseForge fingerprint, unsigned 32-bit
 * @param modIds   lowercase mod ids the file declares, in discovery order
 */
public record ModFileFingerprint(String fileName, long size, String sha1, long murmur2, List<String> modIds) {

    public ModFileFingerprint {
        modIds = List.copyOf(modIds);
    }

    /** The wire form for {@code POST /approved-mods/verify}: {@code {sha1, murmur2, size, fileName, modIds}}. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("sha1", sha1);
        o.addProperty("murmur2", murmur2);
        o.addProperty("size", size);
        o.addProperty("fileName", fileName);
        JsonArray ids = new JsonArray();
        for (String id : modIds) ids.add(id);
        o.add("modIds", ids);
        return o;
    }

    /** {@code "<file> (id1, id2)"} — how the login notice and the cause tooltip name this file. */
    public String display() {
        return fileName + " (" + String.join(", ", modIds) + ")";
    }
}
