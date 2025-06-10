package zerouni.bannedwords.util;

import zerouni.bannedwords.BannedWords;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;

/**
 * Utility class for downloading Whisper models from Hugging Face repository.
 * Handles downloading, verification, and caching of Whisper model files.
 */
public class WhisperModelDownloader {
    private static final String HUGGINGFACE_BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";
    private static final String DEFAULT_MODEL_NAME = "ggml-small.en-q8_0.bin";
    private static final int DOWNLOAD_TIMEOUT_MS = 30000;
    private static final int BUFFER_SIZE = 8192;
    /**
     * Downloads the default Whisper model if it doesn't exist locally.
     * @return Path to the model file, or null if download failed
     */
    public static Path ensureDefaultModelExists() {
        return ensureModelExists(DEFAULT_MODEL_NAME);
    }
    
    /**
     * Downloads the specified Whisper model if it doesn't exist locally.
     * @param modelName The name of the model file (e.g., "ggml-small.en-q8_0.bin")
     * @return Path to the model file, or null if download failed
     */
    public static Path ensureModelExists(String modelName) {
        Path modelPath = FabricLoader.getInstance().getGameDir().resolve(modelName);
        
        if (Files.exists(modelPath)) {
            BannedWords.LOGGER.info("Whisper model already exists: {}", modelPath);
            return modelPath;
        }
        
        BannedWords.LOGGER.info("Whisper model not found locally, attempting to download: {}", modelName);
        
        if (downloadModel(modelName, modelPath)) {
            return modelPath;
        } else {
            return null;
        }
    }
    
    /**
     * Downloads a Whisper model from Hugging Face repository.
     * @param modelName The name of the model file
     * @param targetPath The local path where the model should be saved
     * @return true if download was successful, false otherwise
     */
    private static boolean downloadModel(String modelName, Path targetPath) {
        String downloadUrl = HUGGINGFACE_BASE_URL + modelName;
        
        try {
            BannedWords.LOGGER.info("Downloading Whisper model from: {}", downloadUrl);
            BannedWords.LOGGER.info("Saving to: {}", targetPath);
            
            URL url = URI.create(downloadUrl).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(DOWNLOAD_TIMEOUT_MS);
            connection.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "BannedWords-Minecraft-Mod/1.0");
            
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                BannedWords.LOGGER.error("Failed to download model: HTTP {} - {}", responseCode, connection.getResponseMessage());
                return false;
            }
            
            long contentLength = connection.getContentLengthLong();
            if (contentLength > 0) {
                BannedWords.LOGGER.info("Model file size: {} MB", contentLength / (1024 * 1024));
            }
            
            Files.createDirectories(targetPath.getParent());
            
            Path tempPath = targetPath.getParent().resolve(targetPath.getFileName() + ".tmp");
            
            try (InputStream inputStream = new BufferedInputStream(connection.getInputStream());
                 OutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(tempPath))) {
                
                byte[] buffer = new byte[BUFFER_SIZE];
                long totalBytesRead = 0;
                int bytesRead;
                long lastLogTime = System.currentTimeMillis();
                
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastLogTime > 5000) {
                        if (contentLength > 0) {
                            double progress = (double) totalBytesRead / contentLength * 100;
                            BannedWords.LOGGER.info("Download progress: {:.1f}% ({} MB / {} MB)", 
                                progress, totalBytesRead / (1024 * 1024), contentLength / (1024 * 1024));
                        } else {
                            BannedWords.LOGGER.info("Downloaded: {} MB", totalBytesRead / (1024 * 1024));
                        }
                        lastLogTime = currentTime;
                    }
                }
            }
            
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            
            long finalSize = Files.size(targetPath);
            BannedWords.LOGGER.info("Successfully downloaded Whisper model: {} ({} MB)", 
                targetPath, finalSize / (1024 * 1024));
            
            return true;
            
        } catch (IOException e) {
            BannedWords.LOGGER.error("Failed to download Whisper model from {}: {}", downloadUrl, e.getMessage());
            
            try {
                Path tempPath = targetPath.getParent().resolve(targetPath.getFileName() + ".tmp");
                Files.deleteIfExists(tempPath);
                Files.deleteIfExists(targetPath);
            } catch (IOException cleanupException) {
                BannedWords.LOGGER.warn("Failed to clean up partial download: {}", cleanupException.getMessage());
            }
            
            return false;
        }
    }
    
    /**
     * Validates that a model file exists and is readable.
     * @param modelPath Path to the model file
     * @return true if the model is valid, false otherwise
     */
    public static boolean validateModel(Path modelPath) {
        if (!Files.exists(modelPath)) {
            BannedWords.LOGGER.warn("Model file does not exist: {}", modelPath);
            return false;
        }
        
        if (!Files.isReadable(modelPath)) {
            BannedWords.LOGGER.warn("Model file is not readable: {}", modelPath);
            return false;
        }
        
        try {
            long fileSize = Files.size(modelPath);
            if (fileSize < 1024 * 1024) { // Less than 1MB is probably not a valid model
                BannedWords.LOGGER.warn("Model file seems too small ({} bytes): {}", fileSize, modelPath);
                return false;
            }
            
            BannedWords.LOGGER.info("Model file validation passed: {} ({} MB)", modelPath, fileSize / (1024 * 1024));
            return true;
            
        } catch (IOException e) {
            BannedWords.LOGGER.error("Failed to validate model file {}: {}", modelPath, e.getMessage());
            return false;
        }
    }
    
    /**
     * Gets the URL for downloading a specific model from Hugging Face.
     * @param modelName The name of the model file
     * @return The download URL
     */
    public static String getModelDownloadUrl(String modelName) {
        return HUGGINGFACE_BASE_URL + modelName;
    }
    
    /**
     * Returns the default model file name.
     * @return The default model file name
     */
    public static String getDefaultModelName() {
        return DEFAULT_MODEL_NAME;
    }
}
