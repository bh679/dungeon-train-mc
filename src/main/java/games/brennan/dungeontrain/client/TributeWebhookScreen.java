package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.TributeWebhookClientConfig;
import games.brennan.dungeontrain.discord.DiscordMarkdown;
import games.brennan.dungeontrain.discord.TributeWebhook;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Options › Account › Tribute webhook: paste a Discord webhook link, and every photo the player pays
 * Tribute to is also posted there.
 *
 * <p>The saved link is never put back on screen (Options can be on a Live Eye stream): the field opens
 * empty, with the saved link's masked form ({@link TributeWebhook#masked}) as its placeholder. Save takes
 * only a valid link; Remove clears it; Send test posts a short text message from this computer.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class TributeWebhookScreen extends Screen {

    private static final String KEY = "gui.dungeontrain.tribute_webhook";
    private static final int FIELD_W = 320;
    private static final int BUTTON_W = 76;
    private static final int GAP = 6;
    /** Tributes burn green: the test post wears the same colour as the real ones. */
    private static final int TEST_COLOR = 0x2ECC71;

    private final Screen parent;
    private EditBox field;
    private Button save;
    private Button test;
    private Button remove;
    /** The last Send test result, shown under the field; null before one. */
    private Component status;

    public TributeWebhookScreen(Screen parent) {
        super(Component.translatable(KEY + ".title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int fieldY = this.height / 2 - 10;
        String typed = this.field == null ? "" : this.field.getValue();
        this.field = new EditBox(this.font, cx - FIELD_W / 2, fieldY, FIELD_W, 20, Component.translatable(KEY + ".field"));
        this.field.setMaxLength(TributeWebhook.MAX_LENGTH);
        this.field.setValue(typed);
        String saved = TributeWebhook.masked(TributeWebhookClientConfig.url());
        this.field.setHint(Component.literal(saved.isEmpty() ? "https://discord.com/api/webhooks/..." : saved)
                .withStyle(ChatFormatting.DARK_GRAY));
        this.field.setResponder(s -> {
            this.status = null;
            refreshButtons();
        });
        // Not focused on open: a focused box hides its placeholder, which shows the saved link masked.
        addRenderableWidget(this.field);

        int rowW = 4 * BUTTON_W + 3 * GAP;
        int x = cx - rowW / 2;
        int y = fieldY + 44;
        this.save = addRenderableWidget(Button.builder(Component.translatable(KEY + ".save"), b -> save())
                .bounds(x, y, BUTTON_W, 20).build());
        this.test = addRenderableWidget(Button.builder(Component.translatable(KEY + ".test"), b -> sendTest())
                .bounds(x + (BUTTON_W + GAP), y, BUTTON_W, 20).build());
        this.remove = addRenderableWidget(Button.builder(Component.translatable(KEY + ".remove"), b -> remove())
                .bounds(x + 2 * (BUTTON_W + GAP), y, BUTTON_W, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(x + 3 * (BUTTON_W + GAP), y, BUTTON_W, 20).build());
        refreshButtons();
    }

    private String typed() {
        return this.field == null ? "" : TributeWebhook.normalize(this.field.getValue());
    }

    /** The link Send test uses: what is typed if it is valid, else the saved one. */
    private String testTarget() {
        return TributeWebhook.isValid(typed()) ? typed() : TributeWebhookClientConfig.url();
    }

    private void refreshButtons() {
        if (this.save == null) return;
        this.save.active = TributeWebhook.isValid(typed());
        this.test.active = TributeWebhook.isValid(testTarget());
        this.remove.active = TributeWebhookClientConfig.isSet();
    }

    private void save() {
        if (TributeWebhookClientConfig.setUrl(typed())) onClose();
    }

    private void remove() {
        TributeWebhookClientConfig.setUrl("");
        onClose();
    }

    private void sendTest() {
        String url = testTarget();
        if (!TributeWebhook.isValid(url)) return;
        if (!DiscordPresenceClientConfig.isGranted()) {
            this.status = Component.translatable(KEY + ".test_offline").withStyle(ChatFormatting.RED);
            return;
        }
        this.status = Component.translatable(KEY + ".test_sending").withStyle(ChatFormatting.GRAY);
        this.test.active = false;
        String name = this.minecraft.getUser().getName();
        TributeWebhook.post(url, name, this.minecraft.getUser().getProfileId(), TributeWebhook.embed("📸 Dungeon Train is connected",
                        "Photos " + DiscordMarkdown.escape(name) + " pays Tribute to will post here.", TEST_COLOR, false), null)
                .thenAccept(ok -> this.minecraft.execute(() -> {
                    this.status = ok
                            ? Component.translatable(KEY + ".test_sent").withStyle(ChatFormatting.GREEN)
                            : Component.translatable(KEY + ".test_failed").withStyle(ChatFormatting.RED);
                    refreshButtons();
                }));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int fieldY = this.height / 2 - 10;
        g.drawCenteredString(this.font, this.title, cx, fieldY - 62, 0xFFFFFF);
        g.drawCenteredString(this.font, Component.translatable(KEY + ".line1"), cx, fieldY - 44, 0xA0A0A0);
        g.drawCenteredString(this.font, Component.translatable(KEY + ".line2"), cx, fieldY - 32, 0xA0A0A0);
        g.drawCenteredString(this.font, Component.translatable(KEY + ".footnote"), cx, fieldY - 18, 0x808080);
        Component under = this.status;
        if (under == null && !typed().isEmpty() && !TributeWebhook.isValid(typed())) {
            under = Component.translatable(KEY + ".invalid").withStyle(ChatFormatting.RED);
        } else if (under == null && typed().isEmpty() && TributeWebhookClientConfig.isSet()) {
            under = Component.translatable(KEY + ".saved").withStyle(ChatFormatting.GRAY);
        }
        if (under != null) g.drawCenteredString(this.font, under, cx, fieldY + 28, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
