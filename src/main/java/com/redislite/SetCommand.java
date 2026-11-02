package com.redislite;
 
class SetCommand implements Command {
 
    private static final String USAGE = "usage: SET key value [EX seconds]";
 
    @Override
    public String execute(InMemoryStore store, String[] args) {
        // SET key value
        if (args.length == 2) {
            store.set(args[0], args[1]);
            return Response.ok();
        }
 
        // SET key value EX seconds
        if (args.length == 4 && args[2].equalsIgnoreCase("EX")) {
            long seconds;
            try {
                seconds = Long.parseLong(args[3]);
            } catch (NumberFormatException e) {
                return Response.error("EX seconds must be an integer");
            }
            if (seconds <= 0) {
                return Response.error("EX seconds must be positive");
            }
            // One atomic write of value + expiry. If the number is so large that it
            // overflows, this throws before anything is stored (handled in CommandProcessor).
            store.set(args[0], args[1], seconds);
            return Response.ok();
        }
 
        return Response.error(USAGE);
    }
}