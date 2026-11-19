package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.compat.mixinguard.MixinTargetCheck;
import games.brennan.dungeontrain.compat.mixinguard.MixinTargetRequirement;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gates {@code dungeontrain.musicmods.mixins.json}: each mixin there hides one stream-muted music mod's
 * lyrics while the player streams ({@code client.live.StreamHiddenOverlays}). A mixin applies only when
 * its mod is installed and its target still has the hooked method; otherwise it is skipped with one log
 * line (INFO when the mod is simply absent, WARN when an installed build moved the hook, because that
 * mod's lyrics then reach the stream).
 */
public final class MusicModsMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One mixin's mod, target class and the methods it hooks. */
    record Hook(String modId, String target, List<MixinTargetRequirement> methods) {}

    static final Map<String, Hook> HOOKS = Map.of(
        "ShowTheLyricsHideMixin", new Hook("showthelyrics", "com.showthelyrics.render.Lyrics3DRenderer",
            List.of(MixinTargetRequirement.method("render",
                "(Lnet/neoforged/neoforge/client/event/RenderLevelStageEvent;)V"))),
        "DangoMusicHideMixin", new Hook("dangomusic", "me.sorihiko.dango.modern.ModernDango",
            List.of(MixinTargetRequirement.method("hudOverlay", "(Ljava/lang/Object;)V"),
                MixinTargetRequirement.method("worldRenderEnd", "()V"))),
        "ConcertoHudHideMixin", new Hook("concerto", "top.gregtao.concerto.screen.InGameHudRenderer",
            List.of(MixinTargetRequirement.method("render", "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"))));

    private final Map<String, Boolean> decisions = new ConcurrentHashMap<>();

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return decisions.computeIfAbsent(mixinClassName, MusicModsMixinPlugin::decide);
    }

    private static boolean decide(String mixinClassName) {
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        Hook hook = HOOKS.get(simple);
        if (hook == null) {
            LOGGER.error("[DungeonTrain] {} has no entry in MusicModsMixinPlugin.HOOKS; not applied", simple);
            return false;
        }
        try {
            if (LoadingModList.get().getModFileById(hook.modId()) == null) return false;
            ClassNode node = MixinService.getService().getBytecodeProvider()
                .getClassNode(hook.target().replace('.', '/'));
            List<String> misses = MixinTargetCheck.missing(node, hook.methods());
            if (!misses.isEmpty()) {
                LOGGER.warn("[DungeonTrain] {} no longer has {}; its lyrics are NOT hidden from the Live Eye stream",
                    hook.modId(), String.join(", ", misses));
                return false;
            }
            LOGGER.info("[DungeonTrain] {}: lyrics hidden from the Live Eye stream while streaming", hook.modId());
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not read {} ({}); its lyrics are NOT hidden from the Live Eye stream",
                hook.target(), t.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public void onLoad(String mixinPackage) {
        // no-op
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // no-op
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // no-op
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // no-op
    }
}
