package com.redislite;
import java.util.List;
 
/** All reply formatting lives here, so changing the wire format later is a one-file change. */
final class Response {
    private Response() {}
 
    static String ok()              { return "+OK"; }
    static String error(String msg) { return "-ERR " + msg; }
    static String integer(long n)   { return ":" + n; }
    static String nil()             { return "$-1"; }
    static String bulk(String s)    { return s; }

    static String list(List<String> items) {
    return items.isEmpty() ? "(empty)" : String.join(" ", items);
}
}

