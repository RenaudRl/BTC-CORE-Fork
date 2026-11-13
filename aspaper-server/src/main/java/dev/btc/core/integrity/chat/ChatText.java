package dev.btc.core.integrity.chat;

import java.util.List;

/**
 * A message as the rules see it. Built once by {@link ChatNormalizer#normalize}; never altered, and
 * never sent anywhere: what players read is the original, the guard only ever refuses or lets through.
 *
 * @param raw           the message as typed, cut to {@link ChatNormalizer#MAX_INPUT_CHARS}
 * @param normalized    compatibility-folded, lower-cased, accents and invisible characters removed,
 *                      look-alike letters of a mixed-script word folded; digits and punctuation kept
 * @param letters       letters of {@code raw}
 * @param upperCase     upper-case letters of {@code raw}
 * @param combiningMax  the most combining marks stacked on one character of {@code raw}
 * @param wordForms     the message as lists of words, in the spellings a word list is matched against
 *                      (plain, leet-folded, spaced letters joined)
 */
public record ChatText(String raw, String normalized, int letters, int upperCase, int combiningMax,
                       List<List<String>> wordForms) {}
