package games.brennan.dungeontrain.compat.photo.album;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.photo.PhotoPngCodec;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.ExposureServer;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.item.component.StackedPhotographs;
import io.github.mortuusars.exposure.world.level.storage.ExposureData;
import io.github.mortuusars.exposure.world.level.storage.ExposureIdentifier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Photographs in an Ender Chest keep their pictures from one run to the next.
 *
 * <p>EnderChestPersistence carries the chest into every new world, but an Exposure photograph only
 * names a picture in its <em>old</em> world's store ({@code data/exposures/<id>.dat}), so in the new
 * world it showed blank paper. Same cure as albums ({@link AlbumWorldBridge}): before ECP writes the
 * chest, each photo's picture is kept outside any world ({@link StashPhotoStore}) and the photo is
 * re-pointed at {@link AlbumImageHash#exposureId}; after ECP restores the chest, those pictures are
 * lent to the new world. Photographer and capture details on the stack are kept — only the picture's
 * name changes.</p>
 *
 * <p>Photos stashed before this existed are recovered from any other world in {@code saves/} that
 * still holds their picture (singleplayer only). A photo whose picture is nowhere stays as it is.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class StashPhotos {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Exposure ids already looked for in other worlds — {@code saves/} is listed once per id per
     * world. Not a "missing" list: a fresh photo's picture can still be on its way up from the
     * client, so reading this world's store is always tried again.
     */
    private static final Set<String> searched = ConcurrentHashMap.newKeySet();

    private StashPhotos() {}

    // ---- lifecycle ------------------------------------------------------------------

    /** After ECP restores the chest (it runs at NORMAL): lend its pictures to this world. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) settle(player);
    }

    /** Covers ECP swapping in another slot's chest mid-session (game mode, Free Play, difficulty). */
    @SubscribeEvent
    public static void onOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayer player && isEnderChest(player, event.getContainer())) settle(player);
    }

    @SubscribeEvent
    public static void onClose(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer player && isEnderChest(player, event.getContainer())) settle(player);
    }

    /** Before ECP checkpoints the chest (autosave, /save-all, logout save). */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onSave(PlayerEvent.SaveToFile event) {
        if (event.getEntity() instanceof ServerPlayer player) settle(player);
    }

    /** Before ECP's logout save. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) settle(player);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        searched.clear();
    }

    private static boolean isEnderChest(ServerPlayer player, Object menu) {
        return menu instanceof ChestMenu chest && chest.getContainer() == player.getEnderChestInventory();
    }

    // ---- the chest ------------------------------------------------------------------

    /** Every photo in the player's Ender Chest: kept outside the world, and shown in this one. */
    static void settle(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        PlayerEnderChestContainer chest = player.getEnderChestInventory();
        try {
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                if (stack.isEmpty()) continue;
                ItemStack settled = settleStack(server, stack);
                if (settled != stack) chest.setItem(slot, settled);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Ender Chest photos of {} not kept: {}", player.getGameProfile().getName(), e.toString());
        }
    }

    /** {@code stack} itself when nothing changed, else a re-pointed copy. */
    private static ItemStack settleStack(MinecraftServer server, ItemStack stack) {
        StackedPhotographs stacked = stack.get(Exposure.DataComponents.STACKED_PHOTOGRAPHS);
        if (stacked != null && !stacked.isEmpty()) {
            List<ItemStack> photos = stacked.photographs();
            List<ItemStack> settled = new ArrayList<>(photos.size());
            boolean changed = false;
            for (ItemStack photo : photos) {
                ItemStack s = settlePhoto(server, photo);
                changed |= s != photo;
                settled.add(s);
            }
            if (!changed) return stack;
            ItemStack copy = stack.copy();
            copy.set(Exposure.DataComponents.STACKED_PHOTOGRAPHS, new StackedPhotographs(settled));
            return copy;
        }
        return stack.has(Exposure.DataComponents.PHOTOGRAPH_FRAME) ? settlePhoto(server, stack) : stack;
    }

    private static ItemStack settlePhoto(MinecraftServer server, ItemStack photo) {
        Optional<String> known = AlbumPageImages.knownHash(photo);
        if (known.isPresent()) {
            lend(server, known.get());
            return photo;
        }
        String id = AlbumPageImages.exposureId(photo);
        if (id.isEmpty()) return photo;
        if (searched.add(id)) recover(server, id);
        Optional<AlbumPageImages.Picture> read = AlbumPageImages.read(server, photo);
        if (read.isEmpty()) return photo;
        AlbumPageImages.Picture picture = read.get();
        String hash = picture.hash();
        if (!keep(hash, picture)) return photo;
        PhotoPngCodec.Decoded decoded = new PhotoPngCodec.Decoded(picture.width(), picture.height(), picture.pixels());
        AlbumPictureCache.get().put(hash, decoded);
        if (!AlbumWorldBridge.lend(server, hash, () -> Optional.of(decoded))) return photo;
        return repointed(photo, hash);
    }

    private static boolean keep(String hash, AlbumPageImages.Picture picture) {
        StashPhotoStore store = StashPhotoStore.get();
        if (store.has(hash)) return true;
        try {
            return store.put(hash, picture.png());
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Stashed photo {} not encoded: {}", hash, e.toString());
            return false;
        }
    }

    private static ItemStack repointed(ItemStack photo, String hash) {
        ItemStack copy = photo.copy();
        Frame frame = copy.getOrDefault(Exposure.DataComponents.PHOTOGRAPH_FRAME, Frame.EMPTY);
        Frame pointed = frame.toMutable().setIdentifier(ExposureIdentifier.id(AlbumImageHash.exposureId(hash))).toImmutable();
        copy.set(Exposure.DataComponents.PHOTOGRAPH_FRAME, pointed);
        copy.set(Exposure.DataComponents.PHOTOGRAPH_TYPE, pointed.type());
        return copy;
    }

    // ---- pictures in and out of worlds ---------------------------------------------

    /** Lend a kept picture to this world: from memory, the stash store, or an album. */
    private static void lend(MinecraftServer server, String hash) {
        AlbumWorldBridge.lend(server, hash, () -> AlbumPictureCache.get().picture(hash).or(() ->
                StashPhotoStore.get().image(hash).or(() -> AlbumStore.get().image(hash)).flatMap(png -> {
                    try {
                        PhotoPngCodec.Decoded decoded = PhotoPngCodec.decode(png);
                        AlbumPictureCache.get().put(hash, decoded);
                        return Optional.of(decoded);
                    } catch (IOException e) {
                        LOGGER.warn("[DungeonTrain] Stashed photo {} unreadable: {}", hash, e.toString());
                        return Optional.empty();
                    }
                })));
    }

    /**
     * A photo stashed before pictures were kept: if this world lacks its picture, copy it in from
     * any other world on this computer that still has it. Singleplayer only — a dedicated server's
     * other worlds are not the player's.
     */
    private static void recover(MinecraftServer server, String id) {
        if (server.isDedicatedServer()) return;
        Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        if (Files.exists(exposureFile(world, id))) return;
        Path saves = world.getParent();
        if (saves == null || !Files.isDirectory(saves)) return;
        try (Stream<Path> worlds = Files.list(saves)) {
            Optional<Path> found = worlds.filter(Files::isDirectory)
                    .filter(dir -> !dir.toAbsolutePath().normalize().equals(world))
                    .map(dir -> exposureFile(dir, id))
                    .filter(Files::isRegularFile)
                    .findFirst();
            if (found.isEmpty()) return;
            CompoundTag root = NbtIo.readCompressed(found.get(), NbtAccounter.unlimitedHeap());
            ExposureData data = ExposureData.load(root.getCompound("data"), server.registryAccess());
            ExposureServer.exposureRepository().save(id, data);
            LOGGER.info("[DungeonTrain] Recovered stashed photo {} from {}", id, found.get().getParent().getParent().getParent());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Stashed photo {} not recovered: {}", id, e.toString());
        }
    }

    private static Path exposureFile(Path world, String id) {
        return world.resolve("data").resolve("exposures").resolve(id + ".dat");
    }
}
