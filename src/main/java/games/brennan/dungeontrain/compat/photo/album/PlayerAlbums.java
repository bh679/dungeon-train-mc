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
 * A player's one photo album ("Your Album"). Every copy of it — found on the train, crafted, held
 * in two hands — is a window onto the same album: its pages are refilled from {@link AlbumWorldCache}
 * the moment it opens, and the relay carries it on to the player's next world.
 *
 * <ul>
 *   <li>Opening it to look costs nothing.</li>
 *   <li>Putting a photo in it burns it on the spot: the album, photo and all, drifts off down the
 *       train, to be found again later.</li>
 *   <li>Taking a photo out or changing a note is saved when it closes.</li>
 *   <li>It cannot be signed; it always carries its owner's name.</li>
 * </ul>
 *
 * <p>Uploads follow the photo rules: never from a Free Play run, never without the player's sharing
 * consent — the album then lives in this world only. A page only goes to the relay when its picture
 * may be seen by others: the owner's own photograph, a community photo, or a picture that already
 * came from an album. Anyone else's picture stays a private page (an empty slot to other players).</p>
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

    // ---- opening --------------------------------------------------------------

    /**
     * Called (from {@code AlbumItemOpenMixin}) just before Exposure opens an album at inventory
     * {@code slot}. Fills an owned album's pages from the world's copy and syncs them to the client
     * before the menu is built. Returns false when the open must not go ahead — the album was
     * someone else's and has been turned into a read-only copy instead.
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
        refresh(player, stack);
        return true;
    }

    private static boolean isBlank(ItemStack stack) {
        AlbumContent content = stack.get(Exposure.DataComponents.ALBUM_CONTENT);
        return content == null || content.pages().stream().allMatch(AlbumPage::isEmpty);
    }

    private static void refresh(ServerPlayer player, ItemStack stack) {
        AlbumContent content = AlbumWorldCache.get(player.server).get(player.getUUID())
                .map(AlbumWorldCache.Entry::content).orElse(AlbumContent.EMPTY);
        stack.set(Exposure.DataComponents.ALBUM_CONTENT, copyOf(content));
        // The menu reads the pages off the client's copy of the stack — send it first.
        player.containerMenu.broadcastChanges();
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

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getContainer() instanceof io.github.mortuusars.exposure.world.inventory.AlbumMenu menu) {
            ItemStack album = player.getInventory().getItem(menu.getAlbumSlot());
            if (!AlbumOwnership.isOwnedBy(album, player.getUUID())) return;
            AlbumContent edited = album.getOrDefault(Exposure.DataComponents.ALBUM_CONTENT, AlbumContent.EMPTY);
            AlbumContent known = AlbumWorldCache.get(player.server).get(player.getUUID())
                    .map(AlbumWorldCache.Entry::content).orElse(AlbumContent.EMPTY);
            if (!signature(edited).equals(signature(known))) save(player, edited);
        } else if (event.getContainer() instanceof io.github.mortuusars.exposure.world.inventory.SignedAlbumMenu menu) {
            FoundAlbums.onClosed(player, menu.getAlbumSlot());
        }
    }

    /** What an album shows, page by page: which picture and which note. Stacks don't compare by value. */
    static List<String> signature(AlbumContent content) {
        List<String> out = new ArrayList<>();
        for (AlbumPage page : content.removeTrailingPages().pages()) {
            out.add(AlbumPageImages.exposureId(page.photograph()) + "|" + page.note());
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
     * becomes theirs, and another player's album becomes a read-only found album.
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
        // Normally rolled in the chest; a placeholder that reached a hand some other way (the
        // creative tab, /give) is resolved here, and stays a placeholder while none is available.
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

    /** A fresh copy of {@code player}'s album. */
    public static ItemStack newAlbum(ServerPlayer player) {
        ItemStack album = new ItemStack(Exposure.Items.ALBUM.get());
        AlbumOwnership.stamp(album, player.getUUID(), player.getGameProfile().getName());
        AlbumWorldCache.get(player.server).get(player.getUUID())
                .ifPresent(entry -> album.set(Exposure.DataComponents.ALBUM_CONTENT, copyOf(entry.content())));
        return album;
    }

    /** Pages with their own photograph stacks, so no copy of the album can change another's. */
    static AlbumContent copyOf(AlbumContent content) {
        return new AlbumContent(content.pages().stream()
                .map(page -> new AlbumPage(page.photograph().copy(), page.note())).toList());
    }

    // ---- saving ----------------------------------------------------------------

    /** Keep {@code content} as the player's album in this world, and send it on when they share. */
    static void save(ServerPlayer player, AlbumContent content) {
        AlbumContent trimmed = copyOf(content.removeTrailingPages());
        AlbumWorldCache cache = AlbumWorldCache.get(player.server);
        long rev = AlbumSavePayload.nextRev(cache.rev(player.getUUID()), System.currentTimeMillis());
        cache.put(player.getUUID(), new AlbumWorldCache.Entry(rev, trimmed));
        if (!SharedBookGate.canContribute(player) || RunIntegrity.isCheated(player)) return;
        upload(player, trimmed, rev);
    }

    /** A page's picture, as read on the server thread: its known hash, or its pixels to hash. Null for a private page. */
    private record PageSource(String knownHash, AlbumPageImages.Picture picture, String note) {}

    private static void upload(ServerPlayer player, AlbumContent content, long rev) {
        MinecraftServer server = player.server;
        UUID owner = player.getUUID();
        String name = player.getGameProfile().getName();
        List<PageSource> sources = new ArrayList<>();
        for (AlbumPage page : content.pages()) {
            ItemStack photo = page.photograph();
            Optional<String> known = AlbumPageImages.knownHash(photo);
            if (known.isPresent()) {
                sources.add(new PageSource(known.get(), null, page.note()));
            } else if (mayShare(photo, name)) {
                sources.add(new PageSource(null, AlbumPageImages.read(server, photo).orElse(null), page.note()));
            } else {
                sources.add(new PageSource(null, null, page.note()));
            }
        }
        CompletableFuture
                .supplyAsync(() -> encode(sources))
                .whenComplete((encoded, error) -> server.execute(() -> {
                    if (error != null) {
                        LOGGER.warn("[DungeonTrain] Album of {} not sent: {}", name, error.toString());
                        return;
                    }
                    encoded.images().forEach((hash, png) -> {
                        if (sentImages.add(hash)) {
                            RelayOutbox.get().enqueue(IMAGE_PATH,
                                    AlbumSavePayload.image(owner, Base64.getEncoder().encodeToString(png)).toString());
                        }
                    });
                    RelayOutbox.get().enqueue(SAVE_PATH, AlbumSavePayload.save(owner, name, rev, encoded.pages()).toString());
                }));
    }

    private record Encoded(List<AlbumSavePayload.Page> pages, Map<String, byte[]> images) {}

    /** Off the server thread: hash every picture, and encode the ones not sent before. */
    private static Encoded encode(List<PageSource> sources) {
        List<AlbumSavePayload.Page> pages = new ArrayList<>();
        Map<String, byte[]> images = new HashMap<>();
        for (PageSource source : sources) {
            String hash = source.knownHash();
            if (hash == null && source.picture() != null) {
                hash = source.picture().hash();
                if (!sentImages.contains(hash) && !images.containsKey(hash)) {
                    try {
                        images.put(hash, source.picture().png());
                    } catch (java.io.IOException e) {
                        LOGGER.warn("[DungeonTrain] Album picture could not be encoded: {}", e.toString());
                        hash = null;
                    }
                }
            }
            pages.add(new AlbumSavePayload.Page(hash, source.note()));
        }
        return new Encoded(pages, images);
    }

    /** Whether others may see this picture in the owner's album: their own photo, or one already public. */
    static boolean mayShare(ItemStack photograph, String ownerName) {
        Frame frame = photograph.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        if (frame == null || frame.isProjected()) return false;
        if (frame.extraData().getInt(SharedPhotos.SHARED_ID_KEY) > 0) return true;
        return frame.photographer().isPlayer() && ownerName.equals(frame.photographer().name());
    }

    // ---- loading from the relay --------------------------------------------------

    /** On joining, pull the player's album from the relay when it is newer than this world's copy. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !SharedBookGate.canContribute(player)) return;
        MinecraftServer server = player.server;
        UUID owner = player.getUUID();
        long known = AlbumWorldCache.get(server).rev(owner);
        String base = DungeonTrain.relayBaseUrl();
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + MINE_PATH + "?uuid=" + AlbumSavePayload.bare(owner)))
                .timeout(REQUEST_TIMEOUT).header("Accept", "application/json").GET().build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApplyAsync(response -> {
                    if (response.statusCode() != 200) throw new IllegalStateException("mine answered " + response.statusCode());
                    JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                    return AlbumSavePayload.parse(body.get("album"))
                            .filter(album -> album.rev() > known)
                            .map(album -> new Downloaded(album, download(base, album.pages())));
                })
                .whenComplete((downloaded, error) -> {
                    if (error != null) {
                        LOGGER.debug("[DungeonTrain] Album of {} not fetched: {}", owner, error.toString());
                        return;
                    }
                    downloaded.ifPresent(d -> server.execute(() -> adopt(server, d)));
                });
    }

    record Downloaded(AlbumSavePayload.Album album, Map<String, PhotoPngCodec.Decoded> images) {}

    /** Off-thread: fetch and decode each picture an album names. A picture that cannot be fetched leaves its page empty. */
    static Map<String, PhotoPngCodec.Decoded> download(String base, List<AlbumSavePayload.Page> pages) {
        Map<String, PhotoPngCodec.Decoded> images = new HashMap<>();
        for (AlbumSavePayload.Page page : pages) {
            if (page.hash() == null || images.containsKey(page.hash())) continue;
            try {
                HttpRequest image = HttpRequest.newBuilder(URI.create(base + IMAGE_PATH + "/" + page.hash()))
                        .timeout(REQUEST_TIMEOUT).GET().build();
                HttpResponse<byte[]> bytes = HTTP.send(image, HttpResponse.BodyHandlers.ofByteArray());
                if (bytes.statusCode() == 200) images.put(page.hash(), PhotoPngCodec.decode(bytes.body()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.debug("[DungeonTrain] Album picture {} not fetched: {}", page.hash(), e.toString());
            }
        }
        return images;
    }

    /** Server thread: the relay's album is newer — make it this world's. */
    private static void adopt(MinecraftServer server, Downloaded downloaded) {
        AlbumWorldCache cache = AlbumWorldCache.get(server);
        UUID owner = downloaded.album().owner();
        if (downloaded.album().rev() <= cache.rev(owner)) return;   // edited here while it was on its way
        List<AlbumPage> before = cache.get(owner).map(e -> e.content().pages()).orElse(List.of());
        List<AlbumPage> pages = new ArrayList<>();
        for (AlbumSavePayload.Page page : downloaded.album().pages()) {
            PhotoPngCodec.Decoded image = page.hash() == null ? null : downloaded.images().get(page.hash());
            ItemStack photo = image == null ? ItemStack.EMPTY
                    : AlbumPageImages.photograph(page.hash(), image, templateFor(before, page.hash()));
            if (page.hash() != null) sentImages.add(page.hash());
            pages.add(new AlbumPage(photo, page.note()));
        }
        cache.put(owner, new AlbumWorldCache.Entry(downloaded.album().rev(), new AlbumContent(pages)));
    }

    private static ItemStack templateFor(List<AlbumPage> pages, String hash) {
        String id = AlbumImageHash.exposureId(hash);
        return pages.stream().map(AlbumPage::photograph).filter(p -> id.equals(AlbumPageImages.exposureId(p)))
                .findFirst().orElse(ItemStack.EMPTY);
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
    }
}
