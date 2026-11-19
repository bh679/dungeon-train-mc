package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.loot.VaultFinds;
import games.brennan.dungeontrain.provenance.ItemProvenance;
import games.brennan.dungeontrain.provenance.ProvenanceLines;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The Shift section at the foot of a piece of gear's tooltip: who found, made or bought it, the day,
 * its vault number and the carriage it was rolled in ({@link ItemProvenance}). Nothing shows without
 * Shift, not even a hint. Also keeps the always-on vault lines older finds still carry out of the
 * tooltip unless Shift is held, until the server moves them into the data.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ProvenanceTooltip {

    private static final String LORE_SERIAL = VaultFinds.LORE_SERIAL;
    private static final String LORE_PULLED_BY = "lore.dungeontrain.vault_find.pulled_by";

    private ProvenanceTooltip() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        List<Component> tooltip = event.getToolTip();
        List<Component> legacy = takeLegacyVaultLines(tooltip);
        Optional<ItemProvenance.View> view = ItemProvenance.view(stack);
        OptionalInt serial = VaultFinds.serialOf(stack);
        if (view.isEmpty() && legacy.isEmpty() && serial.isEmpty()) return;
        if (!Screen.hasShiftDown()) return;
        String name = view.map(ProvenanceTooltip::currentName).orElse("");
        for (Component line : ProvenanceLines.of(view, name, viewerLocale(), serial, legacy)) {
            // An old-item line may run over several lines of text.
            for (String part : line.getString().split("\n")) {
                tooltip.add(Component.literal(part).withStyle(ChatFormatting.DARK_GRAY));
            }
        }
    }

    /** Remove the old vault lines from {@code tooltip} and return them, number first. */
    private static List<Component> takeLegacyVaultLines(List<Component> tooltip) {
        List<Component> serials = new ArrayList<>();
        List<Component> pulled = new ArrayList<>();
        for (Iterator<Component> it = tooltip.iterator(); it.hasNext(); ) {
            Component line = it.next();
            String key = keyOf(line);
            if (LORE_SERIAL.equals(key)) {
                serials.add(line);
                it.remove();
            } else if (LORE_PULLED_BY.equals(key)) {
                pulled.add(line);
                it.remove();
            }
        }
        List<Component> out = new ArrayList<>(serials);
        out.addAll(pulled);
        return out;
    }

    /** The translation key of a lore line; lore is wrapped in a styled parent on the way to the tooltip. */
    private static String keyOf(Component line) {
        if (line.getContents() instanceof TranslatableContents t) return t.getKey();
        for (Component sibling : line.getSiblings()) {
            String key = keyOf(sibling);
            if (key != null) return key;
        }
        return null;
    }

    /** The holder's name as the server lists it now, or the one stored with the item. */
    private static String currentName(ItemProvenance.View view) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null && view.who().isPresent()) {
            PlayerInfo info = connection.getPlayerInfo(view.who().get());
            if (info != null) return info.getProfile().getName();
        }
        return view.name();
    }

    private static Locale viewerLocale() {
        String code = Minecraft.getInstance().getLanguageManager().getSelected();
        return code == null ? Locale.getDefault() : Locale.forLanguageTag(code.replace('_', '-'));
    }
}
