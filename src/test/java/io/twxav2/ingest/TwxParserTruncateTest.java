package io.twxav2.ingest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TwxParserTruncateTest {

    @Test
    void shortStaysUnchanged() {
        assertNull(TwxParser.truncate(null, 10));
        assertEquals("abc", TwxParser.truncate("abc", 3));
        assertEquals("abc", TwxParser.truncate("abc", 100));
    }

    @Test
    void longIsWithDotsMarked() {
        String out = TwxParser.truncate("abcdefghij", 8);
        assertEquals("abcde...", out);
        assertEquals(8, out.length()); // maximum length is respected
    }
}
