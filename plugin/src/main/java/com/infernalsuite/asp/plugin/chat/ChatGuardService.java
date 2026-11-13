package com.infernalsuite.asp.plugin.chat;

import dev.btc.core.config.AnticheatConfig;
import dev.btc.core.integrity.chat.ChatFinding;
import dev.btc.core.integrity.chat.ChatGuard;
import dev.btc.core.integrity.chat.ChatGuardConfig;
import dev.btc.core.integrity.chat.ChatGuardFiles;
import dev.btc.core.integrity.chat.ChatJournal;
import dev.btc.core.integrity.chat.ChatMessages;
import dev.btc.core.integrity.chat.ChatRuleId;
import dev.btc.core.integrity.chat.ChatVerdict;
import dev.btc.core.integrity.chat.WhisperCommand;
import dev.btc.core.security.NativeAnticheatDB;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Runs the chat guard on the live server: loads its files, judges a message for a player, and turns the
 * verdict into what the server does (tell the player, journal, alert staff). The decisions are all in
 * {@link ChatGuard}; this class holds no rule.
 *
 * <p>Folia: nothing here touches a world or schedules on the global scheduler. A judgement may run on the
 * asynchronous chat thread or on a player's region thread; it only reads the player's name and permissions
 * and sends messages, which are both safe from any thread. File reads happen at start-up and on
 * {@code /chatguard reload}, which runs on the async scheduler.
 *
 * <p>The guard failing must never silence the chat: an unexpected exception lets the message through and
 * is reported once.
 */
final class ChatGuardService {

    /** The violation journal check type is {@code chat:<rule>}. */
    private static final String CHECK_TYPE_PREFIX = "chat:";

    /** Width of the details column of the violation journal: a longer line is cut, never dropped. */
    private static final int MAX_DETAILS_CHARS = 255;

    /** Its own logger name, so an admin can route the chat journal to a file of its own in log4j2.xml. */
    private static final Logger JOURNAL = LoggerFactory.getLogger("btc.chatguard.journal");

    /** What a reload did, for the command's reply. */
    record Reload(boolean applied, List<String> lines) {}

    private final Plugin plugin;
    private final Logger log;
    private final Path folder;
    private final LongSupplier monotonicMillis;
    private final ChatGuard guard;
    private final AtomicReference<ChatGuardFiles.Loaded> loaded;
    private final AtomicBoolean failureReported = new AtomicBoolean();

    private ChatGuardService(final Plugin plugin, final Logger log, final Path folder, final ChatGuardFiles.Loaded start) {
        this.plugin = plugin;
        this.log = log;
        this.folder = folder;
        this.monotonicMillis = () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        this.guard = new ChatGuard(start.config(), start.tlds(), start.words());
        this.loaded = new AtomicReference<>(start);
    }

    /**
     * Copies the default files that are missing, then loads them. A file that cannot be read leaves the
     * guard on the shipped defaults (observe, nothing refused) rather than off or half-configured.
     */
    static ChatGuardService start(final Plugin plugin, final Logger log) {
        final Path folder = plugin.getDataFolder().toPath();
        ChatGuardFiles.Loaded start;
        try {
            ChatGuardFiles.installDefaults(folder);
            start = ChatGuardFiles.load(folder);
        } catch (IOException | RuntimeException failure) {
            log.error("[ChatGuard] the configuration could not be loaded, the shipped defaults (observe only) are used: {}",
                failure.getMessage());
            start = ChatGuardFiles.shipped();
        }
        start.warnings().forEach(warning -> log.warn("[ChatGuard] {}", warning));
        final ChatGuardService service = new ChatGuardService(plugin, log, folder, start);
        log.info("[ChatGuard] mode={} words={} entries in {} language(s), {} TLDs", start.config().mode(),
            start.words().entryCount(), start.words().languages().size(), start.tlds().size());
        return service;
    }

    ChatGuard guard() {
        return guard;
    }

    ChatGuardFiles.Loaded loaded() {
        return loaded.get();
    }

    long now() {
        return monotonicMillis.getAsLong();
    }

    /** Reads the files again and swaps them in; on any problem the previous settings stay. Does I/O: not on a game thread. */
    Reload reload() {
        try {
            final ChatGuardFiles.Loaded next = ChatGuardFiles.load(folder);
            guard.reload(next.config(), next.tlds(), next.words());
            loaded.set(next);
            final List<String> lines = new ArrayList<>();
            lines.add("Reloaded: mode=" + next.config().mode() + ", " + next.words().entryCount() + " word entries, "
                + next.tlds().size() + " TLDs.");
            next.warnings().forEach(warning -> lines.add("Warning: " + warning));
            return new Reload(true, lines);
        } catch (IOException | RuntimeException failure) {
            log.error("[ChatGuard] reload refused, the previous settings stay: {}", failure.getMessage());
            return new Reload(false, List.of("Reload refused, the previous settings stay: " + failure.getMessage()));
        }
    }

    /** The text of a private-message command line, or empty when the line is not one. */
    Optional<String> whisperText(final String commandLine) {
        final ChatGuardConfig config = guard.config();
        return WhisperCommand.textOf(commandLine, config.whisperCommands(), config.replyCommands());
    }

    void forget(final UUID player) {
        guard.forget(player);
    }

    /**
     * Judges one message of {@code player}, tells them why when it is refused, journals, and alerts staff.
     *
     * @return whether the message must be cancelled (only in {@code enforce} mode)
     */
    boolean mustCancel(final Player player, final String text, final ChatJournal.Channel channel) {
        try {
            final ChatGuardConfig config = guard.config();
            final boolean bypass = player.hasPermission(config.bypassPermission());
            final ChatVerdict verdict = guard.judge(player.getUniqueId(), text, now(), bypass);
            final List<ChatFinding> findings = verdict.findings();
            if (findings.isEmpty()) {
                return false;
            }
            verdict.refusal().ifPresent(refusal ->
                player.sendMessage(ChatMessages.refusal(player.getUniqueId(), refusal, config.messages())));
            if (verdict.journals()) {
                journal(player, text, channel, verdict, findings, config);
            }
            verdict.staffAlertCount().ifPresent(count -> alertStaff(player, findings.get(0).rule(), count, config));
            return verdict.cancels();
        } catch (RuntimeException failure) {
            if (failureReported.compareAndSet(false, true)) {
                log.error("[ChatGuard] a message could not be judged and went through; further failures are not logged.", failure);
            }
            return false;
        }
    }

    private void journal(final Player player, final String text, final ChatJournal.Channel channel,
                         final ChatVerdict verdict, final List<ChatFinding> findings, final ChatGuardConfig config) {
        final long now = System.currentTimeMillis();
        final UUID uuid = player.getUniqueId();
        for (int i = 0; i < findings.size(); i++) {
            final ChatFinding finding = findings.get(i);
            final ChatJournal.Action action = verdict.refusal().isPresent() && i == 0
                ? ChatJournal.Action.REFUSED : ChatJournal.Action.OBSERVED;
            final ChatGuardConfig.Mode mode = config.modeOf(finding.rule());
            // The lines held back by the journal interval are counted on the first line only.
            final int held = i == 0 ? verdict.journalHeld() : 0;
            // A private message is never handed over, whatever include-text says.
            final String said = channel == ChatJournal.Channel.PUBLIC ? text : null;
            JOURNAL.info(ChatJournal.line(now, uuid, player.getName(), AnticheatConfig.serverId, finding, action, mode,
                channel, config.journal().includeText(), said, held));
            final String details = ChatJournal.details(finding, action, mode, channel, held);
            NativeAnticheatDB.reportViolation(uuid.toString(), player.getName(), CHECK_TYPE_PREFIX + finding.rule().key(),
                details.length() > MAX_DETAILS_CHARS ? details.substring(0, MAX_DETAILS_CHARS) : details);
        }
    }

    private void alertStaff(final Player player, final ChatRuleId rule, final int count, final ChatGuardConfig config) {
        final String permission = config.staffAlert().permission();
        for (final Player staff : plugin.getServer().getOnlinePlayers()) {
            if (staff.hasPermission(permission)) {
                staff.sendMessage(ChatMessages.staffAlert(staff.getUniqueId(), player.getName(), rule, count, config.messages()));
            }
        }
        // The console is staff too, and has no language to pick: the log line is English.
        log.warn("[ChatGuard] {} tripped the chat filter {} times (last rule: {})", player.getName(), count, rule.key());
    }
}
