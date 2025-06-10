package zerouni.bannedwords;

import zerouni.bannedwords.client.MicrophoneInputHandler;
import zerouni.bannedwords.client.WhisperProcessor;
import zerouni.bannedwords.networking.BannedWordPacket;
import zerouni.bannedwords.util.NativeLibraryExtractor;
import zerouni.bannedwords.util.WhisperModelDownloader;
import io.github.givimad.whisperjni.WhisperJNI;
import io.github.givimad.whisperjni.WhisperContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Client-side entrypoint for the BannedWords mod.
 * Handles microphone input, speech-to-text processing, and sending transcripts to the server.
 */
public class BannedWordsClient implements ClientModInitializer {
    private static final Path WHISPER_MODEL_PATH = BannedWords.getConfig().getWhisperModelPath();
    private MicrophoneInputHandler microphoneInputHandler;
    private WhisperProcessor whisperProcessor;
    private Thread sttThread;
    
    private final Queue<String> pendingTranscripts = new LinkedList<>();
    private static final int MAX_PENDING_TRANSCRIPTS = 10;
    
    /**
     * Called when the client-side mod is initialized.
     */
    @Override
    public void onInitializeClient() {
        try {
            if (!NativeLibraryExtractor.isInitialized()) {
                if (NativeLibraryExtractor.isNativeFilesExtracted()) {
                    BannedWords.LOGGER.info("Native libraries already extracted, proceeding to configure library path...");
                } else {
                    BannedWords.LOGGER.warn("Native libraries not yet extracted, attempting extraction...");
                    if (!NativeLibraryExtractor.extractNativeFiles()) {
                        BannedWords.LOGGER.error("Failed to extract native libraries for whisper-jni");
                        return;
                    }
                }
            }

            NativeLibraryExtractor.configureLibraryPath();

            Path nativesDir = NativeLibraryExtractor.getNativesDirectory();
        boolean allLibsManuallyLoaded = true;

        // Generally, load foundational libraries first, then those that depend on them.
        // whisper-jni.dll likely depends on whisper.dll and ggml.dll.
        List<String> nativeLibsToLoad = NativeLibraryExtractor.getNativeLibraries();

        BannedWords.LOGGER.info("Attempting to manually load native libraries from: {}", nativesDir.toAbsolutePath());
            for (String libName : nativeLibsToLoad) {
                Path libPath = nativesDir.resolve(libName);
                if (Files.exists(libPath)) {
                    try {
                        System.load(libPath.toAbsolutePath().toString());
                        BannedWords.LOGGER.info("Successfully loaded native library: {}", libPath.toAbsolutePath());
                    } catch (UnsatisfiedLinkError e) {
                        BannedWords.LOGGER.error("Failed to load native library {}: {}. Check for missing dependencies or architecture mismatch (32/64-bit).", libPath.toAbsolutePath(), e.getMessage(), e);
                        allLibsManuallyLoaded = false;
                        break;
                    }
                } else {
                    BannedWords.LOGGER.error("Native library file not found for manual loading: {}", libPath.toAbsolutePath());
                    allLibsManuallyLoaded = false;
                    break;
                }
            }

            if (allLibsManuallyLoaded) {
                BannedWords.LOGGER.info("All specified native libraries loaded successfully via System.load().");
                try {
                    WhisperJNI.loadLibrary();
                    BannedWords.LOGGER.info("WhisperJNI library initialization seems successful after manual loads.");
                } catch (Throwable t) {
                    BannedWords.LOGGER.error("Error during WhisperJNI.loadLibrary() after manual loads: {}", t.getMessage(), t);
                    return;
                }
            } else {
                BannedWords.LOGGER.error("One or more native libraries could not be loaded manually. Whisper functionality will likely fail.");
                BannedWords.LOGGER.error("Current java.library.path: {}", System.getProperty("java.library.path"));
                return;
            }
                        
            WhisperJNI.loadLibrary();
            BannedWords.LOGGER.info("WhisperJNI library loaded successfully.");
        } catch (IOException e) {
            BannedWords.LOGGER.error("Failed to load whisper-jni library: {}", e.getMessage());
            return;
        }

        checkWhisperModelSetup();

        microphoneInputHandler = new MicrophoneInputHandler(
            BannedWords.getConfig().getAudioProcessingFrameSizeMillis(),
            BannedWords.getConfig().getMinSpeechDurationMillis(),
            BannedWords.getConfig().getSpeechDetectionThreshold(),
            BannedWords.getConfig().getSilenceTimeoutMillis()
        );
        
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            BannedWords.LOGGER.info("Player joined game, processing any queued transcripts");
            processQueuedTranscripts();
        });

        startSttThread();
    }
    
    /**
     * Checks if the Whisper model file is properly set up and downloads it if necessary.
     */
    private void checkWhisperModelSetup() {
        try {
            if (Files.exists(WHISPER_MODEL_PATH)) {
                long modelSize = Files.size(WHISPER_MODEL_PATH);
                BannedWords.LOGGER.info("Using configured Whisper model: {} ({} MB)", WHISPER_MODEL_PATH, modelSize / (1024 * 1024));
                return;
            }
            
            BannedWords.LOGGER.info("Configured model not found at: {}", WHISPER_MODEL_PATH);
            BannedWords.LOGGER.info("Attempting to download default Whisper model...");
            
            Path downloadedModel = WhisperModelDownloader.ensureDefaultModelExists();
            if (downloadedModel != null && WhisperModelDownloader.validateModel(downloadedModel)) {
                long modelSize = Files.size(downloadedModel);
                BannedWords.LOGGER.info("Successfully downloaded default Whisper model: {} ({} MB)", downloadedModel, modelSize / (1024 * 1024));
            } else {
                BannedWords.LOGGER.warn("=".repeat(80));
                BannedWords.LOGGER.warn("WHISPER MODEL DOWNLOAD FAILED!");
                BannedWords.LOGGER.warn("Expected configured location: {}", WHISPER_MODEL_PATH);
                BannedWords.LOGGER.warn("Attempted to download to: {}", FabricLoader.getInstance().getGameDir().resolve(WhisperModelDownloader.getDefaultModelName()));
                BannedWords.LOGGER.warn("");
                BannedWords.LOGGER.warn("You can manually download a model:");
                BannedWords.LOGGER.warn("1. Download the Whisper model file from:");
                BannedWords.LOGGER.warn("   {}", WhisperModelDownloader.getModelDownloadUrl(WhisperModelDownloader.getDefaultModelName()));
                BannedWords.LOGGER.warn("2. Place it in your Minecraft game directory:");
                BannedWords.LOGGER.warn("   {}", WHISPER_MODEL_PATH.getParent());
                BannedWords.LOGGER.warn("3. The file should be named exactly: {}", WHISPER_MODEL_PATH.getFileName());
                BannedWords.LOGGER.warn("");
                BannedWords.LOGGER.warn("Without a valid model file, speech-to-text will not work!");
                BannedWords.LOGGER.warn("=".repeat(80));
            }
        } catch (Exception e) {
            BannedWords.LOGGER.error("Error during Whisper model setup: {}", e.getMessage(), e);
        }
    }

    /**
     * Starts the dedicated thread for speech-to-text processing.
     * This thread continuously captures audio, applies VAD or continuous detection, and transcribes.
     */
    private void startSttThread() {
        sttThread = new Thread(() -> {
            Path modelToUse = null;
            
            if (Files.exists(WHISPER_MODEL_PATH)) {
                modelToUse = WHISPER_MODEL_PATH;
                BannedWords.LOGGER.info("Using configured Whisper model: {}", modelToUse);
            } else {
                // Try to ensure the default model exists (download if needed)
                Path defaultModel = WhisperModelDownloader.ensureDefaultModelExists();
                if (defaultModel != null && WhisperModelDownloader.validateModel(defaultModel)) {
                    modelToUse = defaultModel;
                    BannedWords.LOGGER.info("Using downloaded default Whisper model: {}", modelToUse);
                } else {
                    BannedWords.LOGGER.error("No valid Whisper model found. Configured model: {}, Default model download failed.", WHISPER_MODEL_PATH);
                    BannedWords.LOGGER.error("Please check your internet connection and ensure the model can be downloaded from Hugging Face.");
                    return;
                }
            }
            
            if (modelToUse == null) {
                BannedWords.LOGGER.error("No Whisper model available for transcription");
                return;
            }
            
            whisperProcessor = new WhisperProcessor(modelToUse);
            
            BannedWords.LOGGER.info("STT Thread starting up...");
            
            WhisperContext ctx = null;
            int initRetries = 3;
            for (int i = 0; i < initRetries; i++) {
                ctx = whisperProcessor.getContext();
                if (ctx != null) {
                    BannedWords.LOGGER.info("Whisper context initialized successfully on attempt {}/{}", i + 1, initRetries);
                    break;
                } else {
                    BannedWords.LOGGER.warn("Whisper context initialization failed, attempt {}/{}", i + 1, initRetries);
                    if (i < initRetries - 1) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        whisperProcessor.resetContext();
                    }
                }
            }
            
            if (ctx == null) {
                BannedWords.LOGGER.error("Whisper context could not be initialized after {} attempts. STT will not function.", initRetries);
                BannedWords.LOGGER.error("Please check that the Whisper model file exists in the game directory:");
                BannedWords.LOGGER.error("Expected file: {}", BannedWords.getConfig().getWhisperModelPath());
                return;
            }
            
            try (WhisperContext context = ctx) {
                microphoneInputHandler.startCapture();
                BannedWords.LOGGER.info("Microphone capture started.");

                boolean useContinuousDetection = BannedWords.getConfig().isEnableContinuousDetection();
                BannedWords.LOGGER.info("STT: Detection mode: {}", useContinuousDetection ? "Continuous" : "VAD");

                int segmentCount = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        BannedWords.LOGGER.debug("STT: Waiting for audio segment...");
                        
                        byte[] audioSegmentBytes;
                        if (useContinuousDetection) {
                            audioSegmentBytes = microphoneInputHandler.readContinuousAudioSegment(
                                BannedWords.getConfig().getContinuousSegmentDurationMillis(),
                                BannedWords.getConfig().getContinuousSegmentOverlapMillis()
                            );
                        } else {
                            audioSegmentBytes = microphoneInputHandler.readAudioSegment();
                        }
                        
                        if (audioSegmentBytes != null && audioSegmentBytes.length > 0) {
                            segmentCount++;
                            BannedWords.LOGGER.info("STT: Received audio segment #{} ({} bytes, {} seconds)", 
                                segmentCount, audioSegmentBytes.length, String.format("%.2f", audioSegmentBytes.length / (16000.0f * 2)));
                            
                            try {
                                float[] samples = convertBytesToFloats(audioSegmentBytes);
                                BannedWords.LOGGER.debug("STT: Converted to {} float samples", samples.length);

                                BannedWords.LOGGER.debug("STT: Starting transcription for segment #{}", segmentCount);
                                String transcript = whisperProcessor.transcribe(context, samples);
                                BannedWords.LOGGER.debug("STT: Transcription completed for segment #{}", segmentCount);
                                
                                if (transcript != null && !transcript.trim().isEmpty()) {
                                    BannedWords.LOGGER.info("STT: Transcribed segment #{}: \"{}\"", segmentCount, transcript);
                                    MinecraftClient.getInstance().execute(() -> {
                                        if (sendOrQueueTranscript(transcript)) {
                                            BannedWords.LOGGER.info("STT: Successfully sent transcript to server: \"{}\"", transcript);
                                        } else {
                                            BannedWords.LOGGER.warn("STT: Can't send transcript to server - client not in game, queued for later");
                                        }
                                    });
                                } else {
                                    BannedWords.LOGGER.debug("STT: Segment #{} produced no transcription", segmentCount);
                                }
                            } catch (Exception transcriptionError) {
                                BannedWords.LOGGER.error("STT: Error during transcription of segment #{}: {}", segmentCount, transcriptionError.getMessage(), transcriptionError);
                            }
                        } else {
                            BannedWords.LOGGER.debug("STT: Received null or empty audio segment");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        BannedWords.LOGGER.warn("STT thread interrupted during audio segment read.");
                        break;
                    } catch (Exception e) {
                        BannedWords.LOGGER.error("Error during STT processing loop: {}", e.getMessage(), e);
                    }
                }
            } catch (Exception e) {
                BannedWords.LOGGER.error("Error initializing Whisper context or STT thread setup: {}", e.getMessage(), e);
            } finally {
                microphoneInputHandler.stopCapture();
                BannedWords.LOGGER.info("Microphone capture stopped and resources released.");
            }
        }, "BannedWordsSTTThread");
        sttThread.setDaemon(true);
        sttThread.start();
    }

    /**
     * Converts raw audio bytes (16-bit signed PCM, little-endian) to float samples normalized to -1.0 to 1.0.
     * This format is typically expected by Whisper models.
     * @param audioBytes The byte array containing 16-bit PCM audio data.
     * @return A float array of normalized audio samples.
     */
    private float[] convertBytesToFloats(byte[] audioBytes) {
        float[] floatSamples = new float[audioBytes.length / 2]; // Assuming 16-bit PCM (2 bytes per sample)
        for (int i = 0; i < floatSamples.length; i++) {
            // Reconstruct short from two bytes (little-endian assumed for Java AudioSystem)
            short s = (short) ((audioBytes[2 * i + 1] & 0xFF) << 8 | (audioBytes[2 * i] & 0xFF));
            floatSamples[i] = s / 32768.0f; // Normalize to -1.0 to 1.0 (max short value is 32767)
        }
        return floatSamples;
    }
    
    /**
     * Adds a transcript to the queue if the client is not in game
     * @param transcript The transcript to potentially queue
     * @return true if the transcript was sent immediately, false if it was queued
     */
    private boolean sendOrQueueTranscript(String transcript) {
        MinecraftClient client = MinecraftClient.getInstance();
        
        if (client.getNetworkHandler() != null && client.player != null && client.world != null) {
            BannedWords.LOGGER.info("STT: Sending transcript to server: \"{}\"", transcript);
            ClientPlayNetworking.send(new BannedWordPacket(transcript));
            return true;
        } else {
            if (pendingTranscripts.size() < MAX_PENDING_TRANSCRIPTS) {
                pendingTranscripts.offer(transcript);
                BannedWords.LOGGER.info("STT: Client not in game. Queued transcript for later: \"{}\"", transcript);
            } else {
                BannedWords.LOGGER.warn("STT: Transcript queue full, discarding oldest transcript");
                pendingTranscripts.poll();
                pendingTranscripts.offer(transcript);
            }
            return false;
        }
    }
    
    /**
     * Processes any queued transcripts when the client joins a game
     */
    private void processQueuedTranscripts() {
        if (pendingTranscripts.isEmpty()) {
            return;
        }
        
        BannedWords.LOGGER.info("Processing {} queued transcripts", pendingTranscripts.size());
        
        while (!pendingTranscripts.isEmpty()) {
            String transcript = pendingTranscripts.peek();
            if (sendOrQueueTranscript(transcript)) {
                pendingTranscripts.poll();
            } else {
                break;
            }
        }
    }
}