package games.brennan.dungeontrain.client;

import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.support.BookCredits;
import games.brennan.dungeontrain.mixin.client.BookEditScreenAccessor;
import games.brennan.dungeontrain.narrative.PriorityBookMessage;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PriorityBookIntentPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * The "Use a Priority Book (N left)" toggle on the book-and-quill sign screen: the moment a giver
 * spends the thank-you their donation carried, on the book they are about to share.
 *
 * <p>Shown only while signing a community book the player could actually share: network consent given
 * (without it nothing is uploaded), at least one Priority Book waiting ({@link BookCredits}), not in an
 * editor plot (those are props), not a lectern letter, and not while uploads are paused. It starts OFF
 * on every sign screen, so a credit is only ever spent by a deliberate tick.</p>
 *
 * <p>The choice travels as a {@link PriorityBookIntentPacket} from {@code saveChanges(publish=true)} HEAD
 * ({@code BookEditScreenSignMixin}), ahead of vanilla's sign packet, exactly like the book-language pick.
 * The relay does the charging and the server reports back in chat what happened.</p>
 *
 * <p>A star on a yellow face just left of Sign and Close, on its row: outline when off, filled when
 * ticked, the count left as a corner badge, the words in its tooltip.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PriorityBookSignToggle {

    private static final int SIZE = 20;                  // square icon button
    private static final int GAP = 4;                    // between the star and Sign and Close

    // The book art inside vanilla's 192×192 image, which renderBackground blits at ((width-192)/2, 2):
    // measured from the screen, the visible leather spans x+20..x+166 and y+1..y+181.
    private static final int BOOK_IMAGE = 192;
    private static final int BOOK_BLIT_Y = 2;
    private static final int ART_LEFT = 20, ART_RIGHT = 166, ART_TOP = 1, ART_BOTTOM = 181;
    private static final int FRAME_OUT = 3;              // how far the gold reaches past the book art
    private static final int FRAME_RADIUS = 5;           // rounded corners on the gold, like the book's own
    private static final int FRAME_GOLD = 0xFFFFC21A;
    private static final int FRAME_RIM = 0xFF8A5F00;     // 1 px darker rim round the gold

    // The page column vanilla's sign layout writes in, relative to the book image, and the thank-you note.
    private static final int SIGN_BELOW_BOOK = 194;      // Sign and Close sits at bookY + 194 (y 196)
    private static final int PAGE_X = 36, PAGE_W = 114;
    private static final int WARNING_Y = 82;             // vanilla's "Note! When you sign..." warning
    private static final int PAGE_BOTTOM = 170;          // last row of writable page
    private static final int NOTE_INK = 0xFF8A5F00;      // dark gold, the same gold as the frame's rim
    private static final float MIN_NOTE_SCALE = 0.5F;

    // Yellow face, Minecraft-button bevel. Off is a softer yellow so ticking it visibly lights it up.
    private static final int FACE_ON = 0xFFFFC21A;
    private static final int FACE_OFF = 0xFFE3C35C;
    private static final int FACE_HOVER_LIFT = 0x00141414;
    private static final int LIGHT_EDGE = 0xFFFFE89A;
    private static final int DARK_EDGE = 0xFF8A5F00;
    private static final int OUTLINE = 0xFF000000;
    private static final int OUTLINE_ON = 0xFF5A3A00;
    /** The star is tinted dark amber so it reads on yellow; filled when ticked, outline when not. */
    private static final float[] STAR_TINT = {0.36F, 0.22F, 0.0F};

    private static final ResourceLocation STAR =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/star");
    private static final ResourceLocation STAR_FILLED =
            ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "widget/star_filled");

    /** The sign screen this toggle's state belongs to; a new screen starts unticked. */
    private static WeakReference<BookEditScreen> owner = new WeakReference<>(null);
    private static boolean ticked = false;

    private PriorityBookSignToggle() {}

    /**
     * A 20×20 star on a yellow face. The words live in the tooltip and the narration message; the
     * Priority Books left sit as a corner badge, the way an item stack shows its count.
     */
    private static final class Toggle extends Button {
        Toggle(int x, int y, OnPress onPress) {
            super(x, y, SIZE, SIZE, Component.empty(), onPress, DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int face = (ticked ? FACE_ON : FACE_OFF) + (isHoveredOrFocused() ? FACE_HOVER_LIFT : 0);
            g.fill(x, y, x + SIZE, y + SIZE, ticked ? OUTLINE_ON : OUTLINE);
            g.fill(x + 1, y + 1, x + SIZE - 1, y + SIZE - 1, DARK_EDGE);
            g.fill(x + 1, y + 1, x + SIZE - 2, y + SIZE - 2, LIGHT_EDGE);
            g.fill(x + 2, y + 2, x + SIZE - 2, y + SIZE - 2, face);
            g.setColor(STAR_TINT[0], STAR_TINT[1], STAR_TINT[2], 1.0F);
            g.blitSprite(ticked ? STAR_FILLED : STAR, x + 1, y + 1, 18, 18);
            g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            String count = Integer.toString(shownCount());
            Font font = Minecraft.getInstance().font;
            g.pose().pushPose();
            g.pose().translate(0.0F, 0.0F, 200.0F);
            g.drawString(font, count, x + SIZE - font.width(count), y + SIZE - font.lineHeight + 1, 0xFFFFFFFF, true);
            g.pose().popPose();
        }
    }

    private static boolean applies(BookEditScreen screen) {
        return ((BookEditScreenAccessor) screen).dungeontrain$isSigning()
                && consented()
                && !EditorStatusHudOverlay.isActive()
                && !LetterEditorClient.isLetterOpen()
                && !ClientBookSuspension.isSuspended()
                && BookCredits.current() > 0;
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof BookEditScreen screen)) return;
        if (owner.get() != screen) {
            owner = new WeakReference<>(screen);
            ticked = false;
            BookCredits.refresh(null);
        }
        // Positioned beside Sign and Close every frame (see placeBesideSign); 0,0 until the first one.
        event.addListener(new Toggle(0, 0, b -> ticked = !ticked));
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof BookEditScreen screen)) return;
        boolean show = applies(screen);
        if (!show) ticked = false; // credits ran out or signing was cancelled: never spend unseen
        for (GuiEventListener child : screen.children()) {
            if (child instanceof Toggle toggle) {
                toggle.visible = show;
                placeBesideSign(screen, toggle);
                Component text = label(shownCount());
                Component shown = ticked ? Component.literal("✔ ").append(text).withStyle(ChatFormatting.GOLD) : text;
                // The message is what narration reads; the tooltip carries the words a sighted reader needs.
                if (shown.getString().equals(toggle.getMessage().getString())) continue;
                toggle.setMessage(shown);
                toggle.setTooltip(Tooltip.create(shown.copy().append("\n").append(
                        PriorityBookMessage.signTooltip().copy().withStyle(ChatFormatting.GRAY))));
            }
        }
    }

    /**
     * While the star is ticked, a gold glow BEHIND the whole book — the selected state, readable from
     * across the screen. Called by {@code BookEditScreenPriorityGlowMixin} immediately before vanilla's
     * {@code renderBackground} blits the book (vanilla never calls {@code super.renderBackground}, so
     * NeoForge's {@code BackgroundRendered} event does not fire here). The book then lands on top, and the
     * gold shows round its edges and through its rounded corners; nothing on the page is ever covered.
     * Drawn at the book blit's own coordinates and under the same pose, so a mod that moves the book
     * moves the glow with it.
     */
    public static void drawGlow(BookEditScreen screen, GuiGraphics g) {
        if (owner.get() != screen || !ticked || !applies(screen)) return;
        int bx = (screen.width - BOOK_IMAGE) / 2;
        int by = BOOK_BLIT_Y;
        int l = bx + ART_LEFT - FRAME_OUT, r = bx + ART_RIGHT + FRAME_OUT;
        int t = by + ART_TOP - FRAME_OUT, b = by + ART_BOTTOM + FRAME_OUT;
        roundedRect(g, l - 1, t - 1, r + 1, b + 1, FRAME_RADIUS + 1, FRAME_RIM);
        roundedRect(g, l, t, r, b, FRAME_RADIUS, FRAME_GOLD);
    }

    /**
     * While the star is ticked, the thank-you note on the page, under vanilla's "Note! When you sign the
     * book..." warning: same column, same left alignment, dark gold ink. A translation too long for the
     * space left above the page bottom is drawn scaled down until it fits. Located from the live Sign
     * and Close button (vanilla puts it 194 below the book image), so it follows a moved book.
     */
    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof BookEditScreen screen) || owner.get() != screen) return;
        if (!ticked || !applies(screen)) return;
        Button sign = ((BookEditScreenAccessor) screen).dungeontrain$getFinalizeButton();
        if (sign == null) return;
        Font font = Minecraft.getInstance().font;
        int x = (screen.width - BOOK_IMAGE) / 2 + PAGE_X;
        int bookY = sign.getY() - SIGN_BELOW_BOOK;
        int warningLines = font.split(Component.translatable("book.finalizeWarning"), PAGE_W).size();
        int top = bookY + WARNING_Y + (warningLines + 1) * font.lineHeight;
        int room = bookY + PAGE_BOTTOM - top;
        float scale = 1.0F;
        List<FormattedCharSequence> lines = font.split(PriorityBookMessage.signNote(), PAGE_W);
        while (lines.size() * font.lineHeight * scale > room && scale > MIN_NOTE_SCALE) {
            scale -= 0.05F;
            lines = font.split(PriorityBookMessage.signNote(), (int) (PAGE_W / scale));
        }
        GuiGraphics g = event.getGuiGraphics();
        g.pose().pushPose();
        g.pose().translate(x, top, 0.0F);
        g.pose().scale(scale, scale, 1.0F);
        int y = 0;
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, 0, y, NOTE_INK, false);
            y += font.lineHeight;
        }
        g.pose().popPose();
    }

    /** A filled rectangle with corners rounded to {@code radius}, one fill per corner row. */
    private static void roundedRect(GuiGraphics g, int x0, int y0, int x1, int y1, int radius, int color) {
        g.fill(x0, y0 + radius, x1, y1 - radius, color);
        for (int i = 0; i < radius; i++) {
            double dy = radius - i - 0.5;
            int inset = (int) Math.round(radius - Math.sqrt(radius * radius - dy * dy));
            g.fill(x0 + inset, y0 + i, x1 - inset, y0 + i + 1, color);
            g.fill(x0 + inset, y1 - i - 1, x1 - inset, y1 - i, color);
        }
    }

    /**
     * Just left of Sign and Close, on its row. Read from the live button each frame, so the star
     * follows it wherever vanilla, a resize, or a re-centring mod (Scribble) has put it.
     */
    private static void placeBesideSign(BookEditScreen screen, Toggle toggle) {
        Button sign = ((BookEditScreenAccessor) screen).dungeontrain$getFinalizeButton();
        if (sign == null) return;
        toggle.setX(sign.getX() - GAP - SIZE);
        toggle.setY(sign.getY() + (sign.getHeight() - SIZE) / 2);
    }

    /**
     * Called from {@code saveChanges} HEAD: on a sign with the box ticked, send the intent ahead of
     * vanilla's sign packet and count the credit as on its way.
     */
    public static void sendForNextSign(BookEditScreen screen, boolean publish) {
        if (!publish || owner.get() != screen || !ticked || !applies(screen)) return;
        ticked = false;
        DungeonTrainNet.sendToServer(PriorityBookIntentPacket.INSTANCE);
        BookCredits.spentOne();
    }

    /**
     * The Priority Books left AFTER this sign: ticking the star takes one, so the badge and the
     * "(N left)" go down by one the moment it is pressed and come back if it is pressed again.
     */
    private static int shownCount() {
        int now = BookCredits.current();
        return ticked ? Math.max(0, now - 1) : now;
    }

    private static Component label(int left) {
        return PriorityBookMessage.signToggle(ClientLanguage.selected(), left);
    }

    private static boolean consented() {
        try {
            return DiscordPresenceClientConfig.isGranted();
        } catch (Throwable t) {
            return false;
        }
    }
}
