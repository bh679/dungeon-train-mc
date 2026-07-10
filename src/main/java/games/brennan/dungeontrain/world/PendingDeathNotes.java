package games.brennan.dungeontrain.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persisted death-note store on the overworld world save
 * ({@code <world>/data/dungeontrain_pending_deathnotes.dat}). Holds two lists:
 * <ul>
 *   <li><b>pending</b> — signed but not-yet-armed curses (the author is still alive; no death
 *       carriage yet). Armed when the author next dies.</li>
 *   <li><b>armed</b> — the DEV-mode local equivalent of a relay-uploaded curse: after the author
 *       dies (dev build), the curse is armed here with the carriage they died at, so it survives a
 *       world reload / quit-to-title (the in-memory {@code DeathNotePool} does not). The target reads
 *       these on the arrival scan. Release builds use the relay instead of this list.</li>
 * </ul>
 * A world SavedData (not a player attachment): a curse must outlive the author's death and be readable
 * when a <em>different</em> player (the target) reaches the death carriage.
 */
public final class PendingDeathNotes extends SavedData {

    public static final String NAME = "dungeontrain_pending_deathnotes";
    /** Soft cap per list so a griefer signing endless notes can't grow the save unbounded. */
    private static final int MAX_PENDING = 256;

    private static final String TAG_NOTES = "notes";
    private static final String TAG_ARMED = "armed";
    private static final String TAG_ARMED_SEQ = "armedSeq";
    private static final String TAG_AUTHOR_UUID = "authorUuid";
    private static final String TAG_AUTHOR_NAME = "authorName";
    private static final String TAG_TARGET_NAME = "targetName";
    private static final String TAG_TARGET_UUID = "targetUuid";
    private static final String TAG_ID = "id";
    private static final String TAG_DEATH_CARRIAGE = "deathCarriage";

    /** One signed, awaiting-death curse. {@code targetUuid} is "" when unresolved at sign time. */
    public record PendingDeathNote(UUID authorUuid, String authorName, String targetName, String targetUuid) {}

    /** One armed (dev-local) curse — knows the carriage the author died at. */
    public record ArmedNote(int id, UUID authorUuid, String authorName, String targetName,
                            String targetUuid, int deathCarriage) {}

    private final List<PendingDeathNote> notes;
    private final List<ArmedNote> armed;
    private int armedSeq;

    private PendingDeathNotes(List<PendingDeathNote> notes, List<ArmedNote> armed, int armedSeq) {
        this.notes = new ArrayList<>(notes);
        this.armed = new ArrayList<>(armed);
        this.armedSeq = armedSeq;
    }

    public static PendingDeathNotes get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(() -> new PendingDeathNotes(List.of(), List.of(), 0),
                    (tag, registries) -> load(tag)),
            NAME);
    }

    // ---- pending (pre-death) ----------------------------------------------------

    /** Record a new pending curse; evicts the oldest if over the soft cap. */
    public void add(PendingDeathNote note) {
        notes.add(note);
        while (notes.size() > MAX_PENDING) notes.remove(0);
        setDirty();
    }

    /**
     * Remove and return every pending note authored by {@code authorUuid} — called when that player
     * dies, to arm them with the carriage they died at. Empty when the author had none.
     */
    public List<PendingDeathNote> takeForAuthor(UUID authorUuid) {
        List<PendingDeathNote> taken = new ArrayList<>();
        notes.removeIf(n -> {
            if (n.authorUuid().equals(authorUuid)) { taken.add(n); return true; }
            return false;
        });
        if (!taken.isEmpty()) setDirty();
        return taken;
    }

    // ---- armed (dev-local, post-death) -----------------------------------------

    /** Arm a curse with the carriage the author died at; returns its id (for removal on spawn). */
    public int addArmed(UUID authorUuid, String authorName, String targetName, String targetUuid, int deathCarriage) {
        int id = ++armedSeq;
        armed.add(new ArmedNote(id, authorUuid, authorName, targetName, targetUuid, deathCarriage));
        while (armed.size() > MAX_PENDING) armed.remove(0);
        setDirty();
        return id;
    }

    /**
     * Armed curses targeting {@code (playerUuid, playerName)} whose death carriage the player has
     * reached — {@code cur >= deathCarriage - lead}. Matches the target by uuid or (case-insensitive)
     * name.
     */
    public List<ArmedNote> armedReachedFor(UUID playerUuid, String playerName, int cur, int lead) {
        String uuidNoDash = playerUuid.toString().replace("-", "");
        List<ArmedNote> out = new ArrayList<>();
        for (ArmedNote a : armed) {
            boolean byUuid = a.targetUuid() != null && !a.targetUuid().isBlank()
                    && a.targetUuid().replace("-", "").equalsIgnoreCase(uuidNoDash);
            boolean byName = a.targetName() != null && a.targetName().equalsIgnoreCase(playerName);
            if ((byUuid || byName) && cur >= a.deathCarriage() - lead) out.add(a);
        }
        return out;
    }

    /** Remove an armed curse by id (called once its echo spawns). */
    public void removeArmed(int id) {
        if (armed.removeIf(a -> a.id() == id)) setDirty();
    }

    /** [DN-DEBUG] snapshot of all armed curses (diagnostic; safe copy). */
    public List<ArmedNote> allArmed() {
        return new ArrayList<>(armed);
    }

    // ---- persistence ------------------------------------------------------------

    private static PendingDeathNotes load(CompoundTag tag) {
        List<PendingDeathNote> loadedPending = new ArrayList<>();
        ListTag list = tag.getList(TAG_NOTES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag n = list.getCompound(i);
            try {
                UUID author = UUID.fromString(n.getString(TAG_AUTHOR_UUID));
                loadedPending.add(new PendingDeathNote(author, n.getString(TAG_AUTHOR_NAME),
                    n.getString(TAG_TARGET_NAME), n.getString(TAG_TARGET_UUID)));
            } catch (IllegalArgumentException ignored) {
                // skip a corrupt entry rather than fail the whole load
            }
        }
        List<ArmedNote> loadedArmed = new ArrayList<>();
        ListTag armedList = tag.getList(TAG_ARMED, Tag.TAG_COMPOUND);
        for (int i = 0; i < armedList.size(); i++) {
            CompoundTag n = armedList.getCompound(i);
            try {
                UUID author = UUID.fromString(n.getString(TAG_AUTHOR_UUID));
                loadedArmed.add(new ArmedNote(n.getInt(TAG_ID), author, n.getString(TAG_AUTHOR_NAME),
                    n.getString(TAG_TARGET_NAME), n.getString(TAG_TARGET_UUID), n.getInt(TAG_DEATH_CARRIAGE)));
            } catch (IllegalArgumentException ignored) {
                // skip a corrupt entry
            }
        }
        int seq = tag.contains(TAG_ARMED_SEQ) ? tag.getInt(TAG_ARMED_SEQ) : 0;
        return new PendingDeathNotes(loadedPending, loadedArmed, seq);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (PendingDeathNote n : notes) {
            CompoundTag c = new CompoundTag();
            c.putString(TAG_AUTHOR_UUID, n.authorUuid().toString());
            c.putString(TAG_AUTHOR_NAME, n.authorName());
            c.putString(TAG_TARGET_NAME, n.targetName());
            c.putString(TAG_TARGET_UUID, n.targetUuid());
            list.add(c);
        }
        tag.put(TAG_NOTES, list);

        ListTag armedList = new ListTag();
        for (ArmedNote a : armed) {
            CompoundTag c = new CompoundTag();
            c.putInt(TAG_ID, a.id());
            c.putString(TAG_AUTHOR_UUID, a.authorUuid().toString());
            c.putString(TAG_AUTHOR_NAME, a.authorName());
            c.putString(TAG_TARGET_NAME, a.targetName());
            c.putString(TAG_TARGET_UUID, a.targetUuid());
            c.putInt(TAG_DEATH_CARRIAGE, a.deathCarriage());
            armedList.add(c);
        }
        tag.put(TAG_ARMED, armedList);
        tag.putInt(TAG_ARMED_SEQ, armedSeq);
        return tag;
    }
}
