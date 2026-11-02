package com.redislite;
 
class GetCommand implements Command {
 
    @Override
    public String execute(InMemoryStore store, String[] args) {
        if (args.length != 1) {
            return Response.error("usage: GET key");
        }
        String value = store.get(args[0]);
        return value == null ? Response.nil() : Response.bulk(value);
    }
}