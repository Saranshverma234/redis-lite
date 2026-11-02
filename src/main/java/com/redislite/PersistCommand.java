package com.redislite;
 
class PersistCommand implements Command {
 
    @Override
    public String execute(InMemoryStore store, String[] args) {
        if (args.length != 1) {
            return Response.error("usage: PERSIST key");
        }
        return Response.integer(store.persist(args[0]) ? 1 : 0);
    }
}