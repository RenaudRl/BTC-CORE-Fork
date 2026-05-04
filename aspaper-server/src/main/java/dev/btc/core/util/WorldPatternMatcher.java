package dev.btc.core.util;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility for matching world names against patterns (wildcards and regex).
 */
public final class WorldPatternMatcher {

    private static final Logger LOGGER = LogManager.getLogger("WorldPatternMatcher");

    /**
     * Checks if a world name matches a given pattern.
     * 
     * Supported formats:
     * - "exact_match" -> Equals (case insensitive)
     * - "prefix*"    -> Starts with "prefix"
     * - "*suffix"    -> Ends with "suffix"
     * - "regex:^.*$" -> Full Java Regex
     * 
     * @param worldName The name of the world to check
     * @param pattern   The pattern string
     * @return true if matches, false otherwise
     */
    public static boolean matches(String worldName, String pattern) {
        if (pattern == null || worldName == null) return false;
        
        String lowerWorld = worldName.toLowerCase();
        String lowerPattern = pattern.toLowerCase();

        // 1. Regex Match
        if (pattern.startsWith("regex:")) {
            String regex = pattern.substring(6);
            try {
                return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(worldName).find();
            } catch (PatternSyntaxException e) {
                LOGGER.warn("Invalid regex pattern in configuration: {}", regex);
                return false;
            }
        }

        // 2. Exact Match
        if (lowerPattern.equals(lowerWorld) || pattern.equals("*")) {
            return true;
        }

        // 3. Prefix Match
        if (pattern.endsWith("*")) {
            String prefix = lowerPattern.substring(0, pattern.length() - 1);
            return lowerWorld.startsWith(prefix);
        }

        // 4. Suffix Match
        if (pattern.startsWith("*")) {
            String suffix = lowerPattern.substring(1);
            return lowerWorld.endsWith(suffix);
        }

        // 5. Fallback to Equals
        return lowerPattern.equals(lowerWorld);
    }

    /**
     * Checks if a world name matches any of the provided patterns.
     */
    public static boolean matchesAny(String worldName, java.util.Collection<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return false;
        for (String pattern : patterns) {
            if (matches(worldName, pattern)) return true;
        }
        return false;
    }
}
