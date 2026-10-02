package games.brennan.dungeontrain.compat.photo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.SharedBookGate;
import games.brennan.dungeontrain.net.relay.RelayOutbox;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Community photos — the picture counterpart of shared books. A disposable-camera print is uploaded
 * to the relay as a small JPEG when it is printed; approved photos from other players are kept in a
 * small pre-decoded pool and handed out as ordinary Exposure photographs when a container rolls
 * {@code dungeontrain:random_playerphoto}.
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

    /** Send-off lines, keyed {@code chat.dungeontrain.shared_photo.1..N}; familiar lines likewise. */
    private static final int SEND_OFF_LINES = 10;
    private static final int FAMILIAR_LINES = 5;

    /** The client uploads the pixels to the server separately; they can trail the print by a moment. */
    private static final int PIXEL_WAIT_TICKS = 100;
    private static final int REFRESH_PERIOD_TICKS = 600;
    private static final int FIRST_REFRESH_DELAY_TICKS = 100;
    private static final int POOL_FETCH_LIMIT = 10;
    private static final int POOL_MAX = 40;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    // HTTP/1.1 on purpose: over plain http the default client first asks to upgrade to HTTP/2, and the
    // relay closes that connection without answering.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(REQUEST_TIMEOUT).build();

    private record PendingUpload(UUID playerId, String author, String exposureId, JsonObject meta, int ticksLeft) {
        PendingUpload tick() { return new PendingUpload(playerId, author, exposureId, meta, ticksLeft - 1); }
    }

    /** One approved photo, already snapped to Exposure's default palette. */
    private record PoolPhoto(int id, String author, PhotoJpegCodec.Decoded image) {}

    /** Server thread only. */
    private static List<PendingUpload> pendingUploads = List.of();
    private static volatile List<PoolPhoto> pool = List.of();
    /** Found photos a player has already been greeted for, by relay id. Server thread only. */
    private static final Map<UUID, Set<Integer>> greeted = new HashMap<>();
    private static final AtomicBoolean fetchInFlight = new AtomicBoolean();
    private static int ticksUntilRefresh = FIRST_REFRESH_DELAY_TICKS;

    private SharedPhotos() {}

    // ---- upload ---------------------------------------------------------------

    /** Called the tick a disposable camera prints: queue the photograph for the relay if it may be shared. */
    public static void queueUpload(ServerPlayer player, ItemStack photograph) {
        if (photograph.isEmpty() || !SharedBookGate.canContribute(player)) return;
        Frame frame = photograph.get(Exposure.DataComponents.PHOTOGRAPH_FRAME);
        // Projected frames are images from outside the game — never shared.
        if (frame == null || frame.isProjected() || !frame.identifier().isId()) return;
        List<PendingUpload> next = new ArrayList<>(pendingUploads);
        next.add(new PendingUpload(player.getUUID(), player.getGameProfile().getName(),
                frame.identifier().id(), buildMeta(frame.extraData()), PIXEL_WAIT_TICKS));
        pendingUploads = List.copyOf(next);
    }

    static JsonObject buildMeta(CompoundTag extraData) {
        JsonObject meta = new JsonObject();
        if (extraData.contains("biome")) meta.addProperty("biome", extraData.getString("biome"));
        if (extraData.contains("dimension")) meta.addProperty("dimension", extraData.getString("dimension"));
        if (extraData.contains("day_time")) meta.addProperty("dayTime", extraData.getInt("day_time"));
        if (extraData.getBoolean("selfie")) meta.addProperty("selfie", true);
        ModList.get().getModContainerById(DungeonTrain.MOD_ID)
                .ifPresent(mod -> meta.addProperty("version", mod.getModInfo().getVersion().toString()));
        return meta;
    }

    static JsonObject buildPayload(String uuid, String author, String key, byte[] jpeg, JsonObject meta) {
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        body.addProperty("author", author == null ? "" : author);
        body.addProperty("key", key);
        body.addProperty("jpeg", Base64.getEncoder().encodeToString(jpeg));
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
                encodeAndSend(server, upload, data.get());
            } else if (upload.ticksLeft() > 1) {
                waiting.add(upload.tick());
            } else {
                LOGGER.warn("[DungeonTrain] Photo {} never reached the server; not shared.", upload.exposureId());
            }
        }
        pendingUploads = List.copyOf(waiting);
    }

    private static void encodeAndSend(MinecraftServer server, PendingUpload upload, ExposureData data) {
        int width = data.getWidth();
        int height = data.getHeight();
        byte[] pixels = data.getPixels().clone();
        int[] palette = ColorPalettes.get(server.registryAccess(), data.getPaletteId()).value().colors();
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return PhotoJpegCodec.encode(width, height, pixels, palette, PhotoJpegCodec.QUALITY);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .whenComplete((jpeg, error) -> server.execute(() -> {
                    if (error != null) {
                        LOGGER.warn("[DungeonTrain] Could not encode photo {} for sharing: {}", upload.exposureId(), error.toString());
                        return;
                    }
                    String uuid = upload.playerId().toString().replace("-", "");
                    String key = UUID.randomUUID().toString().replace("-", "");
                    RelayOutbox.get().enqueue("/photos/submit",
                            buildPayload(uuid, upload.author(), key, jpeg, upload.meta()).toString());
                    ServerPlayer player = server.getPlayerList().getPlayer(upload.playerId());
                    if (player != null) {
                        int line = 1 + player.getRandom().nextInt(SEND_OFF_LINES);
                        player.sendSystemMessage(Component.translatable("chat.dungeontrain.shared_photo." + line)
                                .withStyle(ChatFormatting.GRAY));
                    }
                }));
    }

    // ---- pool -----------------------------------------------------------------

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
        greeted.clear();
        ticksUntilRefresh = FIRST_REFRESH_DELAY_TICKS;
    }

    private static void refreshPool(MinecraftServer server) {
        if (!fetchInFlight.compareAndSet(false, true)) return;
        int[] palette = ColorPalettes.getDefault(server.registryAccess()).value().colors();
        List<PoolPhoto> held = pool;
        String base = DungeonTrain.relayBaseUrl();
        StringBuilder url = new StringBuilder(base).append("/photos/pool?limit=").append(POOL_FETCH_LIMIT);
        if (!held.isEmpty()) {
            url.append("&exclude=").append(held.stream().map(p -> String.valueOf(p.id())).collect(Collectors.joining(",")));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(REQUEST_TIMEOUT).header("Accept", "application/json").GET().build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApplyAsync(response -> fetchNew(base, response, held, palette))
                .whenComplete((merged, error) -> {
                    fetchInFlight.set(false);
                    if (error != null) {
                        LOGGER.debug("[DungeonTrain] Shared-photo pool refresh failed: {}", error.toString());
                    } else {
                        pool = merged;
                    }
                });
    }

    /** Off-thread: download and decode every photo in the response we do not hold yet. */
    private static List<PoolPhoto> fetchNew(String base, HttpResponse<String> response, List<PoolPhoto> held, int[] palette) {
        if (response.statusCode() != 200) throw new IllegalStateException("pool answered " + response.statusCode());
        JsonArray photos = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("photos");
        List<PoolPhoto> merged = new ArrayList<>(held);
        for (int i = 0; photos != null && i < photos.size(); i++) {
            JsonObject row = photos.get(i).getAsJsonObject();
            int id = row.get("id").getAsInt();
            if (merged.stream().anyMatch(p -> p.id() == id)) continue;
            String author = row.has("author") ? row.get("author").getAsString() : "";
            try {
                HttpRequest image = HttpRequest.newBuilder(URI.create(base + "/photos/" + id + "/image"))
                        .timeout(REQUEST_TIMEOUT).GET().build();
                HttpResponse<byte[]> bytes = HTTP.send(image, HttpResponse.BodyHandlers.ofByteArray());
                if (bytes.statusCode() != 200) continue;
                merged.add(new PoolPhoto(id, author, PhotoJpegCodec.decode(bytes.body(), palette)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.debug("[DungeonTrain] Shared photo {} could not be fetched: {}", id, e.toString());
            }
        }
        // Oldest out first, so a long-running server keeps seeing new pictures.
        return List.copyOf(merged.subList(Math.max(0, merged.size() - POOL_MAX), merged.size()));
    }

    // ---- found photo ----------------------------------------------------------

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
        int line = 1 + player.getRandom().nextInt(FAMILIAR_LINES);
        player.sendSystemMessage(Component.translatable("chat.dungeontrain.familiar_photo." + line)
                .withStyle(ChatFormatting.GRAY));
    }

    /** A community photo as an Exposure photograph, or {@link ItemStack#EMPTY} when none is available. */
    public static ItemStack rollFound(long seed) {
        List<PoolPhoto> photos = pool;
        if (photos.isEmpty() || !SharedBookGate.canDiscover()) return ItemStack.EMPTY;
        PoolPhoto photo = photos.get((int) Math.floorMod(seed, (long) photos.size()));
        try {
            String exposureId = "dt_shared_" + Integer.toHexString(DungeonTrain.relayBaseUrl().hashCode()) + "_" + photo.id();
            ExposureRepository repository = ExposureServer.exposureRepository();
            if (repository.load(exposureId).getData().isEmpty()) {
                PhotoJpegCodec.Decoded image = photo.image();
                repository.save(exposureId, new ExposureData(image.width(), image.height(), image.pixels(),
                        ColorPalettes.DEFAULT.location(), ExposureData.Tag.EMPTY));
            }
            Frame frame = Frame.create()
                    .setIdentifier(ExposureIdentifier.id(exposureId))
                    .updateExtraData(tag -> {
                        tag.putInt(SHARED_ID_KEY, photo.id());
                        tag.putString(SHARED_AUTHOR_KEY, photo.author());
                    })
                    .toImmutable();
            ItemStack stack = new ItemStack(Exposure.Items.PHOTOGRAPH.get());
            stack.set(Exposure.DataComponents.PHOTOGRAPH_FRAME, frame);
            stack.set(Exposure.DataComponents.PHOTOGRAPH_TYPE, frame.type());
            if (!photo.author().isBlank()) {
                stack.set(DataComponents.LORE, new ItemLore(List.of(
                        Component.translatable("item.dungeontrain.shared_photo.by", photo.author())
                                .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false)))));
            }
            return stack;
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Could not place shared photo {}: {}", photo.id(), e.toString());
            return ItemStack.EMPTY;
        }
    }
}
