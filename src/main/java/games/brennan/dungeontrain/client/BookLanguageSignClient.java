package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.mixin.client.BookEditScreenAccessor;
import games.brennan.dungeontrain.narrative.ReadingLanguages;
import games.brennan.dungeontrain.net.BookLanguagePacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
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
import java.util.ArrayList;
import java.util.List;

/**
 * The "which language is this book in?" dropdown on the book-and-quill sign screen.
 *
 * <p>Shown only while signing, only for a player who reads more than one language (Options › Dungeon
 * Train › Book languages), and never in an editor plot (those books are props, not community books). It
 * starts on the game language; picking another one files the book under it. The pick travels as a
 * {@link BookLanguagePacket} from {@code saveChanges(publish=true)} HEAD (see
 * {@code BookEditScreenSignMixin}), ahead of vanilla's edit packet; the server only honours one of the
 * player's reading languages.</p>
 *
 * <p>A dropdown is a head button plus one button per language beneath it, shown while it is open. It sits
 * to the right of the book, clear of the page text and the sign buttons; on a GUI too narrow for that it
 * goes on the page below the sign note.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BookLanguageSignClient {

    private static final String KEY = "gui.dungeontrain.book_language";
    /** Width bounds; within them the dropdown fits its longest language name plus the arrow. */
    private static final int MIN_WIDTH = 110;
    private static final int MAX_WIDTH = 170;
    private static final int TEXT_PAD = 24;
    /** Air kept between a side-placed dropdown and the window's right edge. */
    private static final int EDGE = 4;
    private static final int HEIGHT = 20;
    /** Half the vanilla book texture's width. */
    private static final int BOOK_HALF = 96;
    private static final int GAP = 6;
    private static final int SIDE_Y = 40;
    /** On-page fallback: the left edge of the page column and the row below the sign note. */
    private static final int PAGE_X_FROM_BOOK = 36;
    private static final int PAGE_W = 114;
    private static final int PAGE_Y = 150;

    /** The screen the state below belongs to — a new sign screen starts over on the game language. */
    private static WeakReference<BookEditScreen> owner = new WeakReference<>(null);
    private static String chosen;
    private static boolean open;

    private BookLanguageSignClient() {}

    /** Head button of the dropdown. */
    private static final class Head extends Button {
        Head(int x, int y, int width, OnPress onPress) {
            super(x, y, width, HEIGHT, Component.empty(), onPress, DEFAULT_NARRATION);
        }
    }

    /** One language in the open dropdown. */
    private static final class Option extends Button {
        Option(int x, int y, int width, Component name, OnPress onPress) {
            super(x, y, width, HEIGHT, name, onPress, DEFAULT_NARRATION);
        }
    }

    /** The game language followed by the extra reading languages. */
    private static List<String> choices() {
        String game = ClientLanguage.selected();
        List<String> out = new ArrayList<>();
        out.add(game);
        for (ReadingLanguages.Entry e : ClientDisplayConfig.readingLanguages(game)) out.add(e.locale());
        return out;
    }

    private static boolean applies(BookEditScreen screen) {
        return ((BookEditScreenAccessor) screen).dungeontrain$isSigning()
                && !EditorStatusHudOverlay.isActive()
                && choices().size() > 1;
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof BookEditScreen screen)) return;
        List<String> choices = choices();
        if (choices.size() < 2) return;
        if (owner.get() != screen) {
            owner = new WeakReference<>(screen);
            chosen = choices.get(0);
            open = false;
        }
        int width = MIN_WIDTH;
        for (String locale : choices) {
            Component head = Component.translatable(KEY + ".head", ReadingLanguagesScreen.languageName(locale));
            width = Math.max(width, net.minecraft.client.Minecraft.getInstance().font.width(head) + TEXT_PAD);
        }
        width = Math.min(width, MAX_WIDTH);
        boolean side = screen.width / 2 + BOOK_HALF + GAP + width + EDGE <= screen.width;
        int x;
        if (side) {
            x = screen.width / 2 + BOOK_HALF + GAP;
        } else {
            width = Math.min(width, PAGE_W);
            x = (screen.width - 2 * BOOK_HALF) / 2 + PAGE_X_FROM_BOOK + (PAGE_W - width) / 2;
        }
        int y = side ? SIDE_Y : PAGE_Y;
        Head head = new Head(x, y, width, b -> open = !open);
        head.setTooltip(Tooltip.create(Component.translatable(KEY + ".tip")));
        event.addListener(head);
        for (int i = 0; i < choices.size(); i++) {
            String locale = choices.get(i);
            event.addListener(new Option(x, y + HEIGHT * (i + 1), width, ReadingLanguagesScreen.languageName(locale), b -> {
                chosen = locale;
                open = false;
            }));
        }
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof BookEditScreen screen)) return;
        boolean show = applies(screen);
        for (GuiEventListener child : screen.children()) {
            if (child instanceof Head head) {
                head.visible = show;
                head.setMessage(Component.translatable(KEY + ".head", ReadingLanguagesScreen.languageName(chosen)));
            } else if (child instanceof Option option) {
                option.visible = show && open;
            }
        }
    }

    /** From {@code saveChanges(publish=true)} HEAD: tell the server which language this book is in. */
    public static void sendForNextSign(BookEditScreen screen) {
        if (owner.get() != screen || chosen == null || !applies(screen)) return;
        try {
            DungeonTrainNet.sendToServer(new BookLanguagePacket(chosen));
        } catch (Throwable ignored) {
            // No connection — the server files the book under the game language, as before.
        }
    }
}
