package dev.btc.core.integrity.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Judges chat messages. The instance owns every player's {@link PlayerChatHistory} (nothing is static)
 * and a snapshot of the settings and rules that {@link #reload} replaces atomically: a message is judged
 * wholly by the old rules or wholly by the new ones.
 *
 * <p>A player is judged only between {@link #track} (they joined) and {@link #forget} (they left).
 *
 * <p><b>Any thread.</b> Public chat arrives asynchronously, private messages on a region thread; each
 * judgement locks the player's own history, so two players never wait for each other. The guard never
 * rewrites a message: it answers "refuse" or "let through" (a changed text would be unsigned content,
 * and a client showing only secure chat would still show the original).
 *
 * <p>The rules run cheapest first. A rule in {@code enforce} mode that finds something ends the
 * judgement; rules in {@code observe} mode only add a finding. A message is charged to the player's
 * history (rate credit, similarity memory) only when it goes through.
 */
public final class ChatGuard {

    private record Armed(ChatRule rule, ChatGuardConfig.Mode mode) {}

    private record Snapshot(ChatGuardConfig config, List<Armed> rules) {}

    /** A rule's opinion of a message, for {@code /chatguard test}. */
    public record Opinion(ChatRuleId rule, ChatGuardConfig.Mode mode, Optional<ChatFinding> finding) {}

    private final AtomicReference<Snapshot> snapshot;
    private final ConcurrentHashMap<UUID, PlayerChatHistory> histories = new ConcurrentHashMap<>();

    public ChatGuard(ChatGuardConfig config, Set<String> tlds, WordLists words) {
        this.snapshot = new AtomicReference<>(build(config, tlds, words));
    }

    /** Replaces the settings and the lists; the players' histories are kept. Two reloads never interleave. */
    public synchronized void reload(ChatGuardConfig config, Set<String> tlds, WordLists words) {
        snapshot.set(build(config, tlds, words));
    }

    /** Starts remembering {@code player}: call when they join. Only a tracked player is judged. */
    public void track(UUID player) {
        histories.putIfAbsent(player, new PlayerChatHistory());
    }

    public ChatGuardConfig config() {
        return snapshot.get().config();
    }

    private static Snapshot build(ChatGuardConfig config, Set<String> tlds, WordLists words) {
        List<ChatRule> rules = List.of(
            new RateRule(config.rate()),
            new RepetitionRule(config.repetition()),
            new CapsRule(config.caps()),
            new UnicodeRule(config.unicode()),
            new SimilarityRule(config.similarity()),
            new AdvertisingRule(config.advertising(), tlds),
            new WordListRule(words));
        List<Armed> armed = new ArrayList<>(rules.size());
        for (ChatRule rule : rules) {
            armed.add(new Armed(rule, config.modeOf(rule.id())));
        }
        return new Snapshot(config, List.copyOf(armed));
    }

    /**
     * @param bypass whether the player may speak unfiltered (staff): they are neither judged nor remembered
     */
    public ChatVerdict judge(UUID player, String raw, long nowMillis, boolean bypass) {
        Snapshot current = snapshot.get();
        ChatGuardConfig config = current.config();
        if (bypass || config.mode() == ChatGuardConfig.Mode.OFF) {
            return ChatVerdict.CLEAN;
        }
        // Never created here: a message that arrives after the player left (the chat thread lags the quit)
        // must not bring their history back, nobody would ever forget it.
        PlayerChatHistory history = histories.get(player);
        if (history == null) {
            return ChatVerdict.CLEAN;
        }
        ChatText text = ChatNormalizer.normalize(raw);
        synchronized (history) {
            Optional<ChatFinding> refusal = Optional.empty();
            List<ChatFinding> observed = new ArrayList<>();
            for (Armed armed : current.rules()) {
                if (armed.mode() == ChatGuardConfig.Mode.OFF) {
                    continue;
                }
                Optional<ChatFinding> found = armed.rule().check(text, history, nowMillis);
                if (found.isEmpty()) {
                    continue;
                }
                if (armed.mode() == ChatGuardConfig.Mode.ENFORCE) {
                    refusal = found;
                    break;
                }
                observed.add(found.get());
            }

            if (refusal.isEmpty()) {
                for (Armed armed : current.rules()) {
                    if (armed.mode() != ChatGuardConfig.Mode.OFF) {
                        armed.rule().accepted(text, history, nowMillis);
                    }
                }
            }
            if (refusal.isEmpty() && observed.isEmpty()) {
                return ChatVerdict.CLEAN;
            }

            ChatGuardConfig.StaffAlert alert = config.staffAlert();
            int hits = history.recordHit(nowMillis, alert.windowSeconds() * 1000L);
            OptionalInt alertCount = hits >= alert.refusals() && history.takeAlert(nowMillis, alert.cooldownSeconds() * 1000L)
                ? OptionalInt.of(hits) : OptionalInt.empty();
            int journalHeld = history.takeJournalSlot(nowMillis, config.journal().minIntervalSeconds() * 1000L);
            return new ChatVerdict(refusal, observed, journalHeld, alertCount);
        }
    }

    /**
     * What every rule thinks of {@code raw} for {@code player}, without remembering anything: the rules
     * see the player's real history, so the rate and similarity rules answer as they would for the next
     * message. Rules that are off are listed too, so the answer says why they stay silent.
     */
    public List<Opinion> explain(UUID player, String raw, long nowMillis) {
        Snapshot current = snapshot.get();
        ChatText text = ChatNormalizer.normalize(raw);
        PlayerChatHistory history = histories.getOrDefault(player, new PlayerChatHistory());
        List<Opinion> opinions = new ArrayList<>();
        synchronized (history) {
            for (Armed armed : current.rules()) {
                opinions.add(new Opinion(armed.rule().id(), armed.mode(), armed.rule().check(text, history, nowMillis)));
            }
        }
        return opinions;
    }

    /** Drops what is remembered about {@code player}; call when they leave. */
    public void forget(UUID player) {
        histories.remove(player);
    }

    /** How many players are remembered, for {@code /chatguard status}. */
    public int tracked() {
        return histories.size();
    }
}
