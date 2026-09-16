package games.brennan.dungeontrain.builder.relay;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What this builder world has uploaded to the relay: one entry per saved template, holding the relay's
 * id for it and the credentials to go on managing it.
 *
 * <p>Persisted in the world's saved data because none of it can be re-derived. The relay identifies a
 * build by an id it assigns; the {@code secret} is issued once, to the submitter, and is the only thing
 * that authorises publishing that build to the train or claiming it back to edit — lose the record and
 * the build is still on the relay, still in the player's profile, but this world can no longer do
 * anything with it. (Re-uploading identical blocks recovers it, because the relay dedupes a builder
 * submit per author and template and hands the secret back, but that is a fallback rather than the
 * design.)</p>
 *
 * <p>Keyed by {@link #keyOf} rather than by relay id: a Save knows which template it just wrote, not
 * what the relay called it. Not thread-safe — it is owned by the world's saved data and touched from
 * the server thread.</p>
 */
public final class BuilderRelayBuilds {

    private static final String TAG_KEY = "k";
    private static final String TAG_ID = "id";
    private static final String TAG_SECRET = "s";
    private static final String TAG_TOKEN = "t";
    private static final String TAG_PUBLISHED = "p";
    private static final String TAG_LOADED_SEQ = "v";
    private static final String TAG_OWNER = "o";

    /**
     * One uploaded build.
     *
     * @param relayId   the relay's id for it, within this world's capability pool
     * @param secret    the durable owner capability, issued at submit — authorises publish and claim
     * @param token     the current lease token, or empty when the world isn't holding the lease (which
     *                  is the case for every build that has been submitted to the train). A save needs
     *                  one, so an empty token means "claim before saving".
     * @param published whether it is on the train, as far as this world last knew. The relay is the
     *                  authority; this is what the builder's own screen shows before a refresh lands.
     * @param loadedSeq the relay version the local template currently stands at — what the last
     *                  load brought down or the last save became — which the next save names as the
     *                  version it was made from. 0 = unknown (a record from before versions), which
     *                  the relay reads as "from the current one".
     * @param ownerUuid whose build this is on the relay when it is not this player's: a dev-build
     *                  world that loaded somebody else's build as-is saves back into THEIR history,
     *                  credited as itself. Blank for the player's own builds, which is every record
     *                  a release build holds.
     */
    public record Entry(int relayId, String secret, String token, boolean published, int loadedSeq,
                        String ownerUuid) {
        public Entry {
            secret = secret == null ? "" : secret;
            token = token == null ? "" : token;
            ownerUuid = ownerUuid == null ? "" : ownerUuid;
        }

        /** A record of one of the player's own builds, at no particular version. */
        public Entry(int relayId, String secret, String token, boolean published) {
            this(relayId, secret, token, published, 0, "");
        }

        public Entry withToken(String newToken) {
            return new Entry(relayId, secret, newToken, published, loadedSeq, ownerUuid);
        }

        public Entry withPublished(boolean nowPublished) {
            return new Entry(relayId, secret, token, nowPublished, loadedSeq, ownerUuid);
        }

        public Entry withLoadedSeq(int seq) {
            return new Entry(relayId, secret, token, published, seq, ownerUuid);
        }

        /** Whether the relay row belongs to somebody other than this player. */
        public boolean isForeign() {
            return !ownerUuid.isEmpty();
        }
    }

    /** Insertion-ordered, so the profile screen's fallback ordering is the order things were built. */
    private final Map<String, Entry> byKey = new LinkedHashMap<>();

    /**
     * What joins a key's three fields: a character no kind, sub kind or template name can contain, so
     * a name with a space in it cannot be read back as a different build.
     */
    private static final char SEPARATOR = '\0';

    /**
     * The key one template is filed under: its store, the sub kind whose id-space it belongs to, and
     * its name.
     *
     * <p>All three, because none of them alone identifies a template: {@code standard} is both a floor
     * part and a door part, and a carriage and a portal room may share a name across their two stores.
     * This is the same {@code (kind, subKind, id)} triple {@code BuilderSave.Written} carries, which is
     * what makes a save able to find its own previous upload.</p>
     */
    public static String keyOf(String kind, String subKind, String id) {
        return (kind == null ? "" : kind) + SEPARATOR
                + (subKind == null ? "" : subKind) + SEPARATOR
                + (id == null ? "" : id);
    }

    /**
     * The relay kind a key was filed under — the first field {@link #keyOf} wrote.
     *
     * <p>Read back here rather than re-split at the point of use, because the caller that needs it is
     * asking a question about the build ("is this a carriage?") and should not have to know how the
     * three fields are joined.</p>
     *
     * @return the empty string for a null or malformed key, which no kind test matches
     */
    public static String kindOfKey(String key) {
        if (key == null) return "";
        int end = key.indexOf(SEPARATOR);
        return end < 0 ? "" : key.substring(0, end);
    }

    public Entry get(String key) {
        return byKey.get(key);
    }

    /** Record (or replace) one build's relay record. */
    public void put(String key, Entry entry) {
        if (key == null || entry == null) return;
        byKey.put(key, entry);
    }

    /** Forget a build — used when the relay says it no longer knows the id (evicted, or admin-deleted). */
    public void remove(String key) {
        byKey.remove(key);
    }

    /** Every recorded build, in the order they were first uploaded. */
    public Collection<Map.Entry<String, Entry>> all() {
        return List.copyOf(byKey.entrySet());
    }

    /** The key a relay id is filed under, or null — how a publish result finds the entry to update. */
    public String keyForRelayId(int relayId) {
        for (Map.Entry<String, Entry> e : byKey.entrySet()) {
            if (e.getValue().relayId() == relayId) return e.getKey();
        }
        return null;
    }

    /** Whose relay row a recorded build is, when not this player's — blank for the player's own or unknown ids. */
    public String ownerForRelayId(int relayId) {
        String key = keyForRelayId(relayId);
        return key == null ? "" : byKey.get(key).ownerUuid();
    }

    public boolean isEmpty() {
        return byKey.isEmpty();
    }

    // ---- persistence ----

    public ListTag toTag() {
        ListTag list = new ListTag();
        for (Map.Entry<String, Entry> e : byKey.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putString(TAG_KEY, e.getKey());
            t.putInt(TAG_ID, e.getValue().relayId());
            t.putString(TAG_SECRET, e.getValue().secret());
            t.putString(TAG_TOKEN, e.getValue().token());
            t.putBoolean(TAG_PUBLISHED, e.getValue().published());
            if (e.getValue().loadedSeq() > 0) t.putInt(TAG_LOADED_SEQ, e.getValue().loadedSeq());
            if (e.getValue().isForeign()) t.putString(TAG_OWNER, e.getValue().ownerUuid());
            list.add(t);
        }
        return list;
    }

    /** Replace the contents from a saved tag. An absent/garbled entry is skipped, never fatal. */
    public void loadFrom(ListTag list) {
        byKey.clear();
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            String key = t.getString(TAG_KEY);
            if (key.isEmpty() || !t.contains(TAG_ID, Tag.TAG_INT)) continue;
            // Two fields a record from before versions lacks; absent reads as "unknown" / "mine".
            byKey.put(key, new Entry(t.getInt(TAG_ID), t.getString(TAG_SECRET), t.getString(TAG_TOKEN),
                    t.getBoolean(TAG_PUBLISHED),
                    t.contains(TAG_LOADED_SEQ, Tag.TAG_INT) ? t.getInt(TAG_LOADED_SEQ) : 0,
                    t.getString(TAG_OWNER)));
        }
    }

    /** Every key currently recorded — the order things were built. */
    public List<String> keys() {
        return new ArrayList<>(byKey.keySet());
    }
}
