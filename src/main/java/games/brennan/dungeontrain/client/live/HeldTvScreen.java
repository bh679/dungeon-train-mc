package games.brennan.dungeontrain.client.live;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import games.brennan.dungeontrain.compat.vista.TvItems;
import net.mehvahdjukaar.vista.client.renderer.TvBlockEntityRenderer;
import net.mehvahdjukaar.vista.common.tv.IntAnimationState;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * The screen of a hand-held Vista TV that is switched on ({@link TvItems#isOn}): the live feed drawn
 * on the item's front face, and a pose that turns that face toward the camera.
 *
 * <p>Vista's item renderer draws the TV as its block model and, for a TV worn on the head, paints a
 * smile where the screen is. {@code mixin/vista/TvItemRendererMixin} calls {@link #pose} before that
 * and {@link #draw} after it, in the hand contexts only. The screen quad sits exactly where Vista's
 * smile does, on the north face; the picture is the same shared decode every TV in the world shows
 * ({@link LiveFeedSource#MAIN}).</p>
 *
 * <p>The item model shows the set edge-on (yaw 45° right hand, 225° left hand), which is why a plain
 * block item never showed its screen. {@link #poseFor} spins the set about its centre so the front
 * faces the viewer, then tips it back a little the way one holds a phone; the numbers are per
 * context and were tuned in-game.</p>
 */
public final class HeldTvScreen {

    /** Yaw about Y (degrees), pitch about X (degrees), then a translate, applied at the block centre. */
    public record Pose(float yaw, float pitch, float dx, float dy, float dz) {
        public static final Pose NONE = new Pose(0, 0, 0, 0, 0);
    }

    /** The model yaws the right hand 45° and the left 225°; the front faces the viewer at 180°. */
    static final Pose FIRST_PERSON_RIGHT = new Pose(135f, -12f, -0.08f, 0.06f, 0.05f);
    static final Pose FIRST_PERSON_LEFT = new Pose(-45f, -12f, 0.08f, 0.06f, 0.05f);
    /** Third person lays the set down (pitch 75°); a half turn shows the screen to onlookers. */
    static final Pose THIRD_PERSON = new Pose(135f, 0f, 0f, 0f, 0f);

    /** Where Vista paints the head smile: the north face, inset to the bezel, a hair in front of it. */
    static final float HALF = 0.375f;
    static final float FACE_Z = -0.005f;

    private HeldTvScreen() {}

    /** The contexts the screen shows in: in a hand, first or third person. Head, GUI, ground: no. */
    public static boolean isHandContext(ItemDisplayContext context) {
        return context.firstPerson() || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
            || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
    }

    /** True when {@code stack} in {@code context} gets a screen and a pose. */
    public static boolean shows(ItemStack stack, ItemDisplayContext context) {
        return isHandContext(context) && TvItems.isOn(stack);
    }

    public static Pose poseFor(ItemDisplayContext context) {
        return switch (context) {
            case FIRST_PERSON_RIGHT_HAND -> FIRST_PERSON_RIGHT;
            case FIRST_PERSON_LEFT_HAND -> FIRST_PERSON_LEFT;
            case THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> THIRD_PERSON;
            default -> Pose.NONE;
        };
    }

    /** Apply {@link #poseFor} to {@code poseStack}, whose origin is the block centre. */
    public static void pose(ItemDisplayContext context, PoseStack poseStack) {
        Pose p = poseFor(context);
        poseStack.translate(p.dx(), p.dy(), p.dz());
        poseStack.mulPose(Axis.YP.rotationDegrees(p.yaw()));
        poseStack.mulPose(Axis.XP.rotationDegrees(p.pitch()));
    }

    /**
     * Draw the feed on the front face. {@code poseStack}'s origin is the block centre, as Vista
     * leaves it after its own render; {@code light} is the item's packed light, whose sky half is
     * kept and whose block half is forced to full — a screen glows.
     */
    public static void draw(PoseStack poseStack, MultiBufferSource buffer, int light) {
        Minecraft mc = Minecraft.getInstance();
        int tick = mc.player != null ? mc.player.tickCount : 0;
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
        VertexConsumer vc = LiveFeedSource.MAIN.screenConsumer(buffer, TVBlockEntity.MIN_SCREEN_PIXEL_SIZE_VEC,
            tick, partial, false, IntAnimationState.MAX_ANIM, IntAnimationState.MAX_ANIM);
        poseStack.pushPose();
        poseStack.translate(0, 0, FACE_Z);
        int glow = LightTexture.pack(15, LightTexture.sky(light));
        TvBlockEntityRenderer.addQuad(vc, poseStack, -HALF, -HALF, HALF, HALF, glow);
        poseStack.popPose();
    }
}
