package zerouni.bannedwords.client;

import zerouni.bannedwords.BannedWords;
import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Handles capturing audio input from the default microphone and performs Voice Activity Detection (VAD).
 * Provides segmented audio chunks representing speech for transcription.
 */
public class MicrophoneInputHandler {
    private TargetDataLine microphone;
    private AudioFormat audioFormat;
    private volatile boolean isCapturing;
    private BlockingQueue<byte[]> audioFrameQueue;
    private Thread captureThread;

    private final int frameSizeBytes; // Size of a single audio frame in bytes
    private final int minSpeechFrames; // Minimum number of consecutive speech frames to consider it speech
    private final int silenceTimeoutFrames; // Number of consecutive silence frames to end an utterance
    private final double speechThreshold; // RMS energy threshold for speech detection
    private final int maxSegmentSizeFrames; // Max frames in a single segment to prevent excessively long segments
    private final int bytesPerSample; // 2 for 16-bit audio
    private final float sampleRate; // 16000.0f
    
    // Sliding window VAD fields
    private final int preSpeechWindowFrames; // Number of frames to keep before speech detection
    private final BlockingQueue<byte[]> preSpeechBuffer; // Circular buffer for pre-speech audio
    private volatile long lastSegmentEndTime; // Timestamp when last segment ended
    private final int minGapBetweenSegmentsMs; // Minimum gap to avoid overlapping segments

    /**
     * Constructs a MicrophoneInputHandler with VAD parameters.
     * Uses default sliding window parameters: 300ms pre-speech window, 100ms minimum gap between segments.
     * @param frameSizeMillis The duration of each audio frame in milliseconds for VAD analysis.
     * @param minSpeechDurationMillis The minimum duration of detected speech to consider it an utterance.
     * @param speechDetectionThreshold The RMS energy threshold (0.0-1.0) above which a frame is considered speech.
     * @param silenceTimeoutMillis The maximum duration of silence (in ms) before an utterance is considered ended.
     */
    public MicrophoneInputHandler(int frameSizeMillis, int minSpeechDurationMillis, double speechDetectionThreshold, int silenceTimeoutMillis) {
        this(frameSizeMillis, minSpeechDurationMillis, speechDetectionThreshold, silenceTimeoutMillis, 300, 100);
    }
    
    /**
     * Constructs a MicrophoneInputHandler with VAD and sliding window parameters.
     * @param frameSizeMillis The duration of each audio frame in milliseconds for VAD analysis.
     * @param minSpeechDurationMillis The minimum duration of detected speech to consider it an utterance.
     * @param speechDetectionThreshold The RMS energy threshold (0.0-1.0) above which a frame is considered speech.
     * @param silenceTimeoutMillis The maximum duration of silence (in ms) before an utterance is considered ended.
     * @param preSpeechWindowMillis Duration of audio to capture before speech detection (sliding window).
     * @param minGapBetweenSegmentsMs Minimum gap between segments to avoid overlapping audio.
     */
    public MicrophoneInputHandler(int frameSizeMillis, int minSpeechDurationMillis, double speechDetectionThreshold, 
                                int silenceTimeoutMillis, int preSpeechWindowMillis, int minGapBetweenSegmentsMs) {
        // Standard format for Whisper: 16kHz, 16-bit PCM, mono
        this.audioFormat = new AudioFormat(16000.0f, 16, 1, true, false); // Sample Rate, Bits, Channels, Signed, LittleEndian
        this.sampleRate = audioFormat.getSampleRate();
        this.bytesPerSample = audioFormat.getSampleSizeInBits() / 8;
        this.frameSizeBytes = (int) (audioFormat.getFrameRate() * audioFormat.getFrameSize() * frameSizeMillis / 1000);
        this.audioFrameQueue = new ArrayBlockingQueue<>(500);

        this.minSpeechFrames = (int) (minSpeechDurationMillis / frameSizeMillis);
        this.silenceTimeoutFrames = (int) (silenceTimeoutMillis / frameSizeMillis);
        this.speechThreshold = speechDetectionThreshold;
        this.maxSegmentSizeFrames = (int) (audioFormat.getSampleRate() * 10 / frameSizeMillis);
        
        this.preSpeechWindowFrames = (int) (preSpeechWindowMillis / frameSizeMillis);
        this.preSpeechBuffer = new ArrayBlockingQueue<>(preSpeechWindowFrames + 10);
        this.lastSegmentEndTime = 0;
        this.minGapBetweenSegmentsMs = minGapBetweenSegmentsMs;
        
        BannedWords.LOGGER.info("MicrophoneInputHandler initialized with sliding window VAD: preSpeechWindow={}ms ({}frames), minGap={}ms", 
            preSpeechWindowMillis, preSpeechWindowFrames, minGapBetweenSegmentsMs);
    }

    /**
     * Starts capturing audio from the default microphone in a dedicated thread.
     */
    public void startCapture() {
        if (isCapturing) return;

        try {
            BannedWords.LOGGER.info("Available audio input devices (Mixers):");
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                Line.Info[] targetLineInfo = mixer.getTargetLineInfo(new DataLine.Info(TargetDataLine.class, audioFormat));
                if (targetLineInfo.length > 0) {
                    BannedWords.LOGGER.info("  - Mixer: {} ({}), Supports TargetDataLine: Yes", mixerInfo.getName(), mixerInfo.getDescription());
                } else {
                    BannedWords.LOGGER.info("  - Mixer: {} ({}), Supports TargetDataLine: No", mixerInfo.getName(), mixerInfo.getDescription());
                }
            }

            DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat);
            if (!AudioSystem.isLineSupported(info)) {
                BannedWords.LOGGER.error("Default microphone line not supported with format: {}. Trying to find a suitable one.", audioFormat);
                // Attempt to find a mixer that supports the format
                Mixer.Info selectedMixerInfo = null;
                for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                    Mixer mixer = AudioSystem.getMixer(mixerInfo);
                    if (mixer.isLineSupported(info)) {
                        selectedMixerInfo = mixerInfo;
                        BannedWords.LOGGER.info("Found a suitable mixer: {}", selectedMixerInfo.getName());
                        microphone = (TargetDataLine) mixer.getLine(info);
                        break;
                    }
                }
                if (microphone == null) {
                    BannedWords.LOGGER.error("No suitable microphone line found that supports the audio format: {}", audioFormat);
                    return;
                }
            } else {
                // Default line is supported
                microphone = (TargetDataLine) AudioSystem.getLine(info);
                BannedWords.LOGGER.info("Using default system microphone line. The specific mixer providing this line can be inferred from the 'Available audio input devices' list printed earlier if needed.");
            }
            
            BannedWords.LOGGER.info("Selected microphone line: {}", microphone.getLineInfo().toString());
            microphone.open(audioFormat);
            microphone.start();
            isCapturing = true;
            BannedWords.LOGGER.info("Microphone started capture with format: {}. Line: {}", audioFormat, microphone.getLineInfo().toString());

            captureThread = new Thread(this::captureAudioLoop, "MicrophoneCaptureThread");
            captureThread.setDaemon(true);
            captureThread.start();
        } catch (LineUnavailableException e) {
            BannedWords.LOGGER.error("Microphone line unavailable: {}", e.getMessage());
            isCapturing = false;
        }
    }    
    
    /**
     * The main loop for the audio capture thread.
     * Reads audio frames from the microphone and puts them into a queue.
     */
    private void captureAudioLoop() {
        byte[] buffer = new byte[frameSizeBytes];
        int frameCount = 0;
        while (isCapturing && !Thread.currentThread().isInterrupted()) {
            int bytesRead = microphone.read(buffer, 0, buffer.length);
            if (bytesRead > 0) {
                try {
                    byte[] frameCopy = new byte[bytesRead];
                    System.arraycopy(buffer, 0, frameCopy, 0, bytesRead);
                    audioFrameQueue.put(frameCopy);
                    frameCount++;
                    if (frameCount % 50 == 0) {
                        BannedWords.LOGGER.debug("Audio capture: {} frames captured, queue size: {}, latest frame bytes: {}", frameCount, audioFrameQueue.size(), bytesRead);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }    
    
    /**
     * Adds a frame to the pre-speech buffer, maintaining the sliding window size.
     * @param frame The audio frame to add to the buffer.
     */
    private void addToPreSpeechBuffer(byte[] frame) {
        // If buffer is full, remove oldest frame
        if (preSpeechBuffer.size() >= preSpeechWindowFrames) {
            preSpeechBuffer.poll();
        }
        
        // Add new frame (make a copy to avoid reference issues)
        byte[] frameCopy = new byte[frame.length];
        System.arraycopy(frame, 0, frameCopy, 0, frame.length);
        preSpeechBuffer.offer(frameCopy);
    }    
    
    /**
     * Reads and processes audio frames to detect and return a complete speech segment.
     * This method includes a sliding window to capture audio before speech detection,
     * preventing cut-off words while avoiding overlap between consecutive segments.
     * @return A byte array containing the detected speech segment, or null if capture stops.
     * @throws InterruptedException If the thread is interrupted while waiting for audio frames.
     */
    public byte[] readAudioSegment() throws InterruptedException {
        ByteArrayOutputStream currentSegmentBuffer = new ByteArrayOutputStream();
        int speechFrameCount = 0;
        int silenceFrameCount = 0;
        boolean inSpeech = false;
        long segmentStartMillis = System.currentTimeMillis();
        int totalFramesProcessed = 0;
        double maxRmsEnergy = 0.0;
        double avgRmsEnergy = 0.0;
        boolean preSpeechBufferUsed = false;

        BannedWords.LOGGER.debug("STT: Starting sliding window audio segment detection. Threshold: {}, MinSpeechFrames: {}, SilenceTimeoutFrames: {}, PreSpeechFrames: {}", 
            speechThreshold, minSpeechFrames, silenceTimeoutFrames, preSpeechWindowFrames);

        while (isCapturing && !Thread.currentThread().isInterrupted()) {
            byte[] frame = audioFrameQueue.poll(100, TimeUnit.MILLISECONDS);
            if (frame == null) {
                if (inSpeech && System.currentTimeMillis() - segmentStartMillis > BannedWords.getConfig().getMinSpeechDurationMillis() + BannedWords.getConfig().getSilenceTimeoutMillis()) {
                    BannedWords.LOGGER.debug("STT: Segment timed out (no new frames). Max RMS: {}, Avg RMS: {}, Frames: {}",
                        String.format("%.6f", maxRmsEnergy), String.format("%.6f", avgRmsEnergy / totalFramesProcessed), totalFramesProcessed);
                    lastSegmentEndTime = System.currentTimeMillis();
                    return currentSegmentBuffer.toByteArray();
                }
                continue;
            }

            totalFramesProcessed++;
            double rmsEnergy = calculateRms(frame);
            avgRmsEnergy += rmsEnergy;
            maxRmsEnergy = Math.max(maxRmsEnergy, rmsEnergy);
            boolean frameIsSpeech = rmsEnergy > speechThreshold;
            
            if (totalFramesProcessed % 50 == 0) {
                BannedWords.LOGGER.debug("STT: Frame {}: RMS={}, Speech={}, InSpeech={}, SpeechFrames={}, SilenceFrames={}, QueueSize={}, PreSpeechBuffer={}", 
                    totalFramesProcessed, String.format("%.6f", rmsEnergy), frameIsSpeech, inSpeech, speechFrameCount, silenceFrameCount, audioFrameQueue.size(), preSpeechBuffer.size());
            }

            if (frameIsSpeech) {
                silenceFrameCount = 0;
                speechFrameCount++;
                if (!inSpeech) {
                    // Check if enough time has passed since last segment to avoid overlap
                    long timeSinceLastSegment = System.currentTimeMillis() - lastSegmentEndTime;
                    if (timeSinceLastSegment >= minGapBetweenSegmentsMs || lastSegmentEndTime == 0) {
                        inSpeech = true;
                        segmentStartMillis = System.currentTimeMillis();
                        currentSegmentBuffer.reset();
                        
                        // Include pre-speech buffer if available and not already used
                        if (!preSpeechBufferUsed && !preSpeechBuffer.isEmpty()) {
                            byte[] preSpeechFrame;
                            while ((preSpeechFrame = preSpeechBuffer.poll()) != null) {
                                try {
                                    currentSegmentBuffer.write(preSpeechFrame);
                                } catch (Exception e) {
                                    BannedWords.LOGGER.error("Error writing pre-speech frame to segment buffer: {}", e.getMessage(), e);
                                }
                            }
                            preSpeechBufferUsed = true;
                            BannedWords.LOGGER.debug("STT: Speech detected! Starting new segment with {} pre-speech frames. RMS: {}", 
                                preSpeechBuffer.size(), String.format("%.6f", rmsEnergy));
                        } else {
                            BannedWords.LOGGER.debug("STT: Speech detected! Starting new segment (no pre-speech buffer). RMS: {}", String.format("%.6f", rmsEnergy));
                        }
                    } else {
                        // Too close to previous segment, skip this speech detection
                        BannedWords.LOGGER.debug("STT: Speech detected but too close to previous segment ({}ms < {}ms gap), ignoring", 
                            timeSinceLastSegment, minGapBetweenSegmentsMs);
                        speechFrameCount = 0;
                    }
                }
            } else {
                silenceFrameCount++;
                if (inSpeech && silenceFrameCount >= silenceTimeoutFrames) {
                    // Check if segment duration (in bytes) meets minimum requirement
                    int totalSegmentFrames = currentSegmentBuffer.size() / frameSizeBytes;
                    if (totalSegmentFrames >= minSpeechFrames) {
                        BannedWords.LOGGER.debug("STT: Segment ended (silence timeout). Length: {} frames, Max RMS: {}, Avg RMS: {}", 
                            totalSegmentFrames, String.format("%.6f", maxRmsEnergy), String.format("%.6f", avgRmsEnergy / totalFramesProcessed));
                        lastSegmentEndTime = System.currentTimeMillis();
                        return currentSegmentBuffer.toByteArray();
                    } else {
                        // Too short to be a valid utterance, reset
                        inSpeech = false;
                        speechFrameCount = 0;
                        currentSegmentBuffer.reset();
                        preSpeechBufferUsed = false;
                        BannedWords.LOGGER.debug("STT: Segment too short ({} frames < {} required), resetting. Max RMS was: {}", 
                            totalSegmentFrames, minSpeechFrames, String.format("%.6f", maxRmsEnergy));
                        maxRmsEnergy = 0.0;
                        avgRmsEnergy = 0.0;
                        totalFramesProcessed = 0;
                    }
                }
            }

            // Manage pre-speech buffer during non-speech periods
            if (!inSpeech) {
                addToPreSpeechBuffer(frame);
            }

            // Append frame to current segment buffer if in speech or just transitioned out of speech
            if (inSpeech || (!frameIsSpeech && silenceFrameCount < silenceTimeoutFrames)) {
                try {
                    currentSegmentBuffer.write(frame);
                    if (currentSegmentBuffer.size() / frameSizeBytes >= maxSegmentSizeFrames) {
                        // Prevent segments from growing indefinitely
                        BannedWords.LOGGER.warn("STT: Segment reached max size. Force-ending utterance. Max RMS: {}, Avg RMS: {}", 
                            String.format("%.6f", maxRmsEnergy), String.format("%.6f", avgRmsEnergy / totalFramesProcessed));
                        lastSegmentEndTime = System.currentTimeMillis();
                        return currentSegmentBuffer.toByteArray();
                    }
                } catch (Exception e) {
                    BannedWords.LOGGER.error("Error writing audio frame to segment buffer: {}", e.getMessage(), e);
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Reads audio data in a continuous mode with a sliding window approach.
     * This method creates overlapping segments without relying on VAD.
     * @param segmentDurationMillis Duration of each segment in milliseconds
     * @param overlapMillis Overlap between segments in milliseconds
     * @return A byte array containing the audio segment, or null if capture stops.
     * @throws InterruptedException If the thread is interrupted while waiting for audio frames.
     */
    public byte[] readContinuousAudioSegment(int segmentDurationMillis, int overlapMillis) throws InterruptedException {
        int segmentFrames = (int) (segmentDurationMillis / (frameSizeBytes / audioFormat.getFrameSize() / audioFormat.getFrameRate() * 1000));
        int overlapFrames = (int) (overlapMillis / (frameSizeBytes / audioFormat.getFrameSize() / audioFormat.getFrameRate() * 1000));
        
        ByteArrayOutputStream segmentBuffer = new ByteArrayOutputStream();
        int framesCollected = 0;
        long segmentStartMillis = System.currentTimeMillis();
        
        BannedWords.LOGGER.debug("STT: Starting continuous segment collection. Target frames: {}, Overlap frames: {}", segmentFrames, overlapFrames);
        
        while (isCapturing && !Thread.currentThread().isInterrupted() && framesCollected < segmentFrames) {
            byte[] frame = audioFrameQueue.poll(100, TimeUnit.MILLISECONDS);
            if (frame == null) {
                if (System.currentTimeMillis() - segmentStartMillis > segmentDurationMillis + 1000) {
                    BannedWords.LOGGER.debug("STT: Continuous segment timed out waiting for frames");
                    break;
                }
                continue;
            }
            
            try {
                segmentBuffer.write(frame);
                framesCollected++;
                
                if (framesCollected % 100 == 0) {
                    BannedWords.LOGGER.debug("STT: Continuous segment progress: {}/{} frames, queue size: {}", 
                        framesCollected, segmentFrames, audioFrameQueue.size());
                }
            } catch (Exception e) {
                BannedWords.LOGGER.error("Error writing frame to continuous segment buffer: {}", e.getMessage(), e);
                return null;
            }
        }
        if (framesCollected > 0) {
            byte[] result = segmentBuffer.toByteArray();
            long actualDurationMillis = System.currentTimeMillis() - segmentStartMillis;
            BannedWords.LOGGER.debug("STT: Continuous segment complete: {} frames, {} bytes, {}ms duration", 
                framesCollected, result.length, actualDurationMillis);
            return result;
        }
        
        return null;
    }

    /**
     * Calculates the Root Mean Square (RMS) energy of an audio frame.
     * @param audioFrame The byte array representing a single audio frame (16-bit PCM).
     * @return The RMS energy normalized between 0.0 and 1.0.
     */
    private double calculateRms(byte[] audioFrame) {
        long sumOfSquares = 0;
        ByteBuffer buffer = ByteBuffer.wrap(audioFrame).order(ByteOrder.LITTLE_ENDIAN); // Assume little-endian for PCM
        for (int i = 0; i < audioFrame.length / bytesPerSample; i++) {
            short sample = buffer.getShort();
            sumOfSquares += (long) sample * sample;
        }
        double rms = Math.sqrt((double) sumOfSquares / (audioFrame.length / bytesPerSample));
        return rms / 32768.0; // Normalize by max possible 16-bit short value
    }    /**
     * Stops capturing audio from the microphone and closes the line.
     * Also clears the pre-speech buffer to prevent stale data.
     */
    public void stopCapture() {
        isCapturing = false;
        if (captureThread != null) {
            captureThread.interrupt();
            try {
                captureThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (microphone != null) {
            microphone.stop();
            microphone.close();
            BannedWords.LOGGER.info("Microphone capture stopped and line closed.");
        }
        
        // Clear pre-speech buffer to prevent stale audio data
        preSpeechBuffer.clear();
        lastSegmentEndTime = 0;
        BannedWords.LOGGER.debug("Pre-speech buffer cleared on capture stop.");
    }

    /**
     * Checks if the microphone is currently capturing.
     * @return true if capturing, false otherwise.
     */
    public boolean isCapturing() {
        return isCapturing;
    }
}