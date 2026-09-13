package games.brennan.dungeontrain.mixin;

import com.mojang.brigadier.StringReader;
import games.brennan.dungeontrain.advancement.SelfSelectorGrant;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Parse-time half of {@link SelfSelectorGrant}: lets a capstone-holder write {@code @s} below
 * permission 2. Vanilla decides selector permission once per argument, at
 * {@code EntityArgument.parse(reader, source)} → {@code EntitySelectorParser.allowSelectors(source)};
 * this redirects that one call and ORs in the grant, which sees the reader too and so can insist
 * on a bare {@code @s} token rather than any selector.
 *
 * <p>Resolve-time is fenced separately in {@link EntitySelectorSelfPermissionMixin} — vanilla checks
 * again when the selector is actually resolved against the world.</p>
 */
@Mixin(EntityArgument.class)
public abstract class EntityArgumentSelfSelectorMixin {

    @Redirect(
        method = "parse(Lcom/mojang/brigadier/StringReader;Ljava/lang/Object;)Lnet/minecraft/commands/arguments/selector/EntitySelector;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/arguments/selector/EntitySelectorParser;allowSelectors(Ljava/lang/Object;)Z"))
    private boolean dungeontrain$allowSelfSelector(Object source, StringReader reader, Object sourceAgain) {
        return EntitySelectorParser.allowSelectors(source) || SelfSelectorGrant.allowsAtParse(reader, source);
    }
}
