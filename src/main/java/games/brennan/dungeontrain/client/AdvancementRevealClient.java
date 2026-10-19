package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.AdvancementReveal;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * After an earn, tells the player in chat what it opened up, {@link #DELAY_TICKS} later: a gold line
 * for each advancement tab that just appeared, then a {@code Revealed: [A], [B]} line for the
 * advancements now visible on tabs they already had.
 *
 * <p>Only tabs the advancements screen would show count — {@link AdvancementTabGate}, so by default
 * Dungeon Train's tabs and not Minecraft's or other mods' (Options → Dungeon Train). That setting is
 * client-side, which is why this reads the update packet here rather than the server deciding.</p>
 *
 * <p>Fed by {@code ClientAdvancementsRevealMixin} after each update packet is applied. An update counts
 * as an earn when it carries a completed advancement; the login/reset packet never does.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class AdvancementRevealClient {

    /** Three seconds after the earn. */
    static final int DELAY_TICKS = 60;

    private record Pending(int dueTick, List<Component> tabs, Optional<Component> revealed) {}

    /** Client thread only (packet handling and client tick both run there). */
    private static final List<Pending> PENDING = new ArrayList<>();
    private static int ticks;

    private AdvancementRevealClient() {}

    /** Called after {@code advancements} has applied {@code packet}. */
    public static void onUpdate(ClientAdvancements advancements, ClientboundUpdateAdvancementsPacket packet) {
        if (packet.shouldReset() || packet.getAdded().isEmpty()) return;
        Map<ResourceLocation, AdvancementProgress> progress = packet.getProgress();
        if (progress.values().stream().noneMatch(AdvancementProgress::isDone)) return;

        List<Component> tabs = new ArrayList<>();
        List<AdvancementHolder> onShownTabs = new ArrayList<>();
        for (AdvancementHolder holder : packet.getAdded()) {
            AdvancementNode node = advancements.getTree().get(holder.id());
            if (node == null || AdvancementTabGate.shouldHideTab(node.root().holder().id())) continue;
            if (node.parent() == null) {
                holder.value().display().ifPresent(d -> tabs.add(AdvancementTabTitles.titleFor(holder.id(), d.getTitle())));
            } else {
                onShownTabs.add(holder);
            }
        }
        List<AdvancementHolder> revealed = AdvancementReveal.select(onShownTabs,
            holder -> Optional.ofNullable(progress.get(holder.id())).map(AdvancementProgress::isDone).orElse(false),
            holder -> holder.value().display().map(DisplayInfo::getTitle).map(Component::getString));
        List<Component> names = new ArrayList<>(revealed.size());
        for (AdvancementHolder holder : revealed) holder.value().name().ifPresent(names::add);

        Optional<Component> line = AdvancementReveal.message(names);
        if (tabs.isEmpty() && line.isEmpty()) return;
        PENDING.add(new Pending(ticks + DELAY_TICKS, List.copyOf(tabs), line));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ticks++;
        if (PENDING.isEmpty()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            PENDING.clear();
            return;
        }
        while (!PENDING.isEmpty() && PENDING.get(0).dueTick() <= ticks) {
            Pending due = PENDING.remove(0);
            due.tabs().forEach(tab -> announceTab(player, tab));
            due.revealed().ifPresent(line -> player.displayClientMessage(line, false));
        }
    }

    /** The gold chat line pointing at the L key. */
    private static void announceTab(LocalPlayer player, Component tab) {
        Component name = tab.copy().withStyle(ChatFormatting.YELLOW);
        Component key = Minecraft.getInstance().options.keyAdvancements.getTranslatedKeyMessage();
        player.displayClientMessage(AdvancementReveal.tabUnlocked(name, key), false);
    }
}
