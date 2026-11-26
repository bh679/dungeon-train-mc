package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.perks.aura.AuraClientState;
import games.brennan.dungeontrain.perks.aura.AuraDef;
import games.brennan.dungeontrain.perks.aura.AuraDraws;
import games.brennan.dungeontrain.perks.aura.AuraLadder;
import games.brennan.dungeontrain.perks.aura.AuraPreview;
import games.brennan.dungeontrain.perks.aura.AuraTogglePacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * An aura perk's chips in the details bar: one per drawn effect, its name (and level from II) with its
 * range in small text, in the aura's colour; switched-off ones grey and struck through. A hovered point
 * previews in gold: an arrow on the one range it grows, or a last dashed chip saying what it does.
 * Clicking a chip asks the server to switch that effect off or on ({@link AuraTogglePacket}).
 */
@OnlyIn(Dist.CLIENT)
final class AuraChips {

    private static final String KEY = "gui.dungeontrain.perks.aura.";
    private static final String STAT = "gui.dungeontrain.perks.stat.";
    private static final int OFF = 0x7A7A86, SUB = 0x9AA0B8;
    private static final float MIN_SCALE = 0.6F;

    /** Where each chip was last drawn, for clicks. */
    private record Hit(float x0, float y0, float x1, float y1, ResourceLocation effect) {}

    private record Chip(String name, String sub, String next, int colour, boolean off, ResourceLocation effect, boolean dashed) {}

    private static final List<Hit> HITS = new ArrayList<>();
    private static String hitsFor;

    private AuraChips() {}

    static void render(GuiGraphics g, Font font, AuraDef def, int points, int preview, int x, int y, int right,
                       int chipH, int pad, int gap, int bg, int gold, int a, boolean thisLife) {
        HITS.clear();
        hitsFor = def.perkId();
        List<Chip> chips = chips(def, points, preview, thisLife);
        if (chips.isEmpty()) return;
        int width = 0;
        for (Chip c : chips) width += chipWidth(font, c, pad) + gap;
        width -= gap;
        int room = right - x;
        float scale = width <= room ? 1F : Math.max(MIN_SCALE, room / (float) width);
        g.pose().pushPose();
        g.pose().translate(x, y + chipH * (1F - scale), 0);
        g.pose().scale(scale, scale, 1F);
        int cx = 0;
        int ty = (chipH - font.lineHeight) / 2 + 1;
        for (Chip c : chips) {
            int w = chipWidth(font, c, pad);
            if (cx + w > room / scale && cx > 0) break; // never past the bar
            int border = c.dashed() ? gold : c.off() ? OFF : c.colour();
            g.fill(cx, 0, cx + w, chipH, ((int) (a * 0.9) << 24) | (bg & 0xFFFFFF));
            outline(g, cx, w, chipH, (a << 24) | (border & 0xFFFFFF), c.dashed());
            int tx = cx + pad;
            int nameColour = c.dashed() ? gold : c.off() ? OFF : c.colour();
            g.drawString(font, c.name(), tx, ty, (a << 24) | (nameColour & 0xFFFFFF), false);
            int nameW = font.width(c.name());
            if (c.off()) g.fill(tx, ty + 3, tx + nameW, ty + 4, (a << 24) | (OFF & 0xFFFFFF));
            tx += nameW;
            if (!c.sub().isEmpty()) {
                g.drawString(font, c.sub(), tx, ty, (a << 24) | ((c.off() ? OFF : SUB) & 0xFFFFFF), false);
                tx += font.width(c.sub());
            }
            if (!c.next().isEmpty()) g.drawString(font, c.next(), tx, ty, (a << 24) | (gold & 0xFFFFFF), false);
            if (c.effect() != null) {
                HITS.add(new Hit(x + cx * scale, y + chipH * (1F - scale), x + (cx + w) * scale, y + chipH, c.effect()));
            }
            cx += w + gap;
        }
        g.pose().popPose();
    }

    /** A click on a drawn chip switches it; true when it hit one. */
    static boolean click(String perkId, double mx, double my) {
        if (!perkId.equals(hitsFor)) return false;
        for (Hit h : HITS) {
            if (mx >= h.x0() && mx < h.x1() && my >= h.y0() && my < h.y1()) {
                DungeonTrainNet.sendToServer(new AuraTogglePacket(perkId, h.effect()));
                return true;
            }
        }
        return false;
    }

    /** True once the player holds a drawn effect of this aura, so the hint line has something to point at. */
    static boolean hasDraws(AuraDef def) {
        return !AuraClientState.draws().of(def.perkId()).effects().isEmpty();
    }

    static Component hint(AuraDef def) {
        return Component.translatable(KEY + def.langGroup() + ".hint");
    }

    private static List<Chip> chips(AuraDef def, int points, int preview, boolean thisLife) {
        AuraLadder.Shape now = def.ladder().shape(points);
        // on the death menu the life's draws are gone: the next life rolls its own, so name none
        AuraDraws draws = thisLife ? AuraClientState.draws().of(def.perkId()) : AuraDraws.EMPTY;
        Set<ResourceLocation> off = AuraClientState.toggles().off(def.perkId());
        AuraPreview.Change change = preview > points
            ? AuraPreview.between(now, def.ladder().shape(preview)) : new AuraPreview.Change(AuraPreview.Kind.NONE, -1);
        AuraLadder.Shape then = preview > points ? def.ladder().shape(preview) : now;
        List<Chip> out = new ArrayList<>();
        for (int i = 0; i < now.count(); i++) {
            ResourceLocation id = i < draws.effects().size() ? draws.effects().get(i) : null;
            int level = id == null ? 1 : draws.level(i, now.levelUps());
            String name = id == null ? Component.translatable(KEY + def.langGroup() + ".unknown").getString() : name(id, level, def);
            String sub = " · " + Component.translatable(STAT + "unit.blocks", StatChips.number(now.range(i))).getString();
            String next = change.kind() == AuraPreview.Kind.ONE_RANGE && change.index() == i
                ? " → " + StatChips.number(then.range(i)) : "";
            out.add(new Chip(name, sub, next, def.chipColour(), id != null && off.contains(id), id, false));
        }
        String dashed = switch (change.kind()) {
            case NEW -> Component.translatable(KEY + def.langGroup() + ".new").getString();
            case ALL_AND_NEW -> Component.translatable(KEY + def.langGroup() + ".all_new").getString();
            case ALL -> Component.translatable(KEY + def.langGroup() + ".all").getString();
            case LEVEL -> Component.translatable(KEY + def.langGroup() + ".level",
                Component.translatable("enchantment.level." + nextLevel(def, draws, now, off))).getString();
            default -> null;
        };
        if (dashed != null) out.add(new Chip("→ " + dashed, "", "", def.chipColour(), false, null, true));
        return out;
    }

    /** The level the next level round reaches: one above the lowest switched-on effect that takes levels. */
    private static int nextLevel(AuraDef def, AuraDraws draws, AuraLadder.Shape now, Set<ResourceLocation> off) {
        int lowest = Integer.MAX_VALUE;
        for (int i = 0; i < Math.min(now.count(), draws.effects().size()); i++) {
            ResourceLocation e = draws.effects().get(i);
            if (off.contains(e) || def.noLevels().contains(e)) continue;
            lowest = Math.min(lowest, draws.level(i, now.levelUps()));
        }
        return lowest == Integer.MAX_VALUE ? 2 : lowest + 1;
    }

    private static String name(ResourceLocation id, int level, AuraDef def) {
        MobEffect effect = BuiltInRegistries.MOB_EFFECT.get(id);
        String base = effect == null ? id.getPath() : Component.translatable(effect.getDescriptionId()).getString();
        if (level <= 1 || def.noLevels().contains(id)) return base;
        return base + " " + Component.translatable("enchantment.level." + level).getString();
    }

    private static int chipWidth(Font font, Chip c, int pad) {
        return pad * 2 + font.width(c.name()) + font.width(c.sub()) + font.width(c.next());
    }

    private static void outline(GuiGraphics g, int x, int w, int h, int colour, boolean dashed) {
        if (!dashed) {
            g.renderOutline(x, 0, w, h, colour);
            return;
        }
        for (int i = x; i < x + w; i += 4) {
            g.fill(i, 0, Math.min(i + 2, x + w), 1, colour);
            g.fill(i, h - 1, Math.min(i + 2, x + w), h, colour);
        }
        for (int j = 0; j < h; j += 4) {
            g.fill(x, j, x + 1, Math.min(j + 2, h), colour);
            g.fill(x + w - 1, j, x + w, Math.min(j + 2, h), colour);
        }
    }
}
