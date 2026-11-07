package com.selfanalyst.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfanalyst.wiki.WikiPrivacyPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventPrivacyFilterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void nestedArraysDropOnlyMatchingEventObjectsAndKeepTheirOrder() throws Exception {
        String response = """
                {"results":[[{"data":{"app":"PRIVATE.EXE","title":"secret"}},
                  {"data":{"app":"editor.exe","title":"visible"},"duration":12}],
                  {"nested":[{"data":{"app":"private.exe"}},7,"unchanged"]}],"total":20}
                """;
        var filtered = JSON.readTree(EventPrivacyFilter.filter(response, WikiPrivacyPolicy.of("private.exe", "")));
        assertEquals(1, filtered.at("/results/0").size());
        assertEquals("visible", filtered.at("/results/0/0/data/title").asText());
        assertEquals(12, filtered.at("/results/0/0/duration").asInt());
        assertEquals(JSON.readTree("[7,\"unchanged\"]"), filtered.at("/results/1/nested"));
        assertEquals(20, filtered.path("total").asInt());
    }

    @Test void titlesHostsAndPrivateFlagsUseTheSamePolicy() throws Exception {
        String response = """
                [{"data":{"context_title":"Private.test article"}},
                 {"data":{"url_host":"sub.private.test"}},
                 {"data":{"title":"Browser Incognito"}},
                 {"data":{"private_browsing":true}},
                 {"data":{"title":"normal","private_browsing":false}}]
                """;
        var filtered = JSON.readTree(EventPrivacyFilter.filter(response, WikiPrivacyPolicy.of("", "private.test")));
        assertEquals(1, filtered.size());
        assertEquals("normal", filtered.get(0).path("data").path("title").asText());
    }

    @Test void nonEventObjectsAndNonArrayEventRootsRemainIntact() {
        var policy = WikiPrivacyPolicy.of("private.exe", "private.test");
        for (String response : new String[] {
                "[{\"app\":\"private.exe\"},{\"data\":\"private.exe\"},42,true,null]",
                "{\"data\":{\"app\":\"private.exe\"},\"duration\":12}"}) {
            assertEquals(response, EventPrivacyFilter.filter(response, policy));
        }
    }

    @Test void unchangedJsonPreservesFormattingAndFullNameMatching() {
        String response = " [ { \"data\" : { \"app\" : \"PotPlayerMini64.exe\" } } ] ";
        assertEquals(response, EventPrivacyFilter.filter(response, WikiPrivacyPolicy.none()));
        assertEquals(response, EventPrivacyFilter.filter(response, WikiPrivacyPolicy.of("PotPlayer.exe", "")));
    }

    @Test void malformedOrTrailingJsonFailsClosedWithoutEchoingPrivateContent() throws Exception {
        for (String response : new String[] {null, "", "<html>private.exe secret</html>",
                "[] {\"data\":{\"app\":\"private.exe\"}}"}) {
            String filtered = EventPrivacyFilter.filter(response, WikiPrivacyPolicy.none());
            assertTrue(JSON.readTree(filtered).has("error"), "Malformed input: " + response);
            assertFalse(filtered.contains("private.exe"));
            assertFalse(filtered.contains("secret"));
        }
    }
    @Test void aqlRecognizesFlatRowsAndAwEventsButPreservesNonEvents() throws Exception {
        String response = """
                {"rows":[{"timestamp":"now","duration":5,"app":"private.exe","title":"secret"},
                  {"timestamp":"now","duration":3,"app":"editor.exe","title":"visible"},
                  {"data":{"app":"private.exe"}},
                  {"app":"private.exe"},7],
                 "named":{"results":[{"timestamp":null,"duration":"changed","app":"private.exe"}]},
                 "data":{"app":"private.exe"},"total_duration":8}
                """;
        var policy = WikiPrivacyPolicy.of("private.exe", "");
        var result = JSON.readTree(EventPrivacyFilter.filterAql(response, policy));
        assertEquals(3, result.path("rows").size());
        assertEquals("visible", result.at("/rows/0/title").asText());
        assertEquals("private.exe", result.at("/rows/1/app").asText());
        assertEquals(7, result.at("/rows/2").asInt());
        assertTrue(result.at("/named/results").isEmpty());
        assertEquals("private.exe", result.at("/data/app").asText());
        assertEquals(8, result.path("total_duration").asInt());
        // QueryEvents must keep its narrower AW event-only contract.
        var ordinary = JSON.readTree(EventPrivacyFilter.filter(response, policy));
        assertEquals("secret", ordinary.at("/rows/0/title").asText());
        assertEquals(1, ordinary.at("/named/results").size());
    }

    @Test void aqlInvalidJsonFailsClosedAndUnchangedJsonKeepsFormatting() {
        assertTrue(EventPrivacyFilter.filterAql("private.exe", WikiPrivacyPolicy.none()).contains("error"));
        String response = " { \"rows\" : [ { \"timestamp\" : \"now\", \"duration\" : 5 } ], \"other\" : null } ";
        assertEquals(response, EventPrivacyFilter.filterAql(response, WikiPrivacyPolicy.none()));
    }

}
