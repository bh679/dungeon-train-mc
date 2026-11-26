package games.brennan.dungeontrain.client.warlock;

import games.brennan.dungeontrain.perks.effects.warlock.TeleportListPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * Teleport Player's list: online players first, then PlayerMobs met this life, newest first, one button
 * each, scrolling with the wheel. A press calls them and closes the list. Empty, it says there is no one
 * to call; on cooldown the buttons wait and the title says how long.
 */
@OnlyIn(Dist.CLIENT)
final class TeleportCallScreen extends Screen {

    private static final String KEY = "gui.dungeontrain.warlock.call.";
    private static final int ROW_H = 22, ROW_W = 200, TOP = 40;
    private final Screen parent;
    private final List<Button> rows = new ArrayList<>();
    private int scroll;

    TeleportCallScreen(Screen parent) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
    }

    void refresh() {
        rebuildWidgets();
    }

    @Override
    protected void init() {
        rows.clear();
        List<TeleportListPacket.Entry> entries = TeleportCallClient.entries();
        int fits = Math.max(1, (height - TOP - 40) / ROW_H);
        scroll = Math.max(0, Math.min(scroll, entries.size() - fits));
        boolean ready = TeleportCallClient.cooldownSeconds() == 0;
        for (int i = scroll; i < Math.min(entries.size(), scroll + fits); i++) {
            TeleportListPacket.Entry e = entries.get(i);
            Component label = e.kind() == TeleportListPacket.Entry.PLAYER
                ? Component.literal(e.name())
                : Component.translatable(KEY + "playermob", e.name());
            Button b = Button.builder(label, btn -> {
                TeleportCallClient.call(e);
                onClose();
            }).bounds((width - ROW_W) / 2, TOP + (i - scroll) * ROW_H, ROW_W, 20).build();
            b.active = ready;
            rows.add(addRenderableWidget(b));
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
            .bounds((width - 100) / 2, height - 30, 100, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int before = scroll;
        scroll = Math.max(0, scroll - (int) Math.signum(dy));
        if (scroll != before) rebuildWidgets();
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
        int wait = TeleportCallClient.cooldownSeconds();
        if (wait > 0) {
            g.drawCenteredString(font, Component.translatable("gui.dungeontrain.warlock.ready_in", Component.translatable("gui.dungeontrain.perks.stat.unit.seconds", wait)), width / 2, 26, 0xAAAAAA);
        } else if (rows.stream().noneMatch(b -> b.active)) {
            for (Button b : rows) b.active = true;
        }
        if (TeleportCallClient.entries().isEmpty()) {
            g.drawCenteredString(font, Component.translatable(KEY + "no_one"), width / 2, height / 2, 0xAAAAAA);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
