package games.brennan.dungeontrain.event;

import games.brennan.discordpresence.discord.DeathField;
import games.brennan.discordpresence.discord.DiscordService;

import net.minecraft.Util;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds a dev/public <b>top-level</b> death report (see {@code RunStatsEvents.postRunSummary}) until the
 * client delivers this run's scenic ride photo via {@code DeathPhotoPacket}, then posts the report with
 * that photo as the embed image. If the photo never arrives (ride snapshots off, none captured, vanilla
 * client, or a dropped packet), a short timeout posts the report with the gear-composite fallback — so it
 * always lands. The <i>threaded</i> report still posts instantly at death; only the top-level copy waits.
 */
public final class DeathReportBuffer {

    /** How long to wait for the client's ride photo before posting with the gear-composite fallback. */
    private static final long TIMEOUT_MS = 5_000L;
    private static final String PHOTO_FILENAME = "ride.png";

    private record Pending(ServerPlayer player, String title, String description,
                           List<DeathField> fields, List<ItemStack> fallbackIcons,
                           String webhookOverride, long deadlineMs) {}

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private DeathReportBuffer() {}

    /**
     * Buffer a top-level report; it posts when the photo arrives ({@link #onPhoto}) or the timeout fires.
     * {@code webhookOverride} routes it to a specific channel cap (the public channel on release builds);
     * {@code null} = the build's default cap (the dev channel on dev builds).
     */
    public static void await(ServerPlayer player, String title, String description,
                             List<DeathField> fields, List<ItemStack> fallbackIcons, String webhookOverride) {
        PENDING.put(player.getUUID(),
                new Pending(player, title, description, fields, fallbackIcons, webhookOverride,
                        Util.getMillis() + TIMEOUT_MS));
    }

    /** Client delivered the scenic ride photo — post the buffered report with it (or the fallback if empty). */
    public static void onPhoto(ServerPlayer player, byte[] png) {
        Pending p = PENDING.remove(player.getUUID());
        if (p == null) return;
        if (png != null && png.length > 0) {
            DiscordService.get().postDeathReportTopLevel(
                    p.player(), p.title(), p.description(), p.fields(), png, PHOTO_FILENAME, p.webhookOverride());
        } else {
            DiscordService.get().postDeathReportTopLevel(
                    p.player(), p.title(), p.description(), p.fields(), p.fallbackIcons(), p.webhookOverride());
        }
    }

    /** Flush any reports whose photo never arrived within {@link #TIMEOUT_MS} — gear-composite fallback. */
        public static void onServerTick(net.minecraft.server.MinecraftServer tickedServer) {
        if (PENDING.isEmpty()) return;
        long now = Util.getMillis();
        for (Iterator<Map.Entry<UUID, Pending>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Pending p = it.next().getValue();
            if (now >= p.deadlineMs()) {
                it.remove();
                DiscordService.get().postDeathReportTopLevel(
                        p.player(), p.title(), p.description(), p.fields(), p.fallbackIcons(), p.webhookOverride());
            }
        }
    }
}
