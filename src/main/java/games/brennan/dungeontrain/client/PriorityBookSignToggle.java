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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.lang.ref.WeakReference;

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
 * <p>A star on a yellow face, just outside the book's left edge (the language dropdown sits on the
 * right): outline when off, filled when ticked, the count left as a corner badge, the words in its
 * tooltip.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PriorityBookSignToggle {

    private static final int SIZE = 20;                  // square icon button
    private static final int BOOK_HALF = 96;
    private static final int GAP = 6;
    private static final int SIDE_Y = 40;                // level with the title area
    private static final int PAGE_Y = 172;               // fallback: centred at the book's foot

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
            String count = Integer.toString(BookCredits.current());
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
        // Just outside the book's left edge when there is room (almost always: it is 20 px), else
        // centred at the book's foot above the sign buttons.
        boolean side = screen.width / 2 - BOOK_HALF - GAP - SIZE >= 0;
        int x = side ? screen.width / 2 - BOOK_HALF - GAP - SIZE : (screen.width - SIZE) / 2;
        int y = side ? SIDE_Y : PAGE_Y;
        event.addListener(new Toggle(x, y, b -> ticked = !ticked));
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof BookEditScreen screen)) return;
        boolean show = applies(screen);
        if (!show) ticked = false; // credits ran out or signing was cancelled: never spend unseen
        for (GuiEventListener child : screen.children()) {
            if (child instanceof Toggle toggle) {
                toggle.visible = show;
                Component text = label(BookCredits.current());
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
     * Called from {@code saveChanges} HEAD: on a sign with the box ticked, send the intent ahead of
     * vanilla's sign packet and count the credit as on its way.
     */
    public static void sendForNextSign(BookEditScreen screen, boolean publish) {
        if (!publish || owner.get() != screen || !ticked || !applies(screen)) return;
        ticked = false;
        DungeonTrainNet.sendToServer(PriorityBookIntentPacket.INSTANCE);
        BookCredits.spentOne();
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
