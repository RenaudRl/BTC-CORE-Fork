package dev.btc.core.integrity.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An IPv4 address (with or without a port) or a domain whose last label is a known TLD, written plainly
 * or with a disguised dot: {@code (.)}, {@code [.]}, {@code (dot)}, and, when it is said at least twice
 * in the message, {@code dot}, {@code point} or a bare dot between spaces. A single "x point com" in a
 * sentence is not read as an address: that wording is too common in French and English prose.
 *
 * <p>Both the raw and the normalized text are searched. A version number ({@code 1.21.4}, {@code 26.3})
 * is neither an address nor a domain. {@code allow-domains} lets a domain and its subdomains through.
 * An email address is not an advertisement and is not matched.
 */
public final class AdvertisingRule implements ChatRule {

    private static final String OCTET = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern IPV4 = Pattern.compile(
        "(?<![\\d.])(" + OCTET + "(?:\\." + OCTET + "){3})(?::\\d{1,5})?(?![\\d])");
    private static final Pattern DOMAIN = Pattern.compile(
        "(?<![\\p{L}\\p{N}@_.-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+)([a-z]{2,24})(?![\\p{L}\\p{N}_-])");

    /** {@code (.)} {@code [.]} {@code {.}} {@code (dot)} {@code [point]}, spaces around them allowed. */
    private static final Pattern SYMBOLIC_DOT = Pattern.compile(
        "\\s*[(\\[{<]\\s*(?:\\.|dot|point)\\s*[)\\]}>]\\s*");
    private static final Pattern SPELLED_DOT = Pattern.compile(
        "(?<=[\\p{L}\\p{N}])\\s+(?:dot|point|\\.)\\s+(?=[\\p{L}\\p{N}])");
    /** Full-width and ideographic full stops that survive in the raw text. */
    private static final Pattern EXOTIC_DOT = Pattern.compile("[\\u3002\\uFF0E\\uFF61]");

    /** The spelled forms only count from this many occurrences (see the class comment). */
    private static final int MIN_SPELLED_DOTS = 2;

    private final boolean ipv4;
    private final boolean domains;
    private final Set<String> allowed;
    private final Set<String> tlds;
    private final int minLabelLength;

    public AdvertisingRule(ChatGuardConfig.Advertising settings, Set<String> tlds) {
        this.ipv4 = settings.ipv4();
        this.domains = settings.domains();
        this.allowed = settings.allowDomains();
        this.tlds = Set.copyOf(tlds);
        this.minLabelLength = settings.minLabelLength();
    }

    @Override
    public ChatRuleId id() {
        return ChatRuleId.ADVERTISING;
    }

    @Override
    public Optional<ChatFinding> check(ChatText text, PlayerChatHistory history, long nowMillis) {
        for (String source : List.of(text.raw().toLowerCase(Locale.ROOT), text.normalized())) {
            for (String view : views(source)) {
                if (ipv4 && hasForeignAddress(view)) {
                    return Optional.of(ChatFinding.of(ChatRuleId.ADVERTISING, "ipv4"));
                }
                if (domains && hasForeignDomain(view)) {
                    return Optional.of(ChatFinding.of(ChatRuleId.ADVERTISING, "domain"));
                }
            }
        }
        return Optional.empty();
    }

    /** The text with symbolic dots read as dots, and the same with the spelled dots when there are enough. */
    private static List<String> views(String source) {
        String symbolic = SYMBOLIC_DOT.matcher(EXOTIC_DOT.matcher(source).replaceAll(".")).replaceAll(".");
        List<String> views = new ArrayList<>(2);
        views.add(symbolic);
        Matcher spelled = SPELLED_DOT.matcher(symbolic);
        int dots = 0;
        while (spelled.find()) {
            dots++;
        }
        if (dots >= MIN_SPELLED_DOTS) {
            views.add(SPELLED_DOT.matcher(symbolic).replaceAll("."));
        }
        return views;
    }

    private boolean hasForeignAddress(String view) {
        Matcher match = IPV4.matcher(view);
        while (match.find()) {
            if (!allowed.contains(match.group(1))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasForeignDomain(String view) {
        Matcher match = DOMAIN.matcher(view);
        while (match.find()) {
            String tld = match.group(2);
            if (!tlds.contains(tld)) {
                continue;
            }
            String[] labels = match.group(1).split("\\.");
            if (labels.length == 1 && labels[0].length() < minLabelLength) {
                continue;
            }
            if (!isAllowed(match.group(1) + tld)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAllowed(String domain) {
        for (String entry : allowed) {
            if (domain.equals(entry) || domain.endsWith("." + entry)) {
                return true;
            }
        }
        return false;
    }
}
