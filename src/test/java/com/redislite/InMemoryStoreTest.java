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
}