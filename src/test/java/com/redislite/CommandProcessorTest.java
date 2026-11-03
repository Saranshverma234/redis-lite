package com.redislite;
 
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
 
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
 
class CommandProcessorTest {
 
    private InMemoryStore store;
    private CommandProcessor processor;
 
    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        processor = new CommandProcessor(store);
    }

    private long number(String reply) {
        assertTrue(reply.startsWith(":"), "expected an integer reply but got: " + reply);
        return Long.parseLong(reply.substring(1));
    }
 
    private void assertError(String reply) {
        assertTrue(reply.startsWith("-ERR"), "expected an error but got: " + reply);
    }
 
    @Test
    void getMissingKeyReturnsNil() {
        assertEquals("$-1", processor.handle("GET nothing"));
    }
 
    @Test
    void setThenGet() {
        assertEquals("+OK", processor.handle("SET name Saransh"));
        assertEquals("Saransh", processor.handle("GET name"));
    }
 
    @Test
    void commandNamesAreCaseInsensitive() {
        assertEquals("+OK", processor.handle("set a 1"));
        assertEquals("1", processor.handle("GeT a"));
    }
 
    @Test
    void extraWhitespaceIsIgnored() {
        assertEquals("+OK", processor.handle("   SET    a    1   "));
        assertEquals("1", processor.handle("GET a"));
    }
 
    @Test
    void emptyAndNullLinesAreErrors() {
        assertError(processor.handle(""));
        assertError(processor.handle("    "));
        assertError(processor.handle(null));
    }
 
    @Test
    void unknownCommandIsAnError() {
        assertError(processor.handle("FLY away"));
    }
 
    @Test
    void wrongArgumentCounts() {
        assertError(processor.handle("GET"));
        assertError(processor.handle("GET a b"));
        assertError(processor.handle("SET a"));
        assertError(processor.handle("SET a b c"));
        assertError(processor.handle("DEL"));
        assertError(processor.handle("DEL a b"));
    }
 
    @Test
    void setWithExSetsAnExpiry() {
        assertEquals("+OK", processor.handle("SET k v EX 60"));
        long ttl = store.ttl("k");
        assertTrue(ttl > 0 && ttl <= 60, "ttl was " + ttl);
    }
 
    @Test
    void badExValuesAreRejectedAndCreateNoKey() {
        assertError(processor.handle("SET k v EX abc"));
        assertError(processor.handle("SET k v EX 0"));
        assertError(processor.handle("SET k v EX -5"));
        assertError(processor.handle("SET k v EX 99999999999999999")); // overflows ms conversion
        assertError(processor.handle("SET k v EX"));
        assertError(processor.handle("SET k v PX 10"));
        assertEquals("$-1", processor.handle("GET k"));
    }
 
    @Test
    void delReturnsOneThenZero() {
        processor.handle("SET a 1");
        assertEquals(":1", processor.handle("DEL a"));
        assertEquals(":0", processor.handle("DEL a"));
        assertEquals("$-1", processor.handle("GET a"));
    }


    @Test
    void existsReturnsOneOrZero() {
        assertEquals(":0", processor.handle("EXISTS a"));
        processor.handle("SET a 1");
        assertEquals(":1", processor.handle("EXISTS a"));
    }
 
    @Test
    void expireSetsATtl() {
        processor.handle("SET a 1");
        assertEquals(":1", processor.handle("EXPIRE a 60"));
        long ttl = number(processor.handle("TTL a"));
        assertTrue(ttl > 0 && ttl <= 60, "ttl was " + ttl);
    }
 
    @Test
    void expireOnMissingKeyReturnsZero() {
        assertEquals(":0", processor.handle("EXPIRE ghost 60"));
    }
 
    @Test
    void ttlReturnsSpecialCodes() {
        assertEquals(":-2", processor.handle("TTL ghost"));   // missing
        processor.handle("SET a 1");
        assertEquals(":-1", processor.handle("TTL a"));       // no expiry
    }
 
    @Test
    void persistRemovesTheTtl() {
        processor.handle("SET a 1");
        processor.handle("EXPIRE a 60");
        assertEquals(":1", processor.handle("PERSIST a"));
        assertEquals(":-1", processor.handle("TTL a"));
        assertEquals(":0", processor.handle("PERSIST a"));      // nothing left to remove
        assertEquals(":0", processor.handle("PERSIST ghost"));  // missing key
    }
 
    @Test
    void incrCreatesAndCounts() {
        assertEquals(":1", processor.handle("INCR n"));
        assertEquals(":2", processor.handle("INCR n"));
        assertEquals("2", processor.handle("GET n"));
    }
 
    @Test
    void incrKeepsTheTtl() {
        processor.handle("SET n 5");
        processor.handle("EXPIRE n 60");
        assertEquals(":6", processor.handle("INCR n"));
        assertTrue(number(processor.handle("TTL n")) > 0);
    }
 
    @Test
    void incrOnNonNumericValueIsAnErrorAndLeavesValueAlone() {
        processor.handle("SET s hello");
        assertError(processor.handle("INCR s"));
        assertEquals("hello", processor.handle("GET s"));
    }
 
    @Test
    void incrOverflowIsAnErrorAndLeavesValueAlone() {
        processor.handle("SET big 9223372036854775807");
        assertError(processor.handle("INCR big"));
        assertEquals("9223372036854775807", processor.handle("GET big"));
    }
 
    @Test
    void badExpireArguments() {
        assertError(processor.handle("EXPIRE a"));
        assertError(processor.handle("EXPIRE a 1 2"));
        assertError(processor.handle("EXPIRE a abc"));
        assertError(processor.handle("EXPIRE a 0"));
        assertError(processor.handle("EXPIRE a -1"));
        assertError(processor.handle("EXPIRE a 99999999999999999")); // overflows ms conversion
    }
 
    @Test
    void wrongArgumentCountsForSingleKeyCommands() {
        for (String cmd : new String[] {"EXISTS", "TTL", "PERSIST", "INCR"}) {
            assertError(processor.handle(cmd));
            assertError(processor.handle(cmd + " a b"));
        }
    }
}