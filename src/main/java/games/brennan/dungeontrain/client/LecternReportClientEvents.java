package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.systems.RenderSystem;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.client.BookViewScreenAccessor;
import games.brennan.dungeontrain.narrative.PlayerNarrativeBookTag;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.LecternReportPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.LecternScreen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/**
 * The ⚠ report control on a LECTERN showing a player-written letter ({@link PlayerNarrativeBookTag}) —
 * the lectern counterpart of the report icon on a held community book's vote page
 * ({@link BookVoteClientEvents}, which stays held-book only). A report here is a report on the letter's
 * whole SERIES: every lectern of it then reads "Reported" for this player, and the world stops serving it.
 *
 * <p>Same glyph, same wording, same two-tap: the first click arms it, a second on the icon commits, a
 * click anywhere else (or turning the page) disarms. It shows on the LAST page only, in the page footer
 * between the two page-turn arrows — the end of the letter, where the held book's vote page also sits, so
 * it is a verdict on something read rather than a button in the reader's way. The confirmation and, once
 * spent, "Reported" are written just under the book, above Done / Take Book. Unlike a held book the
 * screen stays open after reporting — a lectern has no "I'm done with this" moment to fold it into — and
 * the icon dims to show it is spent.</p>
 *
 * <p>Nothing is sent but the lectern's position ({@link LecternReportPacket}): the server checks reach
 * and reads the series off its own copy of the book. Reported state comes back on the book itself — the
 * server stamps it per opener ({@code PlayerNarrativeBookTag.NBT_REPORTED}) — so it survives reopening
 * and covers lecterns this player never reported from.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LecternReportClientEvents {

    // Book geometry shared with BookVoteClientEvents: 192px art at ((width-192)/2, 2), paper ending at
    // x 158; vanilla nails the page-turn buttons at y 159, which is how a moved book is detected.
    private static final int BOOK_WIDTH = 192;
    private static final int BOOK_TOP = 2;
    private static final int VANILLA_PAGE_BUTTON_Y = 159;
    private static final int ICON_SIZE = 18;
    // Centred on the page column (as BookVoteClientEvents' controls are), in the gap between vanilla's
    // back (x 43-66) and forward (x 116-139) arrows, and level with them (y 159, 13 tall).
    private static final int ICON_X_OFFSET = 93 - ICON_SIZE / 2;
    private static final int ICON_DY = VANILLA_PAGE_BUTTON_Y - BOOK_TOP + 13 / 2 - ICON_SIZE / 2;
    private static final int PAGE_CENTER_X_OFFSET = 93;
    private static final int STATUS_DY = 180;       // just under the book, above Done / Take Book
    private static final float REPORTED_ALPHA = 0.4F;
    private static final int COLOR_ARMED = 0xFF6B55;    // on the dark world below the book
    private static final int COLOR_REPORTED = 0xA0A0A0;
    private static final int COLOR_STATUS_BACKING = 0xD0100A06; // ARGB, near-opaque dark leather
    private static final int REPORT_RESPONSE_COUNT = 5;

    private static final ResourceLocation REPORT_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/report");
    private static final ResourceLocation REPORT_HIGHLIGHTED_SPRITE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/report_highlighted");

    // --- the one lectern screen open (null when none) ---
    private static LecternScreen screen = null;
    private static BlockPos lecternPos = null;
    private static boolean armed = false;
    private static boolean sentThisScreen = false; // instant feedback until the server's stamp syncs back

    private LecternReportClientEvents() {}

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        reset();
        if (!(event.getScreen() instanceof LecternScreen lectern)) return;
        // The lectern the player just used is the block under the crosshair. The book itself is read
        // per frame: the menu's slot sync usually lands AFTER the screen opens.
        HitResult hit = Minecraft.getInstance().hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) return;
        screen = lectern;
        lecternPos = blockHit.getBlockPos();
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (event.getScreen() == screen) reset();
    }

    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!shown(event.getScreen())) {
            if (event.getScreen() == screen) armed = false; // turning off the last page abandons it
            return;
        }
        GuiGraphics gfx = event.getGuiGraphics();
        int mx = event.getMouseX();
        int my = event.getMouseY();
        boolean reported = reported();
        boolean hover = inIcon(mx, my);
        boolean lit = !reported && (armed || hover);

        if (reported) {
            // The dim only shows if the sprite is actually blended over the parchment.
            RenderSystem.enableBlend();
            gfx.setColor(1F, 1F, 1F, REPORTED_ALPHA);
        }
        gfx.blitSprite(lit ? REPORT_HIGHLIGHTED_SPRITE : REPORT_SPRITE, iconX(), iconY(), ICON_SIZE, ICON_SIZE);
        if (reported) {
            gfx.setColor(1F, 1F, 1F, 1F);
            RenderSystem.disableBlend();
        }

        // Words only where they're needed, like the held book's report: the confirmation once armed,
        // "Reported" once spent (so a dim icon says why without a hover), and a tooltip on hover.
        Component status = reported ? Component.translatable("gui.dungeontrain.book_vote.reported")
            : armed ? Component.translatable("gui.dungeontrain.book_vote.report_confirm") : null;
        if (status != null) {
            Font font = Minecraft.getInstance().font;
            int centerX = (screen.width - BOOK_WIDTH) / 2 + PAGE_CENTER_X_OFFSET;
            int x = centerX - font.width(status) / 2;
            int y = bookTop() + STATUS_DY;
            // Chat shows through behind the screen at this height (the train's reply lands right here),
            // so the line gets its own backing to stay readable.
            gfx.fill(x - 3, y - 2, x + font.width(status) + 3, y + font.lineHeight + 1, COLOR_STATUS_BACKING);
            gfx.drawString(font, status, x, y, reported ? COLOR_REPORTED : COLOR_ARMED, true);
        } else if (hover) {
            gfx.renderTooltip(Minecraft.getInstance().font,
                Component.translatable("gui.dungeontrain.book_vote.report"), mx, my);
        }
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!shown(event.getScreen()) || event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        if (!inIcon((int) event.getMouseX(), (int) event.getMouseY())) {
            armed = false; // a stray click anywhere else disarms
            return;
        }
        event.setCanceled(true);
        if (reported()) return;
        Minecraft.getInstance().getSoundManager()
            .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        if (armed) commit(); else armed = true;
    }

    /** Send the report and have the train acknowledge it. The lectern stays open, now showing "Reported". */
    private static void commit() {
        armed = false;
        sentThisScreen = true;
        DungeonTrainNet.sendToServer(new LecternReportPacket(lecternPos));
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            int pick = player.getRandom().nextInt(REPORT_RESPONSE_COUNT) + 1;
            player.displayClientMessage(
                Component.translatable("gui.dungeontrain.book_vote.report_response." + pick)
                    .withStyle(ChatFormatting.GRAY), false);
        }
    }

    /**
     * True while {@code s} is the tracked lectern screen, its book is a player-written series letter, and
     * the reader is on its last page.
     */
    private static boolean shown(Object s) {
        return screen != null && s == screen && lecternPos != null
            && PlayerNarrativeBookTag.read(book()).isPresent() && onLastPage();
    }

    private static boolean onLastPage() {
        BookViewScreenAccessor access = (BookViewScreenAccessor) (Object) screen;
        int pages = access.dungeontrain$getBookAccess().getPageCount();
        return access.dungeontrain$getCurrentPage() >= pages - 1;
    }

    private static boolean reported() {
        return sentThisScreen || PlayerNarrativeBookTag.isReported(book());
    }

    private static ItemStack book() {
        return screen.getMenu().getBook();
    }

    private static void reset() {
        screen = null;
        lecternPos = null;
        armed = false;
        sentThisScreen = false;
    }

    private static int iconX() {
        return (screen.width - BOOK_WIDTH) / 2 + ICON_X_OFFSET;
    }

    private static int iconY() {
        return bookTop() + ICON_DY;
    }

    /** Where the book is really drawn — re-centring mods (Scribble) move it; see BookVoteClientEvents#bookTop. */
    private static int bookTop() {
        PageButton back = ((BookViewScreenAccessor) (Object) screen).dungeontrain$getBackButton();
        return back == null ? BOOK_TOP : BOOK_TOP + back.getY() - VANILLA_PAGE_BUTTON_Y;
    }

    private static boolean inIcon(int x, int y) {
        int ix = iconX();
        int iy = iconY();
        return x >= ix && x < ix + ICON_SIZE && y >= iy && y < iy + ICON_SIZE;
    }
}
