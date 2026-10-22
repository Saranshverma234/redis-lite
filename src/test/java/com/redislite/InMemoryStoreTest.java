package com.redislite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

@Test
void shouldReturnMinusTwoWhenKeyDoesNotExist() {
    InMemoryStore store = new InMemoryStore();

    assertEquals(-2, store.ttl("missing"));
}

@Test
void shouldReturnMinusOneWhenKeyHasNoTtl() {
    InMemoryStore store = new InMemoryStore();
    store.set("name", "Saransh");

    assertEquals(-1, store.ttl("name"));
}

@Test
void shouldReturnPositiveTtlForExpiringKey() {
    InMemoryStore store = new InMemoryStore();
    store.set("name", "Saransh");

    boolean result = store.expire("name", 60L);

    assertTrue(result);
    assertTrue(store.ttl("name") > 0);
    assertTrue(store.ttl("name") <= 60);
}

@Test
void shouldRemoveTtlWithoutDeletingKey(){
    InMemoryStore store = new InMemoryStore();

    store.set("name" , "saransh");
    store.expire("name" , 20L);

    assertTrue(store.persist("name"));
    assertEquals("saransh" , store.get("name"));
    assertEquals(-1 , store.ttl("name"));

}

@Test
 void shouldReturnFalseWhenKeyHasNoTtl(){
    InMemoryStore store = new InMemoryStore();
    store.set("name","saransh");

    assertFalse(store.persist("name"));
    assertEquals("saransh" , store.get("name"));

 }

@Test
 void shouldReturnFalseWhenKeyDoesNotExistWhilePersisting() {
    InMemoryStore store = new InMemoryStore();

    assertFalse(store.persist("missing"));
}

@Test
void shouldReturnFalseWhenKeyHasExpired() throws InterruptedException {
    InMemoryStore store = new InMemoryStore();
    store.set("name", "Saransh");
    store.expire("name", 1L);

    Thread.sleep(1100);

    assertFalse(store.persist("name"));
    assertFalse(store.exists("name"));
    assertEquals(-2, store.ttl("name"));
}


@Test 
void shouldIncrementExistingValueOfKey(){

    InMemoryStore store = new InMemoryStore();
    store.set("counter" , "5");

    assertEquals(6L , store.incr("counter"));
    assertEquals("6" , store.get("counter")); 
}

@Test
void shouldInitializeMissingKeyToOne() {
    InMemoryStore store = new InMemoryStore();

    assertEquals(1L, store.incr("visits"));
    assertEquals("1", store.get("visits"));
}

@Test
void shouldIncrementNegativeValue() {
    InMemoryStore store = new InMemoryStore();
    store.set("temperature", "-5");

    assertEquals(-4L, store.incr("temperature"));
    assertEquals("-4", store.get("temperature"));
}

@Test
void shouldRejectNonNumericValue() {
    InMemoryStore store = new InMemoryStore();
    store.set("visits", "hello");

    assertThrows(
        IllegalArgumentException.class,
        () -> store.incr("visits")
    );

    assertEquals("hello", store.get("visits"));
}

@Test
void shouldRejectLongOverflow() {
    InMemoryStore store = new InMemoryStore();
    store.set("counter", "9223372036854775807");

    assertThrows(
        ArithmeticException.class,
        () -> store.incr("counter")
    );

    assertEquals("9223372036854775807", store.get("counter"));
}

@Test 
void shouldHandleConcurrentIncrements() throws InterruptedException{
    InMemoryStore store = new InMemoryStore();
    store.set("counter" , "0");

    int numberOfThreads = 10;
    int incrementsPerThread =100;

    ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);

    for(int i=0 ; i<numberOfThreads ; i++){
        executor.submit(() ->{
            for(int j=0 ; j<incrementsPerThread ; j++){
                store.incr("counter");
            }
        });
    }

    executor.shutdown();
    assertTrue(executor.awaitTermination(10 , TimeUnit.SECONDS));
    assertEquals("1000" , store.get("counter"));

}

}