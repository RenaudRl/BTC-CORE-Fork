package dev.btc.core.integrity.sanction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The words of {@code /unban} or {@code /unmute}, with the {@code --linked} flag taken out.
 *
 * <p>The flag is only honoured as the <em>last</em> word. Anywhere else it is part of the reason: a
 * moderator who writes "--linked" in the middle of a sentence did not ask to lift other accounts.
 *
 * @param words the command words without the trailing flag: target first, then the reason
 * @param includeLinked whether the last word was {@code --linked}
 */
public record LiftArguments(List<String> words, boolean includeLinked) {

    private static final String FLAG = "--linked";

    public static LiftArguments parse(String[] args) {
        List<String> words = new ArrayList<>(Arrays.asList(args));
        boolean linked = !words.isEmpty() && words.get(words.size() - 1).equalsIgnoreCase(FLAG);
        if (linked) {
            words.remove(words.size() - 1);
        }
        return new LiftArguments(List.copyOf(words), linked);
    }
}
