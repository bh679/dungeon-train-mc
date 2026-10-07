package games.brennan.dungeontrain.compat.photo.album;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.photo.PhotoPngCodec;
import games.brennan.dungeontrain.event.SharedBookGate;
import games.brennan.dungeontrain.event.StartingBookEvents;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.world.item.component.album.AlbumContent;
import io.github.mortuusars.exposure.world.item.component.album.AlbumPage;
import io.github.mortuusars.exposure.world.item.component.album.SignedAlbumContent;
import io.github.mortuusars.exposure.world.item.component.album.SignedAlbumPage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LecternBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Someone else's album, found on the train ({@code dungeontrain:random_playerphotoalbum}): a
 * read-only Exposure signed album showing its owner's album as the relay last had it. It can be
 * looked through once — when it closes, it burns. The relay never expires an album; each opening is
 * only counted.
 *
 * <p>The pool works like the community photo pool ({@code SharedPhotos}): a few albums fetched and
 * decoded off the server thread, each handed out once, never one belonging to a player on this server.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class FoundAlbums {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String POOL_PATH = "/albums/pool";
    static final String VIEW_PATH = "/albums/view";

    private static final int REFRESH_PERIOD_TICKS = 600;
    private static final int FIRST_REFRESH_DELAY_TICKS = 140;
    private static final int POOL_FETCH_LIMIT = 3;
    private static final int POOL_MAX = 6;
    private static final int SPENT_MAX = 100;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(REQUEST_TIMEOUT).build();

    /** An album from the relay with its pictures decoded. */
    private record PoolAlbum(AlbumSavePayload.Album album, Map<String, PhotoPngCodec.Decoded> images) {}

    private static volatile List<PoolAlbum> pool = List.of();
    /** Owners whose album was already handed out here, oldest first. Guarded by its own monitor. */
    private static final List<UUID> spent = new ArrayList<>();
    private static final AtomicBoolean fetchInFlight = new AtomicBoolean();
    private static int ticksUntilRefresh = FIRST_REFRESH_DELAY_TICKS;

    private FoundAlbums() {}

    // ---- handing out -----------------------------------------------------------

    /** Someone else's album as a found album, or {@link ItemStack#EMPTY} when none is available. */
    public static ItemStack rollFound(long seed) {
        List<PoolAlbum> albums = pool;
        if (albums.isEmpty() || !SharedBookGate.canDiscover()) return ItemStack.EMPTY;
        PoolAlbum picked = albums.get((int) Math.floorMod(seed, (long) albums.size()));
        try {
            List<SignedAlbumPage> pages = new ArrayList<>();
            for (AlbumSavePayload.Page page : picked.album().pages()) {
                PhotoPngCodec.Decoded image = page.hash() == null ? null : picked.images().get(page.hash());
                ItemStack photo = image == null ? ItemStack.EMPTY : AlbumPageImages.photograph(page.hash(), image, null);
                pages.add(new SignedAlbumPage(photo, Component.literal(page.note())));
            }
            markSpent(picked.album().owner());
            return signed(picked.album().owner(), picked.album().name(), pages);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Could not place found album of {}: {}", picked.album().name(), e.toString());
            return ItemStack.EMPTY;
        }
    }

    /** A read-only copy of another player's album, from the pages its stack last showed. */
    static ItemStack readOnlyCopy(ItemStack album, AlbumOwnership.Owner owner) {
        AlbumContent content = album.getOrDefault(Exposure.DataComponents.ALBUM_CONTENT, AlbumContent.EMPTY).removeTrailingPages();
        List<SignedAlbumPage> pages = content.pages().stream()
                .map(page -> new SignedAlbumPage(page.photograph().copy(), Component.literal(page.note())))
                .toList();
        return signed(owner.uuid(), owner.name(), pages);
    }

    private static ItemStack signed(UUID owner, String name, List<SignedAlbumPage> pages) {
        ItemStack stack = new ItemStack(Exposure.Items.SIGNED_ALBUM.get());
        stack.set(Exposure.DataComponents.SIGNED_ALBUM_CONTENT, new SignedAlbumContent(name, name, pages));
        stack.set(DataComponents.CUSTOM_NAME, AlbumOwnership.albumName(name));
        AlbumOwnership.markFound(stack, owner);
        return stack;
    }

    /** Another player opened an album that is not theirs: swap in the read-only copy and open that instead. */
    static void openAsFound(ServerPlayer player, ItemStack album, int slot, AlbumOwnership.Owner owner) {
        ItemStack copy = readOnlyCopy(album, owner);
        player.getInventory().setItem(slot, copy);
        InteractionHand hand = slot == 40 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        player.containerMenu.broadcastChanges();
        copy.getItem().use(player.level(), player, hand);
    }

    /** A found album closed: it burns, and its owner's album is counted as seen once more. */
    static void onClosed(ServerPlayer player, int slot) {
        ItemStack album = player.getInventory().getItem(slot);
        AlbumOwnership.foundOwner(album).ifPresent(owner -> {
            player.getInventory().setItem(slot, ItemStack.EMPTY);
            StartingBookEvents.dropAndBurn(player, album);
            JsonObject body = new JsonObject();
            body.addProperty("uuid", AlbumSavePayload.bare(owner));
            RelayOutbox.get().enqueue(VIEW_PATH, body.toString());
            player.sendSystemMessage(Component.translatable("chat.dungeontrain.found_album_burns").withStyle(ChatFormatting.GRAY));
        });
    }

    /** A found album cannot go on a lectern: it would be read there for ever without burning. */
    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        if (AlbumOwnership.foundOwner(event.getItemStack()).isEmpty()) return;
        if (event.getLevel().getBlockState(event.getPos()).getBlock() instanceof LecternBlock) {
            event.setCanceled(true);
        }
    }

    // ---- pool ------------------------------------------------------------------

    private static void markSpent(UUID owner) {
        synchronized (spent) {
            spent.remove(owner);
            spent.add(owner);
            if (spent.size() > SPENT_MAX) spent.remove(0);
        }
        pool = pool.stream().filter(a -> !a.album().owner().equals(owner)).toList();
    }

    private static List<UUID> spentOwners() {
        synchronized (spent) { return List.copyOf(spent); }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (--ticksUntilRefresh > 0) return;
        ticksUntilRefresh = REFRESH_PERIOD_TICKS;
        if (SharedBookGate.canDiscover() && pool.size() < POOL_MAX) refreshPool(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        pool = List.of();
        synchronized (spent) { spent.clear(); }
        ticksUntilRefresh = FIRST_REFRESH_DELAY_TICKS;
    }

    private static void refreshPool(MinecraftServer server) {
        if (!fetchInFlight.compareAndSet(false, true)) return;
        String base = DungeonTrain.relayBaseUrl();
        StringBuilder url = new StringBuilder(base).append(POOL_PATH).append("?limit=").append(POOL_FETCH_LIMIT);
        String uuids = server.getPlayerList().getPlayers().stream()
                .map(p -> AlbumSavePayload.bare(p.getUUID())).collect(Collectors.joining(","));
        if (!uuids.isEmpty()) url.append("&uuids=").append(uuids);
        String exclude = Stream.concat(pool.stream().map(a -> a.album().owner()), spentOwners().stream())
                .map(AlbumSavePayload::bare).distinct().collect(Collectors.joining(","));
        if (!exclude.isEmpty()) url.append("&exclude=").append(exclude);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(REQUEST_TIMEOUT).header("Accept", "application/json").GET().build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApplyAsync(response -> fetch(base, response))
                .whenComplete((fetched, error) -> {
                    fetchInFlight.set(false);
                    if (error != null) {
                        LOGGER.debug("[DungeonTrain] Found-album pool refresh failed: {}", error.toString());
                        return;
                    }
                    server.execute(() -> pool = merge(pool, fetched, spentOwners()));
                });
    }

    /** Off-thread: parse the pool answer and download each album's pictures. */
    private static List<PoolAlbum> fetch(String base, HttpResponse<String> response) {
        if (response.statusCode() != 200) throw new IllegalStateException("album pool answered " + response.statusCode());
        JsonArray albums = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("albums");
        List<PoolAlbum> out = new ArrayList<>();
        for (int i = 0; albums != null && i < albums.size(); i++) {
            AlbumSavePayload.parse(albums.get(i)).ifPresent(album -> {
                Map<String, PhotoPngCodec.Decoded> images = PlayerAlbums.download(base, album.pages());
                if (!images.isEmpty()) out.add(new PoolAlbum(album, images));
            });
        }
        return out;
    }

    private static List<PoolAlbum> merge(List<PoolAlbum> held, List<PoolAlbum> fetched, List<UUID> spentOwners) {
        Set<UUID> fresh = fetched.stream().map(a -> a.album().owner()).collect(Collectors.toSet());
        List<PoolAlbum> merged = Stream.concat(held.stream().filter(a -> !fresh.contains(a.album().owner())), fetched.stream())
                .filter(a -> !spentOwners.contains(a.album().owner())).toList();
        return List.copyOf(merged.subList(Math.max(0, merged.size() - POOL_MAX), merged.size()));
    }
}
