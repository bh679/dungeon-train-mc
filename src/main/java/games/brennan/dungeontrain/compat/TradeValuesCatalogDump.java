package games.brennan.dungeontrain.compat;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import games.brennan.tradeeverything.api.TradeEverythingApi;
import games.brennan.tradeeverything.config.TradeEverythingConfig;
import games.brennan.tradeeverything.trade.ItemValuation;
import games.brennan.tradeeverything.trade.RecipeValues;
import games.brennan.tradeeverything.trade.TradePricer;
import net.minecraft.world.item.trading.MerchantOffers;
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

    /** The payouts an emerald trade can use: emeralds, stepping up to emerald blocks for dear items. */
    private static final Item[] PAYOUTS = { Items.EMERALD, Items.EMERALD_BLOCK };

    /** Items the bridge prices per stack rather than per id. */
    private static final Set<Item> DYNAMIC = Set.of(
        Items.WRITTEN_BOOK, Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION);

    private TradeValuesCatalogDump() {}

    /** Writes the catalog and returns the path; throws on I/O failure so the command can report it. */
    public static Path dump(MinecraftServer server, String dtVersion) throws IOException {
        Path out = server.getServerDirectory().resolve(FILE_NAME);
        // The material-printer cap reads the recipe index; make sure it exists before quoting.
        RecipeValues.ensureIndexed(server);
        TradeEverythingConfig config = TradeEverythingApi.config();
        JsonObject root = new JsonObject();
        root.addProperty("generatedAt", Instant.now().toString());
        root.addProperty("dtVersion", dtVersion);
        root.addProperty("unit", "sixteenths");
        root.add("trade", tradeSettings(config));
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
            addTrade(o, item, stack, config);
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

    /** The settings that turn a value into a villager's emerald payout, for the page to redo the maths. */
    private static JsonObject tradeSettings(TradeEverythingConfig config) {
        JsonObject t = new JsonObject();
        t.addProperty("precision", ItemValuation.PRECISION);
        t.addProperty("resultMultiplier", config.resultMultiplier());
        t.addProperty("maxCostCount", config.maxCostCount());
        t.addProperty("maxResultCount", config.maxResultCount());
        t.addProperty("allowUndervaluedTrades", config.allowUndervaluedTrades());
        t.addProperty("preferSingleItemTrades", config.preferSingleItemTrades());
        JsonObject payouts = new JsonObject();
        for (Item payout : PAYOUTS) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(payout);
            JsonObject p = new JsonObject();
            p.addProperty("units", ItemValuation.valueUnits(new ItemStack(payout)));
            p.addProperty("multiplier", TradePricer.payoutMultiplier(payout, config));
            p.addProperty("stack", Math.min(64, new ItemStack(payout).getMaxStackSize()));
            payouts.add(id.toString(), p);
        }
        t.add("payouts", payouts);
        return t;
    }

    /**
     * The item's exact emerald trade as Trade Everything quotes it for a villager with no goods of its
     * own (so the payout is emeralds, stepping up to emerald blocks for dear items), plus what the
     * page needs to redo that quote for a different value: the internal value units, the stack size,
     * and — only for items crafted from emeralds or emerald blocks — the material-printer cap per batch.
     */
    private static void addTrade(JsonObject o, Item item, ItemStack stack, TradeEverythingConfig config) {
        try {
            o.addProperty("units", ItemValuation.valueUnits(stack));
            o.addProperty("stack", stack.getMaxStackSize());
            MerchantOffers none = new MerchantOffers();
            Item payout = TradePricer.payoutFor(stack, Items.EMERALD, none, config);
            TradePricer.quote(stack, payout, TradePricer.payoutValueUnits(payout, none), config).ifPresentOrElse(q -> {
                JsonObject quote = new JsonObject();
                quote.addProperty("cost", q.costCount());
                quote.addProperty("result", q.resultCount());
                quote.addProperty("payout", BuiltInRegistries.ITEM.getKey(payout).toString());
                o.add("quote", quote);
            }, () -> o.add("quote", com.google.gson.JsonNull.INSTANCE));
            JsonObject caps = new JsonObject();
            for (Item payoutItem : PAYOUTS) {
                if (RecipeValues.maxMaterialPayout(item, payoutItem, 1).isEmpty()) continue;
                JsonArray perBatch = new JsonArray();
                for (int count = 1; count <= 64; count++) {
                    perBatch.add(RecipeValues.maxMaterialPayout(item, payoutItem, count).orElse(Integer.MAX_VALUE));
                }
                caps.add(BuiltInRegistries.ITEM.getKey(payoutItem).toString(), perBatch);
            }
            if (caps.size() > 0) o.add("caps", caps);
        } catch (Throwable t) {
            LOGGER.warn("[trade-values] trade quote failed for {}: {}", BuiltInRegistries.ITEM.getKey(item), t.toString());
        }
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
