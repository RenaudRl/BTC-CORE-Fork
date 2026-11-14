package dev.btc.core.integrity.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An IPv4 address (except a portless Minecraft version such as {@code 1.21.4.2}) or a domain whose last label is a known TLD, written plainly
 * or with a disguised dot: {@code (.)}, {@code [.]}, {@code (dot)}, a look-alike dot or letter (small capitals,
 * Cyrillic, middle dots before an address), and, when it is said at least twice in the message, {@code dot},
 * {@code point} or a bare dot between spaces. A single "x point com" in a sentence is not read as an address:
 * that wording is too common in French and English prose. A dot split from its label by a space counts when a
 * path or port follows, or when a strong TLD ({@code strong-tlds}) ends the message.
 *
 * <p>Both the raw and the normalized text are searched. A version number ({@code 1.21.4}, {@code 26.3})
 * is neither an address nor a domain. {@code allow-domains} lets a domain and its subdomains through.
 * An email address is not an advertisement and is not matched.
 */
public final class AdvertisingRule implements ChatRule {

    private static final String OCTET = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern IPV4 = Pattern.compile(
        "(?<![\\d.])(" + OCTET + "(?:\\." + OCTET + "){3})(:\\d{1,5})?(?![\\d])");
    /**
     * Letters and digits that count as part of a word. The Hangul fillers are letters (Lo) that draw nothing:
     * a filler glued to a domain must not stop it from being a domain.
     */
    private static final String WORD_CHAR = "[\\p{L}\\p{N}&&[^\\u115F\\u1160\\u3164\\uFFA0]]";
    private static final Pattern DOMAIN = Pattern.compile(
        "(?<![" + WORD_CHAR + "@_.-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+)([a-z]{2,24})(?![" + WORD_CHAR + "_-])");

    /**
     * {@code e x a m p l e}: a run of three or more single letters or digits, read as one word. A lone dot may sit
     * between two of them ({@code d i s c o r d . g g}): it stays, as the dot of the joined word.
     */
    private static final Pattern SPACED_RUN = Pattern.compile(
        "(?<![\\p{L}\\p{N}])[\\p{L}\\p{N}](?:\\s(?:\\.\\s)?[\\p{L}\\p{N}]){2,}(?![\\p{L}\\p{N}])");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /**
     * The blank braille pattern and the private-use glyphs, read as spaces by this rule only: the normalized text
     * keeps the pack glyphs for the other rules, but between single letters they spell a word out.
     */
    private static final Pattern SPACE_LIKE = Pattern.compile("[\\x{2800}\\p{Co}]");
    /**
     * A dot split from its label by spaces, either {@code example .com} (a space before a dot that leads into a
     * word) or {@code example. com} (a space after it, when the TLD ends the message or leads into a path or a
     * port). Glued back only where an address is plausible (see {@link #glueSplitDots}).
     */
    private static final Pattern SPLIT_DOT = Pattern.compile(
        "(?<=[\\p{L}\\p{N}])(?:\\s+\\.(?=[\\p{L}\\p{N}])|\\s*\\.\\s+(?=[a-z]{2,24}(?:[/:]|\\s*$)))");
    /**
     * {@code https://user:pass@host}: what a browser reads as user info. Greedy up to the LAST {@code @} before
     * {@code / ? # \}, as a browser does: the host is what follows it ({@code https://a@b@evil.com}). The scheme
     * starts where no scheme character precedes it: a long run of them is tried once, not from each of its characters.
     */
    private static final Pattern USERINFO = Pattern.compile("(?<![a-z0-9+.-])[a-z][a-z0-9+.-]*://[^\\s/?#\\\\]*@");
    /** What may end a sentence right after a path: it is the sentence's, not the path's (ellipsis and quotes too). */
    private static final Pattern SENTENCE_END = Pattern.compile(
        "[.,!;:)\\x{2026}\"'\\x{00BB}\\x{201D}\\x{2019}]+(?:\\s.*)?", Pattern.DOTALL);

    /**
     * {@code (.)} {@code [.]} {@code {.}} {@code (dot)} {@code [point]}, spaces around them allowed. Starts where no
     * space precedes: a long run of spaces is tried once.
     */
    private static final Pattern SYMBOLIC_DOT = Pattern.compile(
        "(?:(?<!\\s)\\s+)?[(\\[{<]\\s*(?:\\.|dot|point)\\s*[)\\]}>]\\s*");
    private static final Pattern SPELLED_DOT = Pattern.compile(
        "(?<=[\\p{L}\\p{N}])\\s+(?:dot|point|\\.)\\s+(?=[\\p{L}\\p{N}])");
    /** Full-width, ideographic, hyphenation-point and bullet-operator full stops that survive in the raw text. */
    private static final Pattern EXOTIC_DOT = Pattern.compile("[\\x{3002}\\x{FF0E}\\x{FF61}\\x{2027}\\x{2219}]");
    /**
     * Middle dots: French inclusive writing uses U+00B7 ({@code étudiant·es}), so they count as a dot only before a
     * label that leads into a path or a port, or before a strong TLD (see {@link #middleDots}).
     */
    private static final Pattern MIDDLE_DOT = Pattern.compile("[\\x{00B7}\\x{30FB}]");

    /**
     * TLDs that are also common words ("Hello.It's fine", "Salut.Ca va"): a bare {@code label.tld} with one of
     * them is read as prose only when it is typed with a capital ({@code .It}, see {@link #startsASentence})
     * and no path or port follows; typed in lower case ({@code monserveur.fr}) it is an address.
     */
    private static final Set<String> PROSE_TLDS = Set.of("it", "es", "de", "se", "ca", "by", "co");

    /** The spelled forms only count from this many occurrences (see the class comment). */
    private static final int MIN_SPELLED_DOTS = 2;

    private final boolean ipv4;
    private final boolean domains;
    private final Set<String> allowed;
    private final Set<String> tlds;
    private final int minLabelLength;
    private final Set<String> strongTlds;

    public AdvertisingRule(ChatGuardConfig.Advertising settings, Set<String> tlds) {
        this.ipv4 = settings.ipv4();
        this.domains = settings.domains();
        this.allowed = settings.allowDomains();
        this.tlds = Set.copyOf(tlds);
        this.minLabelLength = settings.minLabelLength();
        this.strongTlds = Set.copyOf(settings.strongTlds());
    }

    @Override
    public ChatRuleId id() {
        return ChatRuleId.ADVERTISING;
    }

    @Override
    public Optional<ChatFinding> check(ChatText text, PlayerChatHistory history, long nowMillis) {
        String lower = text.raw().toLowerCase(Locale.ROOT);
        // A filler is read both as nothing ("exampl<filler>e.com") and as a break ("example.com<filler>hello").
        Set<String> sources = new LinkedHashSet<>(List.of(
            ChatNormalizer.stripFillers(lower, ""), ChatNormalizer.stripFillers(lower, " "), text.normalized()));
        for (String source : sources) {
            for (String view : views(source)) {
                if (ipv4 && hasForeignAddress(view)) {
                    return Optional.of(ChatFinding.of(ChatRuleId.ADVERTISING, "ipv4"));
                }
                if (domains && hasForeignDomain(view, text.raw())) {
                    return Optional.of(ChatFinding.of(ChatRuleId.ADVERTISING, "domain"));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The views of the text with the space-like glyphs read as spaces, then as nothing: like a Hangul filler, a
     * private-use glyph glued inside a word ({@code monserveur.f<glyph>r}) must not split it.
     */
    private List<String> views(String source) {
        String spaced = SPACE_LIKE.matcher(source).replaceAll(" ");
        List<String> views = dottedViews(spaced);
        if (!spaced.equals(source)) {
            views.addAll(dottedViews(SPACE_LIKE.matcher(source).replaceAll("")));
        }
        return views;
    }

    /** The text with symbolic dots read as dots, and the same with the spelled dots when there are enough. */
    private List<String> dottedViews(String source) {
        String folded = ChatNormalizer.foldLookalikes(source);
        String symbolic = SYMBOLIC_DOT.matcher(middleDots(EXOTIC_DOT.matcher(folded).replaceAll("."))).replaceAll(".");
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
            String tight = glueSplitDots(view);
            if (!tight.equals(view)) {
                views.add(tight);
            }
        }
        return views;
    }

    /**
     * A middle dot read as a dot when what follows is a label leading into a path or a port, or a strong TLD that
     * ends the word: {@code discord·gg/x} and {@code monserveur·fr}, not {@code étudiant·es} nor {@code chanteur·se·s}.
     */
    private String middleDots(String text) {
        return MIDDLE_DOT.matcher(text).replaceAll(match -> {
            int labelEnd = match.end();
            while (labelEnd < text.length() && Character.isLetterOrDigit(text.charAt(labelEnd))) {
                labelEnd++;
            }
            boolean addressTail = startsAddressTail(text, labelEnd);
            boolean strongEnd = (labelEnd == text.length() || !isLabelChar(text.charAt(labelEnd)))
                && strongTlds.contains(text.substring(match.end(), labelEnd));
            return addressTail || strongEnd ? "." : Matcher.quoteReplacement(match.group());
        });
    }

    private static boolean isLabelChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '.' || c == 0x00B7 || c == 0x30FB;
    }

    /**
     * Joins the spaced-out runs, then replaces each split dot by a plain dot where an address is plausible: next to a
     * joined run ({@code e x a m p l e .com}: spelled letter by letter on purpose), or see {@link #isAddressPlausible}.
     * Only the dot next to a run is glued that way: "il y a 3 joueurs. top" keeps its prose.
     */
    private String glueSplitDots(String view) {
        Matcher run = SPACED_RUN.matcher(view);
        StringBuilder joined = new StringBuilder(view.length());
        List<int[]> runs = new ArrayList<>();
        int last = 0;
        while (run.find()) {
            joined.append(view, last, run.start());
            int start = joined.length();
            joined.append(SPACES.matcher(run.group()).replaceAll(""));
            runs.add(new int[] {start, joined.length()});
            last = run.end();
        }
        joined.append(view, last, view.length());
        String text = joined.toString();
        return SPLIT_DOT.matcher(text).replaceAll(match ->
            nextToRun(runs, match.start(), match.end()) || isAddressPlausible(text, match.start(), match.end())
                ? "." : Matcher.quoteReplacement(match.group()));
    }

    private static boolean nextToRun(List<int[]> runs, int start, int end) {
        for (int[] run : runs) {
            if (run[1] == start || run[0] == end) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a split dot reads as an address: the TLD is followed by {@code /} or {@code :}, the word before already
     * has a dot ({@code play.example. com}), or a strong TLD ends the message ({@code monserveur. com}). A lone
     * "Super. Top", "Cool. Game" or "i use .net daily" is prose.
     */
    private boolean isAddressPlausible(String text, int dotStart, int dotEnd) {
        int tldEnd = dotEnd;
        while (tldEnd < text.length() && Character.isLetterOrDigit(text.charAt(tldEnd))) {
            tldEnd++;
        }
        if (startsAddressTail(text, tldEnd)) {
            return true;
        }
        if (strongTlds.contains(text.substring(dotEnd, tldEnd)) && onlySpacesFrom(text, tldEnd)) {
            return true;
        }
        int labelStart = dotStart;
        while (labelStart > 0 && (Character.isLetterOrDigit(text.charAt(labelStart - 1))
            || text.charAt(labelStart - 1) == '-' || text.charAt(labelStart - 1) == '.')) {
            labelStart--;
        }
        return text.substring(labelStart, dotStart).indexOf('.') >= 0;
    }

    private static boolean onlySpacesFrom(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean hasForeignAddress(String view) {
        Matcher match = IPV4.matcher(view);
        while (match.find()) {
            if (!allowed.contains(match.group(1)) && (match.group(2) != null || !looksLikeGameVersion(match.group(1)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A Minecraft version with four numbers ({@code 1.21.4.2}, {@code 26.3.1.0}): it starts with 1 or 26 and
     * every number is below 100. Only judged for an address with no port.
     * shortcut: a real address 1.x.x.x or 26.x.x.x written without a port passes; review if abuse is seen.
     */
    private static boolean looksLikeGameVersion(String address) {
        String[] octets = address.split("\\.");
        if (!octets[0].equals("1") && !octets[0].equals("26")) {
            return false;
        }
        for (String octet : octets) {
            if (octet.length() == 3) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether every {@code .tld} of the typed message is written like the start of a sentence glued to the
     * previous one ({@code Hello.It's}, {@code Salut.Ca va}): a capital then lower case. A lower-case or
     * all-capitals {@code .De} is an address, and so is a strong TLD whose capital is the only one, the word
     * before it having none. {@code fr}, {@code pt}, {@code pl} and {@code ch} are not prose TLDs:
     * {@code Monserveur.Fr} is an address.
     * shortcut: {@code Monserveur.De} passes (a sentence start on both sides); acceptable in observe, revisit before enforce.
     */
    private boolean startsASentence(String raw, String tld) {
        Matcher word = AFTER_DOT.matcher(raw);
        boolean seen = false;
        while (word.find()) {
            String written = word.group(2);
            if (!written.equalsIgnoreCase(tld)) {
                continue;
            }
            if (!Character.isUpperCase(written.charAt(0)) || (written.length() > 1 && Character.isUpperCase(written.charAt(1)))) {
                return false;
            }
            if (strongTlds.contains(tld) && word.group(1).chars().noneMatch(Character::isUpperCase)) {
                return false;
            }
            seen = true;
        }
        return seen;
    }

    private static final Pattern AFTER_DOT = Pattern.compile("(\\p{L}*)\\.(\\p{L}+)");

    private boolean hasForeignDomain(String view, String raw) {
        Matcher match = DOMAIN.matcher(view);
        while (match.find()) {
            if (isForeign(match, view, raw)) {
                return true;
            }
        }
        // The DOMAIN pattern skips a host right after an @ (an email's domain); after the user info of a URL it is the host.
        Matcher userInfo = USERINFO.matcher(view);
        while (userInfo.find()) {
            Matcher host = DOMAIN.matcher(view).region(userInfo.end(), view.length());
            if (host.lookingAt() && isForeign(host, view, raw)) {
                return true;
            }
        }
        return false;
    }

    private boolean isForeign(Matcher match, String view, String raw) {
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
            int domainEnd = match.start(1) + domain.length();
            if (tldIndex == 1 && PROSE_TLDS.contains(tld) && !startsAddressTail(view, domainEnd)
                && startsASentence(raw, tld)) {
                continue;
            }
            if (!isAllowed(domain, view.substring(domainEnd))) {
                return true;
            }
        }
        return false;
    }

    /** Whether what follows the TLD is a path or a port: that is what makes a bare {@code word.fr} an address. */
    private static boolean startsAddressTail(String view, int tldEnd) {
        return tldEnd < view.length() && (view.charAt(tldEnd) == '/' || view.charAt(tldEnd) == ':');
    }

    /**
     * An entry {@code host} lets the domain and its subdomains through; {@code host/path} only what continues
     * with that path ({@code discord.gg/btc} allows {@code discord.gg/btc/rules}, not {@code discord.gg/btcfake}).
     *
     * @param tail the text right after the domain's TLD
     */
    private boolean isAllowed(String domain, String tail) {
        if (tail.startsWith("@")) {
            return false; // "youtube.com@evil.com": a domain before an @ is user info, not the site that is visited
        }
        for (String entry : allowed) {
            int slash = entry.indexOf('/');
            String host = slash < 0 ? entry : entry.substring(0, slash);
            if (!domain.equals(host) && !domain.endsWith("." + host)) {
                continue;
            }
            if (slash < 0) {
                return true;
            }
            if (pathStartsWith(tail, entry.substring(slash))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the path written at the start of {@code tail} begins, segment by segment, with {@code allowed}.
     * A path with {@code ..}, an escape ({@code %}) or a backslash is never matched: a browser may resolve it to
     * another page than the one that was written.
     */
    private static boolean pathStartsWith(String tail, String allowed) {
        int end = 0;
        while (end < tail.length() && PATH_CHARS.indexOf(tail.charAt(end)) >= 0) {
            end++;
        }
        // Glued to the path, any other character may be a path character to a browser ("btc!/../other"): only the
        // end of the sentence, a space, a query or a fragment ends the path.
        if (end < tail.length() && !endsPath(tail.substring(end))) {
            return false;
        }
        String written = tail.substring(0, end);
        // Closing full stops or an ellipsis end the sentence, not the path: a last segment that is more than dots
        // loses them ("btc..."); one made only of dots ("btc/..") keeps them, to be refused below.
        String trimmed = TRAILING_DOTS.matcher(written).replaceFirst("");
        if (trimmed.length() > written.lastIndexOf('/') + 1) {
            written = trimmed;
        }
        if (written.contains("..") || written.contains("%") || written.contains("\\")) {
            return false;
        }
        String[] segments = written.split("/", -1);
        for (String segment : segments) {
            if (segment.equals(".")) {
                return false;
            }
        }
        String[] wanted = allowed.split("/");
        if (segments.length < wanted.length) {
            return false;
        }
        for (int i = 0; i < wanted.length; i++) {
            if (!segments[i].equals(wanted[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean endsPath(String rest) {
        char first = rest.charAt(0);
        return Character.isWhitespace(first) || first == '?' || first == '#' || SENTENCE_END.matcher(rest).matches();
    }

    private static final Pattern TRAILING_DOTS = Pattern.compile("\\.+$");

    /** What a path may be made of when read from a message; {@code %} and {@code \} are kept to be refused. */
    private static final String PATH_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789._~%\\/+-";
}
