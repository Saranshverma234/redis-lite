package com.redislite;
 
class TtlCommand implements Command {
 
    @Override
    public String execute(InMemoryStore store, String[] args) {
        if (args.length != 1) {
            return Response.error("usage: TTL key");
        }
        return Response.integer(store.ttl(args[0]));   // -2 missing, -1 no expiry, else seconds
    }
}