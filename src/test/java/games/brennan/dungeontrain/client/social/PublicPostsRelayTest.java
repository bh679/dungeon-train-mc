package games.brennan.dungeontrain.client.social;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicPostsRelayTest {

    @Test
    void parsesLinksListAndFallsBackToSingleUrl() {
        List<PublicPostsRelay.Featured> items = PublicPostsRelay.parseFeatured("""
            {"items":[
              {"id":"social-death-7","kind":"death","carriage":412,"title":null,"ts":1,
               "platform":"instagram","url":"https://www.instagram.com/stories/dungeontrain/1/",
               "links":[{"platform":"instagram","url":"https://www.instagram.com/stories/dungeontrain/1/"},
                        {"platform":"facebook","url":"https://www.facebook.com/100746411470742"},
                        {"platform":"facebook","url":"http://not-https.test/"}]},
              {"id":"social-tribute-9","kind":"tribute","platform":"facebook",
               "url":"https://www.facebook.com/100746411470742","carriage":null,"title":"Sunset Bridge","ts":2}
            ]}""");
        assertEquals(2, items.size());
        assertTrue(items.get(0).isDeath());
        assertEquals(412, items.get(0).carriage());
        assertNull(items.get(0).title());
        assertEquals(List.of("instagram", "facebook"), items.get(0).links().stream().map(PublicPostsRelay.Link::platform).toList());
        assertEquals("Sunset Bridge", items.get(1).title());
        assertEquals(List.of(new PublicPostsRelay.Link("facebook", "https://www.facebook.com/100746411470742")), items.get(1).links());
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
