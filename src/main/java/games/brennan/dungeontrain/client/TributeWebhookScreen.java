package games.brennan.dungeontrain.client;

import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.config.TributeWebhookClientConfig;
import games.brennan.dungeontrain.discord.DiscordMarkdown;
import games.brennan.dungeontrain.discord.TributeWebhook;
import games.brennan.dungeontrain.discord.WebhookFeed;
import games.brennan.dungeontrain.discord.WebhookSettings;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Options › Account › Discord webhooks: paste Discord or Fluxer webhook links, up to {@link TributeWebhook#MAX_LINKS},
 * give each an optional name, and pick what each gets: the photos the player pays Tribute to, their
 * passenger log ({@link WebhookFeed}), or both.
 *
 * <p>Saved links are never put back on screen in full (Options can be on a Live Eye stream): each is listed
 * by its name (or "Webhook 3"), its host and webhook id, token hidden. Under it, one switch per feed. Rename
 * puts the name in the Name box and Add becomes Save name. Send test posts a short text message from this
 * computer to the pasted link, or to every saved one when the field is empty. Changes save at once; the
 * Options screen syncs them to the server when this closes. Listed by name, unnamed links last.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class TributeWebhookScreen extends Screen {

    private static final String KEY = "gui.dungeontrain.tribute_webhook";
    private static final int FIELD_W = 320;
    private static final int NAME_W = 96;
    private static final int FIELD_Y = 64;
    private static final int BUTTON_W = 100;
    private static final int GAP = 6;
    private static final int RENAME_W = 60;
    private static final int REMOVE_W = 20;
    private static final int FEED_W = 104;
    /** Space above the list: title, three text lines, the fields and their status line. */
    private static final int HEADER_H = 104;
    private static final int FOOTER_H = 36;
    /** Tributes burn green: the test post wears the same colour as the real ones. */
    private static final int TEST_COLOR = 0x2ECC71;

    private final Screen parent;
    private EditBox field;
    private EditBox nameField;
    private Button add;
    private Button test;
    /** The saved link being renamed, or null. */
    private String renaming;
    /** The last Add / Send test result, shown under the field; null before one. */
    private Component status;

    public TributeWebhookScreen(Screen parent) {
        super(Component.translatable(KEY + ".title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int left = cx - FIELD_W / 2;
        String typed = this.field == null ? "" : this.field.getValue();
        String typedName = this.nameField == null ? "" : this.nameField.getValue();
        this.field = new EditBox(this.font, left, FIELD_Y, FIELD_W - NAME_W - GAP, 20, Component.translatable(KEY + ".field"));
        this.field.setMaxLength(TributeWebhook.MAX_LENGTH);
        this.field.setValue(typed);
        this.field.setHint(Component.literal("https://discord.com/api/webhooks/...").withStyle(ChatFormatting.DARK_GRAY));
        this.field.setResponder(s -> {
            this.status = null;
            if (!s.isBlank()) this.renaming = null;
            refreshButtons();
        });
        addRenderableWidget(this.field);
        this.nameField = new EditBox(this.font, left + FIELD_W - NAME_W, FIELD_Y, NAME_W, 20,
                Component.translatable(KEY + ".name_field"));
        this.nameField.setMaxLength(WebhookSettings.MAX_NAME_LENGTH);
        this.nameField.setValue(typedName);
        this.nameField.setHint(Component.translatable(KEY + ".name_hint").withStyle(ChatFormatting.DARK_GRAY));
        this.nameField.setResponder(s -> {
            this.status = null;
            refreshButtons();
        });
        addRenderableWidget(this.nameField);

        int listH = Math.max(SettingsRowList.ROW_H, this.height - HEADER_H - FOOTER_H);
        SettingsRowList list = addRenderableWidget(new SettingsRowList(this.minecraft, this.width, listH, HEADER_H));
        populate(list);

        int rowW = 3 * BUTTON_W + 2 * GAP;
        int x = cx - rowW / 2;
        int y = this.height - FOOTER_H + 8;
        this.add = addRenderableWidget(Button.builder(Component.translatable(KEY + ".add"), b -> addOrRename())
                .bounds(x, y, BUTTON_W, 20).build());
        this.test = addRenderableWidget(Button.builder(Component.translatable(KEY + ".test"), b -> sendTest())
                .bounds(x + BUTTON_W + GAP, y, BUTTON_W, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(x + 2 * (BUTTON_W + GAP), y, BUTTON_W, 20).build());
        refreshButtons();
    }

    /** Two rows per saved link: name, host and id with Rename and ✕; then one switch per feed. */
    private void populate(SettingsRowList list) {
        List<String> added = TributeWebhookClientConfig.urls();
        if (added.isEmpty()) {
            list.addWidget(new StringWidget(SettingsRowList.ROW_W, SettingsRowList.ROW_H,
                    Component.translatable(KEY + ".none").withStyle(ChatFormatting.GRAY), this.font));
            return;
        }
        for (String url : TributeWebhookClientConfig.displayOrder()) {
            Button rename = Button.builder(Component.translatable(KEY + ".rename"), b -> startRename(url))
                    .bounds(0, 0, RENAME_W, SettingsRowList.CONTROL_H).build();
            Button remove = Button.builder(Component.literal("✕"), b -> {
                        TributeWebhookClientConfig.remove(url);
                        if (url.equals(this.renaming)) this.renaming = null;
                        this.status = null;
                        rebuildWidgets();
                    })
                    .bounds(0, 0, REMOVE_W, SettingsRowList.CONTROL_H).build();
            remove.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(KEY + ".remove")));
            list.addSetting(SettingsRowList.Icon.item(Items.EMERALD), displayName(url, added),
                    Component.literal(host(url) + " · " + shortMasked(url)), List.of(rename, remove), null);

            List<Button> switches = new ArrayList<>();
            for (WebhookFeed feed : WebhookFeed.values()) {
                switches.add(Button.builder(feedLabel(feed, feed.in(TributeWebhookClientConfig.feeds(url))), b -> {
                            TributeWebhookClientConfig.toggle(url, feed);
                            rebuildWidgets();
                        })
                        .bounds(0, 0, FEED_W, SettingsRowList.CONTROL_H).build());
            }
            list.addSetting(SettingsRowList.Icon.item(Items.WRITABLE_BOOK),
                    Component.translatable(KEY + ".gets").withStyle(ChatFormatting.GRAY),
                    Component.empty(), switches, null);
        }
    }

    /** Its name, or "Webhook 3" by the order it was added. */
    private static Component displayName(String url, List<String> added) {
        String name = TributeWebhookClientConfig.name(url);
        if (!name.isEmpty()) return Component.literal(name);
        return Component.translatable(KEY + ".unnamed", added.indexOf(url) + 1).withStyle(ChatFormatting.GRAY);
    }

    /** {@code "✔ Tributes"} in green, or {@code "✘ Tributes"} in grey. */
    private static Component feedLabel(WebhookFeed feed, boolean on) {
        Component name = Component.translatable(KEY + ".feed." + feed.key);
        return on
                ? Component.literal("✔ ").append(name).withStyle(ChatFormatting.GREEN)
                : Component.literal("✘ ").append(name).withStyle(ChatFormatting.GRAY);
    }

    private void startRename(String url) {
        this.renaming = url;
        this.field.setValue("");
        this.renaming = url; // the field's responder clears it
        this.nameField.setValue(TributeWebhookClientConfig.name(url));
        setFocused(this.nameField);
        this.status = Component.translatable(KEY + ".renaming",
                displayName(url, TributeWebhookClientConfig.urls())).withStyle(ChatFormatting.GRAY);
        refreshButtons();
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
        if (this.renaming != null) {
            this.add.setMessage(Component.translatable(KEY + ".save_name"));
            this.add.active = true;
        } else {
            this.add.setMessage(Component.translatable(KEY + ".add"));
            String typed = typed();
            List<String> links = TributeWebhookClientConfig.urls();
            this.add.active = TributeWebhook.isValid(typed) && !TributeWebhook.contains(links, typed)
                    && links.size() < TributeWebhook.MAX_LINKS;
        }
        String typed = typed();
        this.test.active = TributeWebhook.isValid(typed) || (typed.isEmpty() && TributeWebhookClientConfig.isSet());
    }

    private void addOrRename() {
        if (this.renaming != null) {
            TributeWebhookClientConfig.rename(this.renaming, this.nameField.getValue());
            this.renaming = null;
            this.nameField.setValue("");
            this.status = Component.translatable(KEY + ".renamed").withStyle(ChatFormatting.GREEN);
            rebuildWidgets();
            return;
        }
        if (TributeWebhookClientConfig.add(typed(), this.nameField.getValue())) {
            this.field.setValue("");
            this.nameField.setValue("");
            this.status = Component.translatable(KEY + ".added").withStyle(ChatFormatting.GREEN);
            rebuildWidgets();
        }
    }

    /** To the pasted link if there is one, else to every saved link; each says what it will get. */
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
        List<String> saved = TributeWebhookClientConfig.urls();
        List<CompletableFuture<Boolean>> posts = targets.stream()
                .map(url -> {
                    int feeds = TributeWebhook.contains(saved, url) ? TributeWebhookClientConfig.feeds(url) : WebhookFeed.ALL;
                    return TributeWebhook.post(url, name, this.minecraft.getUser().getProfileId(),
                            TributeWebhook.embed("📸 Dungeon Train is connected", testDescription(name, feeds),
                                    TEST_COLOR, false),
                            null);
                })
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

    /** What the test post says this channel will get. English: it is a Discord post, like the real ones. */
    static String testDescription(String player, int feeds) {
        String who = DiscordMarkdown.escape(player);
        boolean tributes = WebhookFeed.TRIBUTES.in(feeds);
        boolean log = WebhookFeed.PASSENGER_LOG.in(feeds);
        if (tributes && log) return "Photos " + who + " pays Tribute to, and " + who + "'s passenger log, will post here.";
        if (tributes) return "Photos " + who + " pays Tribute to will post here.";
        if (log) return who + "'s passenger log will post here: deaths, milestones and stories.";
        return "Nothing is switched on for this channel yet.";
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
