package games.brennan.dungeontrain.client.live;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stream grabs the finished frame on {@link RenderFrameEvent.Post}; everything the wearer alone
 * should see must draw on that event <em>after</em> the grab, or it is baked into the broadcast.
 */
class LiveFrameOrderTest {

    private static EventPriority priority(Class<?> owner, String method) throws NoSuchMethodException {
        return owner.getDeclaredMethod(method, RenderFrameEvent.Post.class)
            .getAnnotation(SubscribeEvent.class).priority();
    }

    @Test
    void viewfinderAndHeadViewerDrawAfterTheStreamGrabsTheFrame() throws Exception {
        EventPriority grab = priority(LiveStreamController.class, "onRenderFrameEnd");
        EventPriority viewfinder = priority(LiveRecOverlay.class, "onFrameEnd");
        EventPriority viewer = priority(LiveHeadViewer.class, "onFrameEnd");
        // EventPriority is declared HIGHEST → LOWEST, and the bus runs it in that order.
        assertTrue(viewfinder.ordinal() > grab.ordinal(), "the camcorder viewfinder must stay out of the stream");
        assertTrue(viewer.ordinal() > grab.ordinal(), "the pinned live viewer must stay out of the stream");
        assertTrue(viewer.ordinal() > viewfinder.ordinal(), "the pinned viewer layers over the viewfinder");
    }
}
