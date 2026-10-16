package games.brennan.dungeontrain.client.promote;

import games.brennan.dungeontrain.advancement.AdvancementPromotion;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.net.relay.AdvancementPromoteClient;
import games.brennan.dungeontrain.player.DifficultyPartition;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * "Promote Advancements" — the player's side of a relay-sent, one-time promote (see
 * {@link AdvancementPromotion}). Lists every advancement from before the difficulty split that the
 * target difficulty doesn't hold yet; the player ticks the ones that should count there.
 *
 * <p>When the developer sent a <b>copy</b> promote, each row also carries a Copy / Move toggle so the
 * player decides per advancement whether Normal keeps it. A <b>move</b> promote has no toggle.</p>
 *
 * <p>Promote and Decline both spend the promote; Not Now leaves it for the next launch. The list is
 * hand-rolled (scroll offset + widget rebuild) like {@code UnsupportedModsScreen}.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class AdvancementPromoteScreen extends Screen {

    private static final int PANEL_W = 360;
    private static final int ROW_H = 22;
    private static final int BOX = 18;
    private static final int MODE_W = 52;
    private static final int TOOL_W = 64;
    private static final int BUTTON_H = 20;
    private static final int GAP = 6;
    private static final int PAD = 12;
    private static final int PANEL_BG = 0xE0101010;
    private static final int PANEL_EDGE = 0xFF505050;
    private static final int ROW_ALT = 0x20FFFFFF;
    private static final int NAME_COLOUR = 0xFFFFFFFF;
    private static final int OFF_COLOUR = 0xFF808080;
    private static final int BODY_COLOUR = 0xFFD0D0D0;

    private final Screen parent;
    private final UUID player;
    private final AdvancementPromoteClient.Promote promote;
    private final List<PromoteRow> rows;
    private final Component difficulty;
    /** Ticked advancements, and those marked Move. Replaced whole, never mutated. */
    private Set<ResourceLocation> selected;
    private Set<ResourceLocation> moving;
    private int scroll;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listY;
    private int visibleRows;
    private List<FormattedCharSequence> bodyLines = List.of();

    public AdvancementPromoteScreen(Screen parent, UUID player, AdvancementPromoteClient.Promote promote,
                                    List<ResourceLocation> candidates) {
        super(Component.translatable("gui.dungeontrain.adv_promote.title"));
        this.parent = parent;
        this.player = player;
        this.promote = promote;
        this.rows = candidates.stream().map(PromoteRow::of).toList();
        this.difficulty = PromoteRow.difficultyName(promote.target());
        this.selected = Set.copyOf(candidates);
        this.moving = promote.mode() == AdvancementPromotion.Mode.MOVE ? Set.copyOf(candidates) : Set.of();
    }

    /** Per-advancement Copy / Move — only on a copy promote, and never into Normal, where move is copy. */
    private boolean perRowMode() {
        return promote.mode() == AdvancementPromotion.Mode.COPY
            && !DifficultyPartition.LEGACY_KEY.equals(promote.target());
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_W, this.width - 32);
        int inner = panelW - PAD * 2;
        var body = Component.translatable("gui.dungeontrain.adv_promote.body", difficulty.getString());
        if (perRowMode()) {
            body.append(" ").append(Component.translatable("gui.dungeontrain.adv_promote.body_copy"));
        } else if (promote.mode() == AdvancementPromotion.Mode.MOVE) {
            body.append(" ").append(Component.translatable("gui.dungeontrain.adv_promote.body_move", difficulty.getString()));
        }
        bodyLines = this.font.split(body, inner);
        int bodyH = bodyLines.size() * (this.font.lineHeight + 1);
        int chrome = PAD + this.font.lineHeight + GAP   // title
            + bodyH + GAP                                // body
            + BUTTON_H + GAP                             // All / None / All Copy / All Move
            + GAP + BUTTON_H + PAD;                      // Promote | Not Now | Decline
        int maxRows = Math.max(1, (this.height - 16 - chrome) / ROW_H);
        visibleRows = Math.min(rows.size(), maxRows);
        scroll = Math.max(0, Math.min(scroll, rows.size() - visibleRows));
        panelH = chrome + visibleRows * ROW_H;
        panelX = (this.width - panelW) / 2;
        panelY = Math.max(8, (this.height - panelH) / 2);

        int toolY = panelY + PAD + this.font.lineHeight + GAP + bodyH + GAP;
        int x = panelX + PAD;
        addRenderableWidget(new DarkTintedButton(x, toolY, TOOL_W, BUTTON_H,
            Component.translatable("gui.dungeontrain.adv_promote.all"), b -> select(allIds())));
        addRenderableWidget(new DarkTintedButton(x + TOOL_W + GAP, toolY, TOOL_W, BUTTON_H,
            Component.translatable("gui.dungeontrain.adv_promote.none"), b -> select(Set.of())));
        if (perRowMode()) {
            int right = panelX + panelW - PAD;
            addRenderableWidget(new DarkTintedButton(right - TOOL_W, toolY, TOOL_W, BUTTON_H,
                Component.translatable("gui.dungeontrain.adv_promote.all_move"), b -> setMoving(allIds())));
            addRenderableWidget(new DarkTintedButton(right - TOOL_W * 2 - GAP, toolY, TOOL_W, BUTTON_H,
                Component.translatable("gui.dungeontrain.adv_promote.all_copy"), b -> setMoving(Set.of())));
        }

        listY = toolY + BUTTON_H + GAP;
        for (int i = 0; i < visibleRows; i++) {
            PromoteRow row = rows.get(scroll + i);
            int rowY = listY + i * ROW_H + (ROW_H - BOX) / 2;
            boolean on = selected.contains(row.id());
            addRenderableWidget(new DarkTintedButton(panelX + PAD, rowY, BOX, BOX,
                Component.literal(on ? "✔" : ""), b -> toggleSelected(row.id())));
            if (perRowMode()) {
                boolean move = moving.contains(row.id());
                Button mode = addRenderableWidget(new DarkTintedButton(panelX + panelW - PAD - MODE_W,
                    listY + i * ROW_H + (ROW_H - BOX) / 2, MODE_W, BOX,
                    Component.translatable(move ? "gui.dungeontrain.adv_promote.move" : "gui.dungeontrain.adv_promote.copy"),
                    b -> toggleMoving(row.id())));
                mode.active = on;
                mode.setTooltip(Tooltip.create(Component.translatable(move
                    ? "gui.dungeontrain.adv_promote.move.tooltip"
                    : "gui.dungeontrain.adv_promote.copy.tooltip", difficulty.getString())));
            }
        }

        int buttonY = panelY + panelH - PAD - BUTTON_H;
        int third = (inner - GAP * 2) / 3;
        Button go = addRenderableWidget(new DarkTintedButton(panelX + PAD, buttonY, third, BUTTON_H,
            Component.translatable("gui.dungeontrain.adv_promote.promote", selected.size()),
            b -> promote(), 0.45F, 1.15F, 0.45F));
        go.active = !selected.isEmpty();
        addRenderableWidget(new DarkTintedButton(panelX + PAD + third + GAP, buttonY, third, BUTTON_H,
            Component.translatable("gui.dungeontrain.adv_promote.not_now"), b -> onClose()));
        addRenderableWidget(new DarkTintedButton(panelX + PAD + (third + GAP) * 2, buttonY, third, BUTTON_H,
            Component.translatable("gui.dungeontrain.adv_promote.decline"), b -> confirmDecline(),
            1.15F, 0.35F, 0.35F));
    }

    private Set<ResourceLocation> allIds() {
        Set<ResourceLocation> all = new HashSet<>();
        for (PromoteRow row : rows) all.add(row.id());
        return Set.copyOf(all);
    }

    private void select(Set<ResourceLocation> next) {
        selected = Set.copyOf(next);
        rebuildWidgets();
    }

    private void setMoving(Set<ResourceLocation> next) {
        moving = Set.copyOf(next);
        rebuildWidgets();
    }

    private void toggleSelected(ResourceLocation id) {
        select(toggled(selected, id));
    }

    private void toggleMoving(ResourceLocation id) {
        setMoving(toggled(moving, id));
    }

    private static Set<ResourceLocation> toggled(Set<ResourceLocation> set, ResourceLocation id) {
        Set<ResourceLocation> next = new HashSet<>(set);
        if (!next.remove(id)) next.add(id);
        return next;
    }

    /** Apply the ticked rows, spend the promote, tell the relay. */
    private void promote() {
        List<ResourceLocation> copy = rows.stream().map(PromoteRow::id)
            .filter(selected::contains).filter(id -> !moving.contains(id)).toList();
        List<ResourceLocation> move = rows.stream().map(PromoteRow::id)
            .filter(selected::contains).filter(moving::contains).toList();
        var dir = AdvancementPromotion.liveDir();
        AdvancementPromotion.markUsed(dir, promote.id());
        AdvancementPromotion.apply(dir, player, promote.target(), copy, move);
        AdvancementPromoteClient.ack(player, promote.id(), true, copy, move);
        SystemToast.add(this.minecraft.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
            Component.translatable("gui.dungeontrain.adv_promote.done.title"),
            Component.translatable("gui.dungeontrain.adv_promote.done.body",
                copy.size() + move.size(), difficulty.getString()));
        onClose();
    }

    private void confirmDecline() {
        this.minecraft.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                AdvancementPromotion.markUsed(AdvancementPromotion.liveDir(), promote.id());
                AdvancementPromoteClient.ack(player, promote.id(), false, List.of(), List.of());
                this.minecraft.setScreen(parent);
            } else {
                this.minecraft.setScreen(this);
            }
        }, Component.translatable("gui.dungeontrain.adv_promote.decline_confirm.title"),
            Component.translatable("gui.dungeontrain.adv_promote.decline_confirm.body")));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (rows.size() <= visibleRows) return false;
        int next = Math.max(0, Math.min(rows.size() - visibleRows, scroll - (int) Math.signum(scrollY)));
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
        int y = panelY + PAD + this.font.lineHeight + GAP;
        for (FormattedCharSequence line : bodyLines) {
            g.drawString(this.font, line, panelX + PAD, y, BODY_COLOUR, false);
            y += this.font.lineHeight + 1;
        }

        int textX = panelX + PAD + BOX + GAP + 16 + 4;
        int textW = panelX + panelW - PAD - (perRowMode() ? MODE_W + GAP : 0) - textX;
        for (int i = 0; i < visibleRows; i++) {
            PromoteRow row = rows.get(scroll + i);
            int rowY = listY + i * ROW_H;
            if (((scroll + i) & 1) == 0) g.fill(panelX + PAD / 2, rowY, panelX + panelW - PAD / 2, rowY + ROW_H, ROW_ALT);
            if (row.icon() != null) g.renderItem(row.icon(), panelX + PAD + BOX + GAP, rowY + (ROW_H - 16) / 2);
            int colour = selected.contains(row.id()) ? NAME_COLOUR : OFF_COLOUR;
            g.drawString(this.font, trim(row.title().getString(), textW), textX,
                rowY + (ROW_H - this.font.lineHeight) / 2, colour, false);
        }

        String count = selected.size() + " / " + rows.size();
        g.drawString(this.font, count, panelX + panelW - PAD - this.font.width(count), panelY + PAD, OFF_COLOUR, false);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, PANEL_EDGE);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_BG);
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
