package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.client.live.LiveViewerMutes;
import games.brennan.dungeontrain.config.LiveViewerClientConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Options › Dungeon Train › Stream › Muted streamers: every streamer this player muted on the TVs
 * ({@link LiveViewerMutes}), newest first, each with an Unmute button that takes effect at once.
 */
@OnlyIn(Dist.CLIENT)
public final class MutedStreamersScreen extends Screen {

    private static final String KEY = "gui.dungeontrain.muted_streamers";

    private final Screen parent;
    /** Fresh per {@link #init}: every unmute rebuilds. */
    private HeaderAndFooterLayout layout;
    private SettingsRowList list;

    public MutedStreamersScreen(Screen parent) {
        super(Component.translatable(KEY + ".title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.layout = new HeaderAndFooterLayout(this);
        this.layout.addTitleHeader(this.title, this.font);
        this.list = this.layout.addToContents(new SettingsRowList(this.minecraft, this.width,
                this.layout.getContentHeight(), this.layout.getHeaderHeight()));
        populate();
        this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(200).build());
        this.layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        if (this.layout == null) return;
        this.layout.arrangeElements();
        if (this.list != null) this.list.updateSize(this.width, this.layout);
    }

    private void populate() {
        List<LiveViewerMutes.Muted> muted = LiveViewerClientConfig.mutedStreamers();
        if (muted.isEmpty()) {
            this.list.addWidget(new StringWidget(Component.translatable(KEY + ".empty"), this.font));
            return;
        }
        for (int i = muted.size() - 1; i >= 0; i--) {
            LiveViewerMutes.Muted m = muted.get(i);
            Button unmute = Button.builder(Component.translatable(KEY + ".unmute"), b -> {
                        LiveViewerMutes.unmuteStreamer(m);
                        rebuildWidgets();
                    })
                    .bounds(0, 0, SettingsRowList.CONTROL_W, SettingsRowList.CONTROL_H).build();
            this.list.addSetting(SettingsRowList.Icon.item(Items.NOTE_BLOCK), Component.literal(m.name()),
                    Component.translatable(KEY + ".hint"), unmute, Component.translatable(KEY + ".unmute.tip", m.name()));
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
