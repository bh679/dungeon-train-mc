package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.editor.SubmitHints;
import games.brennan.dungeontrain.net.BuilderSubmitHintsRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The wait between pressing Submit for Review and the note screen opening: the server is asked which
 * questions the build earns ({@link BuilderSubmitHintsRequestPacket}), and the screen opens with the
 * answer.
 *
 * <p>Never a wait that can block a submit. If no answer arrives within {@link #TIMEOUT_MS} the screen
 * opens anyway with the general question alone — the question that is always asked. And the answer is
 * only acted on while the player is still on the screen that asked: one who has moved on meanwhile
 * does not get a note screen dropped over wherever they went.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderSubmitHintsRequests {

    /** How long a submit waits for the server's answer before asking the general question alone. */
    static final long TIMEOUT_MS = 3000;

    /** One press waiting on its answer: what to do with it, and the screen it was pressed on. */
    private record Pending(Consumer<SubmitHints.Hints> then, Screen from) {}

    private static final Map<Integer, Pending> PENDING = new HashMap<>();

    private BuilderSubmitHintsRequests() {}

    /** Ask about {@code relayId}, then run {@code then} on the client thread with the answer. */
    public static void ask(int relayId, Consumer<SubmitHints.Hints> then) {
        Minecraft mc = Minecraft.getInstance();
        PENDING.put(relayId, new Pending(then, mc.screen));
        PacketDistributor.sendToServer(new BuilderSubmitHintsRequestPacket(relayId));
        CompletableFuture.delayedExecutor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .execute(() -> mc.execute(() -> accept(relayId, SubmitHints.Hints.NONE)));
    }

    /** An answer — or the timeout standing in for one. Whichever comes first wins; the other is a no-op. */
    public static void accept(int relayId, SubmitHints.Hints hints) {
        Pending pending = PENDING.remove(relayId);
        if (pending == null) return;
        if (Minecraft.getInstance().screen != pending.from()) return;
        pending.then().accept(hints);
    }
}
