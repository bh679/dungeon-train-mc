package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The "Stream 1 | Stream 2" toggle just above the chat input, for a worn TV showing both streams of a
 * Two Streams pair: it picks which stream's Live chat room this player reads and posts in
 * ({@link LiveChatRoom}). The chosen room is highlighted. It is there only while
 * {@link LiveChatRoom#showToggle} holds, re-checked every frame, so it goes the moment the second stream
 * does; with one stream up the chat screen is exactly vanilla's.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveChatRoomToggle {

    static final String STREAM_KEY = "gui.dungeontrain.live_room.stream";
    static final String TOOLTIP_KEY = "gui.dungeontrain.live_room.tooltip";
    static final int W = 54, H = 12, GAP = 2;
    /** Above the input box (the bottom 14 px) and under the chat log, which ends 40 px up. */
    static final int BOTTOM_OFFSET = 28;

    @Nullable private static Button stream1;
    @Nullable private static Button stream2;

    private LiveChatRoomToggle() {}

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof ChatScreen screen)) return;
        EditBox input = null;
        for (GuiEventListener l : event.getListenersList()) {
            if (l instanceof EditBox box) input = box;
        }
        EditBox typing = input;
        int y = screen.height - BOTTOM_OFFSET;
        stream1 = button(screen, typing, 1, 2, y);
        stream2 = button(screen, typing, 2, 2 + W + GAP, y);
        event.addListener(stream1);
        event.addListener(stream2);
        refresh();
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Pre event) {
        if (event.getScreen() instanceof ChatScreen) refresh();
    }

    private static Button button(Screen screen, @Nullable EditBox input, int stream, int x, int y) {
        Button b = Button.builder(label(stream, false), btn -> {
                LiveChatRoom.choose(stream);
                refresh();
                // Back to the input, so the next keys still type the line.
                if (input != null) screen.setFocused(input);
            })
            .bounds(x, y, W, H)
            .tooltip(Tooltip.create(Component.translatable(TOOLTIP_KEY)))
            .build();
        b.visible = false;
        return b;
    }

    /** Shown or not, and which room is lit, from the current state. */
    private static void refresh() {
        boolean show = visible();
        int chosen = LiveChatRoom.chosen();
        if (stream1 != null) {
            stream1.visible = show;
            stream1.setMessage(label(1, chosen == 1));
        }
        if (stream2 != null) {
            stream2.visible = show;
            stream2.setMessage(label(2, chosen == 2));
        }
    }

    static boolean visible() {
        return LiveChatRoom.showToggle(LiveChatBridge.connected(), LiveStatusPoller.current(),
            LiveChatBridge.role(), HeadViewerPanes.choice());
    }

    /** "Stream 1", lit (yellow, bold) when it is the room chosen. */
    static Component label(int stream, boolean chosen) {
        Component c = Component.translatable(STREAM_KEY, stream);
        return chosen ? c.copy().withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD) : c.copy().withStyle(ChatFormatting.GRAY);
    }
}
