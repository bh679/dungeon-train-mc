package games.brennan.dungeontrain.portal.tribute;

import games.brennan.dungeontrain.net.relay.SharedCarriageClient.Credits;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient.Deaths;
import games.brennan.dungeontrain.portal.PortalRoomBlob;
import games.brennan.dungeontrain.train.LeaseSnapshots;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * One player's tribute room as a thing that can be saved: who owns it, the blocks in it, and how many
 * times it has been saved.
 *
 * <p>{@code snapshot} is a {@link games.brennan.dungeontrain.train.CarriageBlockSnapshot} tag — the same
 * shape a shared room travels in — so laying it is {@link PortalRoomBlob} and nothing new.
 * {@code revision} 0 is the untouched default; a room is only worth showing to anybody else once it
 * has been saved at least once.</p>
 *
 * <p>Immutable: every change is a new blob ({@link #saved}).</p>
 *
 * @param owner      the owner's uuid, dashless, as every relay-facing id is written
 * @param ownerName  the owner's name when last saved, for a credit line
 * @param snapshot   the room's blocks
 * @param revision   how many saves this blob is the result of
 * @param savedAtMs  wall-clock time of the last save, 0 for the default
 * @param difficulty the difficulty partition it was last saved under ("" when unknown)
 */
public record TributeRoomBlob(String owner, String ownerName, CompoundTag snapshot, int revision,
                           long savedAtMs, String difficulty, @Nullable byte[] report, String reportMessage,
                           long reportAtMs) {

    static final String TAG_OWNER = "owner";
    static final String TAG_NAME = "name";
    static final String TAG_SNAPSHOT = "snap";
    static final String TAG_REVISION = "rev";
    static final String TAG_SAVED_AT = "at";
    static final String TAG_DIFFICULTY = "difficulty";
    static final String TAG_REPORT = "report";
    static final String TAG_REPORT_MESSAGE = "reportMsg";
    static final String TAG_REPORT_AT = "reportAt";

    public TributeRoomBlob {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(snapshot, "snapshot");
        if (ownerName == null) ownerName = "";
        if (difficulty == null) difficulty = "";
        if (revision < 0) revision = 0;
        if (reportMessage == null) reportMessage = "";
        if (report != null && report.length == 0) report = null;
    }

    /** The old six-field shape: no report yet. */
    public TributeRoomBlob(String owner, String ownerName, CompoundTag snapshot, int revision, long savedAtMs, String difficulty) {
        this(owner, ownerName, snapshot, revision, savedAtMs, difficulty, null, "", 0L);
    }

    /** Whether a last-death report rides with the room. */
    public boolean hasReport() {
        return report != null;
    }

    /** This blob carrying {@code report} as the owner's last death. */
    public TributeRoomBlob withReport(byte[] bytes, String message, long atMs) {
        return new TributeRoomBlob(owner, ownerName, snapshot, revision, savedAtMs, difficulty, bytes, message, atMs);
    }

    /** The untouched default for {@code owner}: revision 0, the bundled template's blocks. */
    public static TributeRoomBlob initial(String owner, String ownerName, CompoundTag snapshot) {
        return new TributeRoomBlob(owner, ownerName, snapshot, 0, 0L, "");
    }

    /** Whether this is still the untouched default. */
    public boolean isDefault() {
        return revision == 0;
    }

    /** Whether the snapshot is exactly {@code size} — the one box a tribute room is ever laid into. */
    public boolean matches(Vec3i size) {
        return LeaseSnapshots.matchesDims(snapshot, size.getX(), size.getY(), size.getZ());
    }

    /** This blob after a save: the new blocks, one more revision; the report it carried stays. */
    public TributeRoomBlob saved(CompoundTag newSnapshot, String newOwnerName, long nowMs, String newDifficulty) {
        return new TributeRoomBlob(owner, newOwnerName, newSnapshot, revision + 1, nowMs, newDifficulty, report, reportMessage, reportAtMs);
    }

    /** What the portal stamp lays: no lease, no relay id — a tribute room is never written back from the train. */
    public PortalRoomBlob toPortalRoomBlob() {
        return new PortalRoomBlob(snapshot, null, null, owner, Credits.EMPTY, Deaths.EMPTY, 0, false);
    }

    // ---- codec (pure, unit-tested) --------------------------------------------

    public CompoundTag encode() {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_OWNER, owner);
        tag.putString(TAG_NAME, ownerName);
        tag.put(TAG_SNAPSHOT, snapshot);
        tag.putInt(TAG_REVISION, revision);
        tag.putLong(TAG_SAVED_AT, savedAtMs);
        if (!difficulty.isEmpty()) tag.putString(TAG_DIFFICULTY, difficulty);
        if (report != null) {
            tag.putByteArray(TAG_REPORT, report);
            tag.putString(TAG_REPORT_MESSAGE, reportMessage);
            tag.putLong(TAG_REPORT_AT, reportAtMs);
        }
        return tag;
    }

    /** Null for a tag that does not hold a tribute room. */
    public static TributeRoomBlob decode(CompoundTag tag) {
        if (tag == null || !tag.contains(TAG_OWNER) || !tag.contains(TAG_SNAPSHOT)) return null;
        return new TributeRoomBlob(tag.getString(TAG_OWNER), tag.getString(TAG_NAME),
            tag.getCompound(TAG_SNAPSHOT), tag.getInt(TAG_REVISION), tag.getLong(TAG_SAVED_AT),
            tag.getString(TAG_DIFFICULTY), tag.contains(TAG_REPORT) ? tag.getByteArray(TAG_REPORT) : null,
            tag.getString(TAG_REPORT_MESSAGE), tag.getLong(TAG_REPORT_AT));
    }
}
