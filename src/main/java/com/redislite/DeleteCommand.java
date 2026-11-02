package com.redislite;
 
class DelCommand implements Command {
 
    @Override
    public String execute(InMemoryStore store, String[] args) {
        if (args.length != 1) {
            return Response.error("usage: DEL key");
        }
        return Response.integer(store.delete(args[0]) ? 1 : 0);
    }
}