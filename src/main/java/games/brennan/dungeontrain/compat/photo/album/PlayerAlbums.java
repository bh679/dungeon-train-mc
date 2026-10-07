package games.brennan.dungeontrain.compat.photo.album;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.compat.photo.PhotoPngCodec;
import games.brennan.dungeontrain.compat.photo.SharedPhotos;
import games.brennan.dungeontrain.event.SharedBookGate;
import games.brennan.dungeontrain.event.StartingBookEvents;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import games.brennan.dungeontrain.registry.ModItems;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.inventory.AlbumMenu;
import io.github.mortuusars.exposure.world.item.component.album.AlbumContent;
import io.github.mortuusars.exposure.world.item.component.album.AlbumPage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player's photo album ("Your Album"). Every copy of it — found on the train, crafted, held in two
 * hands — is a window onto the same album, refilled from {@link AlbumStore} the moment it opens.
 *
 * <p>A player has two: the {@link AlbumKind#LIVE} album, carried between computers by the relay and
 * found by other players, and the {@link AlbumKind#FREE_PLAY} album, which never leaves this
 * computer. The run decides which one a copy opens onto ({@link RunIntegrity#isCheated}).</p>
 *
 * <ul>
 *   <li>Opening it to look costs nothing.</li>
 *   <li>Putting a photo in it burns it on the spot: the album, photo and all, drifts off down the
 *       train, to be found again later.</li>
 *   <li>Taking a photo out or changing a note is saved when it closes.</li>
 *   <li>It cannot be signed; it always carries its owner's name.</li>
 * </ul>
 *
 * <p>The live album uploads only with the player's sharing consent, and a page only goes to the relay
 * when its picture may be seen by others ({@link #mayShare}); anyone else's picture stays a private
 * page — an empty slot to other players.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlayerAlbums {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String IMAGE_PATH = "/albums/image";
    static final String SAVE_PATH = "/albums/save";
    static final String MINE_PATH = "/albums/mine";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(REQUEST_TIMEOUT).build();

    /** An album to burn at the end of this tick: a photo was just put in it. */
    private record PendingBurn(UUID player, int slot) {}

    /** Server thread only. */
    private static List<PendingBurn> pendingBurns = List.of();
    /** Pictures this server has already sent to the relay (by hash). */
    private static final Set<String> sentImages = ConcurrentHashMap.newKeySet();

    private PlayerAlbums() {}

    /** Which of the player's two albums this run shows. */
    static AlbumKind kindFor(ServerPlayer player) {
        return AlbumKind.forRun(RunIntegrity.isCheated(player));
    }

    // ---- opening --------------------------------------------------------------

    /**
     * Called (from {@code AlbumItemOpenMixin}) just before Exposure opens an album at inventory
     * {@code slot}. Fills an owned album's pages from the store and syncs them to the client before
     * the menu is built. Returns false when the open must not go ahead — the album was someone
     * else's and has become a read-only copy instead.
     */
    public static boolean beforeOpen(ServerPlayer player, ItemStack stack, int slot) {
        Optional<AlbumOwnership.Owner> owner = AlbumOwnership.owner(stack);
        if (owner.isEmpty()) {
            if (!isBlank(stack)) return true;           // an album from before albums were personal: left as it was
            AlbumOwnership.stamp(stack, player.getUUID(), player.getGameProfile().getName());
            owner = AlbumOwnership.owner(stack);
        }
        if (!owner.get().uuid().equals(player.getUUID())) {
            FoundAlbums.openAsFound(player, stack, slot, owner.get());
            return false;
        }
        AlbumTimings.time("open album of " + player.getGameProfile().getName(),
                () -> stack.set(Exposure.DataComponents.ALBUM_CONTENT, currentContent(player)));
        // The menu reads the pages off the client's copy of the stack — send it first.
        player.containerMenu.broadcastChanges();
        return true;
    }

    private static AlbumContent currentContent(ServerPlayer player) {
        AlbumStore store = AlbumStore.get();
        return AlbumWorldBridge.toContent(player.server, store, store.entry(player.getUUID(), kindFor(player)));
    }

    private static boolean isBlank(ItemStack stack) {
        AlbumContent content = stack.get(Exposure.DataComponents.ALBUM_CONTENT);
        return content == null || content.pages().stream().allMatch(AlbumPage::isEmpty);
    }

    // ---- editing ---------------------------------------------------------------

    /** Called (from {@code AlbumMenuMixin}, server side) when a photograph lands on a page of the album at {@code slot}. */
    public static void onPhotoPlaced(ServerPlayer player, int slot) {
        if (!AlbumOwnership.isOwnedBy(player.getInventory().getItem(slot), player.getUUID())) return;
        if (pendingBurns.stream().anyMatch(b -> b.player().equals(player.getUUID()))) return;
        List<PendingBurn> next = new ArrayList<>(pendingBurns);
        next.add(new PendingBurn(player.getUUID(), slot));
        pendingBurns = List.copyOf(next);
    }

    private static void tickBurns(MinecraftServer server) {
        if (pendingBurns.isEmpty()) return;
        List<PendingBurn> burns = pendingBurns;
        pendingBurns = List.of();
        for (PendingBurn burn : burns) {
            ServerPlayer player = server.getPlayerList().getPlayer(burn.player());
            if (player == null) continue;
            ItemStack album = player.getInventory().getItem(burn.slot());
            if (!AlbumOwnership.isOwnedBy(album, player.getUUID())) continue;
            save(player, album.getOrDefault(Exposure.DataComponents.ALBUM_CONTENT, AlbumContent.EMPTY));
            // Out of the inventory before the menu closes, so the close handler finds nothing left to save.
            player.getInventory().setItem(burn.slot(), ItemStack.EMPTY);
            player.closeContainer();
            StartingBookEvents.dropAndBurn(player, album);
            player.sendSystemMessage(Component.translatable("chat.dungeontrain.album_drifts_off").withStyle(ChatFormatting.GRAY));
        }
    }

    /** Closing a player's own album after taking a photo out or changing a note: keep what it shows now. */
    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getContainer() instanceof AlbumMenu menu)) return;
        ItemStack album = player.getInventory().getItem(menu.getAlbumSlot());
        if (!AlbumOwnership.isOwnedBy(album, player.getUUID())) return;
        AlbumContent edited = album.getOrDefault(Exposure.DataComponents.ALBUM_CONTENT, AlbumContent.EMPTY);
        AlbumStore.Entry stored = AlbumStore.get().entry(player.getUUID(), kindFor(player));
        if (!pageKeys(edited).equals(AlbumStore.pageKeys(stored))) save(player, edited);
    }

    /** What an album shows, page by page ({@link AlbumStore#pageKey}). Stacks don't compare by value. */
    static List<String> pageKeys(AlbumContent content) {
        List<String> out = new ArrayList<>();
        for (AlbumPage page : content.removeTrailingPages().pages()) {
            ItemStack photo = page.photograph();
            out.add(AlbumStore.pageKey(AlbumPageImages.knownHash(photo).orElse(null), AlbumPageImages.exposureId(photo), page.note()));
        }
        return out;
    }

    // ---- getting hold of it ----------------------------------------------------

    /** A crafted album is its crafter's album from the start. */
    @SubscribeEvent
    public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack crafted = event.getCrafting();
        if (crafted.is(Exposure.Items.ALBUM.get()) && AlbumOwnership.owner(crafted).isEmpty() && isBlank(crafted)) {
            AlbumOwnership.stamp(crafted, player.getUUID(), player.getGameProfile().getName());
        }
    }

    /**
     * Taken in hand: the train's "your album" placeholder becomes the holder's album, a blank album
     * becomes theirs, another player's album becomes a read-only found album, and a "random player
     * album" placeholder that reached a hand some other way is rolled.
     */
    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        EquipmentSlot slot = event.getSlot();
        if (slot != EquipmentSlot.MAINHAND && slot != EquipmentSlot.OFFHAND) return;
        ItemStack held = event.getTo();
        if (held.is(ModItems.YOUR_PHOTOALBUM.get())) {
            player.setItemSlot(slot, newAlbum(player));
            return;
        }
        // Normally rolled in the chest; a placeholder from the creative tab or /give is resolved
        // here, and stays a placeholder while none is available.
        if (held.is(ModItems.RANDOM_PLAYERPHOTOALBUM.get())) {
            ItemStack found = FoundAlbums.rollFound(player.getRandom().nextLong());
            if (!found.isEmpty()) player.setItemSlot(slot, found);
            return;
        }
        if (!held.is(Exposure.Items.ALBUM.get())) return;
        Optional<AlbumOwnership.Owner> owner = AlbumOwnership.owner(held);
        if (owner.isEmpty()) {
            if (isBlank(held)) AlbumOwnership.stamp(held, player.getUUID(), player.getGameProfile().getName());
        } else if (!owner.get().uuid().equals(player.getUUID())) {
            player.setItemSlot(slot, FoundAlbums.readOnlyCopy(held, owner.get()));
        }
    }

    /** A fresh copy of {@code player}'s album for this run. */
    public static ItemStack newAlbum(ServerPlayer player) {
        ItemStack album = new ItemStack(Exposure.Items.ALBUM.get());
        AlbumOwnership.stamp(album, player.getUUID(), player.getGameProfile().getName());
        AlbumTimings.time("new album for " + player.getGameProfile().getName(),
                () -> album.set(Exposure.DataComponents.ALBUM_CONTENT, currentContent(player)));
        return album;
    }

    // ---- saving ----------------------------------------------------------------

    /**
     * Keep {@code content} as the player's album for this run — in the store at once, its new
     * pictures written in the background — and, for the live album, send it on when they share.
     */
    static void save(ServerPlayer player, AlbumContent content) {
        AlbumTimings.time("save album of " + player.getGameProfile().getName(), () -> saveNow(player, content));
    }

    private static void saveNow(ServerPlayer player, AlbumContent content) {
        MinecraftServer server = player.server;
        AlbumStore store = AlbumStore.get();
        AlbumKind kind = kindFor(player);
        UUID owner = player.getUUID();
        String name = player.getGameProfile().getName();
        AlbumWorldBridge.Snapshot snapshot = AlbumWorldBridge.fromContent(server, store, content, name);
        long rev = AlbumSavePayload.nextRev(store.entry(owner, kind).rev(), System.currentTimeMillis());
        store.put(owner, kind, new AlbumStore.Entry(rev,
                snapshot.pages().stream().map(AlbumWorldBridge.SavedPage::page).toList()));

        boolean upload = kind == AlbumKind.LIVE && SharedBookGate.canContribute(player);
        // What the relay may show: a private page keeps its place and note, not its picture.
        List<AlbumSavePayload.Page> relayPages = snapshot.pages().stream()
                .map(p -> new AlbumSavePayload.Page(p.shareable() ? p.page().hash() : null, p.page().note()))
                .toList();
        Map<String, AlbumPageImages.Picture> pictures = snapshot.newPictures();
        CompletableFuture
                .supplyAsync(() -> encode(pictures))
                .whenComplete((pngs, error) -> server.execute(() -> {
                    if (error != null) {
                        LOGGER.warn("[DungeonTrain] Album pictures of {} not encoded: {}", name, error.toString());
                        return;
                    }
                    store.putImages(pngs);
                    if (upload) upload(owner, name, rev, relayPages, store);
                }));
    }

    /** Off the server thread: each new picture as a PNG. A picture that cannot be encoded is skipped. */
    private static Map<String, byte[]> encode(Map<String, AlbumPageImages.Picture> pictures) {
        Map<String, byte[]> pngs = new HashMap<>();
        pictures.forEach((hash, picture) -> {
            try {
                pngs.put(hash, picture.png());
            } catch (java.io.IOException e) {
                LOGGER.warn("[DungeonTrain] Album picture {} could not be encoded: {}", hash, e.toString());
            }
        });
        return pngs;
    }

    /** Server thread: send the pictures the relay has not had from this server, then the album. */
    private static void upload(UUID owner, String name, long rev, List<AlbumSavePayload.Page> pages, AlbumStore store) {
        for (AlbumSavePayload.Page page : pages) {
            if (page.hash() == null || sentImages.contains(page.hash())) continue;
            store.image(page.hash()).ifPresent(png -> {
                sentImages.add(page.hash());
                RelayOutbox.get().enqueue(IMAGE_PATH,
                        AlbumSavePayload.image(owner, Base64.getEncoder().encodeToString(png)).toString());
            });
        }
        RelayOutbox.get().enqueue(SAVE_PATH, AlbumSavePayload.save(owner, name, rev, pages).toString());
    }

    /** Whether others may see this picture in the owner's album: their own photo, or one already public. */
    static boolean mayShare(ItemStack photograph, String ownerName) {
        if (AlbumPageImages.knownHash(photograph).isPresent()) return true;   // came from the relay already
        Frame frame = photograph.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        if (frame == null || frame.isProjected()) return false;
        if (frame.extraData().getInt(SharedPhotos.SHARED_ID_KEY) > 0) return true;
        return frame.photographer().isPlayer() && ownerName.equals(frame.photographer().name());
    }

    // ---- loading from the relay --------------------------------------------------

    /** On joining, pull the player's live album from the relay when it is newer than this computer's copy. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID owner = player.getUUID();
        prewarm(AlbumStore.get().entry(owner, kindFor(player)));
        if (!SharedBookGate.canContribute(player)) return;
        MinecraftServer server = player.server;
        long known = AlbumStore.get().entry(owner, AlbumKind.LIVE).rev();
        String base = DungeonTrain.relayBaseUrl();
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + MINE_PATH + "?uuid=" + AlbumSavePayload.bare(owner)))
                .timeout(REQUEST_TIMEOUT).header("Accept", "application/json").GET().build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApplyAsync(response -> {
                    if (response.statusCode() != 200) throw new IllegalStateException("mine answered " + response.statusCode());
                    JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                    return AlbumSavePayload.parse(body.get("album"))
                            .filter(album -> album.rev() > known)
                            .map(album -> new Downloaded(album, downloadPngs(base, album.pages())));
                })
                .whenComplete((downloaded, error) -> {
                    if (error != null) {
                        LOGGER.debug("[DungeonTrain] Album of {} not fetched: {}", owner, error.toString());
                        return;
                    }
                    downloaded.ifPresent(d -> server.execute(() -> adopt(d)));
                });
    }

    private record Downloaded(AlbumSavePayload.Album album, Map<String, byte[]> pngs) {}

    /** Server thread: the relay's live album is newer — make it this computer's. */
    private static void adopt(Downloaded downloaded) {
        AlbumStore store = AlbumStore.get();
        UUID owner = downloaded.album().owner();
        AlbumStore.Entry before = store.entry(owner, AlbumKind.LIVE);
        if (downloaded.album().rev() <= before.rev()) return;   // edited here while it was on its way
        List<AlbumStore.Page> pages = new ArrayList<>();
        for (AlbumSavePayload.Page page : downloaded.album().pages()) {
            boolean held = page.hash() != null && (downloaded.pngs().containsKey(page.hash()) || store.hasImage(page.hash()));
            pages.add(new AlbumStore.Page(held ? page.hash() : null, page.note(), held ? photoFor(before, page.hash()) : null));
            if (held) sentImages.add(page.hash());
        }
        AlbumStore.Entry adopted = new AlbumStore.Entry(downloaded.album().rev(), pages);
        store.put(owner, AlbumKind.LIVE, adopted);
        store.putImages(downloaded.pngs());
        prewarm(adopted);
    }

    /** Decode an album's pictures in the background, so lending them later costs the tick nothing. */
    private static void prewarm(AlbumStore.Entry entry) {
        AlbumStore store = AlbumStore.get();
        AlbumPictureCache.get().prewarm(entry.pages().stream().map(AlbumStore.Page::hash).toList(), store::image);
    }

    /** The photograph this computer already kept for a picture, so its photographer and details survive. */
    private static String photoFor(AlbumStore.Entry entry, String hash) {
        return entry.pages().stream().filter(p -> hash.equals(p.hash())).map(AlbumStore.Page::photo)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    /** Off-thread: fetch each picture an album names, as the PNG the relay holds. A picture that cannot be fetched is left out. */
    static Map<String, byte[]> downloadPngs(String base, List<AlbumSavePayload.Page> pages) {
        Map<String, byte[]> pngs = new HashMap<>();
        for (AlbumSavePayload.Page page : pages) {
            if (page.hash() == null || pngs.containsKey(page.hash())) continue;
            try {
                HttpRequest image = HttpRequest.newBuilder(URI.create(base + IMAGE_PATH + "/" + page.hash()))
                        .timeout(REQUEST_TIMEOUT).GET().build();
                HttpResponse<byte[]> bytes = HTTP.send(image, HttpResponse.BodyHandlers.ofByteArray());
                if (bytes.statusCode() == 200) pngs.put(page.hash(), bytes.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.debug("[DungeonTrain] Album picture {} not fetched: {}", page.hash(), e.toString());
            }
        }
        return pngs;
    }

    /** Off-thread: {@link #downloadPngs}, decoded — for found albums, which never touch the store. */
    static Map<String, PhotoPngCodec.Decoded> download(String base, List<AlbumSavePayload.Page> pages) {
        Map<String, PhotoPngCodec.Decoded> images = new HashMap<>();
        downloadPngs(base, pages).forEach((hash, png) -> {
            try {
                images.put(hash, PhotoPngCodec.decode(png));
            } catch (java.io.IOException e) {
                LOGGER.debug("[DungeonTrain] Album picture {} unreadable: {}", hash, e.toString());
            }
        });
        return images;
    }

    // ---- lifecycle -----------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tickBurns(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        pendingBurns = List.of();
        sentImages.clear();
        AlbumWorldBridge.forgetLent();
        AlbumStore.get().flush();
    }
}
