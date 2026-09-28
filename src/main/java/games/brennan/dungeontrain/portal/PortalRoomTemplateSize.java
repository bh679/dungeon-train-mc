package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * How big a portal room's template is, read from the file rather than from a loaded structure.
 *
 * <p>{@link PortalRoomSizes} has to answer "how big is this room" on paths with no
 * {@code ServerLevel} — the editor's plot layout resolves a block position to a plot dozens of times
 * a tick and never has one. A structure NBT states its own size in a three-int {@code size} list at
 * the top level, so the answer is a tag read: no palette, no block data, no level.</p>
 *
 * <p>Config-dir copy first, then the bundled resource — the same order everything else reads a
 * template in, so a room the author has saved answers with the size they saved it at rather than the
 * one it shipped with.</p>
 */
final class PortalRoomTemplateSize {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A structure template's three-int size list, as vanilla writes it. */
    private static final String TAG_SIZE = "size";
    private static final int AXES = 3;

    private PortalRoomTemplateSize() {}

    /** The size of room {@code name}, or null when it has no template or the tag is unusable. */
    @Nullable
    static Vec3i read(String name) {
        Optional<CompoundTag> tag = TrackVariantStore.rawTag(TrackKind.PORTAL_ROOM, name);
        if (tag.isEmpty()) return null;
        ListTag size = tag.get().getList(TAG_SIZE, Tag.TAG_INT);
        if (size.size() != AXES) {
            LOGGER.warn("[DungeonTrain] Portal room '{}': template has no usable size field — "
                + "falling back to the built-in room's box.", name);
            return null;
        }
        int x = size.getInt(0);
        int y = size.getInt(1);
        int z = size.getInt(2);
        if (x <= 0 || y <= 0 || z <= 0) return null;
        return new Vec3i(x, y, z);
    }
}
