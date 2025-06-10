package zerouni.bannedwords.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import zerouni.bannedwords.BannedWords;
import net.fabricmc.loader.api.FabricLoader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the configuration for the BannedWords mod.
 * Loads and saves settings such as banned words, grace period, and explosion power.
 */
public class BannedWordsConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve(BannedWords.MOD_ID + ".json");

    // Configurable fields with default values
    private List<String> bannedWords = Arrays.asList("crafting table",
        "the nether",
        "flint and steel",
        "lava chicken",
        "slime cube",
        "chicken jockey",
        "i am steve",
        "the villagers",
        "first we mine",
        "then we craft",
        "let's minecraft",
        "i yearned for the mines",
        "i think he's swedish",
        "vaya con dios",
        "do you have little knife");
    private int gracePeriodSeconds = 5;
    private float explosionPower = 4.0f; // Default Minecraft TNT explosion power    
    private List<String> audioClipIds = Arrays.asList("da_dog");
    // Map of sound ID to its duration in milliseconds. Must match sounds.json and actual audio files.
    private Map<String, Long> audioClipDurationsMillis = new HashMap<>() {{
        put("da_dog", 2000L); // Adjust this to match your actual audio file duration
    }};    private String whisperModelPath = FabricLoader.getInstance().getGameDir().resolve("ggml-small.en-q8_0.bin").toString();
    private String whisperLanguage = "en"; // Default Whisper language
    private int whisperThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2); // Use half of available cores
    private String logLevel = "INFO"; // Default logging level
    
    // Whisper performance optimization settings
    private int whisperAudioCtx = 0; // Audio context window (0 = auto)
    private int whisperTextCtx = 512; // Reduced text context for faster processing
    private float whisperTemperature = 0.0f; // Temperature for decoding (0.0 = greedy, fastest)
    private int whisperBeamSize = 1; // Beam search size (1 = greedy, fastest)
    private float whisperNoSpeechThreshold = 0.3f; // Lower threshold for faster processing
    
    // VAD specific parameters
    private int audioProcessingFrameSizeMillis = 20; // Size of each audio frame for VAD analysis
    private int minSpeechDurationMillis = 300; // Minimum duration of continuous speech to consider it an utterance
    private double speechDetectionThreshold = 0.005; // RMS energy threshold (0.0-1.0) for speech detection
    private int silenceTimeoutMillis = 500; // Max silence duration after speech to end an utterance
    
    // Continuous detection mode parameters (alternative to VAD)
    private boolean enableContinuousDetection = false; // Enable continuous detection instead of VAD
    private int continuousSegmentDurationMillis = 2000; // Duration of each continuous segment in milliseconds
    private int continuousSegmentOverlapMillis = 500; // Overlap between segments in milliseconds

    /**
     * Loads the configuration from the mod's config file.
     * If the file does not exist, a default configuration is created and saved.
     * @return The loaded or default BannedWordsConfig instance.
     */
    public static BannedWordsConfig load() {
        if (!CONFIG_FILE.toFile().exists()) {
            BannedWords.LOGGER.info("Config file not found, creating default config at {}", CONFIG_FILE);
            BannedWordsConfig defaultConfig = new BannedWordsConfig();
            defaultConfig.save();
            return defaultConfig;
        }

        try (FileReader reader = new FileReader(CONFIG_FILE.toFile())) {
            BannedWordsConfig config = GSON.fromJson(reader, BannedWordsConfig.class);
            if (config == null) {
                // Fallback to default if config file is empty or malformed
                BannedWords.LOGGER.warn("Config file was empty or malformed, loading default config.");
                config = new BannedWordsConfig();
                config.save(); // Save defaults for next time
            }
            BannedWords.LOGGER.info("Config loaded successfully from {}", CONFIG_FILE);
            return config;
        } catch (IOException e) {
            BannedWords.LOGGER.error("Failed to load config from {}: {}", CONFIG_FILE, e.getMessage());
            BannedWords.LOGGER.info("Using default config.");
            return new BannedWordsConfig(); // Return default on error
        }
    }

    /**
     * Saves the current configuration to the mod's config file.
     */
    public void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE.toFile())) {
            GSON.toJson(this, writer);
            BannedWords.LOGGER.info("Config saved successfully to {}", CONFIG_FILE);
        } catch (IOException e) {
            BannedWords.LOGGER.error("Failed to save config to {}: {}", CONFIG_FILE, e.getMessage());
        }
    }

    /**
     * Returns the list of banned words and phrases.
     * @return A list of strings representing banned words.
     */
    public List<String> getBannedWords() {
        return bannedWords;
    }

    /**
     * Returns the duration of the grace period in seconds after a punishment.
     * @return The grace period duration in seconds.
     */
    public int getGracePeriodSeconds() {
        return gracePeriodSeconds;
    }

    /**
     * Returns the power of the explosion that will be triggered.
     * @return The explosion power as a float.
     */
    public float getExplosionPower() {
        return explosionPower;
    }

    /**
     * Returns a list of IDs for the audio clips to be played.
     * These IDs must match entries in sounds.json.
     * @return A list of audio clip identifiers.
     */
    public List<String> getAudioClipIds() {
        return audioClipIds;
    }

    /**
     * Returns a map of audio clip IDs to their durations in milliseconds.
     * @return A map of audio clip durations.
     */
    public Map<String, Long> getAudioClipDurationsMillis() {
        return audioClipDurationsMillis;
    }

    /**
     * Returns the file path to the Whisper GGML model.
     * @return The Path object for the Whisper model.
     */
    public Path getWhisperModelPath() {
        return Path.of(whisperModelPath);
    }

    /**
     * Returns the target language for Whisper transcription (e.g., "en" for English).
     * @return The Whisper language code.
     */
    public String getWhisperLanguage() {
        return whisperLanguage;
    }

    /**
     * Returns the number of threads Whisper should use for inference.
     * @return The number of Whisper inference threads.
     */
    public int getWhisperThreads() {
        return whisperThreads;
    }

    /**
     * Returns the audio context window size for Whisper (0 = auto).
     * @return The audio context window size.
     */
    public int getWhisperAudioCtx() {
        return whisperAudioCtx;
    }

    /**
     * Returns the text context window size for Whisper.
     * @return The text context window size.
     */
    public int getWhisperTextCtx() {
        return whisperTextCtx;
    }

    /**
     * Returns the temperature for Whisper decoding (0.0 = greedy, fastest).
     * @return The decoding temperature.
     */
    public float getWhisperTemperature() {
        return whisperTemperature;
    }

    /**
     * Returns the beam search size for Whisper (1 = greedy, fastest).
     * @return The beam search size.
     */
    public int getWhisperBeamSize() {
        return whisperBeamSize;
    }

    /**
     * Returns the no-speech threshold for Whisper.
     * @return The no-speech threshold.
     */
    public float getWhisperNoSpeechThreshold() {
        return whisperNoSpeechThreshold;
    }

    /**
     * Returns the size of each audio frame in milliseconds for VAD analysis.
     * @return The audio processing frame size in milliseconds.
     */
    public int getAudioProcessingFrameSizeMillis() {
        return audioProcessingFrameSizeMillis;
    }

    /**
     * Returns the minimum duration of continuous speech in milliseconds to consider it a valid utterance.
     * @return The minimum speech duration in milliseconds.
     */
    public int getMinSpeechDurationMillis() {
        return minSpeechDurationMillis;
    }

    /**
     * Returns the RMS energy threshold (0.0-1.0) above which an audio frame is considered speech.
     * @return The speech detection threshold.
     */
    public double getSpeechDetectionThreshold() {
        return speechDetectionThreshold;
    }
    
    /**
     * Returns the maximum duration of silence in milliseconds allowed after speech
     * before an utterance is considered ended.
     * @return The silence timeout in milliseconds.
     */
    public int getSilenceTimeoutMillis() {
        return silenceTimeoutMillis;
    }

    /**
     * Returns whether continuous detection mode is enabled instead of VAD.
     * @return true if continuous detection is enabled, false for VAD mode.
     */
    public boolean isEnableContinuousDetection() {
        return enableContinuousDetection;
    }

    /**
     * Returns the duration of each continuous segment in milliseconds.
     * @return The continuous segment duration in milliseconds.
     */
    public int getContinuousSegmentDurationMillis() {
        return continuousSegmentDurationMillis;
    }    /**
     * Returns the overlap between continuous segments in milliseconds.
     * @return The continuous segment overlap in milliseconds.
     */
    public int getContinuousSegmentOverlapMillis() {
        return continuousSegmentOverlapMillis;
    }

    /**
     * Returns the configured logging level for the mod.
     * @return The logging level as a string (e.g., "DEBUG", "INFO", "WARN", "ERROR").
     */
    public String getLogLevel() {
        return logLevel;
    }
}