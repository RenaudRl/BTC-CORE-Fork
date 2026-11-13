package dev.btc.core.integrity.chat;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** The TLD file of the advertising rule: one lower-case label per line, {@code #} starts a comment. */
public final class TldList {

    private TldList() {}

    public static Set<String> parse(Iterable<String> lines) {
        Set<String> tlds = new HashSet<>();
        for (String line : lines) {
            String tld = line.strip().toLowerCase(Locale.ROOT);
            if (!tld.isEmpty() && !tld.startsWith("#")) {
                tlds.add(tld.startsWith(".") ? tld.substring(1) : tld);
            }
        }
        return Set.copyOf(tlds);
    }
}
