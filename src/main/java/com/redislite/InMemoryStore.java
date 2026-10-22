package com.redislite;
import java.util.HashMap;
import java.util.Map;

public class InMemoryStore {
    private final Map<String ,String> data = new HashMap<>();
    private final Map<String , Long> expTime = new HashMap<>();

    //SET command 
    public void set(String key, String value){
        data.put(key,value);
        expTime.remove(key);
    }

    //GET command
    public String get(String key) {

        Long expirationTime = expTime.get(key);

        if (expirationTime!=null &&  System.currentTimeMillis()>=expirationTime){

            data.remove(key);
            expTime.remove(key);
            return null;

        }
        
        return data.get(key);
    }

    //DELETE command
    public void delete(String key){
        data.remove(key);
        expTime.remove(key);
    }

    //EXPIRATION command
    public boolean expire(String key , Long ttl){

        if(!data.containsKey(key)){
            return false;
        }

        if(ttl<=0){
            return false;
        }

        Long expirationTime = System.currentTimeMillis() + (ttl * 1000L);
        expTime.put(key , expirationTime);
        return true;
    }

    //EXISTS command

    public boolean exists(String key){
        return get(key)!=null;
    }

    //TTL command 

    public long ttl(String key){

        if(get(key)==null){
            return -2;
        }

        Long expirationTime = expTime.get(key);

        if(expirationTime==null){
            return -1;
        }

        return (expirationTime-System.currentTimeMillis())/1000L;
    }

    //PERSIST command 

    public boolean persist(String key){

        if(get(key)==null){
            return false;
        }

        return expTime.remove(key)!=null;
    }

    //INCREMENT command

    public long incr (String key){

        String value = get(key);

        if(value==null){
            set(key, "1");
            return 1L;
        }

        long currentValue;

        try{
            currentValue = Long.parseLong(value);
        } catch(NumberFormatException e){
            throw new IllegalArgumentException ("Value entered is not a valid integer: "+value);
        }

        long incrementedValue = Math.incrementExact(currentValue);
        set(key , String.valueOf(incrementedValue));

        return incrementedValue;
    
    }

    
}
