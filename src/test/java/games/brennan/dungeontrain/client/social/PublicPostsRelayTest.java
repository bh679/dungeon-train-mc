package games.brennan.dungeontrain.client.social;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicPostsRelayTest {

    @Test
    void parsesDeathAndPhotoItems() {
        List<PublicPostsRelay.Featured> items = PublicPostsRelay.parseFeatured("""
            {"items":[
              {"id":"social-death-7-instagram","kind":"death","platform":"instagram",
               "url":"https://www.instagram.com/stories/dungeontrain/1/","carriage":412,"title":null,"ts":1},
              {"id":"social-tribute-9-facebook","kind":"tribute","platform":"facebook",
               "url":"https://www.facebook.com/100746411470742","carriage":null,"title":"Sunset Bridge","ts":2}
            ]}""");
        assertEquals(2, items.size());
        assertTrue(items.get(0).isDeath());
        assertEquals(412, items.get(0).carriage());
        assertNull(items.get(0).title());
        assertEquals("Sunset Bridge", items.get(1).title());
        assertNull(items.get(1).carriage());
    }

    @Test
    void skipsItemsWithoutIdOrHttpsLink() {
        List<PublicPostsRelay.Featured> items = PublicPostsRelay.parseFeatured("""
            {"items":[{"kind":"death","url":"https://x.test/a"},
                      {"id":"a","url":"http://x.test/a"},
                      {"id":"b","url":"javascript:alert(1)"}]}""");
        assertTrue(items.isEmpty());
    }

    @Test
    void unreadableBodyIsEmpty() {
        assertTrue(PublicPostsRelay.parseFeatured("not json").isEmpty());
        assertTrue(PublicPostsRelay.parseFeatured("{}").isEmpty());
    }
}
