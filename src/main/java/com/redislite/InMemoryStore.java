package com.redislite;
import java.util.HashMap;
import java.util.Map;

public class InMemoryStore {
    private final Map<String ,String> data = new HashMap<>();
    private final Map<String , Long> expTime = new HashMap<>();

    //SET command 
    public void set(String key, String value){
        data.put(key,value);
    }

    //GET command
    public String get(String key) {
        return data.get(key);
    }

    //DELETE command
    public void delete(String key){
        data.remove(key);
    }

    //EXPIRATION command
    public void expire(String key , Long ttl){
        long expirationTime = System.currentTimeMillis() + (ttl * 1000L);
        expTime.put(key , expirationTime);
    }

    
}
