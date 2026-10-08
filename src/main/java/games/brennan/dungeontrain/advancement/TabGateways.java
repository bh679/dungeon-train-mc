package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Advancement tabs headed by a copy of an advancement that lives in another tab.
 *
 * <p>"Dungeon Train Explorer" sits in the Dungeon Train tab and also heads the Train Explorer tab;
 * "Others?" heads Others; The Enchiridion and The Darkroom each have a copy in the Dungeon Train tab.
 * An advancement has one parent, so each of those is two advancements: the <b>original</b>, earned by
 * play, and a hidden, silent <b>copy</b> (criterion {@code minecraft:impossible}) that this class keeps
 * in step with it. A hidden root is invisible until earned, so the copy earning is what unlocks a tab.</p>
 *
 * <p>The pairs live in {@code /dungeontrain/advancement_tabs.json} (also read by the advancement editor,
 * {@code scripts/advancements/editor}), so a new pair is a data change. Copies are mirrors, never
 * achievements of their own: no cross-world persistence, hints, accolades or capstone credit
 * ({@link CompletionistAdvancement#isRequiredId}). At login a copy is re-derived from its original,
 * which also revokes a copy whose original was revoked (Start Again).</p>
 */
public final class TabGateways {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Classpath location of the tab layout, shared with the client ({@code AdvancementTabTitles}). */
    public static final String RESOURCE = "/dungeontrain/advancement_tabs.json";

    private static volatile Layout layout;

    private TabGateways() {}

    /**
     * The tab layout: copy → original, tab root → lang key for its tab name, and the tab order.
     * Ids are kept as strings so the parse is testable without Minecraft's registries.
     */
    public record Layout(Map<String, String> copies, Map<String, String> tabNames, List<String> order) {

        public static final Layout EMPTY = new Layout(Map.of(), Map.of(), List.of());

        public static Layout parse(Reader reader) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            return new Layout(stringMap(root, "copies"), stringMap(root, "tabNames"), stringList(root, "order"));
        }

        /** The copies of {@code originalId}, usually one. */
        public List<String> copiesOf(String originalId) {
            List<String> out = new ArrayList<>(1);
            copies.forEach((copy, original) -> { if (original.equals(originalId)) out.add(copy); });
            return out;
        }

        private static Map<String, String> stringMap(JsonObject root, String key) {
            Map<String, String> out = new LinkedHashMap<>();
            if (root.has(key) && root.get(key).isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject(key).entrySet()) {
                    if (e.getValue().isJsonPrimitive()) out.put(e.getKey(), e.getValue().getAsString());
                }
            }
            return Collections.unmodifiableMap(out);
        }

        private static List<String> stringList(JsonObject root, String key) {
            List<String> out = new ArrayList<>();
            if (root.has(key) && root.get(key).isJsonArray()) {
                root.getAsJsonArray(key).forEach(e -> out.add(e.getAsString()));
            }
            return List.copyOf(out);
        }
    }

    /** The shipped layout, read once. A missing or broken file leaves every tab as its own JSON says. */
    public static Layout layout() {
        Layout l = layout;
        if (l == null) {
            l = load();
            layout = l;
        }
        return l;
    }

    private static Layout load() {
        try (InputStream in = TabGateways.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warn("[DungeonTrain] {} missing — advancement tab copies disabled", RESOURCE);
                return Layout.EMPTY;
            }
            return Layout.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[DungeonTrain] Could not read {} — advancement tab copies disabled", RESOURCE, e);
            return Layout.EMPTY;
        }
    }

    /** Is {@code id} a tab copy (a mirror, never earned by play)? */
    public static boolean isCopy(ResourceLocation id) {
        return layout().copies().containsKey(id.toString());
    }

    /**
     * Copies show their original's title and description. The original's description may have been
     * rewritten at load with its required value ({@code RequirementJsonRewriter}), so this runs after
     * that rewrite and copies the result. Pure: returns a new map, the input is never mutated.
     */
    public static Map<ResourceLocation, JsonElement> mirrorText(Map<ResourceLocation, JsonElement> loaded, Layout l) {
        if (l.copies().isEmpty()) return loaded;
        Map<ResourceLocation, JsonElement> out = new LinkedHashMap<>(loaded);
        l.copies().forEach((copyId, originalId) -> {
            ResourceLocation copyRl = ResourceLocation.tryParse(copyId);
            ResourceLocation originalRl = ResourceLocation.tryParse(originalId);
            if (copyRl == null || originalRl == null) return;
            Optional<JsonObject> copy = display(out.get(copyRl));
            Optional<JsonObject> original = display(out.get(originalRl));
            if (copy.isEmpty() || original.isEmpty()) return;
            JsonObject newCopy = out.get(copyRl).getAsJsonObject().deepCopy();
            JsonObject newDisplay = newCopy.getAsJsonObject("display");
            for (String key : new String[] {"title", "description"}) {
                if (original.get().has(key)) newDisplay.add(key, original.get().get(key).deepCopy());
            }
            out.put(copyRl, newCopy);
        });
        return out;
    }

    private static Optional<JsonObject> display(JsonElement advancement) {
        if (advancement == null || !advancement.isJsonObject()) return Optional.empty();
        JsonElement d = advancement.getAsJsonObject().get("display");
        return d != null && d.isJsonObject() ? Optional.of(d.getAsJsonObject()) : Optional.empty();
    }

    /** An original was just earned: earn its copies, which unlocks their tabs. */
    public static void onEarned(ServerPlayer player, ResourceLocation id) {
        List<String> copies = layout().copiesOf(id.toString());
        if (copies.isEmpty()) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        for (String copy : copies) setDone(player, server.getAdvancements(), copy, true);
    }

    /**
     * Login: make every copy match its original. Runs after the cross-world replay, so an original
     * restored from another world unlocks its tab, and one revoked by Start Again locks it again.
     */
    public static void sync(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerAdvancementManager mgr = server.getAdvancements();
        PlayerAdvancements adv = player.getAdvancements();
        layout().copies().forEach((copy, original) -> {
            AdvancementHolder originalHolder = holder(mgr, original);
            if (originalHolder == null) return;
            setDone(player, mgr, copy, adv.getOrStartProgress(originalHolder).isDone());
        });
    }

    private static void setDone(ServerPlayer player, ServerAdvancementManager mgr, String id, boolean done) {
        AdvancementHolder holder = holder(mgr, id);
        if (holder == null) return;
        PlayerAdvancements adv = player.getAdvancements();
        if (adv.getOrStartProgress(holder).isDone() == done) return;
        for (String criterion : holder.value().criteria().keySet()) {
            if (done) adv.award(holder, criterion);
            else adv.revoke(holder, criterion);
        }
        LOGGER.debug("[DungeonTrain] Tab copy {} {} for {}", id, done ? "earned" : "revoked", player.getName().getString());
    }

    private static AdvancementHolder holder(ServerAdvancementManager mgr, String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null || !DungeonTrain.MOD_ID.equals(rl.getNamespace()) ? null : mgr.get(rl);
    }
}
