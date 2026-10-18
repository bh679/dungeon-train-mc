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
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.network.chat.Component;
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
 * <p>Placed to the LEFT of the book, mirroring the language dropdown on the right, when its whole label
 * fits there; otherwise it spans the foot of the book just above the sign buttons.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PriorityBookSignToggle {

    private static final int MIN_WIDTH = 110;
    private static final int MAX_WIDTH = 230;
    private static final int TEXT_PAD = 24;
    private static final int HEIGHT = 20;
    private static final int EDGE = 1;
    private static final int BOOK_HALF = 96;
    private static final int GAP = 6;
    private static final int SIDE_Y = 40;
    private static final int PAGE_Y = 172;

    /** The sign screen this toggle's state belongs to; a new screen starts unticked. */
    private static WeakReference<BookEditScreen> owner = new WeakReference<>(null);
    private static boolean ticked = false;

    private PriorityBookSignToggle() {}

    /** Marker subclass, so the render pass can find our button among the screen's children. */
    private static final class Toggle extends Button {
        Toggle(int x, int y, int width, OnPress onPress) {
            super(x, y, width, HEIGHT, Component.empty(), onPress, DEFAULT_NARRATION);
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
        Minecraft mc = Minecraft.getInstance();
        // Sized for the TICKED label ("✔ " + text), so ticking it never clips "(N left)".
        int width = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH,
                mc.font.width(Component.literal("✔ ").append(label(Math.max(1, BookCredits.current()))))
                        + TEXT_PAD));
        int sideRoom = screen.width / 2 - BOOK_HALF - GAP - EDGE;
        // Beside the book only when the whole label fits there; otherwise across the book's foot.
        boolean side = sideRoom >= width;
        int x;
        int y;
        if (side) {
            width = Math.min(width, sideRoom);
            x = screen.width / 2 - BOOK_HALF - GAP - width;
            y = SIDE_Y;
        } else {
            width = Math.min(width, 2 * BOOK_HALF - 2 * GAP);
            x = (screen.width - width) / 2;
            y = PAGE_Y;
        }
        Toggle toggle = new Toggle(x, y, width, b -> ticked = !ticked);
        toggle.setTooltip(Tooltip.create(PriorityBookMessage.signTooltip()));
        event.addListener(toggle);
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
                toggle.setMessage(ticked
                        ? Component.literal("✔ ").append(text).withStyle(ChatFormatting.GOLD)
                        : text);
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
