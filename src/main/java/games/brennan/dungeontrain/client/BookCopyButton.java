package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.client.support.SupporterStatus;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.mixin.client.BookViewScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.LecternScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WrittenBookContent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A thank-you for supporters: a Copy button on the book-reading screen that puts the whole book on the
 * clipboard as plain text ({@link BookCopyText}). Shown only while the player has switched it on
 * (Options, {@link ClientDisplayConfig#isBookCopyButtonEnabled()}, off by default) <b>and</b> the relay
 * says they have given at least {@link SupporterStatus#MIN_TOTAL_AUD} ({@link SupporterStatus}).
 *
 * <p>Every {@link BookViewScreen}: held books and lecterns alike. It sits just right of the screen's
 * rightmost bottom button (Done, or a lectern's Take Book), re-measured every frame so it follows a
 * resize or a mod that moves the book. Purely local: nothing is sent to the server.</p>
 */
@EventBusSubscriber(modid = "dungeontrain", value = Dist.CLIENT)
public final class BookCopyButton {

    private static final int GAP = 4;
    private static final int PAD = 10;
    private static final int MIN_W = 40;
    private static final int H = 20;
    /** How long the label reads "Copied" after a click. */
    private static final long COPIED_MS = 2000L;

    private static final String COPY_KEY = "gui.dungeontrain.book.copy";
    private static final String COPIED_KEY = "gui.dungeontrain.book.copied";

    private static @Nullable Button button = null;
    private static @Nullable BookViewScreen screen = null;
    private static long copiedAt = 0L;

    private BookCopyButton() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof BookViewScreen book)) return;
        // Re-check the relay now, so a gift given this session unlocks the button on the next book.
        SupporterStatus.refresh(null);
        screen = book;
        copiedAt = 0L;
        Component label = Component.translatable(COPY_KEY);
        Button b = Button.builder(label, btn -> copy(book))
                .bounds(0, 0, widthFor(label), H)
                .tooltip(Tooltip.create(Component.translatable(COPY_KEY + ".tip")))
                .build();
        b.visible = shown();
        button = b;
        event.addListener(b);
        place(book, b);
    }

    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        Button b = button;
        if (b == null || event.getScreen() != screen) return;
        b.visible = shown();
        if (!b.visible) return;
        boolean showCopied = copiedAt != 0L && System.currentTimeMillis() - copiedAt < COPIED_MS;
        Component label = Component.translatable(showCopied ? COPIED_KEY : COPY_KEY);
        if (!label.getString().equals(b.getMessage().getString())) {
            b.setMessage(label);
            b.setWidth(widthFor(label));
        }
        place(screen, b);
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (event.getScreen() != screen) return;
        screen = null;
        button = null;
    }

    private static boolean shown() {
        return ClientDisplayConfig.isBookCopyButtonEnabled() && SupporterStatus.isSupporter();
    }

    private static int widthFor(Component label) {
        int copy = Minecraft.getInstance().font.width(Component.translatable(COPY_KEY));
        int copied = Minecraft.getInstance().font.width(Component.translatable(COPIED_KEY));
        // Sized for the longer of the two labels so the button doesn't jump when it flips.
        return Math.max(MIN_W, Math.max(copy, copied) + PAD);
    }

    /** Beside the rightmost vanilla bottom button (Done / Take Book), or below the book art if none. */
    private static void place(BookViewScreen book, Button b) {
        Button anchor = null;
        Button done = null;
        for (GuiEventListener child : book.children()) {
            if (!(child instanceof Button other) || other == b) continue;
            if (CommonComponents.GUI_DONE.getString().equals(other.getMessage().getString())) done = other;
        }
        if (done != null) {
            anchor = done;
            for (GuiEventListener child : book.children()) {
                if (!(child instanceof Button other) || other == b || !other.visible) continue;
                if (other.getY() == done.getY() && other.getRight() > anchor.getRight()) anchor = other;
            }
        }
        if (anchor != null) {
            b.setPosition(anchor.getRight() + GAP, anchor.getY());
        } else {
            b.setPosition(book.width / 2 + 100 + GAP, 196);
        }
    }

    private static void copy(BookViewScreen book) {
        if (!shown()) return;
        List<String> pages = new ArrayList<>();
        BookViewScreen.BookAccess access = ((BookViewScreenAccessor) (Object) book).dungeontrain$getBookAccess();
        if (access != null) {
            for (Component page : access.pages()) pages.add(page.getString());
        }
        WrittenBookContent content = contentFor(book, pages);
        String title = null;
        String byLine = null;
        if (content != null) {
            title = content.title().get(Minecraft.getInstance().isTextFilteringEnabled());
            if (content.author() != null && !content.author().isBlank()) {
                byLine = Component.translatable("book.byAuthor", content.author()).getString();
            }
        }
        String text = BookCopyText.format(title, byLine, pages);
        if (text.isBlank()) return;
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
        copiedAt = System.currentTimeMillis();
    }

    /**
     * The written-book data behind this screen, for its title and author: the lectern's book, or the
     * held book whose first page matches what is shown. Null when neither can be told.
     */
    private static @Nullable WrittenBookContent contentFor(BookViewScreen book, List<String> shownPages) {
        if (book instanceof LecternScreen lectern) {
            return lectern.getMenu().getBook().get(DataComponents.WRITTEN_BOOK_CONTENT);
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return null;
        String first = shownPages.isEmpty() ? "" : shownPages.get(0);
        for (ItemStack stack : List.of(player.getMainHandItem(), player.getOffhandItem())) {
            WrittenBookContent c = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (c == null) continue;
            List<Component> pages = c.getPages(Minecraft.getInstance().isTextFilteringEnabled());
            String cFirst = pages.isEmpty() ? "" : pages.get(0).getString();
            if (cFirst.equals(first)) return c;
        }
        return null;
    }
}
