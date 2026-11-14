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
    /**
     * Letters and digits that count as part of a word. The Hangul fillers are letters (Lo) that draw nothing:
     * a filler glued to a domain must not stop it from being a domain.
     */
    private static final String WORD_CHAR = "[\\p{L}\\p{N}&&[^\\u115F\\u1160\\u3164\\uFFA0]]";
    private static final Pattern DOMAIN = Pattern.compile(
        "(?<![" + WORD_CHAR + "@_.-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+)([a-z]{2,24})(?![" + WORD_CHAR + "_-])");

    /** {@code e x a m p l e}: a run of three or more single letters or digits, read as one word. */
    private static final Pattern SPACED_RUN = Pattern.compile(
        "(?<![\\p{L}\\p{N}])[\\p{L}\\p{N}](?:\\s[\\p{L}\\p{N}]){2,}(?![\\p{L}\\p{N}])");
    /** {@code example .com}: a space before a dot that leads into a word. */
    private static final Pattern SPACE_BEFORE_DOT = Pattern.compile("(?<=[\\p{L}\\p{N}])\\s+\\.(?=[\\p{L}\\p{N}])");
    /**
     * {@code example. com}: a space after the dot, only when a TLD of three letters or more ends the message or
     * leads into a path. "the end. Net result" and a closing "Sure. No" stay prose.
     */
    private static final Pattern SPACE_AFTER_DOT = Pattern.compile(
        "(?<=[\\p{L}\\p{N}])\\s*\\.\\s+(?=[a-z]{3,24}(?:/|\\s*$))");

    /** {@code (.)} {@code [.]} {@code {.}} {@code (dot)} {@code [point]}, spaces around them allowed. */
    private static final Pattern SYMBOLIC_DOT = Pattern.compile(
        "\\s*[(\\[{<]\\s*(?:\\.|dot|point)\\s*[)\\]}>]\\s*");
    private static final Pattern SPELLED_DOT = Pattern.compile(
        "(?<=[\\p{L}\\p{N}])\\s+(?:dot|point|\\.)\\s+(?=[\\p{L}\\p{N}])");
    /** Full-width and ideographic full stops that survive in the raw text. */
    private static final Pattern EXOTIC_DOT = Pattern.compile("[\\u3002\\uFF0E\\uFF61]");

    /**
     * TLDs that are also common words ("Hello.It's fine", "Salut.Ca va"): a bare {@code label.tld} with one of
     * them is read as prose, unless a path or a port follows or the name has two labels or more.
     */
    private static final Set<String> PROSE_TLDS = Set.of("it", "es", "de", "se", "ca", "by", "pt", "pl", "fr", "co", "ch");

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
        String symbolic = SYMBOLIC_DOT.matcher(
            EXOTIC_DOT.matcher(ChatNormalizer.foldLookalikes(source)).replaceAll(".")).replaceAll(".");
        List<String> dotted = new ArrayList<>(2);
        dotted.add(symbolic);
        Matcher spelled = SPELLED_DOT.matcher(symbolic);
        int dots = 0;
        while (spelled.find()) {
            dots++;
        }
        if (dots >= MIN_SPELLED_DOTS) {
            dotted.add(SPELLED_DOT.matcher(symbolic).replaceAll("."));
        }
        List<String> views = new ArrayList<>(dotted.size() * 2);
        for (String view : dotted) {
            views.add(view);
            String tight = SPACE_AFTER_DOT.matcher(SPACE_BEFORE_DOT.matcher(
                SPACED_RUN.matcher(view).replaceAll(run -> run.group().replaceAll("\\s", ""))).replaceAll(".")).replaceAll(".");
            if (!tight.equals(view)) {
                views.add(tight);
            }
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
            String[] names = (match.group(1) + match.group(2)).split("\\.");
            // Any name after the first may be the TLD: "example.com.thanks" holds the domain "example.com".
            for (int tldIndex = 1; tldIndex < names.length; tldIndex++) {
                String tld = names[tldIndex];
                if (!tlds.contains(tld)) {
                    continue;
                }
                if (tldIndex == 1 && names[0].length() < minLabelLength) {
                    continue;
                }
                String domain = String.join(".", java.util.Arrays.copyOf(names, tldIndex + 1));
                if (tldIndex == 1 && PROSE_TLDS.contains(tld)
                    && !startsAddressTail(view, match.start(1) + domain.length())) {
                    continue;
                }
                if (!isAllowed(domain)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether what follows the TLD is a path or a port: that is what makes a bare {@code word.fr} an address. */
    private static boolean startsAddressTail(String view, int tldEnd) {
        return tldEnd < view.length() && (view.charAt(tldEnd) == '/' || view.charAt(tldEnd) == ':');
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
