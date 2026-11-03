package com.redislite;
 
import org.junit.jupiter.api.Test;
 
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
 
class KeysTest {
 
    // ---------- GlobMatcher ----------
 
    @Test
    void starMatchesAnyRun() {
        assertTrue(GlobMatcher.matches("*", "anything"));
        assertTrue(GlobMatcher.matches("*", ""));
        assertTrue(GlobMatcher.matches("user:*", "user:42"));
        assertFalse(GlobMatcher.matches("user:*", "admin:1"));
        assertTrue(GlobMatcher.matches("*a*b*", "xaxbx"));
        assertFalse(GlobMatcher.matches("*a*b*", "xbxax"));
        assertTrue(GlobMatcher.matches("a**b", "ab"));
    }
 
    @Test
    void questionMarkMatchesExactlyOneCharacter() {
        assertTrue(GlobMatcher.matches("h?llo", "hello"));
        assertFalse(GlobMatcher.matches("h?llo", "hllo"));
        assertFalse(GlobMatcher.matches("h?llo", "heello"));
    }
 
    @Test
    void emptyPatternMatchesOnlyEmptyText() {
        assertTrue(GlobMatcher.matches("", ""));
        assertFalse(GlobMatcher.matches("", "a"));
        assertFalse(GlobMatcher.matches("a*", ""));
    }
 
    @Test
    void regexCharactersAreTreatedLiterally() {
        assertTrue(GlobMatcher.matches("a.b", "a.b"));
        assertFalse(GlobMatcher.matches("a.b", "aXb"));
        assertFalse(GlobMatcher.matches("[abc]", "a"));
        assertTrue(GlobMatcher.matches("[abc]", "[abc]"));
    }
 
    // ---------- KEYS through the processor ----------
 
    private CommandProcessor newProcessor() {
        return new CommandProcessor(new InMemoryStore());
    }
 
    @Test
    void keysReturnsMatchingKeysSorted() {
        CommandProcessor p = newProcessor();
        p.handle("SET user:2 b");
        p.handle("SET user:1 a");
        p.handle("SET admin:1 c");
 
        assertEquals("user:1 user:2", p.handle("KEYS user:*"));
        assertEquals("admin:1 user:1 user:2", p.handle("KEYS *"));
        assertEquals("(empty)", p.handle("KEYS zzz*"));
    }
 
    @Test
    void keysWithoutPatternIsAnError() {
        CommandProcessor p = newProcessor();
        assertTrue(p.handle("KEYS").startsWith("-ERR"));
        assertTrue(p.handle("KEYS a b").startsWith("-ERR"));
    }
 
    @Test
    void keysSkipsExpiredKeys() throws Exception {
        CommandProcessor p = newProcessor();
        p.handle("SET gone v EX 1");
        p.handle("SET stays v");
        Thread.sleep(1100);
 
        assertEquals("stays", p.handle("KEYS *"));
    }
}