package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.discord.MilestoneAdvancementReporter;
import games.brennan.dungeontrain.net.LiveMomentPacket;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.MomentKind;
import games.brennan.dungeontrain.player.LiveStreamers;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Live Eye streamer's moments — an advancement earned, a photo taken, a book signed — told to the
 * streamer's client ({@link LiveMomentPacket}), which shares them into the stream's Live chat when its
 * Stream setting for that kind is on ({@code client/live/LiveMoments}). Nothing is sent for anyone but
 * the player streaming from this server. Photos wait for their pixels in {@code compat/photo/LiveMomentPhotos}.
 */
public final class LiveMomentEvents {

    /** How much of a book's first page goes with it. */
    static final int EXCERPT_CHARS = 200;

    private LiveMomentEvents() {}

    /** Whether {@code player} is streaming with the Live Eye on this server right now. */
    public static boolean isLiveStreamer(ServerPlayer player) {
        UUID streamer = LiveStreamers.get(player.server);
        return streamer != null && streamer.equals(player.getUUID());
    }

    /** A genuine (non-replayed) advancement earn: shared when vanilla would announce it in chat. */
    public static void onAdvancement(ServerPlayer player, AdvancementHolder advancement) {
        if (!sharesAdvancement(advancement.id(), advancement.value().display())) return;
        if (!isLiveStreamer(player)) return;
        share(player, MomentKind.ADVANCEMENT,
            MilestoneAdvancementReporter.advancementTitle(advancement).getString(), "", null);
    }

    /** A book signed (title and pages already masked by the Live Eye text filter). */
    public static void onBookSigned(ServerPlayer player, String title, List<String> pages) {
        if (!isLiveStreamer(player)) return;
        share(player, MomentKind.BOOK, title == null ? "" : title.strip(), excerpt(pages), null);
    }

    /** Tell the streamer's client. {@code png} only for a photo. */
    public static void share(ServerPlayer player, MomentKind kind, String title, String excerpt, @Nullable byte[] png) {
        PacketDistributor.sendToPlayer(player, new LiveMomentPacket(kind, title, excerpt, png));
    }

    /**
     * Shared: an advancement with a display that vanilla announces in chat (so never a recipe, a
     * silent tab root, or a Dungeon Train editor advancement).
     */
    static boolean sharesAdvancement(ResourceLocation id, Optional<DisplayInfo> display) {
        if (id.getNamespace().equals(DungeonTrain.MOD_ID) && id.getPath().startsWith("editor/")) return false;
        return display.isPresent() && display.get().shouldAnnounceChat();
    }

    /**
     * The first written page's opening, on one line: line breaks become spaces, cut at a word near
     * {@link #EXCERPT_CHARS}. Ends in {@code …} whenever the book holds more than is shown — the page
     * was cut, or more written pages follow.
     */
    static String excerpt(@Nullable List<String> pages) {
        if (pages == null) return "";
        List<String> written = pages.stream().filter(p -> p != null && !p.isBlank()).toList();
        if (written.isEmpty()) return "";
        String flat = written.get(0).replaceAll("\\s+", " ").strip();
        if (flat.length() > EXCERPT_CHARS) {
            int cut = flat.lastIndexOf(' ', EXCERPT_CHARS);
            return withEllipsis(cut > EXCERPT_CHARS / 2 ? flat.substring(0, cut) : flat.substring(0, EXCERPT_CHARS));
        }
        return written.size() > 1 ? withEllipsis(flat) : flat;
    }

    /** {@code text…}, never {@code text.…}: a trailing full stop, comma, colon or semicolon gives way to the ellipsis. */
    static String withEllipsis(String text) {
        return text.strip().replaceAll("[.,;:]+$", "") + "…";
    }
}
