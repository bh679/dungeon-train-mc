package games.brennan.dungeontrain.client;

import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.config.TributeWebhookClientConfig;
import games.brennan.dungeontrain.discord.DiscordMarkdown;
import games.brennan.dungeontrain.discord.TributeWebhook;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Options › Account › Tribute webhooks: paste Discord webhook links, up to {@link TributeWebhook#MAX_LINKS},
 * and every photo the player pays Tribute to is also posted to each of them.
 *
 * <p>Saved links are never put back on screen in full (Options can be on a Live Eye stream): each is listed
 * by its host and webhook id, token hidden, with its own Remove button. Add saves the pasted link; Send test
 * posts a short text message from this computer to the pasted link, or to every saved one when the field
 * is empty. Changes save at once; the Options screen syncs them to the server when this closes.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class TributeWebhookScreen extends Screen {

    private static final String KEY = "gui.dungeontrain.tribute_webhook";
    private static final int FIELD_W = 320;
    private static final int FIELD_Y = 64;
    private static final int BUTTON_W = 100;
    private static final int GAP = 6;
    /** Space above the list: title, three text lines, the field and its status line. */
    private static final int HEADER_H = 104;
    private static final int FOOTER_H = 36;
    /** Tributes burn green: the test post wears the same colour as the real ones. */
    private static final int TEST_COLOR = 0x2ECC71;

    private final Screen parent;
    private EditBox field;
    private Button add;
    private Button test;
    /** The last Add / Send test result, shown under the field; null before one. */
    private Component status;

    public TributeWebhookScreen(Screen parent) {
        super(Component.translatable(KEY + ".title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        String typed = this.field == null ? "" : this.field.getValue();
        this.field = new EditBox(this.font, cx - FIELD_W / 2, FIELD_Y, FIELD_W, 20, Component.translatable(KEY + ".field"));
        this.field.setMaxLength(TributeWebhook.MAX_LENGTH);
        this.field.setValue(typed);
        this.field.setHint(Component.literal("https://discord.com/api/webhooks/...").withStyle(ChatFormatting.DARK_GRAY));
        this.field.setResponder(s -> {
            this.status = null;
            refreshButtons();
        });
        addRenderableWidget(this.field);

        int listH = Math.max(SettingsRowList.ROW_H, this.height - HEADER_H - FOOTER_H);
        SettingsRowList list = addRenderableWidget(new SettingsRowList(this.minecraft, this.width, listH, HEADER_H));
        populate(list);

        int rowW = 3 * BUTTON_W + 2 * GAP;
        int x = cx - rowW / 2;
        int y = this.height - FOOTER_H + 8;
        this.add = addRenderableWidget(Button.builder(Component.translatable(KEY + ".add"), b -> add())
                .bounds(x, y, BUTTON_W, 20).build());
        this.test = addRenderableWidget(Button.builder(Component.translatable(KEY + ".test"), b -> sendTest())
                .bounds(x + BUTTON_W + GAP, y, BUTTON_W, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(x + 2 * (BUTTON_W + GAP), y, BUTTON_W, 20).build());
        refreshButtons();
    }

    /** One row per saved link: "#1  discord.com", "webhooks/123…/••••", and Remove. */
    private void populate(SettingsRowList list) {
        List<String> links = TributeWebhookClientConfig.urls();
        if (links.isEmpty()) {
            list.addWidget(new StringWidget(SettingsRowList.ROW_W, SettingsRowList.ROW_H,
                    Component.translatable(KEY + ".none").withStyle(ChatFormatting.GRAY), this.font));
            return;
        }
        for (int i = 0; i < links.size(); i++) {
            String url = links.get(i);
            Button remove = Button.builder(Component.translatable(KEY + ".remove"), b -> {
                        TributeWebhookClientConfig.remove(url);
                        this.status = null;
                        rebuildWidgets();
                    })
                    .bounds(0, 0, SettingsRowList.CONTROL_W, SettingsRowList.CONTROL_H).build();
            list.addSetting(SettingsRowList.Icon.item(Items.EMERALD),
                    Component.literal("#" + (i + 1) + "  " + host(url)),
                    Component.literal(shortMasked(url)), remove, null);
        }
    }

    /** {@code discord.com} from a saved link. */
    private static String host(String url) {
        String rest = url.substring("https://".length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    /** {@code webhooks/1234567890/••••}: enough to tell links apart, never the token. */
    private static String shortMasked(String url) {
        String masked = TributeWebhook.masked(url);
        int at = masked.indexOf("webhooks/");
        return at < 0 ? masked : masked.substring(at);
    }

    private String typed() {
        return this.field == null ? "" : TributeWebhook.normalize(this.field.getValue());
    }

    private void refreshButtons() {
        if (this.add == null) return;
        String typed = typed();
        List<String> links = TributeWebhookClientConfig.urls();
        this.add.active = TributeWebhook.isValid(typed) && !TributeWebhook.contains(links, typed)
                && links.size() < TributeWebhook.MAX_LINKS;
        this.test.active = TributeWebhook.isValid(typed) || (typed.isEmpty() && !links.isEmpty());
    }

    private void add() {
        if (TributeWebhookClientConfig.add(typed())) {
            this.field.setValue("");
            this.status = Component.translatable(KEY + ".added").withStyle(ChatFormatting.GREEN);
            rebuildWidgets();
        }
    }

    /** To the pasted link if there is one, else to every saved link. */
    private void sendTest() {
        String typed = typed();
        List<String> targets = TributeWebhook.isValid(typed) ? List.of(typed) : TributeWebhookClientConfig.urls();
        if (targets.isEmpty()) return;
        if (!DiscordPresenceClientConfig.isGranted()) {
            this.status = Component.translatable(KEY + ".test_offline").withStyle(ChatFormatting.RED);
            return;
        }
        this.status = Component.translatable(KEY + ".test_sending").withStyle(ChatFormatting.GRAY);
        this.test.active = false;
        String name = this.minecraft.getUser().getName();
        List<CompletableFuture<Boolean>> posts = targets.stream()
                .map(url -> TributeWebhook.post(url, name, this.minecraft.getUser().getProfileId(),
                        TributeWebhook.embed("📸 Dungeon Train is connected",
                                "Photos " + DiscordMarkdown.escape(name) + " pays Tribute to will post here.", TEST_COLOR, false),
                        null))
                .toList();
        CompletableFuture.allOf(posts.toArray(CompletableFuture[]::new)).thenRun(() -> {
            boolean ok = posts.stream().allMatch(f -> Boolean.TRUE.equals(f.join()));
            this.minecraft.execute(() -> {
                this.status = ok
                        ? Component.translatable(KEY + ".test_sent").withStyle(ChatFormatting.GREEN)
                        : Component.translatable(KEY + ".test_failed").withStyle(ChatFormatting.RED);
                refreshButtons();
            });
        });
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        g.drawCenteredString(this.font, this.title, cx, 10, 0xFFFFFF);
        g.drawCenteredString(this.font, Component.translatable(KEY + ".line1"), cx, 26, 0xA0A0A0);
        g.drawCenteredString(this.font, Component.translatable(KEY + ".line2"), cx, 37, 0xA0A0A0);
        g.drawCenteredString(this.font, Component.translatable(KEY + ".footnote"), cx, 50, 0x808080);
        String typed = typed();
        Component under = this.status;
        if (under == null && !typed.isEmpty()) {
            List<String> links = TributeWebhookClientConfig.urls();
            if (!TributeWebhook.isValid(typed)) {
                under = Component.translatable(KEY + ".invalid").withStyle(ChatFormatting.RED);
            } else if (TributeWebhook.contains(links, typed)) {
                under = Component.translatable(KEY + ".already_added").withStyle(ChatFormatting.GRAY);
            } else if (links.size() >= TributeWebhook.MAX_LINKS) {
                under = Component.translatable(KEY + ".full").withStyle(ChatFormatting.RED);
            }
        }
        if (under != null) g.drawCenteredString(this.font, under, cx, FIELD_Y + 26, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
