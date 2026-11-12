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
 * {@code [Dungeon Train] Your death at carriage 412 is on our Instagram and Facebook stories!}, each
 * platform name a link to the post there. One line per story, however many platforms it went to. The
 * same note lands in their inbox from the relay.
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
    /** Suffix for the two-platform form of each key above ("…on our %s and %s stories!"). */
    static final String TWO = ".two";

    /** Render thread only. */
    private static int ticksToCheck = -1;
    private static boolean inFlight;

    private PublicPostNotifier() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        PublicPostsRelay.syncNow();
        WebsiteNameRelay.syncNow();
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

    /** The chat line for one story, each platform name a link. Pure, so it is unit-tested. */
    static Component message(PublicPostsRelay.Featured item) {
        List<PublicPostsRelay.Link> links = item.links();
        boolean two = links.size() >= 2;
        // A third platform would need its own phrasing; the relay posts to two today, so name the first two.
        Object[] names = two
            ? new Object[] {link(links.get(0)), link(links.get(1))}
            : new Object[] {link(links.get(0))};
        String key;
        Object[] args;
        if (item.isDeath()) {
            key = item.carriage() != null ? DEATH_KEY : DEATH_PLAIN_KEY;
            args = item.carriage() != null ? prepend(item.carriage(), names) : names;
        } else {
            key = item.title() != null ? PHOTO_KEY : PHOTO_PLAIN_KEY;
            args = item.title() != null ? prepend(item.title(), names) : names;
        }
        Component line = Component.translatable(two ? key + TWO : key, args).withStyle(ChatFormatting.WHITE);
        return Component.translatable(PREFIX_KEY).withStyle(ChatFormatting.GOLD).append(" ").append(line);
    }

    /** A platform's name as a link: aqua, underlined, opens the post, the address on hover. */
    static Component link(PublicPostsRelay.Link link) {
        return Component.literal(platformName(link.platform()))
            .withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, link.url()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(link.url()))));
    }

    private static Object[] prepend(Object first, Object[] rest) {
        Object[] out = new Object[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    /** The platform's own name, which stays untranslated. */
    static String platformName(String platform) {
        if ("instagram".equals(platform)) return "Instagram";
        if ("facebook".equals(platform)) return "Facebook";
        return "Discord";
    }
}
