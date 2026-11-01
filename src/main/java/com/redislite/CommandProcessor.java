package com.redislite;
 
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
 
/**
 * Turns one text line into a store operation and a reply line. No networking here.
 * Safe to share between client threads: the command table is immutable after construction
 * and the command objects hold no state.
 */
public class CommandProcessor {
 
    private final InMemoryStore store;
    private final Map<String, Command> commands;
 
    public CommandProcessor(InMemoryStore store) {
        this.store = store;
 
        Map<String, Command> m = new HashMap<>();
        m.put("GET", new GetCommand());
        m.put("SET", new SetCommand());
        m.put("DEL", new DelCommand());
        m.put("EXISTS", new ExistsCommand());
        m.put("EXPIRE", new ExpireCommand());
        m.put("TTL", new TtlCommand());
        m.put("PERSIST", new PersistCommand());
        m.put("INCR", new IncrCommand());
        this.commands = Map.copyOf(m); // immutable
    }
 
    public String handle(String line) {
        if (line == null || line.isBlank()) {
            return Response.error("empty command");
        }
 
        String[] parts = line.trim().split("\\s+");
        Command command = commands.get(parts[0].toUpperCase(Locale.ROOT));
        if (command == null) {
            return Response.error("unknown command");
        }
 
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
 
        try {
            return command.execute(store, args);
        } catch (NumberFormatException e) {          // must come before IllegalArgumentException
            return Response.error("value is not an integer");
        } catch (ArithmeticException e) {
            return Response.error("number out of range");
        } catch (IllegalArgumentException e) {
            return Response.error(e.getMessage());
        } catch (RuntimeException e) {
            return Response.error("internal error");  // never leak a stack trace to a client
        }
    }
}