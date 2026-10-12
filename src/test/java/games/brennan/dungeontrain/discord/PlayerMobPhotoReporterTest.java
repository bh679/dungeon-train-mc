package games.brennan.dungeontrain.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerMobPhotoReporterTest {

    @Test
    void titleCreditsTheMobByName() {
        assertEquals("📸 Shutterbug took a photo of Steve", PlayerMobPhotoReporter.title("Shutterbug", "Steve"));
    }

    @Test
    void namelessMobIsAFellowPassenger() {
        assertEquals("📸 A fellow passenger took a photo of Steve", PlayerMobPhotoReporter.title("", "Steve"));
        assertEquals("Steve handed over a camera · They handed back the print", PlayerMobPhotoReporter.description(null, "Steve"));
    }

    @Test
    void descriptionSaysWhoKeepsThePrint() {
        assertEquals("Steve handed over a camera · Shutterbug handed back the print",
            PlayerMobPhotoReporter.description("Shutterbug", "Steve"));
    }
}
