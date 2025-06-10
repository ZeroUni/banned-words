package zerouni.bannedwords.client;

import zerouni.bannedwords.BannedWords;
import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import java.nio.file.Path;
import java.nio.file.Files;

/**
 * Handles the integration with the whisper-jni library for speech-to-text transcription.
 * Manages the Whisper context and parameters.
 */
public class WhisperProcessor {
    private final Path modelPath;
    private final WhisperJNI whisperJNI;
    private WhisperContext context; // Managed by the calling thread for proper closing

    /**
     * Constructs a WhisperProcessor.
     * @param modelPath The file path to the ggml Whisper model (e.g., ggml-tiny.bin).
     */
    public WhisperProcessor(Path modelPath) {
        this.modelPath = modelPath;
        this.whisperJNI = new WhisperJNI();
    }    
    
    /**
     * Initializes the Whisper context. This operation can be resource-intensive and should ideally
     * be called once and the context reused.
     * @return The initialized WhisperContext, or null if initialization fails.
     */
    public WhisperContext getContext() {
        if (context == null) {
            try {
                // First, verify the model file exists
                if (!Files.exists(modelPath)) {
                    BannedWords.LOGGER.error("Whisper model file not found at path: {}. Please ensure the model file exists.", modelPath);
                    BannedWords.LOGGER.error("Expected model file: ggml-tiny.en.bin");
                    BannedWords.LOGGER.error("You can download it from: https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin");
                    return null;
                }
                
                if (!Files.isReadable(modelPath)) {
                    BannedWords.LOGGER.error("Whisper model file exists but is not readable: {}", modelPath);
                    return null;
                }
                
                long modelSize = Files.size(modelPath);
                BannedWords.LOGGER.info("Initializing Whisper context from model: {} (size: {} MB)", modelPath, modelSize / (1024 * 1024));
                
                context = whisperJNI.init(modelPath);
                if (context == null) {
                    BannedWords.LOGGER.error("Failed to initialize Whisper context from model: {}. The model file may be corrupted or incompatible.", modelPath);
                    BannedWords.LOGGER.error("Try downloading a fresh copy of the model file.");
                } else {
                    BannedWords.LOGGER.info("Whisper context initialized successfully from model: {}", modelPath);
                }
            } catch (Exception e) {
                BannedWords.LOGGER.error("Error initializing Whisper context: {}", e.getMessage(), e);
                // Reset context to null to allow retry attempts
                context = null;
            }
        }        
        return context;
    }
    
    /**
     * Resets the Whisper context by closing it if it exists. 
     * The next call to getContext() will create a fresh context.
     * This can be useful if the context becomes corrupted or needs to be reinitialized.
     */
    public void resetContext() {
        if (context != null) {
            try {
                context.close();
                BannedWords.LOGGER.info("Whisper context reset and closed successfully.");
            } catch (Exception e) {
                BannedWords.LOGGER.warn("Error closing Whisper context during reset: {}", e.getMessage());
            } finally {
                context = null;
            }
        }
    }
    
    /**
     * Returns whether the Whisper context is currently initialized and ready for use.
     * @return true if context is initialized, false otherwise.
     */
    public boolean isContextReady() {
        return context != null;
    }
    
    /**
     * Transcribes an array of audio samples using the provided Whisper context.
     * @param ctx The initialized WhisperContext.
     * @param samples An array of normalized float audio samples (-1.0 to 1.0).
     * @return The transcribed text, or null if transcription fails.
     */
    public String transcribe(WhisperContext ctx, float[] samples) {
        BannedWords.LOGGER.info("Whisper: Transcribing {} samples", samples.length);
        if (ctx == null) {
            BannedWords.LOGGER.error("Whisper context is null, cannot transcribe");
            return null;
        }
        BannedWords.LOGGER.info("Whisper: Starting transcription of {} samples ({} seconds)", 
            samples.length, String.format("%.2f", samples.length / 16000.0f));        // Minimum audio length check (Whisper needs at least ~0.1 seconds of audio)
        if (samples.length < 1600) { // 0.1 seconds at 16kHz
            BannedWords.LOGGER.warn("Whisper: Audio segment too short ({} samples, need at least 1600)", samples.length);
            return null;
        }
        
        // For performance, limit very long audio segments
        float[] processedSamples = samples;
        if (samples.length > 80000) { // More than 5 seconds at 16kHz
            BannedWords.LOGGER.info("Whisper: Audio segment too long ({} samples), truncating to first 5 seconds for performance", samples.length);
            processedSamples = new float[80000];
            System.arraycopy(samples, 0, processedSamples, 0, 80000);
        }

        // Pad audio samples less than one second to ensure Whisper can process them
        if (processedSamples.length < 16160) { // Less than 1 second at 16kHz
            BannedWords.LOGGER.info("Whisper: Padding audio segment to 1 second ({} samples)", processedSamples.length);
            float[] paddedSamples = new float[16160];
            System.arraycopy(processedSamples, 0, paddedSamples, 0, processedSamples.length);
            processedSamples = paddedSamples;
        }

        try {
            // Fast pre-processing checks: Skip very short or very quiet audio immediately
            float audioDurationSeconds = samples.length / 16000.0f;
            
            // Skip audio that's too short to contain meaningful speech
            if (audioDurationSeconds < 0.3f) {
                BannedWords.LOGGER.info("Whisper: Audio too short ({:.2f}s) for meaningful speech, skipping", audioDurationSeconds);
                return "";
            }
            
            // Validate audio samples before processing
            if (samples == null || samples.length == 0) {
                BannedWords.LOGGER.warn("Whisper: Received null or empty samples array");
                return null;
            }
                        
            WhisperFullParams params = new WhisperFullParams();
            params.language = BannedWords.getConfig().getWhisperLanguage();
            params.nThreads = BannedWords.getConfig().getWhisperThreads();
            params.noContext = false;
            params.printProgress = false;
            params.printTimestamps = false;
            params.printRealtime = false;
            params.suppressBlank = true;
            
            // Use configuration-driven optimizations
            params.noSpeechThold = BannedWords.getConfig().getWhisperNoSpeechThreshold();
            params.temperature = BannedWords.getConfig().getWhisperTemperature();
            params.maxInitialTs = 0.0f;   // Don't look for initial timestamp
            // params.suppressNonSpeechTokens = true; // Suppress non-speech tokens
            
            // Set context windows from configuration
            if (BannedWords.getConfig().getWhisperAudioCtx() > 0) {
                params.audioCtx = BannedWords.getConfig().getWhisperAudioCtx();
            }
            params.nMaxTextCtx = BannedWords.getConfig().getWhisperTextCtx();
            params.beamSearchBeamSize = BannedWords.getConfig().getWhisperBeamSize();

            BannedWords.LOGGER.info("Whisper: Calling native transcription with {} samples", samples.length);
            long startTime = System.currentTimeMillis();
            int result = -1; // Initialize result to an invalid state
            long transcriptionTime = 0;
            try {
                result = whisperJNI.full(ctx, params, processedSamples, processedSamples.length);
                transcriptionTime = System.currentTimeMillis() - startTime;
                BannedWords.LOGGER.info("Whisper: Native call completed with result {} in {}ms", result, transcriptionTime);                
            } catch (Exception e) {
                BannedWords.LOGGER.error("Whisper: Exception during native transcription: {}", e.getMessage(), e);
                return null; // Return null on any exception
            }
            
            if (result != 0) {
                BannedWords.LOGGER.error("Whisper transcription failed with code: {} (took {}ms)", result, transcriptionTime);
                return null;
            }

            StringBuilder fullText = new StringBuilder();
            int numSegments = whisperJNI.fullNSegments(ctx);
            BannedWords.LOGGER.info("Whisper: Transcription completed in {}ms, {} segments found", transcriptionTime, numSegments);
            
            for (int i = 0; i < numSegments; i++) {
                String segmentText = whisperJNI.fullGetSegmentText(ctx, i);
                BannedWords.LOGGER.debug("Whisper: Segment {}: '{}'", i, segmentText);
                fullText.append(segmentText);
            }
            
            String result_text = fullText.toString().trim();
            BannedWords.LOGGER.info("Whisper: Final transcription: '{}'", result_text);
            return result_text;
        } catch (Exception e) {
            BannedWords.LOGGER.error("Error during Whisper transcription: {}", e.getMessage(), e);
            return null;
        }
    }
}