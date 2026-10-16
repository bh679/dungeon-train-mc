package games.brennan.dungeontrain.editor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The signed-book side of a {@link ContainerContentsEntry}: reading the book off a stack an author
 * adds from their hand, putting it back on the stack the roller spawns, and the JSON / NBT forms the
 * store files and the clipboard keep it in.
 *
 * <p>Mirrors {@link ContainerContentsPotions}. A written-book entry with no stored book (added by id
 * alone) spawns vanilla's blank written book, exactly as before.</p>
 */
public final class ContainerContentsBooks {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private static final String KEY_TITLE = "title";
    private static final String KEY_AUTHOR = "author";
    private static final String KEY_GENERATION = "generation";
    private static final String KEY_PAGES = "pages";

    private ContainerContentsBooks() {}

    /**
     * The signed book carried by {@code stack}, or {@code null} when it is not a written book or
     * carries no content. Pages are taken unfiltered — the book as its author wrote it.
     */
    public static @Nullable StoredBook bookOf(ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.WRITTEN_BOOK)) return null;
        WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) return null;
        List<String> pages = new ArrayList<>(content.pages().size());
        for (Filterable<Component> page : content.pages()) {
            pages.add(Component.Serializer.toJson(page.raw(), registries));
        }
        return new StoredBook(content.title().raw(), content.author(), content.generation(), pages);
    }

    /**
     * Put the stored book onto a freshly built stack. No-op when {@code book} is null or the stack is
     * not a written book. A page that will not parse spawns empty rather than failing the roll.
     */
    public static void applyStoredBook(ItemStack stack, @Nullable StoredBook book,
                                       HolderLookup.Provider registries) {
        if (book == null || stack.isEmpty() || !stack.is(Items.WRITTEN_BOOK)) return;
        List<Filterable<Component>> pages = new ArrayList<>(book.pages().size());
        for (String json : book.pages()) {
            pages.add(Filterable.passThrough(pageComponent(json, registries)));
        }
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
            Filterable.passThrough(book.title()), book.author(), book.generation(), pages, true));
    }

    private static Component pageComponent(String json, HolderLookup.Provider registries) {
        try {
            Component parsed = Component.Serializer.fromJson(json, registries);
            if (parsed != null) return parsed;
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Stored loot book: unreadable page — {}", e.toString());
        }
        return Component.empty();
    }

    // ---- JSON (store files) -----------------------------------------------------------------

    public static JsonObject toJson(StoredBook book) {
        JsonObject o = new JsonObject();
        o.addProperty(KEY_TITLE, book.title());
        o.addProperty(KEY_AUTHOR, book.author());
        o.addProperty(KEY_GENERATION, book.generation());
        JsonArray pages = new JsonArray();
        book.pages().forEach(pages::add);
        o.add(KEY_PAGES, pages);
        return o;
    }

    /** Compact JSON text for the hand-built store writers. Strings are escaped by Gson. */
    public static String toJsonText(StoredBook book) {
        return GSON.toJson(toJson(book));
    }

    /** The book in {@code element}, or {@code null} when it is missing or malformed. */
    public static @Nullable StoredBook fromJson(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        try {
            JsonObject o = element.getAsJsonObject();
            if (!o.has(KEY_TITLE) || !o.has(KEY_PAGES) || !o.get(KEY_PAGES).isJsonArray()) return null;
            List<String> pages = new ArrayList<>();
            for (JsonElement p : o.getAsJsonArray(KEY_PAGES)) {
                if (p.isJsonPrimitive()) pages.add(p.getAsString());
            }
            String author = o.has(KEY_AUTHOR) ? o.get(KEY_AUTHOR).getAsString() : "";
            int generation = o.has(KEY_GENERATION) ? o.get(KEY_GENERATION).getAsInt() : 0;
            return new StoredBook(o.get(KEY_TITLE).getAsString(), author, generation, pages);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Stored loot book: malformed JSON, dropping it — {}", e.toString());
            return null;
        }
    }

    // ---- NBT (clipboard) --------------------------------------------------------------------

    public static CompoundTag toNbt(StoredBook book) {
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_TITLE, book.title());
        tag.putString(KEY_AUTHOR, book.author());
        tag.putInt(KEY_GENERATION, book.generation());
        ListTag pages = new ListTag();
        book.pages().forEach(p -> pages.add(StringTag.valueOf(p)));
        tag.put(KEY_PAGES, pages);
        return tag;
    }

    /** The book in {@code tag}, or {@code null} when it is missing or malformed. */
    public static @Nullable StoredBook fromNbt(@Nullable CompoundTag tag) {
        if (tag == null || !tag.contains(KEY_TITLE, Tag.TAG_STRING)
            || !tag.contains(KEY_PAGES, Tag.TAG_LIST)) return null;
        ListTag list = tag.getList(KEY_PAGES, Tag.TAG_STRING);
        List<String> pages = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) pages.add(list.getString(i));
        return new StoredBook(tag.getString(KEY_TITLE), tag.getString(KEY_AUTHOR),
            tag.getInt(KEY_GENERATION), pages);
    }
}
