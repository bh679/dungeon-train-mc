package games.brennan.dungeontrain.client.live.audio;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * Which sounds may reach the stream. The streamer hears everything; the stream carries only sounds
 * whose file comes from vanilla Minecraft or from an approved mod's own jar, so a resource pack
 * (or a server's) cannot swap a song or clip into the broadcast, and a mod that is not on the
 * approved list cannot play one. Fails closed: a sound whose origin is unknown stays off the stream.
 *
 * <p>Pack ids are NeoForge 21.1's: {@code vanilla}, {@code mod/<modid>} for each mod jar,
 * {@code file/<name>} for a resource pack, {@code server/<serial>/<id>} for a server's pack. Pure —
 * no Minecraft types — so it is unit-tested on a bare JVM.</p>
 */
public final class StreamSoundPolicy {

    static final String VANILLA = "vanilla";
    static final String MOD_PREFIX = "mod/";

    private StreamSoundPolicy() {}

    /** True when a sound file served by pack {@code packId} may be mirrored onto the stream. */
    public static boolean allowed(@Nullable String packId, Predicate<String> approvedMod) {
        if (packId == null) return false;
        if (VANILLA.equals(packId)) return true;
        if (!packId.startsWith(MOD_PREFIX)) return false;
        String ids = packId.substring(MOD_PREFIX.length());
        if (ids.isEmpty()) return false;
        for (String id : ids.split(",")) {
            if (id.isBlank() || !approvedMod.test(id)) return false;
        }
        return true;
    }

    /** The loaded mods the approved list does not cover, sorted; empty when every one is approved. */
    public static List<String> unapproved(Collection<String> loadedModIds, Predicate<String> approvedMod) {
        return loadedModIds.stream().filter(id -> !approvedMod.test(id)).sorted().toList();
    }
}
