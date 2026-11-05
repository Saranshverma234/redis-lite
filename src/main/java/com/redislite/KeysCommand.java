package com.redislite;
 
class KeysCommand implements Command {
 
    @Override
    public String execute(InMemoryStore store, String[] args) {
        if (args.length != 1) {
            return Response.error("usage: KEYS pattern");
        }
        return Response.list(store.keys(args[0]));
    }
}