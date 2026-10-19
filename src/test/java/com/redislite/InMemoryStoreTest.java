package com.redislite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryStoreTest {

    @Test
    void shouldStoreAndRetrieveValue() {
        InMemoryStore store = new InMemoryStore();

        store.set("name", "Saransh");

        assertEquals("Saransh", store.get("name"));
    }

    @Test
     void shouldDeleteKey() {

        InMemoryStore store = new InMemoryStore();

        store.set("name" , "Saransh");
        store.delete("name");

        assertNull(store.get("name"));
    }

    @Test
    void shouldReturnNullForExpiredKey() throws InterruptedException{

        InMemoryStore store = new InMemoryStore();

        store.set("name" , "Saransh");
        store.expire("name", 1L);

        Thread.sleep(1100);

        assertNull(store.get("name"));
    }

    
@Test
void shouldClearOldTtlWhenKeyIsOverwritten() {
    InMemoryStore store = new InMemoryStore();

    store.set("name", "Saransh");
    store.expire("name", 1L);

    store.set("name", "Alex");

    assertEquals("Alex", store.get("name"));
}


@Test 
void clearOldTtlWhenKeyIsDeleted() {
    InMemoryStore store = new InMemoryStore();

    store.set("name" , "Saransh" );
    store.expire("name" , 10L);

    store.delete("name");

    assertNull(store.get("name"));
}


@Test
void shouldNotSetTtlForNonexistentKey() {
    InMemoryStore store = new InMemoryStore();

    boolean result = store.expire("missing" , 20L);

    assertFalse(result);
}

@Test
void shouldSetTtlForExistingKey() {
    InMemoryStore store = new InMemoryStore();
    store.set("name", "Saransh");

    boolean result = store.expire("name", 20L);

    assertTrue(result);
}

@Test
void shouldRejectZeroTtl() {
    InMemoryStore store = new InMemoryStore();
    store.set("name", "Saransh");

    boolean result = store.expire("name", 0L);

    assertFalse(result);
    assertEquals("Saransh", store.get("name"));
}

@Test
void shouldRejectNegativeTtl() {
    InMemoryStore store = new InMemoryStore();
    store.set("name", "Saransh");

    boolean result = store.expire("name", -5L);

    assertFalse(result);
    assertEquals("Saransh", store.get("name"));
}

@Test
void shouldReturnTrueWhenKeyExists() {
    InMemoryStore store = new InMemoryStore();
    store.set("name", "Saransh");

    assertTrue(store.exists("name"));
}

@Test
void shouldReturnFalseWhenKeyDoesNotExist() {
    InMemoryStore store = new InMemoryStore();

    assertFalse(store.exists("missing"));
}

@Test
void shouldRemoveExpiredKeyWhenCheckingExists() throws InterruptedException {
    InMemoryStore store = new InMemoryStore();
    store.set("name", "Saransh");
    store.expire("name", 1L);

    Thread.sleep(1100);

    assertFalse(store.exists("name"));
    assertNull(store.get("name"));
}


}