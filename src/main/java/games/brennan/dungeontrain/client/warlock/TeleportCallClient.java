package games.brennan.dungeontrain.client.warlock;

import games.brennan.dungeontrain.client.ChatClickActions;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.perks.effects.warlock.TeleportCallPacket;
import games.brennan.dungeontrain.perks.effects.warlock.TeleportCalls;
import games.brennan.dungeontrain.perks.effects.warlock.TeleportListPacket;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.UUID;

/**
 * Teleport Player on the client: the last list the server sent (so the details card's Call a player
 * button can grey itself when there is no one), the screen that shows it, and the chat Accept button.
 */
@OnlyIn(Dist.CLIENT)
public final class TeleportCallClient {

    /** Ask again for the list at most this often while the card is shown, in ms. */
    private static final long REFRESH_MS = 3000;

    private static volatile List<TeleportListPacket.Entry> entries = List.of();
    private static volatile int cooldownTicks;
    private static volatile long receivedAt = -1, askedAt = -REFRESH_MS;
    private static volatile int version;
    private static volatile boolean openWhenListed;

    private TeleportCallClient() {}

    public static void registerClicks() {
        ChatClickActions.register(TeleportCalls.CLICK_ACTION, args -> {
            if (args.size() != 2 || !"accept".equals(args.get(0))) return;
            try {
                DungeonTrainNet.sendToServer(TeleportCallPacket.accept(UUID.fromString(args.get(1))));
            } catch (IllegalArgumentException ignored) {
                // not a UUID: a forged click, nothing to accept
            }
        });
    }

    /** Called while the card shows the perk: keeps the list fresh enough for the button. */
    public static void keepFresh() {
        long now = Util.getMillis();
        if (now - askedAt < REFRESH_MS) return;
        askedAt = now;
        DungeonTrainNet.sendToServer(TeleportCallPacket.list());
    }

    /** The button was pressed: open the list once it arrives. */
    public static void open() {
        openWhenListed = true;
        askedAt = Util.getMillis();
        DungeonTrainNet.sendToServer(TeleportCallPacket.list());
    }

    public static void onList(TeleportListPacket packet) {
        entries = packet.entries();
        cooldownTicks = packet.cooldownTicks();
        receivedAt = Util.getMillis();
        version++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TeleportCallScreen screen) {
            screen.refresh();
        } else if (openWhenListed) {
            openWhenListed = false;
            mc.setScreen(new TeleportCallScreen(mc.screen));
        }
    }

    public static List<TeleportListPacket.Entry> entries() {
        return entries;
    }

    /** Seconds left on the cooldown now, counting down since the list came. */
    public static int cooldownSeconds() {
        if (receivedAt < 0) return 0;
        long leftMs = cooldownTicks * 50L - (Util.getMillis() - receivedAt);
        return leftMs <= 0 ? 0 : (int) ((leftMs + 999) / 1000);
    }

    /** True once a list has come, so "no one" means no one rather than not asked yet. */
    public static boolean listed() {
        return receivedAt >= 0;
    }

    /** Bumps with every list, so the card rebuilds its button. */
    public static int version() {
        return version;
    }

    static void call(TeleportListPacket.Entry entry) {
        DungeonTrainNet.sendToServer(TeleportCallPacket.call(entry.kind(), entry.id()));
    }
}
