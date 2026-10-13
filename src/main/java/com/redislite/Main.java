package com.redislite;

public class Main {
    public static void main(String[] args) {

        InMemoryStore s1 = new InMemoryStore();

    s1.set("name" , "saransh");

    System.out.println(s1.get("name"));

    String test = s1.get("name");

    s1.delete("name");

    System.out.println(s1.get("name"));

    System.out.println(test);


    } 
    
}