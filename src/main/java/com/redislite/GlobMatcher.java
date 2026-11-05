package com.redislite;
 
/**
 * Minimal glob matching: '*' matches any run of characters (including none),
 * '?' matches exactly one character. Everything else matches literally.
 *
 * Written by hand on purpose: converting a client-supplied pattern to a regex would let
 * characters like '.' or '[' change its meaning, and a nasty regex can burn CPU.
 */
final class GlobMatcher {
    private GlobMatcher() {}
 
    static boolean matches(String pattern, String text) {
        int p = 0;          // position in pattern
        int t = 0;          // position in text
        int star = -1;      // position of the most recent '*' in pattern
        int mark = 0;       // text position where that '*' started matching
 
        while (t < text.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;                 // remember the star, first try matching zero characters
                mark = t;
            } else if (p < pattern.length()
                    && (pattern.charAt(p) == '?' || pattern.charAt(p) == text.charAt(t))) {
                p++;
                t++;
            } else if (star != -1) {
                p = star + 1;               // backtrack: let the star swallow one more character
                t = ++mark;
            } else {
                return false;
            }
        }
 
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;                            // trailing stars can match nothing
        }
        return p == pattern.length();
    }
}