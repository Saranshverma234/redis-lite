package com.redislite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
void ClearOldTtlWhenKeyIsDeleted() {
    InMemoryStore store = new InMemoryStore();

    store.set("name" , "Saransh" );
    store.expire("name" , 10L);

    store.delete("name");

    assertNull(store.get("name"));
}

}