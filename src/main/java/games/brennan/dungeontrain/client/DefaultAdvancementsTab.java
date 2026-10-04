package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.ExposureCaptureState;
import games.brennan.dungeontrain.narrative.BurnableBookTag;
import io.github.mortuusars.exposure.client.gui.screen.PhotographScreen;
import io.github.mortuusars.exposure.world.item.PhotographItem;
import io.github.mortuusars.exposure.world.item.StackedPhotographsItem;
import io.github.mortuusars.exposure.world.item.camera.CameraItem;
import net.minecraft.Util;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.util.WeakHashMap;

/**
 * Picks the tab the advancements screen opens on. Vanilla falls back to whichever tab the network sync
 * happened to list first, which is not deterministic across loads or worlds, so Dungeon Train chooses:
 *
 * <ul>
 *   <li><b>The Enchiridion</b> when the player has a camera, photo or book in hand, or took a photo or
 *       read or wrote something in the last {@link #RECENT_MILLIS} — and has the tab at all;</li>
 *   <li>otherwise <b>Dungeon Train</b>.</li>
 * </ul>
 *
 * <p>The pick goes through {@link ClientAdvancements#setSelectedTab}, the same path a tab click takes,
 * so the client's remembered tab and the screen agree. (Changing only the screen's tab once left the
 * client remembering another one, and a click on that tab then did nothing — it was "already
 * selected".) Once per screen instance, both for vanilla's screen and Better Advancements'.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DefaultAdvancementsTab {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation DUNGEON_TRAIN_ROOT_ID =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/root");
    private static final ResourceLocation ENCHIRIDION_ROOT_ID =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "dungeon_train/the_enchiridion");
    private static final String BETTER_ADVANCEMENTS_SCREEN = "betteradvancements.common.gui.BetterAdvancementsScreen";

    /** How long after a photo, a read or a write the advancements screen still opens on The Enchiridion. */
    static final long RECENT_MILLIS = 30_000L;

    /** Screen instances already set — reopening makes a fresh instance, which gets its pick on first render. */
    private static final WeakHashMap<Screen, Boolean> ADJUSTED = new WeakHashMap<>();

    /** When the player last took a photo, or read or wrote a book or photo. */
    private static long lastEnchiridionMoment = Long.MIN_VALUE / 2;

    private DefaultAdvancementsTab() {}

    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        Screen screen = event.getScreen();
        if (!isAdvancementsScreen(screen) || ADJUSTED.containsKey(screen)) return;
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) return;
        ClientAdvancements advancements = connection.getAdvancements();
        AdvancementHolder dungeonTrain = advancements.get(DUNGEON_TRAIN_ROOT_ID);
        if (dungeonTrain == null) return; // Tabs not synced yet — try again next frame.
        AdvancementHolder enchiridion = advancements.get(ENCHIRIDION_ROOT_ID);
        AdvancementHolder pick = enchiridion != null && wantsEnchiridion() ? enchiridion : dungeonTrain;
        advancements.setSelectedTab(pick, true);
        ADJUSTED.put(screen, Boolean.TRUE);
        LOGGER.debug("[DungeonTrain] Advancements screen opened on {}", pick.id());
    }

    /** A camera, photo or book is in hand, or one was used a moment ago. */
    static boolean wantsEnchiridion() {
        if (Util.getMillis() - lastEnchiridionMoment <= RECENT_MILLIS) return true;
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && (isEnchiridionItem(player.getMainHandItem()) || isEnchiridionItem(player.getOffhandItem()));
    }

    private static boolean isEnchiridionItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getItem() instanceof CameraItem
                || stack.getItem() instanceof PhotographItem
                || stack.getItem() instanceof StackedPhotographsItem
                || stack.is(Items.BOOK) || stack.is(Items.WRITABLE_BOOK) || stack.is(Items.WRITTEN_BOOK)
                || BurnableBookTag.isBurnable(stack);
    }

    /** A photo is being taken: remember the moment. */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (ExposureCaptureState.captureInFlight()) lastEnchiridionMoment = Util.getMillis();
    }

    /** A book or photo was just read, or a book written: remember the moment. */
    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        Screen screen = event.getScreen();
        if (screen instanceof BookViewScreen || screen instanceof BookEditScreen || screen instanceof PhotographScreen) {
            lastEnchiridionMoment = Util.getMillis();
        }
    }

    private static boolean isAdvancementsScreen(Screen screen) {
        return screen instanceof AdvancementsScreen
                || (screen != null && BETTER_ADVANCEMENTS_SCREEN.equals(screen.getClass().getName()));
    }
}
