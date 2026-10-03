package games.brennan.dungeontrain.discord;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.discord.DiscordService;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.Util;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds a composed milestone announcement until the earning player's client sends the screenshot
 * of their advancement toast ({@code AdvancementPhotoPacket}), then posts it with that image — or
 * text-only once {@link #TIMEOUT_MS} passes (vanilla/old client, capture failed). Same shape as
 * {@code event/DeathReportBuffer} for the death manifest's ride photo.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class MilestonePostBuffer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How long to wait for the client's screenshot before posting without one. */
    static final long TIMEOUT_MS = 10_000L;
    private static final String PHOTO_FILENAME = "advancement.jpg";

    private record Pending(ServerPlayer player, ResourceLocation advancementId, String title,
                           String description, String webhookOverride, long deadlineMs) {}

    /** Keyed by player + advancement, so two milestones in quick succession each keep their own post. */
    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();

    private MilestonePostBuffer() {}

    static String key(UUID player, ResourceLocation advancementId) {
        return player + "|" + advancementId;
    }

    /** Buffer a composed announcement; it posts when the photo arrives or the timeout fires. */
    public static void await(ServerPlayer player, ResourceLocation advancementId, String title,
                             String description, String webhookOverride) {
        PENDING.put(key(player.getUUID(), advancementId), new Pending(player, advancementId, title,
                description, webhookOverride, Util.getMillis() + TIMEOUT_MS));
    }

    /** The client delivered its screenshot (possibly empty) — post now. */
    public static void onPhoto(ServerPlayer player, ResourceLocation advancementId, byte[] image) {
        Pending p = PENDING.remove(key(player.getUUID(), advancementId));
        if (p == null) return;
        post(p, image);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        long now = Util.getMillis();
        for (Iterator<Map.Entry<String, Pending>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Pending p = it.next().getValue();
            if (now >= p.deadlineMs()) {
                it.remove();
                LOGGER.info("[DungeonTrain] no screenshot for {}'s {} announcement — posting text-only.",
                        p.player().getGameProfile().getName(), p.advancementId());
                post(p, null);
            }
        }
    }

    private static void post(Pending p, byte[] image) {
        try {
            byte[] png = image == null || image.length == 0 ? null : image;
            DiscordService.get().postReportTopLevel(p.player(), p.title(), p.description(), List.of(),
                    png, png == null ? null : PHOTO_FILENAME, MilestoneAdvancementReporter.EMBED_COLOR,
                    p.webhookOverride(), List.of(DungeonTrain.BRENNAN_DISCORD_ID));
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] milestone advancement announcement failed: {}", t.toString());
        }
    }
}
