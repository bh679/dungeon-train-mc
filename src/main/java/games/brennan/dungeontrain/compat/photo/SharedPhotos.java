package games.brennan.dungeontrain.compat.photo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.GlobalTributeStats;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.discord.PhotoPaperComposite;
import games.brennan.dungeontrain.discord.PhotoUpscale;
import games.brennan.dungeontrain.discord.RunPosition;
import games.brennan.dungeontrain.discord.TributePhotoReporter;
import games.brennan.dungeontrain.event.SharedBookGate;
import games.brennan.dungeontrain.event.StartingBookEvents;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.registry.ModItems;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import io.github.mortuusars.exposure.Exposure;
import io.github.mortuusars.exposure.ExposureServer;
import io.github.mortuusars.exposure.data.ColorPalettes;
import io.github.mortuusars.exposure.world.camera.frame.Frame;
import io.github.mortuusars.exposure.world.level.storage.ExposureData;
import io.github.mortuusars.exposure.world.level.storage.ExposureIdentifier;
import io.github.mortuusars.exposure.world.level.storage.ExposureRepository;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Community photos — the picture counterpart of shared books. A disposable-camera print is uploaded
 * to the relay, losslessly, when it is printed; photos from other players are kept in a small
 * pre-decoded pool and handed out as ordinary Exposure photographs when a container rolls
 * {@code dungeontrain:random_playerphoto}.
 *
 * <p>On the relay a photo lives by attention: every open uses one of its views, and a player can
 * pay Tribute — emeralds — to refill them. This class reports the opens, takes the emeralds, and
 * sends a photo back when the relay says a tributed photo had already been removed.</p>
 *
 * <p>All image work is off the server thread: encoding runs while the print animation plays,
 * decoding when the pool refreshes. A container roll only copies an already-decoded image into
 * Exposure's own store, under an id of this world, so Exposure renders it like any local photo.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SharedPhotos {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Frame extra-data key carrying the relay id of a found photo. */
    public static final String SHARED_ID_KEY = "dt_shared_photo_id";
    /** Frame extra-data key carrying the photographer's name on a found photo. */
    public static final String SHARED_AUTHOR_KEY = "dt_shared_photo_author";
    /** Frame extra-data key naming who last paid Tribute, on a photographer's own returning photo. */
    public static final String SHARED_TRIBUTED_BY_KEY = "dt_shared_photo_tributed_by";

    /** Frame extra-data key carrying how many times a found photo has been tributed, as far as this copy knows. */
    public static final String SHARED_TRIBUTES_KEY = "dt_shared_photo_tributes";

    /** Frame extra-data key carrying how many views a found photo had left when it was handed out. */
    public static final String SHARED_VIEWS_LEFT_KEY = "dt_shared_photo_views_left";

    /** Frame extra-data key carrying how many people had opened a found photo when it was handed out. */
    public static final String SHARED_VIEWS_KEY = "dt_shared_photo_views";

    /** Views a photo starts with, and goes back to after a Tribute (the relay's PLAYER_PHOTOS_VIEWS). */
    public static final int VIEWS_MAX = 10;

    /** Emeralds the first Tribute to a photo costs; each Tribute it has had adds one. */
    public static final int TRIBUTE_BASE_COST = 1;

    static final String SUBMIT_PATH = "/photos/submit";
    static final String VIEW_PATH = "/photos/view";
    static final String TRIBUTE_PATH = "/photos/tribute";
    static final String RESTORE_PATH = "/photos/restore";

    /** Chat line families, keyed {@code <key>.1..N} in the lang files. */
    private static final int SEND_OFF_LINES = 10;
    private static final int FAMILIAR_LINES = 5;
    private static final int FAMILIAR_TRIBUTED_LINES = 3;
    private static final int TRIBUTE_PAID_LINES = 3;

    /** The client uploads the pixels to the server separately; they can trail the print by a moment. */
    private static final int PIXEL_WAIT_TICKS = 100;
    private static final int REFRESH_PERIOD_TICKS = 600;
    private static final int FIRST_REFRESH_DELAY_TICKS = 100;
    private static final int POOL_FETCH_LIMIT = 10;
    private static final int POOL_MAX = 40;
    /** How many handed-out or opened photo ids this server remembers, so it never asks for them again. */
    private static final int SPENT_MAX = 300;
    /** Below this many approved photos on the relay, a photo slot with nothing to hand out may give a camera. */
    static final int CAMERA_FALLBACK_BELOW = 1000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    // HTTP/1.1 on purpose: over plain http the default client first asks to upgrade to HTTP/2, and the
    // relay closes that connection without answering.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(REQUEST_TIMEOUT).build();

    private record PendingUpload(UUID playerId, String author, String exposureId, JsonObject meta, int ticksLeft) {
        PendingUpload tick() { return new PendingUpload(playerId, author, exposureId, meta, ticksLeft - 1); }
    }

    /** One photo from the relay, decoded. {@code tributedBy} is set on a photographer's own tributed photo. */
    private record PoolPhoto(int id, String author, String tributedBy, int tributes, int viewsLeft, int views, PhotoPngCodec.Decoded image) {}

    /** Server thread only. */
    private static List<PendingUpload> pendingUploads = List.of();
    private static volatile List<PoolPhoto> pool = List.of();
    /** Ids already handed out or opened here, oldest first. Guarded by its own monitor. */
    private static final List<Integer> spent = new ArrayList<>();
    /** Found photos a player has already been greeted for, by relay id. Server thread only. */
    private static final Map<UUID, Set<Integer>> greeted = new HashMap<>();
    private static final AtomicBoolean fetchInFlight = new AtomicBoolean();
    private static int ticksUntilRefresh = FIRST_REFRESH_DELAY_TICKS;
    /** Approved photos the relay holds, from the last pool answer; -1 until one arrives. */
    private static volatile int relayTotal = -1;

    private SharedPhotos() {}

    private static String newKey() { return UUID.randomUUID().toString().replace("-", ""); }

    private static String bare(UUID id) { return id.toString().replace("-", ""); }

    private static Component line(String family, int count, ServerPlayer player, Object... args) {
        int n = 1 + player.getRandom().nextInt(count);
        return Component.translatable(family + "." + n, args).withStyle(ChatFormatting.GRAY);
    }

    // ---- upload ---------------------------------------------------------------

    /** Called the tick a disposable camera prints: queue the photograph for the relay if it may be shared. */
    public static void queueUpload(ServerPlayer player, ItemStack photograph) {
        if (photograph.isEmpty() || !SharedBookGate.canContribute(player)) return;
        Frame frame = photograph.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        // Projected frames are images from outside the game — never shared.
        if (frame == null || frame.isProjected() || !frame.identifier().isId()) return;
        List<PendingUpload> next = new ArrayList<>(pendingUploads);
        next.add(new PendingUpload(player.getUUID(), player.getGameProfile().getName(),
                frame.identifier().id(), buildMeta(player, frame.extraData()), PIXEL_WAIT_TICKS));
        pendingUploads = List.copyOf(next);
    }

    /** Where and when the photo was taken: the frame's own details plus the photographer's run. */
    private static JsonObject buildMeta(ServerPlayer player, CompoundTag extraData) {
        JsonObject meta = frameMeta(extraData);
        meta.addProperty("takenTs", System.currentTimeMillis());
        ModList.get().getModContainerById(DungeonTrain.MOD_ID)
                .ifPresent(mod -> meta.addProperty("version", mod.getModInfo().getVersion().toString()));
        try {
            Integer cart = TrainCarriageAppender.lastCarriageIndex(player.getUUID());
            if (cart != null) meta.addProperty("cart", cart);
            meta.addProperty("runSec", player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).trainTimeTicks() / 20);
            RunPosition position = RunPosition.of(player);
            if (position.distanceTravelled() != null) meta.addProperty("distance", position.forwardMetres());
            if (position.band() != null) meta.addProperty("band", position.band());
        } catch (RuntimeException e) {
            LOGGER.debug("[DungeonTrain] Photo run details unavailable: {}", e.toString());
        }
        return meta;
    }

    static JsonObject frameMeta(CompoundTag extraData) {
        JsonObject meta = new JsonObject();
        if (extraData.contains("biome")) meta.addProperty("biome", extraData.getString("biome"));
        if (extraData.contains("dimension")) meta.addProperty("dimension", extraData.getString("dimension"));
        if (extraData.contains("day_time")) meta.addProperty("dayTime", extraData.getInt("day_time"));
        if (extraData.getBoolean("selfie")) meta.addProperty("selfie", true);
        return meta;
    }

    static JsonObject buildPayload(String uuid, String author, String key, byte[] png, JsonObject meta) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        body.addProperty("author", author == null ? "" : author);
        body.addProperty("key", key);
        body.addProperty("image", Base64.getEncoder().encodeToString(png));
        body.add("meta", meta);
        return body;
    }

    private static void tickUploads(MinecraftServer server) {
        if (pendingUploads.isEmpty()) return;
        ExposureRepository repository = ExposureServer.exposureRepository();
        List<PendingUpload> waiting = new ArrayList<>();
        for (PendingUpload upload : pendingUploads) {
            Optional<ExposureData> data = repository.load(upload.exposureId()).getData();
            if (data.isPresent()) {
                encodeThen(server, data.get(), upload.exposureId(), png -> {
                    RelayOutbox.get().enqueue(SUBMIT_PATH,
                            buildPayload(bare(upload.playerId()), upload.author(), newKey(), png, upload.meta()).toString());
                    ServerPlayer player = server.getPlayerList().getPlayer(upload.playerId());
                    if (player != null) player.sendSystemMessage(line("chat.dungeontrain.shared_photo", SEND_OFF_LINES, player));
                });
            } else if (upload.ticksLeft() > 1) {
                waiting.add(upload.tick());
            } else {
                LOGGER.warn("[DungeonTrain] Photo {} never reached the server; not shared.", upload.exposureId());
            }
        }
        pendingUploads = List.copyOf(waiting);
    }

    /** Encode off-thread, then hand the PNG to {@code then} back on the server thread. */
    private static void encodeThen(MinecraftServer server, ExposureData data, String label, java.util.function.Consumer<byte[]> then) {
        encodeThen(server, data, PhotoPngCodec::encode, label, then);
    }

    /** Turns a photo's palette indices into the bytes to send. Runs off the server thread. */
    @FunctionalInterface
    private interface Encoder {
        byte[] encode(int width, int height, byte[] pixels, int[] palette) throws java.io.IOException;
    }

    /** As above, with the bytes made by {@code encoder} — the relay's true pixels, or {@link #discordPng}. */
    private static void encodeThen(MinecraftServer server, ExposureData data, Encoder encoder, String label,
                                   java.util.function.Consumer<byte[]> then) {
        int width = data.getWidth();
        int height = data.getHeight();
        byte[] pixels = data.getPixels().clone();
        int[] palette = ColorPalettes.get(server.registryAccess(), data.getPaletteId()).value().colors();
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return encoder.encode(width, height, pixels, palette);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .whenComplete((png, error) -> server.execute(() -> {
                    if (error != null) {
                        LOGGER.warn("[DungeonTrain] Could not encode photo {} for sharing: {}", label, error.toString());
                    } else {
                        then.accept(png);
                    }
                }));
    }

    /**
     * The photo for Discord: enlarged ({@link PhotoUpscale}) so it shows at a viewable size in an
     * embed, and laid on its paper with the paper's tears ({@link PhotoPaperComposite}) when the paper
     * could be read. Only the Discord post uses this; uploads to the relay keep the true pixels.
     */
    static byte[] discordPng(int width, int height, byte[] pixels, int[] palette, Optional<int[]> paper) throws java.io.IOException {
        int factor = PhotoUpscale.factorFor(width, height);
        int scaledWidth = width * factor;
        int scaledHeight = height * factor;
        byte[] scaled = PhotoUpscale.nearest(pixels, width, height, factor);
        if (paper.isEmpty()) return PhotoPngCodec.encode(scaledWidth, scaledHeight, scaled, palette);
        int[] argb = new int[scaled.length];
        for (int i = 0; i < scaled.length; i++) {
            int index = scaled[i] & 0xFF;
            argb[i] = index < palette.length ? palette[index] : 0;
        }
        PhotoPaperComposite.Composite composite = PhotoPaperComposite.compose(paper.get(), argb, scaledWidth, scaledHeight);
        return PhotoPngCodec.encodeArgb(composite.width(), composite.height(), composite.argb());
    }

    // ---- views and Tribute ----------------------------------------------------

    /** The relay id of a found photo, or 0 for anything else. */
    public static int sharedId(ItemStack stack) {
        Frame frame = stack.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        return frame == null ? 0 : frame.extraData().getInt(SHARED_ID_KEY);
    }

    /** Views a found photo had left when it was handed out, or {@link #VIEWS_MAX} for anything else. */
    public static int viewsLeft(ItemStack stack) {
        Frame frame = stack.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        return frame == null || !frame.extraData().contains(SHARED_VIEWS_LEFT_KEY) ? VIEWS_MAX : frame.extraData().getInt(SHARED_VIEWS_LEFT_KEY);
    }

    /** How many people had opened a found photo before it was handed out. */
    public static int viewsSeen(ItemStack stack) {
        Frame frame = stack.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        return frame == null ? 0 : Math.max(0, frame.extraData().getInt(SHARED_VIEWS_KEY));
    }

    /** Emeralds a Tribute to this found photo costs: one more for every Tribute it has already had. */
    public static int tributeCost(ItemStack stack) {
        Frame frame = stack.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        return TRIBUTE_BASE_COST + (frame == null ? 0 : Math.max(0, frame.extraData().getInt(SHARED_TRIBUTES_KEY)));
    }

    /** The hand holding a found photo, if either does. */
    private static Optional<InteractionHand> heldSharedHand(ServerPlayer player) {
        return Stream.of(InteractionHand.values()).filter(hand -> sharedId(player.getItemInHand(hand)) > 0).findFirst();
    }

    private static JsonObject action(ServerPlayer player, int photoId) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", bare(player.getUUID()));
        body.addProperty("photoId", photoId);
        body.addProperty("key", newKey());
        return body;
    }

    /**
     * The player closed the photo viewer. If they were looking at a found photo, tell the relay it
     * was opened, then let the photo drop and burn. Returns whether a found photo was in hand.
     */
    public static boolean reportView(ServerPlayer player) {
        Optional<InteractionHand> hand = heldSharedHand(player);
        if (hand.isEmpty()) return false;
        ItemStack held = player.getItemInHand(hand.get());
        recordView(player, sharedId(held));
        player.setItemInHand(hand.get(), ItemStack.EMPTY);
        StartingBookEvents.dropAndBurn(player, held);
        // As far as this copy knows: the views it came with, less the one just taken.
        int left = Math.max(0, viewsLeft(held) - 1);
        String key = left == 0 ? "chat.dungeontrain.photo_viewed.last"
                : left == 1 ? "chat.dungeontrain.photo_viewed.one" : "chat.dungeontrain.photo_viewed.more";
        player.sendSystemMessage(Component.translatable(key, left).withStyle(ChatFormatting.GRAY));
        return true;
    }

    private static void recordView(ServerPlayer player, int photoId) {
        markSpent(photoId);
        RelayOutbox.get().enqueue(VIEW_PATH, action(player, photoId).toString());
    }

    /**
     * The player chose Tribute for the found photo in hand: the emeralds are taken, the relay is
     * told (the view first, so the Tribute's fresh views come after it), and the photo burns in
     * green flames.
     */
    public static void payTribute(ServerPlayer player) {
        Optional<InteractionHand> hand = heldSharedHand(player);
        if (hand.isEmpty()) return;
        ItemStack held = player.getItemInHand(hand.get());
        int photoId = sharedId(held);
        int cost = tributeCost(held);
        if (!TributePayment.canPay(player.getInventory(), cost)) {
            player.sendSystemMessage(Component.translatable("chat.dungeontrain.photo_tribute.cannot_afford").withStyle(ChatFormatting.GRAY));
            return;
        }
        // The photo leaves the hand first: any change from a broken emerald block lands in its slot.
        player.setItemInHand(hand.get(), ItemStack.EMPTY);
        TributePayment.pay(player, cost);
        recordView(player, photoId);
        JsonObject body = action(player, photoId);
        body.addProperty("name", player.getGameProfile().getName());
        RelayOutbox.get().enqueue(TRIBUTE_PATH, body.toString());
        announceTribute(player, held, photoId, cost);
        StartingBookEvents.dropAndBurnApproved(player, held);
        player.sendSystemMessage(line("chat.dungeontrain.photo_tribute.paid", TRIBUTE_PAID_LINES, player, VIEWS_MAX));
    }

    /**
     * Show the tributed photo in the public passenger log ({@link TributePhotoReporter}). Clean runs
     * only — a Free Play Tribute still keeps the photo alive, it just isn't announced. The picture
     * comes from this world's copy, the same one {@link #restore} sends back; no copy, no post.
     * And only a personal best: the most emeralds this player has ever paid for one Tribute
     * ({@link GlobalTributeStats}). Only Tributes that could post count toward that record.
     */
    private static void announceTribute(ServerPlayer player, ItemStack held, int photoId, int cost) {
        MinecraftServer server = player.getServer();
        if (server == null || RunIntegrity.isCheated(player)) return;
        Frame frame = held.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        String photographer = frame == null ? "" : frame.extraData().getString(SHARED_AUTHOR_KEY);
        // The frame carries the Tributes the photo had when handed out; this one is the next.
        int tributeNumber = (frame == null ? 0 : Math.max(0, frame.extraData().getInt(SHARED_TRIBUTES_KEY))) + 1;
        String exposureId = exposureIdFor(photoId);
        Optional<ExposureData> data = ExposureServer.exposureRepository().load(exposureId).getData();
        if (data.isEmpty()) {
            LOGGER.debug("[DungeonTrain] Tributed photo {} has no copy in this world; not posted.", photoId);
            return;
        }
        if (!GlobalTributeStats.recordIfHighest(player.getUUID(), cost)) {
            LOGGER.debug("[DungeonTrain] Tribute of {} to photo {} is not {}'s best (best {}); not posted.",
                    cost, photoId, player.getGameProfile().getName(), GlobalTributeStats.highestTributePaid(player.getUUID()));
            return;
        }
        // As the tributer held it: how many hands before theirs, and the paper those views had worn.
        int hands = viewsSeen(held) + 1;
        int viewsLeft = viewsLeft(held);
        Optional<int[]> paper = PhotoPaperTextures.paper(WornPhotographs.forViewsLeft(viewsLeft, photoId));
        encodeThen(server, data.get(), (w, h, pixels, palette) -> discordPng(w, h, pixels, palette, paper), exposureId,
                png -> TributePhotoReporter.post(player, photographer, tributeNumber, cost, hands, viewsLeft, png));
    }

    /** Registration only — no network, no game state. Called once at mod construction. */
    public static void registerResponses() {
        RelayOutbox.get().onResponse(TRIBUTE_PATH, SharedPhotos::onTributeResponse);
    }

    /** HTTP thread. The relay answers {@code deleted: true} when the tributed photo's image was already removed. */
    private static void onTributeResponse(String requestBody, int status, String responseBody) {
        try {
            if (status != 200 || !tributeWantsRestore(responseBody)) return;
            int photoId = JsonParser.parseString(requestBody).getAsJsonObject().get("photoId").getAsInt();
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) server.execute(() -> restore(server, photoId));
        } catch (RuntimeException e) {
            LOGGER.debug("[DungeonTrain] Photo tribute response not understood: {}", e.toString());
        }
    }

    static boolean tributeWantsRestore(String responseBody) {
        JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();
        return response.has("deleted") && response.get("deleted").getAsBoolean();
    }

    /** Send the picture back from this world's copy. */
    private static void restore(MinecraftServer server, int photoId) {
        String exposureId = exposureIdFor(photoId);
        Optional<ExposureData> data = ExposureServer.exposureRepository().load(exposureId).getData();
        if (data.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Tributed photo {} is no longer in this world; it cannot be sent back.", photoId);
            return;
        }
        encodeThen(server, data.get(), exposureId, png -> {
            JsonObject body = new JsonObject();
            body.addProperty("photoId", photoId);
            body.addProperty("image", Base64.getEncoder().encodeToString(png));
            RelayOutbox.get().enqueue(RESTORE_PATH, body.toString());
        });
    }

    // ---- pool -----------------------------------------------------------------

    private static void markSpent(int photoId) {
        synchronized (spent) {
            spent.remove((Integer) photoId);
            spent.add(photoId);
            if (spent.size() > SPENT_MAX) spent.remove(0);
        }
        pool = pool.stream().filter(photo -> photo.id() != photoId).toList();
    }

    private static List<Integer> spentIds() {
        synchronized (spent) { return List.copyOf(spent); }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        tickUploads(server);
        if (--ticksUntilRefresh > 0) return;
        ticksUntilRefresh = REFRESH_PERIOD_TICKS;
        if (SharedBookGate.canDiscover()) refreshPool(server);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        pendingUploads = List.of();
        pool = List.of();
        relayTotal = -1;
        greeted.clear();
        synchronized (spent) { spent.clear(); }
        ticksUntilRefresh = FIRST_REFRESH_DELAY_TICKS;
    }

    private static void refreshPool(MinecraftServer server) {
        if (!fetchInFlight.compareAndSet(false, true)) return;
        List<PoolPhoto> held = pool;
        String base = DungeonTrain.relayBaseUrl();
        StringBuilder url = new StringBuilder(base).append("/photos/pool?limit=").append(POOL_FETCH_LIMIT);
        // Who is here, so the relay can skip what they have already opened and lead with their own tributed photos.
        String uuids = server.getPlayerList().getPlayers().stream().map(p -> bare(p.getUUID())).collect(Collectors.joining(","));
        if (!uuids.isEmpty()) url.append("&uuids=").append(uuids);
        String exclude = Stream.concat(held.stream().map(PoolPhoto::id), spentIds().stream())
                .map(String::valueOf).collect(Collectors.joining(","));
        if (!exclude.isEmpty()) url.append("&exclude=").append(exclude);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(REQUEST_TIMEOUT).header("Accept", "application/json").GET().build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApplyAsync(response -> fetchNew(base, response))
                .whenComplete((fetched, error) -> {
                    fetchInFlight.set(false);
                    if (error != null) {
                        LOGGER.debug("[DungeonTrain] Shared-photo pool refresh failed: {}", error.toString());
                        return;
                    }
                    server.execute(() -> pool = merge(pool, fetched, spentIds()));
                });
    }

    /** Off-thread: download and decode every photo in the response. */
    private static List<PoolPhoto> fetchNew(String base, HttpResponse<String> response) {
        if (response.statusCode() != 200) throw new IllegalStateException("pool answered " + response.statusCode());
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        if (body.has("total")) relayTotal = body.get("total").getAsInt();
        JsonArray photos = body.getAsJsonArray("photos");
        List<PoolPhoto> fetched = new ArrayList<>();
        for (int i = 0; photos != null && i < photos.size(); i++) {
            JsonObject row = photos.get(i).getAsJsonObject();
            int id = row.get("id").getAsInt();
            String author = row.has("author") ? row.get("author").getAsString() : "";
            String tributedBy = row.has("tributedBy") ? row.get("tributedBy").getAsString() : "";
            try {
                HttpRequest image = HttpRequest.newBuilder(URI.create(base + "/photos/" + id + "/image"))
                        .timeout(REQUEST_TIMEOUT).GET().build();
                HttpResponse<byte[]> bytes = HTTP.send(image, HttpResponse.BodyHandlers.ofByteArray());
                if (bytes.statusCode() != 200) continue;
                int tributes = row.has("tributes") ? row.get("tributes").getAsInt() : 0;
                int viewsLeft = row.has("viewsLeft") ? row.get("viewsLeft").getAsInt() : VIEWS_MAX;
                int views = row.has("views") ? row.get("views").getAsInt() : 0;
                fetched.add(new PoolPhoto(id, author, tributedBy, tributes, viewsLeft, views, PhotoPngCodec.decode(bytes.body())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.debug("[DungeonTrain] Shared photo {} could not be fetched: {}", id, e.toString());
            }
        }
        return fetched;
    }

    /** Newly fetched photos replace a held copy of the same id; spent ids stay out; the oldest give way. */
    private static List<PoolPhoto> merge(List<PoolPhoto> held, List<PoolPhoto> fetched, List<Integer> spentIds) {
        Set<Integer> fresh = fetched.stream().map(PoolPhoto::id).collect(Collectors.toSet());
        List<PoolPhoto> merged = Stream.concat(held.stream().filter(p -> !fresh.contains(p.id())), fetched.stream())
                .filter(p -> !spentIds.contains(p.id())).toList();
        return List.copyOf(merged.subList(Math.max(0, merged.size() - POOL_MAX), merged.size()));
    }

    // ---- found photo ----------------------------------------------------------

    private static String exposureIdFor(int photoId) {
        return "dt_shared_" + Integer.toHexString(DungeonTrain.relayBaseUrl().hashCode()) + "_" + photoId;
    }

    /** Who took the photo in {@code stack}: a found photo's credited name, else the frame's photographer. Blank if none. */
    public static String authorOf(ItemStack stack) {
        Frame frame = stack.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        if (frame == null) return "";
        String shared = frame.extraData().getString(SHARED_AUTHOR_KEY);
        return shared.isBlank() ? frame.photographer().name() : shared;
    }

    /** A photographer who picks up their own photo from the train is told so, once per photo. */
    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Frame frame = event.getTo().get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        if (frame == null || !frame.extraData().contains(SHARED_ID_KEY)) return;
        if (!player.getGameProfile().getName().equals(frame.extraData().getString(SHARED_AUTHOR_KEY))) return;
        int id = frame.extraData().getInt(SHARED_ID_KEY);
        if (!greeted.computeIfAbsent(player.getUUID(), key -> new HashSet<>()).add(id)) return;
        String tributedBy = frame.extraData().getString(SHARED_TRIBUTED_BY_KEY);
        player.sendSystemMessage(tributedBy.isBlank()
                ? line("chat.dungeontrain.familiar_photo", FAMILIAR_LINES, player)
                : line("chat.dungeontrain.familiar_photo.tributed", FAMILIAR_TRIBUTED_LINES, player, tributedBy));
    }

    /**
     * True while the relay holds fewer than {@link #CAMERA_FALLBACK_BELOW} approved photos — or its
     * count is not known yet (offline, discovery off, first answer pending), so cameras still turn up.
     */
    public static boolean relayIsShortOfPhotos() {
        return isShort(relayTotal);
    }

    static boolean isShort(int total) {
        return total < CAMERA_FALLBACK_BELOW;
    }

    /**
     * A community photo as an Exposure photograph, or {@link ItemStack#EMPTY} when none is available.
     * A photographer's own tributed photo goes out first; each photo is handed out once.
     */
    public static ItemStack rollFound(long seed) {
        List<PoolPhoto> photos = pool;
        if (photos.isEmpty() || !SharedBookGate.canDiscover()) return ItemStack.EMPTY;
        PoolPhoto photo = photos.stream().filter(p -> !p.tributedBy().isBlank()).findFirst()
                .orElseGet(() -> photos.get((int) Math.floorMod(seed, (long) photos.size())));
        try {
            String exposureId = exposureIdFor(photo.id());
            ExposureRepository repository = ExposureServer.exposureRepository();
            if (repository.load(exposureId).getData().isEmpty()) {
                PhotoPngCodec.Decoded image = photo.image();
                repository.save(exposureId, new ExposureData(image.width(), image.height(), image.pixels(),
                        ColorPalettes.DEFAULT.location(), ExposureData.Tag.EMPTY));
            }
            Frame frame = Frame.create()
                    .setIdentifier(ExposureIdentifier.id(exposureId))
                    .updateExtraData(tag -> {
                        tag.putInt(SHARED_ID_KEY, photo.id());
                        tag.putString(SHARED_AUTHOR_KEY, photo.author());
                        tag.putInt(SHARED_TRIBUTES_KEY, photo.tributes());
                        tag.putInt(SHARED_VIEWS_LEFT_KEY, photo.viewsLeft());
                        tag.putInt(SHARED_VIEWS_KEY, photo.views());
                        if (!photo.tributedBy().isBlank()) tag.putString(SHARED_TRIBUTED_BY_KEY, photo.tributedBy());
                    })
                    .toImmutable();
            ItemStack stack = new ItemStack(ModItems.FOUND_PHOTOGRAPH.get());
            stack.set(Exposure.DataComponents.PHOTOGRAPH_FRAME, frame);
            stack.set(Exposure.DataComponents.PHOTOGRAPH_TYPE, frame.type());
            if (!photo.author().isBlank()) {
                stack.set(DataComponents.LORE, new ItemLore(List.of(
                        Component.translatable("item.dungeontrain.shared_photo.by", photo.author())
                                .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false)))));
            }
            markSpent(photo.id());
            return stack;
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Could not place shared photo {}: {}", photo.id(), e.toString());
            return ItemStack.EMPTY;
        }
    }
}
