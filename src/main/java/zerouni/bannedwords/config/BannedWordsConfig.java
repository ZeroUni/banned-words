package zerouni.bannedwords.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import zerouni.bannedwords.BannedWords;
import net.fabricmc.loader.api.FabricLoader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import zerouni.bannedwords.util.GitHubFileDownloader;

/**
 * Manages the configuration for the BannedWords mod.
 * Loads and saves settings such as banned words, grace period, and explosion power.
 */
public class BannedWordsConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve(BannedWords.MOD_ID + ".json");
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve(BannedWords.MOD_ID);
    private static final String BANNED_WORDS_FILENAME = "blocks.txt";
    private static final String GITHUB_RAW_URL = "https://raw.githubusercontent.com/ZeroUni/banned-words/main/resources/blocks.txt";


    private List<String> bannedWords = new ArrayList<>();
    private int gracePeriodSeconds = 5;
    private float explosionPower = 4.0f;

    private List<String> audioClipIds = Arrays.asList("da_dog");

    private Map<String, Long> audioClipDurationsMillis = new HashMap<>() {{
        put("da_dog", 2000L);
    }};    private String whisperModelPath = FabricLoader.getInstance().getGameDir().resolve("ggml-small.en-q8_0.bin").toString();
    private String whisperLanguage = "en";
    private int whisperThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    private String logLevel = "INFO";
    
    // Whisper performance optimization settings
    private int whisperAudioCtx = 0;
    private int whisperTextCtx = 512;
    private float whisperTemperature = 0.0f;
    private int whisperBeamSize = 1;
    private float whisperNoSpeechThreshold = 0.3f;
    
    // VAD specific parameters
    private int audioProcessingFrameSizeMillis = 20;
    private int minSpeechDurationMillis = 300;
    private double speechDetectionThreshold = 0.005;
    private int silenceTimeoutMillis = 500;
    
    // Continuous detection mode parameters (alternative to VAD)
    private boolean enableContinuousDetection = false;
    private int continuousSegmentDurationMillis = 2000;
    private int continuousSegmentOverlapMillis = 500;

    /**
     * Loads the configuration from the mod's config file.
     * If the file does not exist, a default configuration is created and saved.
     * @return The loaded or default BannedWordsConfig instance.
     */
    public static BannedWordsConfig load() {
        BannedWordsConfig config;
        if (!CONFIG_FILE.toFile().exists()) {
            BannedWords.LOGGER.info("Config file not found, creating default config at {}", CONFIG_FILE);
            config = new BannedWordsConfig();
            config.loadBannedWordsFromFile();
            config.save();
            return config;
        }
        try (java.io.FileReader reader = new java.io.FileReader(CONFIG_FILE.toFile())) {
            config = GSON.fromJson(reader, BannedWordsConfig.class);
            if (config == null) {
                BannedWords.LOGGER.warn("Config file was empty or malformed, loading default config.");
                config = new BannedWordsConfig();
                config.loadBannedWordsFromFile();
                config.save();
                return config;
            }
            BannedWords.LOGGER.info("Config loaded successfully from {}", CONFIG_FILE);
        } catch (IOException e) {
            BannedWords.LOGGER.error("Failed to load config from {}: {}", CONFIG_FILE, e.getMessage());
            BannedWords.LOGGER.info("Using default config.");
            config = new BannedWordsConfig();
            config.loadBannedWordsFromFile();
            return config;
        }

        config.loadBannedWordsFromFile();
        return config;
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

    /**
     * Loads banned word patterns from the blocks.txt file in the config directory.
     * Downloads the file from GitHub if it's missing locally.
     */
    private void loadBannedWordsFromFile() {
        try {

            Files.createDirectories(CONFIG_DIR);
            Path filePath = CONFIG_DIR.resolve(BANNED_WORDS_FILENAME);
            if (!GitHubFileDownloader.ensureFileExists(GITHUB_RAW_URL, filePath)) {
                BannedWords.LOGGER.error("Failed to ensure banned words file exists at {}", filePath);
                return;
            }
            List<String> lines = Files.readAllLines(filePath);
            this.bannedWords = new ArrayList<>();
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String pattern = line;
                if (line.contains(";")) {
                    pattern = line.split(";", 2)[0].trim();
                }
                this.bannedWords.add(pattern);
                BannedWords.LOGGER.debug("Loaded banned pattern: {}", pattern);
            }
        } catch (IOException e) {
            BannedWords.LOGGER.error("Error loading banned words file: {}", e.getMessage());
        }
    }
}