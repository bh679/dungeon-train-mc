package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.effects.archivist.ArchivistPerks;
import games.brennan.dungeontrain.perks.effects.archivist.follow.FollowRequestPacket;
import games.brennan.dungeontrain.perks.effects.archivist.follow.FollowsClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.HashSet;
import java.util.Set;

/**
 * Favourite Author's Follow button on a community book's vote page, by the boost star: follows the
 * account that wrote the book (the relay resolves it from the book id, so a pen name still reaches
 * the right player). Shown with a perk point free for one more follow; reads "Following" for a followed author.
 */
public final class BookFollowControl {

    private static final int COLOR = 0xFF3F2A14, COLOR_HOVER = 0xFF8A5A10, COLOR_DONE = 0xFF5A6A3A;
    /** Books followed from here this session, so the button flips before the relay answers. */
    private static final Set<String> CLICKED = new HashSet<>();

    private static String author = "";
    private static int x0, y0, x1, y1;
    private static boolean drawn;

    private BookFollowControl() {}

    static void open(ItemStack stack) {
        WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        author = content == null ? "" : content.author();
        drawn = false;
    }

    /**
     * On somebody else's community book, with a Favourite Author point free for one more follow (or the
     * author already followed, so it reads "Following").
     */
    static boolean shown(String bookType, String bookId, boolean own) {
        Minecraft mc = Minecraft.getInstance();
        return "shared".equals(bookType) && !own && bookId != null && bookId.matches("\\d{1,9}")
            && mc.player != null && Perks.isUnlocked(mc.player, ArchivistPerks.FAV_AUTHOR)
            && (following(bookId) || FollowsClientState.canFollowMore(Perks.rank(mc.player, ArchivistPerks.FAV_AUTHOR)));
    }

    private static boolean following(String bookId) {
        return CLICKED.contains(bookId) || FollowsClientState.followsName(author);
    }

    /** Draws the button centred on {@code cx} at {@code y}. */
    static void render(GuiGraphics g, Font font, String bookId, int cx, int y, int mouseX, int mouseY) {
        boolean done = following(bookId);
        Component label = Component.translatable(done ? "gui.dungeontrain.follows.following" : "gui.dungeontrain.follows.follow");
        int w = font.width(label);
        x0 = cx - w / 2;
        y0 = y;
        x1 = x0 + w;
        y1 = y + font.lineHeight;
        drawn = true;
        boolean hover = !done && mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
        Component shownLabel = hover ? label.copy().withStyle(net.minecraft.ChatFormatting.UNDERLINE) : label;
        g.drawString(font, shownLabel, x0, y0, done ? COLOR_DONE : hover ? COLOR_HOVER : COLOR, false);
    }

    /** A click on the button: follow. True when it took the click. */
    static boolean click(int mx, int my, String bookId) {
        if (!drawn || mx < x0 || mx >= x1 || my < y0 || my >= y1 || following(bookId)) return false;
        CLICKED.add(bookId);
        DungeonTrainNet.sendToServer(new FollowRequestPacket(FollowRequestPacket.Op.FOLLOW_BOOK, bookId));
        return true;
    }

    static void reset() {
        drawn = false;
        author = "";
    }
}
