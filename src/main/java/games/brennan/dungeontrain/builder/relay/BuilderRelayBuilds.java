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
 * anything with it — unless the player proves to the relay that they own it ({@link RelayOwnerProof},
 * through {@code BuilderRelayUpload.adopt}), which only the signed-in host's own client can do.
 * Re-uploading identical blocks no longer recovers it: the relay's dedupe answers with the id alone.</p>
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
     */
    public record Entry(int relayId, String secret, String token, boolean published) {
        public Entry {
            secret = secret == null ? "" : secret;
            token = token == null ? "" : token;
        }

        public Entry withToken(String newToken) {
            return new Entry(relayId, secret, newToken, published);
        }

        public Entry withPublished(boolean nowPublished) {
            return new Entry(relayId, secret, token, nowPublished);
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

    /**
     * The sub kind a key was filed under — the second field {@link #keyOf} wrote.
     *
     * @return the empty string for a null or malformed key
     */
    public static String subKindOfKey(String key) {
        String[] parts = splitKey(key);
        return parts == null ? "" : parts[1];
    }

    /**
     * The template id a key was filed under — the third field {@link #keyOf} wrote.
     *
     * @return the empty string for a null or malformed key
     */
    public static String idOfKey(String key) {
        String[] parts = splitKey(key);
        return parts == null ? "" : parts[2];
    }

    /** The three fields of a key, or null when it is not one {@link #keyOf} wrote. */
    private static String[] splitKey(String key) {
        if (key == null) return null;
        int first = key.indexOf(SEPARATOR);
        int second = first < 0 ? -1 : key.indexOf(SEPARATOR, first + 1);
        if (second < 0) return null;
        return new String[] {key.substring(0, first), key.substring(first + 1, second), key.substring(second + 1)};
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
            byKey.put(key, new Entry(t.getInt(TAG_ID), t.getString(TAG_SECRET), t.getString(TAG_TOKEN),
                    t.getBoolean(TAG_PUBLISHED)));
        }
    }

    /** Every key currently recorded — the order things were built. */
    public List<String> keys() {
        return new ArrayList<>(byKey.keySet());
    }
}
