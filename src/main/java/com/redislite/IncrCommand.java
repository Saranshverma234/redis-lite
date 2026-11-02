package com.redislite;
 
class IncrCommand implements Command {
 
    @Override
    public String execute(InMemoryStore store, String[] args) {
        if (args.length != 1) {
            return Response.error("usage: INCR key");
        }
        // A non-numeric stored value throws IllegalArgumentException and an overflow throws
        // ArithmeticException; CommandProcessor turns both into error replies, and the
        // stored value is left unchanged because compute() discards the result on exceptions.
        return Response.integer(store.incr(args[0]));
    }
}