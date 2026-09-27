package games.brennan.dungeontrain.client.modcheck;

import games.brennan.dungeontrain.cheat.FreePlayText;
import games.brennan.dungeontrain.cheat.ModSuggestClient;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Unsupported Mod(s) Detected" — shown once per launch, from the title screen, when the player has
 * mods installed that are neither on the approved whitelist nor on the cheat blacklist (see
 * {@link UnsupportedModsPopupHandler}). It explains that the game still runs, but in Free Play, and
 * lets the player suggest each mod for the whitelist ({@link ModSuggestScreen}).
 *
 * <p>Known cheat mods never appear here: they keep the in-world "known cheat mod" notice they always
 * had. This screen is a notice, not a gate — Continue returns to the title screen.</p>
 *
 * <p>The mod list is hand-rolled (scroll offset + widget rebuild) like every other list in the mod,
 * rather than a vanilla selection list.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class UnsupportedModsScreen extends Screen {

    /** One installed, unsupported mod. */
    public record UnsupportedMod(String modId, String displayName) {}

    private static final int PANEL_W = 340;
    private static final int ROW_H = 24;
    /** Mods sit two to a row, so a longer list still fits without scrolling. */
    private static final int COLUMNS = 2;
    private static final int ICON_BUTTON = 20;
    private static final int BUTTON_H = 20;
    private static final int GAP = 8;
    private static final int PAD = 12;
    private static final int PANEL_BG = 0xE0101010;
    private static final int PANEL_EDGE = 0xFF505050;
    private static final int ROW_ALT = 0x20FFFFFF;
    private static final int NAME_COLOUR = 0xFFFFFFFF;
    private static final int ID_COLOUR = 0xFF909090;
    private static final int BODY_COLOUR = 0xFFD0D0D0;
    /**
     * The Free Play effect drawn exactly as the HUD draws a status effect: the vanilla
     * {@code hud/effect_background} frame (24×24) with the effect's own atlas sprite inset 3px at 18×18.
     */
    private static final ResourceLocation EFFECT_BACKGROUND = ResourceLocation.withDefaultNamespace("hud/effect_background");
    private static final int ICON = 24;
    private static final int EFFECT_INSET = 3;
    private static final int EFFECT_SPRITE = 18;
    private static final int ICON_GAP = 6;

    private final Screen parent;
    private final List<UnsupportedMod> mods;
    /** Last result per mod id, shown under that mod's name. Replaced whole, never mutated. */
    private Map<String, ModSuggestClient.Result> results = Map.of();
    private int scroll;

    // Layout, computed in init().
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listY;
    private int visibleRows;
    private List<FormattedCharSequence> bodyLines = List.of();
    private List<FormattedCharSequence> hintLines = List.of();
    /** Set when Quit and Disable could not disable anything, so the player knows to use the launcher. */
    private boolean disableFailed;

    public UnsupportedModsScreen(Screen parent, List<UnsupportedMod> mods) {
        super(Component.translatable("gui.dungeontrain.unsupported_mods.title"));
        this.parent = parent;
        this.mods = List.copyOf(mods);
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_W, this.width - 32);
        int inner = panelW - PAD * 2;
        bodyLines = this.font.split(FreePlayText.withExplanation("gui.dungeontrain.unsupported_mods.body"),
            inner - ICON - ICON_GAP);
        int bodyH = Math.max(ICON, bodyLines.size() * (this.font.lineHeight + 1));
        hintLines = this.font.split(Component.translatable(disableFailed
            ? "gui.dungeontrain.unsupported_mods.disable_failed"
            : "gui.dungeontrain.unsupported_mods.live_hint"), inner);
        int hintH = hintLines.size() * (this.font.lineHeight + 1);
        int chrome = PAD + this.font.lineHeight + GAP      // title
            + GAP + bodyH + GAP + hintH                     // body + live-run hint
            + GAP + this.font.lineHeight                    // link
            + GAP + BUTTON_H + PAD;                         // Continue | Quit and Disable
        int maxRows = Math.max(1, (this.height - 16 - chrome) / ROW_H);
        visibleRows = Math.min(totalRows(), maxRows);
        scroll = Math.max(0, Math.min(scroll, totalRows() - visibleRows));
        panelH = chrome + visibleRows * ROW_H;
        panelX = (this.width - panelW) / 2;
        panelY = Math.max(8, (this.height - panelH) / 2);
        listY = panelY + PAD + this.font.lineHeight + GAP;

        for (int i = 0; i < visibleRows; i++) {
            for (int c = 0; c < COLUMNS; c++) {
                int index = (scroll + i) * COLUMNS + c;
                if (index >= mods.size()) break;
                int buttonX = cellX(c) + cellW() - ICON_BUTTON - 2;
                int rowY = listY + i * ROW_H + (ROW_H - ICON_BUTTON) / 2;
                addRenderableWidget(suggestButton(mods.get(index), buttonX, rowY));
            }
        }

        int linkY = listY + visibleRows * ROW_H + GAP + bodyH + GAP + hintH + GAP;
        Component link = Component.translatable("gui.dungeontrain.unsupported_mods.whitelist_link")
            .withStyle(ChatFormatting.AQUA);
        int linkW = this.font.width(link);
        addRenderableWidget(new PlainTextButton(this.width / 2 - linkW / 2, linkY, linkW,
            this.font.lineHeight, link, b -> openWhitelist(), this.font));

        int buttonY = panelY + panelH - PAD - BUTTON_H;
        int half = (inner - GAP) / 2;
        // Continue is red: carrying on means playing this launch in Free Play.
        addRenderableWidget(new DarkTintedButton(panelX + PAD, buttonY, half, BUTTON_H,
            CommonComponents.GUI_CONTINUE, b -> onClose(), 1.15F, 0.35F, 0.35F));
        int quitX = panelX + PAD + inner - half;
        if (disableFailed) {
            // Disabling from here didn't work, so this is now a plain, blue Quit: the player turns the
            // mods off in their launcher and comes back.
            addRenderableWidget(new DarkTintedButton(quitX, buttonY, half, BUTTON_H,
                Component.translatable("menu.quit"), b -> this.minecraft.stop(), 0.45F, 0.60F, 1.25F));
        } else {
            Button quit = addRenderableWidget(new DarkTintedButton(quitX, buttonY, half, BUTTON_H,
                Component.translatable("gui.dungeontrain.unsupported_mods.quit_disable"), b -> quitAndDisable()));
            quit.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.unsupported_mods.quit_disable.tooltip")));
        }
    }

    /**
     * Rename the unsupported mods' jars to {@code .jar.disabled} and quit, so the next launch is a
     * live run. If nothing could be disabled from here (a dev classpath, mods nested in another jar),
     * stay open and say so rather than quitting for nothing.
     */
    private void quitAndDisable() {
        ModDisabler.Outcome outcome = ModDisabler.disable(mods.stream().map(UnsupportedMod::modId).toList());
        if (outcome.anyDisabled()) {
            this.minecraft.stop();
            return;
        }
        disableFailed = true;
        rebuildWidgets();
    }

    private Button suggestButton(UnsupportedMod mod, int x, int y) {
        boolean done = SuggestedMods.contains(mod.modId()) || isFinal(results.get(mod.modId()));
        Button b = new DarkTintedButton(x, y, ICON_BUTTON, ICON_BUTTON,
            Component.literal(done ? "✔" : "+"), btn -> openSuggest(mod));
        b.active = !done;
        b.setTooltip(Tooltip.create(Component.translatable(done
            ? "gui.dungeontrain.unsupported_mods.suggested.tooltip"
            : "gui.dungeontrain.unsupported_mods.suggest.tooltip", mod.displayName())));
        return b;
    }

    private static boolean isFinal(ModSuggestClient.Result r) {
        return r != null && r.isFinal();
    }

    private void openSuggest(UnsupportedMod mod) {
        this.minecraft.setScreen(new ModSuggestScreen(this, mod, result -> {
            Map<String, ModSuggestClient.Result> next = new HashMap<>(results);
            next.put(mod.modId(), result);
            results = Map.copyOf(next);
            if (result.isFinal() && result != ModSuggestClient.Result.ALREADY_DECIDED
                    && result != ModSuggestClient.Result.ALREADY_LISTED) {
                SuggestedMods.add(mod.modId());
            }
        }));
    }

    private void openWhitelist() {
        // Read at click time so a relay-served URL that lands after the screen opened still counts.
        String url = OfficialLinks.modWhitelist();
        this.minecraft.setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) Util.getPlatform().openUri(URI.create(url));
            this.minecraft.setScreen(this);
        }, url, true));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (totalRows() <= visibleRows) return false;
        int next = Math.max(0, Math.min(totalRows() - visibleRows, scroll - (int) Math.signum(scrollY)));
        if (next != scroll) {
            scroll = next;
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title.copy().withStyle(ChatFormatting.BOLD),
            this.width / 2, panelY + PAD, 0xFFFFD060);

        int textX = panelX + PAD;
        int textW = cellW() - ICON_BUTTON - 8;
        for (int i = 0; i < visibleRows; i++) {
            int rowY = listY + i * ROW_H;
            if (((scroll + i) & 1) == 0) g.fill(panelX + PAD / 2, rowY, panelX + panelW - PAD / 2, rowY + ROW_H, ROW_ALT);
            for (int c = 0; c < COLUMNS; c++) {
                int index = (scroll + i) * COLUMNS + c;
                if (index >= mods.size()) break;
                UnsupportedMod mod = mods.get(index);
                int x = cellX(c);
                g.drawString(this.font, trim("• " + mod.displayName(), textW), x, rowY + 3, NAME_COLOUR, false);
                ModSuggestClient.Result r = results.get(mod.modId());
                Component sub = r == null
                    ? Component.literal(mod.modId())
                    : ModSuggestScreen.resultMessage(r);
                int subColour = r == null ? ID_COLOUR : ModSuggestScreen.resultColour(r);
                g.drawString(this.font, trim(sub.getString(), textW - 8), x + 8, rowY + 3 + this.font.lineHeight + 1,
                    subColour, false);
            }
        }
        if (totalRows() > visibleRows) {
            int first = scroll * COLUMNS + 1;
            int last = Math.min(mods.size(), (scroll + visibleRows) * COLUMNS);
            String more = first + "–" + last + " / " + mods.size();
            g.drawString(this.font, more, panelX + panelW - PAD - this.font.width(more),
                panelY + PAD, ID_COLOUR, false);
        }

        // Free Play icon on the left, centred on the paragraph — like the effect in the inventory.
        int y = listY + visibleRows * ROW_H + GAP;
        int bodyH = bodyLines.size() * (this.font.lineHeight + 1);
        int iconY = y + Math.max(0, (bodyH - ICON) / 2);
        g.blitSprite(EFFECT_BACKGROUND, textX, iconY, ICON, ICON);
        g.blit(textX + EFFECT_INSET, iconY + EFFECT_INSET, 0, EFFECT_SPRITE, EFFECT_SPRITE,
            this.minecraft.getMobEffectTextures().get(ModMobEffects.FREE_PLAY));
        int bodyX = textX + ICON + ICON_GAP;
        for (FormattedCharSequence line : bodyLines) {
            g.drawString(this.font, line, bodyX, y, BODY_COLOUR, false);
            y += this.font.lineHeight + 1;
        }
        y = Math.max(y, listY + visibleRows * ROW_H + GAP + ICON) + GAP;
        for (FormattedCharSequence line : hintLines) {
            g.drawCenteredString(this.font, line, this.width / 2, y, disableFailed ? 0xFFE08080 : 0xFFFFD060);
            y += this.font.lineHeight + 1;
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, PANEL_EDGE);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
    }

    private int totalRows() {
        return (mods.size() + COLUMNS - 1) / COLUMNS;
    }

    /** Width of one mod cell: the list's inner width split into {@link #COLUMNS} with a gap between. */
    private int cellW() {
        return (panelW - PAD * 2 - GAP * (COLUMNS - 1)) / COLUMNS;
    }

    private int cellX(int column) {
        return panelX + PAD + column * (cellW() + GAP);
    }

    private String trim(String s, int width) {
        if (this.font.width(s) <= width) return s;
        return this.font.plainSubstrByWidth(s, width - this.font.width("…")) + "…";
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
