package dev.btc.core.integrity.chat;

import dev.btc.core.text.PlayerText;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The validated settings of {@code chat-guard.yml}. Immutable: a reload builds a new one.
 *
 * <p>Every field has a default, so an absent key is never an error, but a value outside its bounds is:
 * {@link #parse} refuses the whole file and names the key, so that a typo cannot silently turn a rule
 * into something stricter than the admin meant. The shipped defaults observe, never enforce, and leave
 * the word lists off.
 */
public record ChatGuardConfig(
    Mode mode,
    String bypassPermission,
    Set<String> whisperCommands,
    Set<String> replyCommands,
    StaffAlert staffAlert,
    Journal journal,
    Rate rate,
    Repetition repetition,
    Similarity similarity,
    Caps caps,
    Unicode unicode,
    Advertising advertising,
    Words words,
    Map<String, String> messages) {

    /** What the guard does with a finding. */
    public enum Mode { OFF, OBSERVE, ENFORCE }

    /** A rule's own mode: {@link #INHERIT} follows the global one. */
    public enum RuleMode {
        INHERIT, OFF, OBSERVE, ENFORCE;

        /** The mode the rule runs in under {@code global}; a module that is off switches every rule off. */
        public Mode resolve(Mode global) {
            if (global == Mode.OFF) {
                return Mode.OFF;
            }
            return switch (this) {
                case INHERIT -> global;
                case OFF -> Mode.OFF;
                case OBSERVE -> Mode.OBSERVE;
                case ENFORCE -> Mode.ENFORCE;
            };
        }
    }

    /** Alert staff when one player trips the filter {@code refusals} times within {@code windowSeconds}. */
    public record StaffAlert(String permission, int refusals, int windowSeconds, int cooldownSeconds) {}

    /** {@code minIntervalSeconds}: at most one journal line per player in that time, the rest are counted. */
    public record Journal(boolean includeText, int minIntervalSeconds) {}

    public record Rate(RuleMode mode, long intervalMillis, int burst) {}

    public record Repetition(RuleMode mode, int maxRepeats) {}

    /** {@code minLength}: shorter normalized messages ("gg", "lol") are never compared. */
    public record Similarity(RuleMode mode, double threshold, int count, int windowSeconds, int history, int minLength) {}

    public record Caps(RuleMode mode, double ratio, int minLetters) {}

    /** An inclusive range of code points. */
    public record CodePointRange(int from, int to) {
        public boolean contains(int codePoint) {
            return codePoint >= from && codePoint <= to;
        }
    }

    /**
     * {@code allowedPrivateUse}: private-use code points inside these ranges are never refused. The pack
     * glyphs live there, so the default allows every private-use range; narrowing it is the admin's choice.
     */
    public record Unicode(RuleMode mode, int maxCombiningPerChar, List<CodePointRange> allowedPrivateUse,
                          boolean refuseMixedScripts) {}

    /** {@code minLabelLength}: the label before the TLD of a bare {@code label.tld} must be at least this long. */
    public record Advertising(RuleMode mode, boolean ipv4, boolean domains, Set<String> allowDomains,
                              String tldFile, int minLabelLength) {}

    public record Words(RuleMode mode, String directory, List<String> languages) {}

    private static final String PREFIX = "chat-guard.yml: ";
    private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*");
    private static final Pattern COMMAND = Pattern.compile("[a-z0-9_:.-]+");
    private static final int MAX_CODE_POINT = 0x10FFFF;

    /** The settings of a server that never wrote the file. */
    public static ChatGuardConfig defaults() {
        return parse(Map.of());
    }

    /** Parses the text of {@code chat-guard.yml}; an empty document gives the defaults. */
    public static ChatGuardConfig fromYaml(String yamlText) {
        LoaderOptions options = new LoaderOptions();
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(yamlText);
        } catch (RuntimeException syntax) {
            throw new IllegalArgumentException(PREFIX + "not valid YAML: " + syntax.getMessage(), syntax);
        }
        if (loaded == null) {
            return defaults();
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException(PREFIX + "the document must be a mapping");
        }
        return parse(Section.cast(root));
    }

    /** Builds the settings from the parsed document; every violation throws {@link IllegalArgumentException}. */
    public static ChatGuardConfig parse(Map<String, Object> root) {
        Section top = new Section(root, "");
        Section rules = top.child("rules");
        Section alert = top.child("staff-alert");
        Section journal = top.child("journal");

        Section rate = rules.child("rate");
        Section repetition = rules.child("repetition");
        Section similarity = rules.child("similarity");
        Section caps = rules.child("caps");
        Section unicode = rules.child("unicode");
        Section advertising = rules.child("advertising");
        Section words = rules.child("words");

        int count = similarity.integer("count", 5, 2, 50);
        int history = similarity.integer("history", 5, 1, 50);
        if (history < count - 1) {
            throw new IllegalArgumentException(PREFIX + similarity.path("history") + " must be at least count-1 ("
                + (count - 1) + "), or the rule could never fire");
        }

        return new ChatGuardConfig(
            top.choice("mode", Mode.class, Mode.OBSERVE),
            top.text("bypass-permission", "btc.chatguard.bypass"),
            top.commands("whisper-commands", List.of("msg", "tell", "w", "whisper", "m", "pm")),
            top.commands("reply-commands", List.of("r", "reply")),
            new StaffAlert(
                alert.text("permission", "btc.chatguard.staff"),
                alert.integer("refusals", 5, 1, 100),
                alert.integer("window-seconds", 60, 1, 3600),
                alert.integer("cooldown-seconds", 60, 0, 86_400)),
            new Journal(journal.bool("include-text", false), journal.integer("min-interval-seconds", 5, 0, 3600)),
            new Rate(rate.ruleMode("mode", RuleMode.INHERIT),
                rate.integer("interval-ms", 2500, 50, 600_000), rate.integer("burst", 3, 1, 50)),
            new Repetition(repetition.ruleMode("mode", RuleMode.INHERIT), repetition.integer("max-repeats", 5, 2, 100)),
            new Similarity(similarity.ruleMode("mode", RuleMode.INHERIT),
                similarity.decimal("threshold", 0.85, 0.5, 1.0), count,
                similarity.integer("window-seconds", 60, 1, 3600), history,
                similarity.integer("min-length", 6, 1, 256)),
            new Caps(caps.ruleMode("mode", RuleMode.INHERIT), caps.decimal("ratio", 0.60, 0.1, 1.0),
                caps.integer("min-letters", 8, 1, 256)),
            new Unicode(unicode.ruleMode("mode", RuleMode.INHERIT),
                unicode.integer("max-combining-per-char", 2, 0, 16),
                unicode.ranges("allowed-private-use", List.of("E000-F8FF", "F0000-FFFFD", "100000-10FFFD")),
                unicode.bool("refuse-mixed-scripts", false)),
            new Advertising(advertising.ruleMode("mode", RuleMode.INHERIT),
                advertising.bool("ipv4", true), advertising.bool("domains", true),
                advertising.domains("allow-domains", List.of("borntocraft.net")),
                advertising.text("tld-file", "chat-guard/tlds.txt"),
                advertising.integer("min-label-length", 3, 1, 63)),
            new Words(words.ruleMode("mode", RuleMode.OFF), words.text("directory", "chat-guard/words"),
                words.languages("languages", List.of("fr", "en", "de", "es", "it", "pl", "pt-BR", "ru"))),
            top.child("messages").messages());
    }

    public ChatGuardConfig {
        whisperCommands = Set.copyOf(whisperCommands);
        replyCommands = Set.copyOf(replyCommands);
        messages = Map.copyOf(messages);
    }

    /** The mode a rule runs in. */
    public Mode modeOf(ChatRuleId rule) {
        return switch (rule) {
            case RATE -> rate.mode().resolve(mode);
            case REPETITION -> repetition.mode().resolve(mode);
            case CAPS -> caps.mode().resolve(mode);
            case UNICODE -> unicode.mode().resolve(mode);
            case SIMILARITY -> similarity.mode().resolve(mode);
            case ADVERTISING -> advertising.mode().resolve(mode);
            case WORDS -> words.mode().resolve(mode);
        };
    }

    /** One level of the document, with the path to report in a refusal. */
    private record Section(Map<String, Object> values, String path) {

        @SuppressWarnings("unchecked")
        static Map<String, Object> cast(Map<?, ?> raw) {
            Map<String, Object> copy = new LinkedHashMap<>();
            raw.forEach((key, value) -> copy.put(String.valueOf(key), value));
            return copy;
        }

        String path(String key) {
            return path.isEmpty() ? key : path + "." + key;
        }

        private IllegalArgumentException bad(String key, String problem) {
            return new IllegalArgumentException(PREFIX + path(key) + " " + problem);
        }

        Section child(String key) {
            Object value = values.get(key);
            if (value == null) {
                return new Section(Map.of(), path(key));
            }
            if (value instanceof Map<?, ?> map) {
                return new Section(cast(map), path(key));
            }
            throw bad(key, "must be a section");
        }

        String text(String key, String fallback) {
            Object value = values.get(key);
            if (value == null) {
                return fallback;
            }
            if (value instanceof String string && !string.isBlank()) {
                return string.strip();
            }
            throw bad(key, "must be a non-empty text");
        }

        boolean bool(String key, boolean fallback) {
            Object value = values.get(key);
            if (value == null) {
                return fallback;
            }
            if (value instanceof Boolean bool) {
                return bool;
            }
            throw bad(key, "must be true or false");
        }

        int integer(String key, int fallback, int min, int max) {
            Object value = values.get(key);
            if (value == null) {
                return fallback;
            }
            if (value instanceof Integer || value instanceof Long) {
                long number = ((Number) value).longValue();
                if (number >= min && number <= max) {
                    return (int) number;
                }
            }
            throw bad(key, "must be a whole number between " + min + " and " + max + " (was " + value + ")");
        }

        double decimal(String key, double fallback, double min, double max) {
            Object value = values.get(key);
            if (value == null) {
                return fallback;
            }
            if (value instanceof Number number) {
                double asDouble = number.doubleValue();
                if (asDouble >= min && asDouble <= max) {
                    return asDouble;
                }
            }
            throw bad(key, "must be a number between " + min + " and " + max + " (was " + value + ")");
        }

        <E extends Enum<E>> E choice(String key, Class<E> type, E fallback) {
            Object value = values.get(key);
            if (value == null) {
                return fallback;
            }
            // YAML 1.1 reads an unquoted `off` as the boolean false: that is what the admin meant.
            Object word = Boolean.FALSE.equals(value) ? "off" : value;
            if (word instanceof String string) {
                for (E constant : type.getEnumConstants()) {
                    if (constant.name().equalsIgnoreCase(string.strip())) {
                        return constant;
                    }
                }
            }
            throw bad(key, "must be one of " + List.of(type.getEnumConstants()).toString().toLowerCase(Locale.ROOT)
                + " (was " + value + ")");
        }

        RuleMode ruleMode(String key, RuleMode fallback) {
            return choice(key, RuleMode.class, fallback);
        }

        private List<String> strings(String key, List<String> fallback) {
            Object value = values.get(key);
            if (value == null) {
                return fallback;
            }
            if (value instanceof List<?> list) {
                List<String> out = new ArrayList<>(list.size());
                for (Object element : list) {
                    if (!(element instanceof String string) || string.isBlank()) {
                        throw bad(key, "must be a list of non-empty texts");
                    }
                    out.add(string.strip());
                }
                return out;
            }
            throw bad(key, "must be a list");
        }

        Set<String> commands(String key, List<String> fallback) {
            List<String> names = strings(key, fallback);
            for (String name : names) {
                if (!COMMAND.matcher(name.toLowerCase(Locale.ROOT)).matches()) {
                    throw bad(key, "has an invalid command name '" + name + "'");
                }
            }
            return names.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        Set<String> domains(String key, List<String> fallback) {
            return strings(key, fallback).stream().map(name -> name.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        List<String> languages(String key, List<String> fallback) {
            List<String> codes = strings(key, fallback);
            for (String code : codes) {
                if (!LANGUAGE.matcher(code).matches()) {
                    throw bad(key, "has an invalid language code '" + code + "'");
                }
            }
            return List.copyOf(codes);
        }

        List<CodePointRange> ranges(String key, List<String> fallback) {
            List<CodePointRange> ranges = new ArrayList<>();
            for (String text : strings(key, fallback)) {
                String[] bounds = text.split("-", -1);
                try {
                    if (bounds.length > 2) {
                        throw new NumberFormatException();
                    }
                    int from = Integer.parseInt(bounds[0].strip(), 16);
                    int to = bounds.length == 2 ? Integer.parseInt(bounds[1].strip(), 16) : from;
                    if (from < 0 || to > MAX_CODE_POINT || from > to) {
                        throw new NumberFormatException();
                    }
                    ranges.add(new CodePointRange(from, to));
                } catch (NumberFormatException invalid) {
                    throw bad(key, "has an invalid range '" + text + "' (use hexadecimal, like E000-F8FF)");
                }
            }
            return List.copyOf(ranges);
        }

        /** The {@code messages} section: a reworded template per chat text key. */
        Map<String, String> messages() {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith("btccore.chat.") || !PlayerText.keys().contains(key)) {
                    throw bad(key, "is not a chat text key");
                }
                if (!(entry.getValue() instanceof String template)) {
                    throw bad(key, "must be a text");
                }
                out.put(key, template);
            }
            return out;
        }
    }
}
