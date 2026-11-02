package com.redislite;
 
class ExpireCommand implements Command {
 
    @Override
    public String execute(InMemoryStore store, String[] args) {
        if (args.length != 2) {
            return Response.error("usage: EXPIRE key seconds");
        }
 
        long seconds;
        try {
            seconds = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            return Response.error("seconds must be an integer");
        }
        if (seconds <= 0) {
            return Response.error("seconds must be positive");
        }
 
        // An overflow throws ArithmeticException, which CommandProcessor turns into an error reply.
        return Response.integer(store.expire(args[0], seconds) ? 1 : 0);
    }
}