package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.client.BookViewScreenAccessor;
import games.brennan.dungeontrain.narrative.PlayerNarrativeBookTag;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.LecternReportPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
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
 * <p>Same glyph, same wording, same two-tap: the first click arms it (the tooltip turns to the
 * confirmation), a second on the icon commits, a click anywhere else disarms. It sits just outside the
 * page's top-right corner, clear of the letter's text and the page-turn buttons. Unlike a held book the
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
    private static final int ICON_X_OFFSET = 160;   // just right of the paper's edge
    private static final int ICON_DY = 12;          // level with the top of the text column
    private static final int ICON_SIZE = 18;
    private static final float REPORTED_ALPHA = 0.4F;
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
        if (!shown(event.getScreen())) return;
        GuiGraphics gfx = event.getGuiGraphics();
        int mx = event.getMouseX();
        int my = event.getMouseY();
        boolean reported = reported();
        boolean hover = inIcon(mx, my);
        boolean lit = !reported && (armed || hover);

        if (reported) gfx.setColor(1F, 1F, 1F, REPORTED_ALPHA);
        gfx.blitSprite(lit ? REPORT_HIGHLIGHTED_SPRITE : REPORT_SPRITE, iconX(), iconY(), ICON_SIZE, ICON_SIZE);
        if (reported) gfx.setColor(1F, 1F, 1F, 1F);

        // Off the page there is no parchment to write a status line on, so the words ride a tooltip:
        // on hover always, and pinned to the icon while armed so the confirmation can't be missed.
        Component line = reported ? Component.translatable("gui.dungeontrain.book_vote.reported")
            : armed ? Component.translatable("gui.dungeontrain.book_vote.report_confirm")
            : hover ? Component.translatable("gui.dungeontrain.book_vote.report") : null;
        if (line == null) return;
        if (hover) {
            gfx.renderTooltip(Minecraft.getInstance().font, line, mx, my);
        } else if (armed) {
            gfx.renderTooltip(Minecraft.getInstance().font, line, iconX(), iconY() + ICON_SIZE + 12);
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

    /** True while {@code s} is the tracked lectern screen and its book is a player-written series letter. */
    private static boolean shown(Object s) {
        return screen != null && s == screen && lecternPos != null
            && PlayerNarrativeBookTag.read(book()).isPresent();
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
