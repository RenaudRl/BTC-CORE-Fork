package dev.btc.core.integrity.chat;

/**
 * What a rule found in a message.
 *
 * @param retryAfterMillis how long until the message would pass; only the rate rule sets it, others give 0
 * @param detail           a short machine word for the journal ("combining", "ipv4", the language of a word
 *                         list); never a part of the message
 */
public record ChatFinding(ChatRuleId rule, long retryAfterMillis, String detail) {

    public static ChatFinding of(ChatRuleId rule, String detail) {
        return new ChatFinding(rule, 0L, detail);
    }
}
