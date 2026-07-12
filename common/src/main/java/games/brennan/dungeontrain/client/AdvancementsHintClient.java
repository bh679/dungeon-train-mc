package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side renderer for the advancements-keybind chat hint. Driven by
 * {@link games.brennan.dungeontrain.net.AdvancementsHintPacket}: when the local
 * player earns a Dungeon Train gameplay advancement and has never opened the
 * advancements screen on this install, show a short train-flavoured line
 * reminding them which key opens advancements.
 *
 * <p>Presentation: a plain grey system line with <em>no</em> sender/prefix, shown
 * roughly one second <em>after</em> the earn (via {@link #DELAY_TICKS}) so it
 * lands just below the vanilla "X has made the advancement" message rather than
 * on top of it.</p>
 *
 * <p>The key is read live from the player's options each time, so it always
 * reflects the actual (rebindable) binding — never hardcoded. One of
 * {@link #HINT_KEYS} is chosen at random per showing. The hint self-terminates:
 * once the player opens the advancements screen, {@link AdvancementsScreenWatcher}
 * flips the {@link ClientDisplayConfig} flag and any pending/future hints become
 * no-ops.</p>
 */
public final class AdvancementsHintClient {

    /**
     * Localised hint variants (see {@code assets/dungeontrain/lang/en_us.json}).
     * Each takes a single {@code %s} argument — the translated advancements
     * keybind. Kept in lockstep with the {@code chat.dungeontrain.advancements_hint.N}
     * keys in the lang file.
     */
    private static final String[] HINT_KEYS = {
        "chat.dungeontrain.advancements_hint.1",
        "chat.dungeontrain.advancements_hint.2",
        "chat.dungeontrain.advancements_hint.3",
        "chat.dungeontrain.advancements_hint.4",
        "chat.dungeontrain.advancements_hint.5",
        "chat.dungeontrain.advancements_hint.6",
        "chat.dungeontrain.advancements_hint.7",
        "chat.dungeontrain.advancements_hint.8",
        "chat.dungeontrain.advancements_hint.9",
        "chat.dungeontrain.advancements_hint.10",
        "chat.dungeontrain.advancements_hint.11",
        "chat.dungeontrain.advancements_hint.12",
    };

    private static final RandomSource RANDOM = RandomSource.create();

    /** ~1 second at 20 TPS — delay between the earn and the chat hint. */
    private static final int DELAY_TICKS = 20;

    /**
     * Countdowns (in client ticks) for hints queued but not yet shown. Mutated
     * only on the client thread (packet {@code enqueueWork} + client tick both
     * run there), so no synchronisation is needed. A list (not a single timer)
     * keeps "every earn shows a hint" correct when several land within a second.
     */
    private static final List<Integer> PENDING = new ArrayList<>();

    private AdvancementsHintClient() {}

    /**
     * Queue a hint to appear ~1 second from now. Called from the packet handler
     * on a gameplay-advancement earn. No-op if the player has already opened the
     * advancements screen (re-checked again at show time).
     */
    public static void queueHint() {
        if (ClientDisplayConfig.isOpenedAdvancementsBefore()) return;
        PENDING.add(DELAY_TICKS);
    }

    public static void onClientTick() {
        if (PENDING.isEmpty()) return;
        for (int i = PENDING.size() - 1; i >= 0; i--) {
            int remaining = PENDING.get(i) - 1;
            if (remaining <= 0) {
                PENDING.remove(i);
                showNow();
            } else {
                PENDING.set(i, remaining);
            }
        }
    }

    /** Render the hint immediately — gated again on the (client-only) opened flag. */
    private static void showNow() {
        if (ClientDisplayConfig.isOpenedAdvancementsBefore()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        // Live keybind — reflects the player's actual binding.
        Component key = mc.options.keyAdvancements.getTranslatedKeyMessage();
        String langKey = HINT_KEYS[RANDOM.nextInt(HINT_KEYS.length)];
        // Plain grey system line, no sender/prefix.
        Component msg = Component.translatable(langKey, key).withStyle(ChatFormatting.GRAY);
        player.displayClientMessage(msg, false);
    }
}
