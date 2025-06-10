package zerouni.bannedwords.server;

import zerouni.bannedwords.BannedWords;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects banned words within transcribed text using an Aho-Corasick automaton.
 * Also manages grace periods for players after punishment.
 */
public class BannedWordDetector {
    private final AhoCorasick ahoCorasick;
    private final Map<UUID, Long> gracePeriods;
    private final long gracePeriodDurationMillis;    
    private String lastDetectedWord; 
    
    /**
     * Constructs a BannedWordDetector.
     * @param bannedWords A list of words and phrases to be banned.
     * @param gracePeriodSeconds The duration of the grace period in seconds after a punishment.
     */
    public BannedWordDetector(List<String> bannedWords, int gracePeriodSeconds) {
        this.ahoCorasick = new AhoCorasick();
        BannedWords.LOGGER.info("Initializing BannedWordDetector with {} banned words.", bannedWords.size());
        for (String word : bannedWords) {
            String lowerWord = word.toLowerCase();
            this.ahoCorasick.addPattern(lowerWord); // Add patterns in lowercase for case-insensitivity
            BannedWords.LOGGER.debug("Added banned word pattern: '{}'", lowerWord);
        }
        this.ahoCorasick.buildFailureLinks(); // Build the Aho-Corasick automaton
        BannedWords.LOGGER.info("Aho-Corasick automaton built successfully.");
        this.gracePeriods = new ConcurrentHashMap<>();
        this.gracePeriodDurationMillis = gracePeriodSeconds * 1000L; // Convert to milliseconds
        BannedWords.LOGGER.info("BannedWordDetector initialized with {} patterns and grace period of {} seconds.", bannedWords.size(), gracePeriodSeconds);
    }

    /**
     * Checks if the given transcript contains any banned words and applies grace period logic.
     * @param player The player whose transcript is being checked.
     * @param transcript The transcribed text to check against banned words.
     * @return true if a banned word was detected and the player is not in a grace period, false otherwise.
     */    
    public boolean checkAndPunish(ServerPlayerEntity player, String transcript) {
        BannedWords.LOGGER.debug("Checking transcript for player {}: '{}'", player.getName().getString(), transcript);
        
        long currentTime = System.currentTimeMillis();
        UUID playerUuid = player.getUuid();

        // Check if player is currently in a grace period
        if (gracePeriods.containsKey(playerUuid) &&
            (currentTime - gracePeriods.get(playerUuid) < gracePeriodDurationMillis)) {
            BannedWords.LOGGER.debug("Player {} is in grace period. Ignoring transcript: '{}'", player.getName().getString(), transcript);
            return false; // Still in grace period
        }

        String lowerTranscript = transcript.toLowerCase();
        BannedWords.LOGGER.debug("Searching for banned words in lowercase transcript: '{}'", lowerTranscript);
        
        List<String> detectedWords = ahoCorasick.findAll(lowerTranscript);
        BannedWords.LOGGER.debug("AhoCorasick search returned {} detected words: {}", detectedWords.size(), detectedWords);

        if (!detectedWords.isEmpty()) {
            // A banned word/phrase was found.
            // For simplicity, we take the first detected word. You might want to choose the longest, etc.
            this.lastDetectedWord = detectedWords.get(0);
            // Apply grace period
            gracePeriods.put(playerUuid, currentTime);
            BannedWords.LOGGER.info("Banned word detected for player {}: '{}' in transcript: '{}'", player.getName().getString(), lastDetectedWord, transcript);
            return true; // Banned word found and punishment should be applied
        } else {
            BannedWords.LOGGER.debug("No banned words found in transcript: '{}'", transcript);
        }
        return false; // No banned words found
    }

    /**
     * Retrieves the last banned word detected by the detector.
     * This is useful for including the specific word in the punishment message.
     * @return The last detected banned word or phrase.
     */
    public String getLastDetectedWord() {
        return lastDetectedWord;
    }
}