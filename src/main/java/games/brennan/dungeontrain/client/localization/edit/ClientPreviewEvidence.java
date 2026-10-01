package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@link PreviewEvidence} answered from the running game: the recorders for what has been seen, the
 * item registry for names, and DT's own advancement files for which titles pop up as a toast.
 */
public final class ClientPreviewEvidence implements PreviewEvidence {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Advancement Plaques replaces the vanilla toast, so a vanilla-toast preview would be wrong. */
    private static final String PLAQUES_MOD_ID = "advancementplaques";
    private static final String ADVANCEMENT_ROOT = "data/dungeontrain/advancement";

    public static final ClientPreviewEvidence INSTANCE = new ClientPreviewEvidence();

    /** What the toast needs from an advancement: its frame and its icon. */
    public record Toast(AdvancementType frame, Item icon) {}

    private static Map<String, Item> itemNames;
    private static Map<String, Toast> toasts;

    private ClientPreviewEvidence() {}

    @Override
    public boolean seenOnButton(String key) {
        return ButtonKeyRecorder.seen(key);
    }

    @Override
    public boolean seenInChat(String key) {
        return ChatKeyRecorder.seen(key);
    }

    @Override
    public boolean seenInActionBar(String key) {
        return ChatKeyRecorder.seenInActionBar(key);
    }

    @Override
    public boolean seenInWidgetTooltip(String key) {
        return ButtonScreenLayouts.layoutForTooltip(key) != null;
    }

    @Override
    public boolean isItemName(String key) {
        return itemNamed(key) != null;
    }

    @Override
    public boolean seenInItemTooltip(String key) {
        return ItemTooltipRecorder.snapshotFor(key) != null;
    }

    @Override
    public boolean showsAdvancementToast(String key) {
        return toastFor(key) != null;
    }

    /** The item whose registered name is {@code key} (a block's, for its item), or null. */
    public static synchronized Item itemNamed(String key) {
        if (itemNames == null) {
            itemNames = new HashMap<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (item != Items.AIR) {
                    itemNames.putIfAbsent(item.getDescriptionId(), item);
                }
            }
        }
        return key == null ? null : itemNames.get(key);
    }

    /**
     * The toast an advancement titled {@code key} pops up when earned, or null — when it never
     * toasts ({@code show_toast: false}), or when Advancement Plaques draws its own instead.
     */
    public static synchronized Toast toastFor(String key) {
        if (toasts == null) {
            toasts = ModList.get().isLoaded(PLAQUES_MOD_ID) ? Map.of() : readToasts();
        }
        return key == null ? null : toasts.get(key);
    }

    private static Map<String, Toast> readToasts() {
        Map<String, Toast> out = new HashMap<>();
        for (Map.Entry<String, String> file : ModJarResources.readAll(ADVANCEMENT_ROOT, ".json").entrySet()) {
            try {
                JsonElement root = JsonParser.parseString(file.getValue());
                JsonObject display = root.isJsonObject() && root.getAsJsonObject().has("display")
                    ? root.getAsJsonObject().getAsJsonObject("display") : null;
                if (display == null || !display.has("title") || !display.get("title").isJsonObject()
                    || !display.getAsJsonObject("title").has("translate")) {
                    continue;
                }
                boolean showsToast = !display.has("show_toast") || display.get("show_toast").getAsBoolean();
                if (!showsToast) {
                    continue;
                }
                String frame = display.has("frame") ? display.get("frame").getAsString() : "task";
                ResourceLocation iconId = display.has("icon") && display.getAsJsonObject("icon").has("id")
                    ? ResourceLocation.tryParse(display.getAsJsonObject("icon").get("id").getAsString()) : null;
                Item icon = iconId == null ? Items.AIR : BuiltInRegistries.ITEM.get(iconId);
                out.put(display.getAsJsonObject("title").get("translate").getAsString(),
                    new Toast(frameOf(frame), icon));
            } catch (Exception e) {
                LOGGER.debug("[DungeonTrain] Translations: skipped advancement {} — {}", file.getKey(), e.toString());
            }
        }
        return out;
    }

    private static AdvancementType frameOf(String frame) {
        return switch (frame.toLowerCase(Locale.ROOT)) {
            case "goal" -> AdvancementType.GOAL;
            case "challenge" -> AdvancementType.CHALLENGE;
            default -> AdvancementType.TASK;
        };
    }
}
