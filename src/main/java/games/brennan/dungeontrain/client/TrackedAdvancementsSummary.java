package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.List;

/**
 * The chat line that confirms tracking once the advancements screen closes: "Tracking X, Y, Z —
 * you will be told if it gets ruled out." Sent only when the tracked set changed while the screen
 * was open, so merely opening and closing the screen says nothing.
 *
 * <p>Watches the client's screen transitions rather than hooking either screen's close: both the
 * vanilla {@code AdvancementsScreen} and Better Advancements' replacement are recognised by
 * class name, and neither needs a mixin for this.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TrackedAdvancementsSummary {

    private static final String SUMMARY_KEY = "chat.dungeontrain.track.summary";
    private static final String NONE_KEY = "chat.dungeontrain.track.none";

    /** Whether the previous tick's screen was an advancements screen. */
    private static boolean wasOpen;
    /** {@link TrackedAdvancements#revision()} when the screen opened. */
    private static int revisionAtOpen;

    private TrackedAdvancementsSummary() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        boolean open = isAdvancementsScreen(mc.screen);
        if (open && !wasOpen) {
            revisionAtOpen = TrackedAdvancements.revision();
        } else if (!open && wasOpen && TrackedAdvancements.revision() != revisionAtOpen) {
            announce(mc);
        }
        wasOpen = open;
    }

    private static boolean isAdvancementsScreen(Screen screen) {
        if (screen == null) return false;
        String name = screen.getClass().getSimpleName();
        return name.equals("AdvancementsScreen") || name.equals("BetterAdvancementsScreen");
    }

    private static void announce(Minecraft mc) {
        if (mc.player == null) return;
        List<ResourceLocation> tracked = TrackedAdvancements.all();
        Component msg = tracked.isEmpty()
            ? Component.translatable(NONE_KEY)
            : Component.translatable(SUMMARY_KEY, titles(mc, tracked));
        mc.player.displayClientMessage(msg.copy().withStyle(ChatFormatting.GRAY), false);
    }

    /** "A, B, C" — each advancement's display title (its id if the client has no display for it). */
    private static Component titles(Minecraft mc, List<ResourceLocation> ids) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(titleOf(mc.getConnection(), ids.get(i)).copy().withStyle(ChatFormatting.WHITE));
        }
        return out;
    }

    private static Component titleOf(ClientPacketListener connection, ResourceLocation id) {
        AdvancementHolder holder = connection == null ? null : connection.getAdvancements().get(id);
        if (holder == null) return Component.literal(id.toString());
        return holder.value().display().map(DisplayInfo::getTitle).orElse(Component.literal(id.toString()));
    }
}
