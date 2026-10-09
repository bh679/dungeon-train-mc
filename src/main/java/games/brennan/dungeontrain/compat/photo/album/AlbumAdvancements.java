package games.brennan.dungeontrain.compat.photo.album;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.EnchiridionAdvancements;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import io.github.mortuusars.exposure.world.item.component.album.AlbumContent;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The album advancements. Keepsake (a photo in your album) and No Room Left (every page holds one) are
 * checked each time your album is saved ({@link PlayerAlbums#save}), the one place every edit lands — placing
 * a photo, and closing the album after changing it. Memory Lane is finding <em>someone else's</em> album: a
 * found album ({@link AlbumOwnership#foundOwner}) whose owner is not you, spotted by an inventory check every
 * {@link #SCAN_INTERVAL_TICKS} ticks until it is earned. The owner is a per-album UUID, which no
 * {@code inventory_changed} item predicate can match.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class AlbumAdvancements {

    private static final int SCAN_INTERVAL_TICKS = 20;
    private static final ResourceLocation MEMORY_LANE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "enchiridion/memory_lane");

    /** Pages in an album, so a photo on every one is a full album. */
    public static final int FULL = AlbumSavePayload.MAX_PAGES;

    private AlbumAdvancements() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % SCAN_INTERVAL_TICKS != 0 || player.isSpectator()) return;
        AdvancementHolder memoryLane = player.server.getAdvancements().get(MEMORY_LANE);
        if (memoryLane == null || player.getAdvancements().getOrStartProgress(memoryLane).isDone()) return;
        for (ItemStack stack : player.getInventory().items) {
            if (isSomeoneElsesAlbum(AlbumOwnership.foundOwner(stack), player.getUUID())) {
                ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, EnchiridionAdvancements.FOUND_ALBUM);
                return;
            }
        }
    }

    /** A found album, and not one of your own. Pure; package-private for tests. */
    static boolean isSomeoneElsesAlbum(Optional<UUID> foundOwner, UUID player) {
        return foundOwner.isPresent() && !foundOwner.get().equals(player);
    }

    /** Award what {@code content} has earned. Re-firing an earned advancement is a no-op. */
    static void onSaved(ServerPlayer player, AlbumContent content) {
        for (String action : actionsFor(photos(content))) {
            ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, action);
        }
    }

    /** Pages holding a photograph. A page with only a note doesn't count. */
    static int photos(AlbumContent content) {
        return (int) content.pages().stream().filter(p -> !p.photograph().isEmpty()).count();
    }

    /** The {@code gameplay_action} ids an album holding {@code photos} photographs fires. Pure; package-private for tests. */
    static List<String> actionsFor(int photos) {
        if (photos >= FULL) return List.of(EnchiridionAdvancements.ALBUM_PHOTO, EnchiridionAdvancements.ALBUM_FULL);
        if (photos > 0) return List.of(EnchiridionAdvancements.ALBUM_PHOTO);
        return List.of();
    }
}
