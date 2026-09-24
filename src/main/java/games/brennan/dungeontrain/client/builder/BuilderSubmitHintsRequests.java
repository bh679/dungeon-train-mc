package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.editor.SubmitHints;
import games.brennan.dungeontrain.net.BuilderSubmitHintsRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The server's word on a build's Submit for Review questions: which ones it earns
 * ({@link BuilderSubmitHintsRequestPacket}), and whether this player may edit its answers.
 *
 * <p>Two ways in. {@link #ask} is the Submit press: it waits for the answer, then opens the note
 * screen — never a wait that can block a submit, because after {@link #TIMEOUT_MS} the screen opens
 * anyway with the general question alone, and only while the player is still on the screen that
 * asked. {@link #peek} is the editor's Submitted answers page: it returns what is known and asks once
 * in the background, so the page draws straight away and fills in when the answer lands.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderSubmitHintsRequests {

    /** How long a submit waits for the server's answer before asking the general question alone. */
    static final long TIMEOUT_MS = 3000;

    /** What the server said about one build. */
    public record Answer(SubmitHints.Hints hints, boolean canEdit) {
        public static final Answer UNKNOWN = new Answer(SubmitHints.Hints.NONE, false);
    }

    /** One press waiting on its answer: what to do with it, and the screen it was pressed on. */
    private record Pending(Consumer<Answer> then, Screen from) {}

    private static final Map<Integer, Pending> PENDING = new HashMap<>();
    private static final Map<Integer, Answer> KNOWN = new HashMap<>();
    private static final Set<Integer> ASKED = new HashSet<>();

    private BuilderSubmitHintsRequests() {}

    /** Ask about one of the player's own builds, then run {@code then} on the client thread. */
    public static void ask(int relayId, Consumer<Answer> then) {
        ask(relayId, "", false, then);
    }

    /** Ask about {@code ownerUuid}'s build (blank for the player's own), then run {@code then}. */
    public static void ask(int relayId, String ownerUuid, boolean live, Consumer<Answer> then) {
        Minecraft mc = Minecraft.getInstance();
        PENDING.put(relayId, new Pending(then, mc.screen));
        PacketDistributor.sendToServer(new BuilderSubmitHintsRequestPacket(relayId, ownerUuid, live));
        CompletableFuture.delayedExecutor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .execute(() -> mc.execute(() -> {
                    Pending pending = PENDING.remove(relayId);
                    if (pending != null && mc.screen == pending.from()) pending.then().accept(Answer.UNKNOWN);
                }));
    }

    /**
     * What is known about a build, asking once in the background when nothing is — for a page that
     * redraws every frame and must not ask every frame. {@link Answer#UNKNOWN} until the answer lands.
     */
    public static Answer peek(int relayId, String ownerUuid, boolean live) {
        if (relayId <= 0) return Answer.UNKNOWN;
        Answer known = KNOWN.get(relayId);
        if (known != null) return known;
        if (ASKED.add(relayId)) {
            PacketDistributor.sendToServer(new BuilderSubmitHintsRequestPacket(relayId, ownerUuid, live));
        }
        return Answer.UNKNOWN;
    }

    /** The server's answer. Kept for {@link #peek}, and handed to a waiting press if there is one. */
    public static void accept(int relayId, SubmitHints.Hints hints, boolean canEdit) {
        Answer answer = new Answer(hints == null ? SubmitHints.Hints.NONE : hints, canEdit);
        KNOWN.put(relayId, answer);
        Pending pending = PENDING.remove(relayId);
        if (pending == null) return;
        if (Minecraft.getInstance().screen != pending.from()) return;
        pending.then().accept(answer);
    }

    /** Forget what is known, so the next look asks again — a build saved since may earn new questions. */
    public static void forget(int relayId) {
        KNOWN.remove(relayId);
        ASKED.remove(relayId);
    }
}
