package games.brennan.dungeontrain.compat;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.tradeeverything.api.TradeEverythingApi;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code /dungeontrain debug trade-values dump} — writes every registered item with its name, what
 * Trade Everything prices it at right now (a plain one-item stack), and where that price comes from,
 * to {@code <server dir>/trade-values-catalog.json}. The file is copied verbatim to the relay page
 * ({@code public/dungeontrain/items/catalog.json}) so the community sees the real list.
 *
 * <p>{@code source} is one of: {@code approved} (from {@link TradeValueTable}), {@code dt-bridge}
 * (a hand-tuned constant in {@link TradeEverythingBridge}), {@code dynamic} (priced per stack —
 * potions, written books — so a fixed value would override that logic), or {@code te-default}.</p>
 *
 * <p>Hard-imports the TE API like the bridge does; the caller gates on
 * {@code ModList.isLoaded("tradeeverything")} + {@code catch (Throwable)}.</p>
 */
public final class TradeValuesCatalogDump {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String FILE_NAME = "trade-values-catalog.json";

    /** Items the bridge prices per stack rather than per id. */
    private static final Set<Item> DYNAMIC = Set.of(
        Items.WRITTEN_BOOK, Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION);

    private TradeValuesCatalogDump() {}

    /** Writes the catalog and returns the path; throws on I/O failure so the command can report it. */
    public static Path dump(MinecraftServer server, String dtVersion) throws IOException {
        Path out = server.getServerDirectory().resolve(FILE_NAME);
        JsonObject root = new JsonObject();
        root.addProperty("generatedAt", Instant.now().toString());
        root.addProperty("dtVersion", dtVersion);
        root.addProperty("unit", "sixteenths");
        JsonArray items = new JsonArray();
        int n = 0;
        for (Item item : sortedItems()) {
            if (item == Items.AIR) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            ItemStack stack = new ItemStack(item);
            JsonObject o = new JsonObject();
            o.addProperty("id", id.toString());
            o.addProperty("name", stack.getHoverName().getString());
            o.addProperty("value", safeValue(stack));
            o.addProperty("source", sourceOf(id, item, stack));
            items.add(o);
            n++;
        }
        root.add("items", items);
        Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        LOGGER.info("[trade-values] wrote {} items to {}", n, out);
        return out;
    }

    private static Iterable<Item> sortedItems() {
        TreeMap<String, Item> byId = new TreeMap<>();
        for (Item item : BuiltInRegistries.ITEM) byId.put(BuiltInRegistries.ITEM.getKey(item).toString(), item);
        return byId.values();
    }

    private static int safeValue(ItemStack stack) {
        try {
            return TradeEverythingApi.getValueSixteenths(stack);
        } catch (Throwable t) {
            return -1;
        }
    }

    static String sourceOf(ResourceLocation id, Item item, ItemStack stack) {
        if (TradeValueTable.valueOf(id).isPresent()) return "approved";
        if (DYNAMIC.contains(item)) return "dynamic";
        if (TradeEverythingBridge.isHandTuned(id, stack)) return "dt-bridge";
        return "te-default";
    }
}
