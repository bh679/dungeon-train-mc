package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.systems.RenderSystem;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.compat.photo.PhotoReportReason;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PhotoReportPacket;
import io.github.mortuusars.exposure.client.gui.screen.PhotographScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The report flag on a found photo, and the reason picker it opens. The flag sits after the {@code X}
 * in {@link PhotoTributeButtons}' row, faded until hovered so it doesn't compete with Tribute and Close.
 *
 * <p>The picker is drawn inside Exposure's {@link PhotographScreen}, not as a screen of its own:
 * opening another screen would close the photo, which counts the view and burns it before the
 * player has picked a reason. While it is open this class takes every click, scroll and key the photo
 * screen would get.</p>
 *
 * <p>Pages: the first offers "Text with an inappropriate message" ›, "Hate symbol" and "Something
 * else" ›; both › pages list {@link PhotoReportReason#TOPICS}. A reason is clicked once to arm it
 * and again to send, as on a book's report icon. Sending closes the photo; the normal close path
 * counts the view and burns it. Book reports are a separate control and are unaffected.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PhotoReportOverlay {

    /** Width the flag takes in the button row. */
    public static final int FLAG_SIZE = 20;
    private static final int SPRITE_SIZE = 18;
    private static final float FLAG_REST_ALPHA = 0.3F;

    private static final int PANEL_WIDTH = 220;
    private static final int PANEL_PAD = 8;
    private static final int ROW_HEIGHT = 16;
    private static final int ROW_GAP = 2;
    private static final int TITLE_HEIGHT = 14;
    private static final int FOOTER_GAP = 6;
    private static final int FOOTER_WIDTH = 60;
    private static final int COLOR_DIM = 0xA0000000;
    private static final int COLOR_PANEL = 0xF0100A06;
    private static final int COLOR_PANEL_EDGE = 0xFF6B4A1E;
    private static final int COLOR_TITLE = 0xFFE7C77B;
    private static final int RESPONSE_COUNT = 3;

    private static final ResourceLocation REPORT_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/report");
    private static final ResourceLocation REPORT_HIGHLIGHTED_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/report_highlighted");

    private enum Page { CLOSED, MAIN, TEXT, OTHER }

    // --- render thread only ---
    private static Screen shownOn;
    private static FlagButton flag;
    private static Page page = Page.CLOSED;
    private static String armed;
    private static List<Button> rows = List.of();
    private static int panelLeft;
    private static int panelTop;
    private static int panelHeight;

    private PhotoReportOverlay() {}

    /** Add the flag at {@code (x, y)} on a found photo's viewer. Called from {@link PhotoTributeButtons}. */
    static void attach(ScreenEvent.Init.Post event, Screen screen, int x, int y) {
        shownOn = screen;
        flag = new FlagButton(x, y, button -> open(Page.MAIN));
        page = Page.CLOSED;
        armed = null;
        rows = List.of();
        event.addListener(flag);
    }

    private static boolean isOpen() {
        return shownOn != null && page != Page.CLOSED;
    }

    private static void open(Page next) {
        page = next;
        armed = null;
        build();
    }

    private static void close() {
        page = Page.CLOSED;
        armed = null;
        rows = List.of();
    }

    /** Lay out the current page's buttons, centred on the screen. */
    private static void build() {
        if (shownOn == null || page == Page.CLOSED) return;
        List<Button> next = new ArrayList<>();
        int rowCount = page == Page.MAIN ? 3 : PhotoReportReason.TOPICS.size();
        int listHeight = rowCount * (ROW_HEIGHT + ROW_GAP) - ROW_GAP;
        panelHeight = PANEL_PAD + TITLE_HEIGHT + listHeight + FOOTER_GAP + ROW_HEIGHT + PANEL_PAD;
        panelLeft = (shownOn.width - PANEL_WIDTH) / 2;
        panelTop = Math.max(2, (shownOn.height - panelHeight) / 2);
        int x = panelLeft + PANEL_PAD;
        int w = PANEL_WIDTH - 2 * PANEL_PAD;
        int y = panelTop + PANEL_PAD + TITLE_HEIGHT;
        if (page == Page.MAIN) {
            next.add(navRow(x, y, w, "gui.dungeontrain.photo_report.text", Page.TEXT));
            y += ROW_HEIGHT + ROW_GAP;
            next.add(reasonRow(x, y, w, PhotoReportReason.HATE_SYMBOL,
                Component.translatable("gui.dungeontrain.photo_report.hate_symbol")));
            y += ROW_HEIGHT + ROW_GAP;
            next.add(navRow(x, y, w, "gui.dungeontrain.photo_report.something_else", Page.OTHER));
            y += ROW_HEIGHT + ROW_GAP;
        } else {
            String prefix = page == Page.TEXT ? PhotoReportReason.TEXT_PREFIX : PhotoReportReason.OTHER_PREFIX;
            for (String topic : PhotoReportReason.TOPICS) {
                next.add(reasonRow(x, y, w, prefix + topic,
                    Component.translatable("gui.dungeontrain.photo_report.topic." + topic)));
                y += ROW_HEIGHT + ROW_GAP;
            }
        }
        y += FOOTER_GAP - ROW_GAP;
        if (page != Page.MAIN) {
            next.add(new DarkTintedButton(x, y, FOOTER_WIDTH, ROW_HEIGHT,
                Component.translatable("gui.dungeontrain.photo_report.back"), b -> open(Page.MAIN)));
        }
        next.add(new DarkTintedButton(x + w - FOOTER_WIDTH, y, FOOTER_WIDTH, ROW_HEIGHT,
            Component.translatable("gui.dungeontrain.photo_report.cancel"), b -> close()));
        rows = List.copyOf(next);
    }

    private static Button navRow(int x, int y, int w, String key, Page target) {
        return new DarkTintedButton(x, y, w, ROW_HEIGHT,
            Component.translatable(key).append(" >"), b -> open(target));
    }

    /** First click arms (the label asks to confirm); a second click on the same row sends. */
    private static Button reasonRow(int x, int y, int w, String reason, Component label) {
        Component shown = reason.equals(armed)
            ? Component.translatable("gui.dungeontrain.photo_report.confirm").withStyle(ChatFormatting.RED)
            : label;
        return new DarkTintedButton(x, y, w, ROW_HEIGHT, shown, b -> {
            if (reason.equals(armed)) {
                send(reason);
            } else {
                armed = reason;
                build();
            }
        });
    }

    private static void send(String reason) {
        Screen screen = shownOn;
        close();
        DungeonTrainNet.sendToServer(new PhotoReportPacket(reason));
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            int pick = player.getRandom().nextInt(RESPONSE_COUNT) + 1;
            player.displayClientMessage(Component.translatable("chat.dungeontrain.photo_report.response." + pick)
                .withStyle(ChatFormatting.GRAY), false);
        }
        // The report packet goes first, so the server reads the photo before the close burns it.
        if (screen != null) screen.onClose();
    }

    /** Drawn after {@link PhotoTributeButtons}' row, so the picker covers it. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRender(ScreenEvent.Render.Post event) {
        if (event.getScreen() != shownOn || flag == null) return;
        GuiGraphics g = event.getGuiGraphics();
        int mx = event.getMouseX();
        int my = event.getMouseY();
        if (!isOpen()) {
            flag.render(g, mx, my, event.getPartialTick());
            return;
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(0, 0, shownOn.width, shownOn.height, COLOR_DIM);
        g.fill(panelLeft - 1, panelTop - 1, panelLeft + PANEL_WIDTH + 1, panelTop + panelHeight + 1, COLOR_PANEL_EDGE);
        g.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + panelHeight, COLOR_PANEL);
        Font font = Minecraft.getInstance().font;
        String titleKey = switch (page) {
            case TEXT -> "gui.dungeontrain.photo_report.text_title";
            case OTHER -> "gui.dungeontrain.photo_report.other_title";
            default -> "gui.dungeontrain.photo_report.title";
        };
        g.drawCenteredString(font, Component.translatable(titleKey), panelLeft + PANEL_WIDTH / 2,
            panelTop + PANEL_PAD, COLOR_TITLE);
        for (Button row : rows) row.render(g, mx, my, event.getPartialTick());
        g.pose().popPose();
    }

    /** While the picker is open it takes every click; a click off the armed row disarms it. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMouseClicked(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getScreen() != shownOn || !isOpen()) return;
        event.setCanceled(true);
        String before = armed;
        for (Button row : List.copyOf(rows)) {
            if (row.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton())) return;
        }
        if (before != null) {
            armed = null;
            build();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (event.getScreen() == shownOn && isOpen()) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (event.getScreen() == shownOn && isOpen()) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (event.getScreen() == shownOn && isOpen()) event.setCanceled(true);
    }

    /** Esc disarms, then steps back, then closes the picker; the photo stays open. Other keys do nothing. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (event.getScreen() != shownOn || !isOpen()) return;
        event.setCanceled(true);
        if (event.getKeyCode() != GLFW.GLFW_KEY_ESCAPE) return;
        if (armed != null) {
            armed = null;
            build();
        } else if (page != Page.MAIN) {
            open(Page.MAIN);
        } else {
            close();
        }
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (event.getScreen() != shownOn) return;
        shownOn = null;
        flag = null;
        close();
    }

    /** The book's report glyph, frameless: faint at rest, full on hover, with a tooltip. */
    private static final class FlagButton extends Button {

        private static final Component LABEL = Component.translatable("gui.dungeontrain.photo_report.tooltip");

        FlagButton(int x, int y, OnPress onPress) {
            super(x, y, FLAG_SIZE, FLAG_SIZE, LABEL, onPress, DEFAULT_NARRATION);
            setTooltip(Tooltip.create(LABEL));
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean lit = isHoveredOrFocused();
            int inset = (FLAG_SIZE - SPRITE_SIZE) / 2;
            RenderSystem.enableBlend();
            g.setColor(1F, 1F, 1F, lit ? 1F : FLAG_REST_ALPHA);
            g.blitSprite(lit ? REPORT_HIGHLIGHTED_SPRITE : REPORT_SPRITE,
                getX() + inset, getY() + inset, SPRITE_SIZE, SPRITE_SIZE);
            g.setColor(1F, 1F, 1F, 1F);
        }
    }
}
