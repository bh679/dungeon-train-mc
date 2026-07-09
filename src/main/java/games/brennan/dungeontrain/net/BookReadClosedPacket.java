package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.discord.BookReadReporter;
import games.brennan.dungeontrain.event.AchievementEvents;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import games.brennan.dungeontrain.narrative.NarrativeProgressData;
import games.brennan.dungeontrain.narrative.StoryFile;
import games.brennan.dungeontrain.narrative.StoryRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Client → server: the player just closed a {@code BookViewScreen} showing a Dungeon Train book, with
 * the measured read attached. The client owns the timing (only it sees page turns + the close); the
 * server owns authorisation + narrative enrichment + the relay POST.
 *
 * <p>On receipt the server gates on the player's network consent
 * ({@link NetworkConsentMirror#isGranted}) — same per-player, fail-closed rule the shared-book upload
 * uses — and, for a narrative read, resolves the story's total letter count + whether the whole story
 * is now read (server-authoritative, cross-session state the client can't know). It then hands off to
 * {@link BookReadReporter}. Metadata + timings only; page text is never sent.</p>
 *
 * <p>Identity ({@code bookType}/{@code bookId}) is decided client-side from the stack's tags:
 * {@code random} (basename), {@code shared} (relay pool id), {@code narrative} ({@code story#letter}),
 * or {@code starting} (basename). {@code variantIndex} is which known text variant this read showed,
 * for {@code random} books (from {@link games.brennan.dungeontrain.narrative.RandomBookTag}) and
 * {@code starting} books (from {@link games.brennan.dungeontrain.narrative.StartingBookTag}); {@code -1}
 * when not applicable.</p>
 */
public record BookReadClosedPacket(
        String bookType, String bookId, String title, String author,
        int pageCount, int pagesViewed, int maxPage, boolean completed, long durationMs,
        List<Integer> pageDwellMs, String story, int letter,
        int variantIndex) implements CustomPacketPayload {

    /** Defensive cap on the per-page dwell array decoded off the wire (vanilla books max ~100 pages). */
    private static final int MAX_PAGES = 256;

    public static final Type<BookReadClosedPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "book_read_closed"));

    public static final StreamCodec<FriendlyByteBuf, BookReadClosedPacket> STREAM_CODEC =
        StreamCodec.of(BookReadClosedPacket::encode, BookReadClosedPacket::decode);

    private static void encode(FriendlyByteBuf buf, BookReadClosedPacket p) {
        buf.writeUtf(p.bookType);
        buf.writeUtf(p.bookId);
        buf.writeUtf(p.title == null ? "" : p.title);
        buf.writeUtf(p.author == null ? "" : p.author);
        buf.writeVarInt(p.pageCount);
        buf.writeVarInt(p.pagesViewed);
        buf.writeVarInt(p.maxPage);
        buf.writeBoolean(p.completed);
        buf.writeVarLong(Math.max(0, p.durationMs));
        List<Integer> dwell = p.pageDwellMs == null ? List.of() : p.pageDwellMs;
        int n = Math.min(dwell.size(), MAX_PAGES);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) buf.writeVarInt(Math.max(0, dwell.get(i)));
        buf.writeUtf(p.story == null ? "" : p.story);
        buf.writeVarInt(p.letter);
        buf.writeVarInt(p.variantIndex);
    }

    private static BookReadClosedPacket decode(FriendlyByteBuf buf) {
        String bookType = buf.readUtf();
        String bookId = buf.readUtf();
        String title = buf.readUtf();
        String author = buf.readUtf();
        int pageCount = buf.readVarInt();
        int pagesViewed = buf.readVarInt();
        int maxPage = buf.readVarInt();
        boolean completed = buf.readBoolean();
        long durationMs = buf.readVarLong();
        int n = Math.min(buf.readVarInt(), MAX_PAGES);
        List<Integer> dwell = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) dwell.add(buf.readVarInt());
        String story = buf.readUtf();
        int letter = buf.readVarInt();
        int variantIndex = buf.readVarInt();
        return new BookReadClosedPacket(bookType, bookId, title, author, pageCount, pagesViewed,
            maxPage, completed, durationMs, dwell, story, letter, variantIndex);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BookReadClosedPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            // "The Enchiridion" — read (open then close) any book. This is a gameplay
            // advancement, not telemetry, so it fires regardless of network consent,
            // before the consent gate below. Idempotent (vanilla award), so re-reads
            // are harmless.
            AchievementEvents.notifyBookRead(player);
            // Per-player, fail-closed: reading behaviour only leaves the machine with the same network
            // consent that gates shared-book uploads. No consent → drop silently.
            if (!NetworkConsentMirror.isGranted(player)) return;

            String story = null;
            int letter = 0;
            int storyLetters = 0;
            boolean storyCompleted = false;
            if ("narrative".equals(packet.bookType) && packet.story != null && !packet.story.isEmpty()) {
                story = packet.story;
                letter = packet.letter;
                MinecraftServer server = player.getServer();
                if (server != null) {
                    Optional<StoryFile> sf = StoryRegistry.getByBasename(story);
                    storyLetters = sf.map(s -> s.letters().size()).orElse(0);
                    if (storyLetters > 0) {
                        // The letter was already marked read on open (NarrativeBookEvents), so this
                        // reflects the read just finished — and stays true on every later re-read.
                        storyCompleted = NarrativeProgressData.get(server.overworld())
                            .progressFor(story).isComplete(storyLetters);
                    }
                }
            }
            BookReadReporter.report(player.getUUID(), player.getName().getString(),
                packet, story, letter, storyLetters, storyCompleted);
        });
    }
}
