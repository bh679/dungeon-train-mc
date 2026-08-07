package games.brennan.dungeontrain.world;

import games.brennan.dungeontrain.narrative.NoteKind;
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
 * Persisted store of signed-but-not-yet-fired notes — Death Note curses and Love Notes alike — on
 * the overworld world save ({@code <world>/data/dungeontrain_pending_deathnotes.dat}).
 *
 * <p>Holds the <b>pending</b> list: notes a player has signed but not yet triggered — the author is
 * still alive, so the carriage they die at isn't known. When the author next dies, {@code DeathNoteEvents}
 * {@link #takeForAuthor takes} their pending notes and uploads each to the relay stamped with the death
 * carriage. Per-world is correct here: signing and the author's death happen in one life/world, and the
 * <em>relay</em> — not this file — carries the armed note across the target's later, differently-seeded
 * worlds. (The old dev-local {@code armed} list was removed: it was per-save-file, so the fresh world a
 * roguelike death starts could never see it.)</p>
 *
 * <p>The file name and NBT keys keep their original "deathnote" spelling: every note lives in one
 * list distinguished by {@link PendingDeathNote#kind}, and renaming the save file would orphan the
 * pending notes in existing worlds for nothing.</p>
 */
public final class PendingDeathNotes extends SavedData {

    public static final String NAME = "dungeontrain_pending_deathnotes";
    /** Soft cap so a griefer signing endless notes can't grow the save unbounded. */
    private static final int MAX_PENDING = 256;

    private static final String TAG_NOTES = "notes";
    private static final String TAG_AUTHOR_UUID = "authorUuid";
    private static final String TAG_AUTHOR_NAME = "authorName";
    private static final String TAG_TARGET_NAME = "targetName";
    private static final String TAG_TARGET_UUID = "targetUuid";
    private static final String TAG_KIND = "kind";

    /**
     * One signed, awaiting-death note. {@code targetUuid} is "" when unresolved at sign time;
     * {@code kind} is which book was signed (a note saved before Love Notes existed reads back as
     * {@link NoteKind#DEATH}, which is what it was).
     */
    public record PendingDeathNote(UUID authorUuid, String authorName, String targetName,
                                   String targetUuid, NoteKind kind) {}

    private final List<PendingDeathNote> notes;

    private PendingDeathNotes(List<PendingDeathNote> notes) {
        this.notes = new ArrayList<>(notes);
    }

    public static PendingDeathNotes get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(() -> new PendingDeathNotes(List.of()),
                    (tag, registries) -> load(tag)),
            NAME);
    }

    /** Record a new pending curse; evicts the oldest if over the soft cap. */
    public void add(PendingDeathNote note) {
        notes.add(note);
        while (notes.size() > MAX_PENDING) notes.remove(0);
        setDirty();
    }

    /**
     * Remove and return every pending note authored by {@code authorUuid} — called when that player
     * dies, to upload them stamped with the carriage they died at. Empty when the author had none.
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

    // ---- persistence ------------------------------------------------------------

    private static PendingDeathNotes load(CompoundTag tag) {
        List<PendingDeathNote> loadedPending = new ArrayList<>();
        ListTag list = tag.getList(TAG_NOTES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag n = list.getCompound(i);
            try {
                UUID author = UUID.fromString(n.getString(TAG_AUTHOR_UUID));
                // A note written before the kind existed has no tag — getString gives "" and
                // NoteKind.fromId reads that as DEATH, which is exactly what it was.
                loadedPending.add(new PendingDeathNote(author, n.getString(TAG_AUTHOR_NAME),
                    n.getString(TAG_TARGET_NAME), n.getString(TAG_TARGET_UUID),
                    NoteKind.fromId(n.getString(TAG_KIND))));
            } catch (IllegalArgumentException ignored) {
                // skip a corrupt entry rather than fail the whole load
            }
        }
        // Any legacy "armed" list from the old dev-local flow is intentionally ignored (the curse is
        // relay-backed now); it simply drops out of the save on the next write.
        return new PendingDeathNotes(loadedPending);
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
            c.putString(TAG_KIND, n.kind().id());
            list.add(c);
        }
        tag.put(TAG_NOTES, list);
        return tag;
    }
}
