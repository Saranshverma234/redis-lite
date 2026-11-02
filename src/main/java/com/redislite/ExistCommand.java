package com.redislite;
 
class ExistsCommand implements Command {
 
    @Override
    public String execute(InMemoryStore store, String[] args) {
        if (args.length != 1) {
            return Response.error("usage: EXISTS key");
        }
        return Response.integer(store.exists(args[0]) ? 1 : 0);
    }
}