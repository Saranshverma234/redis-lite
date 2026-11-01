package com.redislite;
 
public interface Command {
    /** args does not include the command name itself. Returns the response line. */
    String execute(InMemoryStore store, String[] args);
}