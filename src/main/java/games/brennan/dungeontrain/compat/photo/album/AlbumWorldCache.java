package games.brennan.dungeontrain.compat.photo.album;

import com.mojang.logging.LogUtils;
import io.github.mortuusars.exposure.world.item.component.album.AlbumContent;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Every player's album as this world last knew it ({@code <world>/data/dungeontrain_player_albums.dat}):
 * the pages, with photographs whose pictures are in this world's Exposure store, and the save's
 * {@code rev}. Every copy of a player's album opens onto this — the relay is what carries the album
 * on to their next world ({@link PlayerAlbums}).
 */
public final class AlbumWorldCache extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String NAME = "dungeontrain_player_albums";
    private static final String TAG_ALBUMS = "albums";
    private static final String TAG_REV = "rev";
    private static final String TAG_CONTENT = "content";

    public record Entry(long rev, AlbumContent content) {}

    private final Map<UUID, Entry> albums;

    private AlbumWorldCache(Map<UUID, Entry> albums) {
        this.albums = new HashMap<>(albums);
    }

    public static AlbumWorldCache get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(() -> new AlbumWorldCache(Map.of()), AlbumWorldCache::load), NAME);
    }

    public Optional<Entry> get(UUID owner) {
        return Optional.ofNullable(albums.get(owner));
    }

    public long rev(UUID owner) {
        Entry entry = albums.get(owner);
        return entry == null ? 0L : entry.rev();
    }

    public void put(UUID owner, Entry entry) {
        albums.put(owner, entry);
        setDirty();
    }

    private static AlbumWorldCache load(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        Map<UUID, Entry> albums = new HashMap<>();
        CompoundTag all = tag.getCompound(TAG_ALBUMS);
        for (String key : all.getAllKeys()) {
            CompoundTag row = all.getCompound(key);
            try {
                UUID owner = UUID.fromString(key);
                AlbumContent content = AlbumContent.CODEC.parse(ops, row.get(TAG_CONTENT))
                        .resultOrPartial(error -> LOGGER.warn("[DungeonTrain] Album of {} partly unreadable: {}", key, error))
                        .orElse(AlbumContent.EMPTY);
                albums.put(owner, new Entry(row.getLong(TAG_REV), content));
            } catch (IllegalArgumentException e) {
                LOGGER.warn("[DungeonTrain] Skipping album with bad owner id {}", key);
            }
        }
        return new AlbumWorldCache(albums);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        CompoundTag all = new CompoundTag();
        albums.forEach((owner, entry) -> AlbumContent.CODEC.encodeStart(ops, entry.content())
                .resultOrPartial(error -> LOGGER.warn("[DungeonTrain] Album of {} not saved: {}", owner, error))
                .ifPresent(content -> {
                    CompoundTag row = new CompoundTag();
                    row.putLong(TAG_REV, entry.rev());
                    row.put(TAG_CONTENT, content);
                    all.put(owner.toString(), row);
                }));
        tag.put(TAG_ALBUMS, all);
        return tag;
    }
}
