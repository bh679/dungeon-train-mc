package games.brennan.dungeontrain.client.social;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.List;

/**
 * Tells the player in chat when one of their photos or deaths is posted as a story on our socials:
 * {@code [Dungeon Train] Your death at carriage 412 is on our Instagram story! [View it]}, the link
 * opening the post. The same note lands in their inbox from the relay.
 *
 * <p>On joining a world it sends the Public posts switch to the relay ({@link PublicPostsRelay#syncNow})
 * and checks for new stories; while in a world it checks again every {@link #POLL_TICKS}. Each story is
 * acknowledged once shown, so it is never repeated.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PublicPostNotifier {

    /** Five minutes between checks while in a world. */
    static final int POLL_TICKS = 20 * 60 * 5;
    /** First check this long after joining, so the line is not lost under the join messages. */
    static final int FIRST_CHECK_TICKS = 20 * 10;

    static final String PREFIX_KEY = "chat.dungeontrain.bug_response.prefix";
    static final String DEATH_KEY = "chat.dungeontrain.public_posts.death";
    static final String DEATH_PLAIN_KEY = "chat.dungeontrain.public_posts.death.plain";
    static final String PHOTO_KEY = "chat.dungeontrain.public_posts.photo";
    static final String PHOTO_PLAIN_KEY = "chat.dungeontrain.public_posts.photo.plain";
    static final String VIEW_KEY = "chat.dungeontrain.public_posts.view";

    /** Render thread only. */
    private static int ticksToCheck = -1;
    private static boolean inFlight;

    private PublicPostNotifier() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        PublicPostsRelay.syncNow();
        ticksToCheck = FIRST_CHECK_TICKS;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ticksToCheck = -1;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (ticksToCheck < 0 || --ticksToCheck > 0) return;
        ticksToCheck = POLL_TICKS;
        if (inFlight) return;
        inFlight = true;
        PublicPostsRelay.fetchFeatured().thenAccept(items ->
            Minecraft.getInstance().execute(() -> {
                inFlight = false;
                show(items);
            }));
    }

    private static void show(List<PublicPostsRelay.Featured> items) {
        Minecraft mc = Minecraft.getInstance();
        if (items.isEmpty() || mc.player == null) return;
        for (PublicPostsRelay.Featured item : items) {
            mc.player.displayClientMessage(message(item), false);
        }
        PublicPostsRelay.ack(items.stream().map(PublicPostsRelay.Featured::id).toList());
    }

    /** The chat line for one story. Pure, so it is unit-tested. */
    static Component message(PublicPostsRelay.Featured item) {
        Component platform = Component.literal(platformName(item.platform()));
        Component line;
        if (item.isDeath()) {
            line = item.carriage() != null
                ? Component.translatable(DEATH_KEY, item.carriage(), platform)
                : Component.translatable(DEATH_PLAIN_KEY, platform);
        } else {
            line = item.title() != null
                ? Component.translatable(PHOTO_KEY, item.title(), platform)
                : Component.translatable(PHOTO_PLAIN_KEY, platform);
        }
        Component link = Component.literal("[")
            .append(Component.translatable(VIEW_KEY))
            .append("]")
            .withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, item.url()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(item.url()))));
        return Component.translatable(PREFIX_KEY).withStyle(ChatFormatting.GOLD)
            .append(" ").append(line.copy().withStyle(ChatFormatting.WHITE)).append(" ").append(link);
    }

    /** The platform's own name, which stays untranslated. */
    static String platformName(String platform) {
        if ("instagram".equals(platform)) return "Instagram";
        if ("facebook".equals(platform)) return "Facebook";
        return "Discord";
    }
}
