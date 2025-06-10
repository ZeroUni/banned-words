package zerouni.bannedwords.util;

import zerouni.bannedwords.BannedWords;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;

/**
 * Utility class for extracting native libraries and model files from the JAR to the file system.
 * This is required for whisper-jni to function properly as it needs access to native DLL files.
 */
public class NativeLibraryExtractor {
    private static final Path NATIVES_DIR = FabricLoader.getInstance().getGameDir().resolve("banned-words-natives");
    private static final List<String> NATIVE_LIBRARIES = Arrays.asList(
        "ggml.dll",
        "ggml-cpu.dll",
        "ggml-base.dll",
        "whisper.dll",
        "whisper-jni_full.dll",
        "whisper-jni.dll"
    );    private static boolean initialized = false;
    
    /**
     * Extracts all native libraries from the JAR to the game directory.
     * This should be called once during mod initialization.
     * Note: Model files are no longer extracted here - they are downloaded on-demand.
     * @return true if extraction was successful, false otherwise
     */
    public static boolean extractNativeFiles() {
        if (initialized) {
            return true;
        }

        try {
            if (!Files.exists(NATIVES_DIR)) {
                Files.createDirectories(NATIVES_DIR);
                BannedWords.LOGGER.info("Created natives directory: {}", NATIVES_DIR);
            }

            boolean allSuccessful = true;

            for (String library : NATIVE_LIBRARIES) {
                Path targetPath = NATIVES_DIR.resolve(library);
                
                boolean extracted = extractFileFromJar("natives/" + library, targetPath);
                
                if (!extracted) {
                    Path developmentSource = FabricLoader.getInstance().getGameDir().resolve(library);
                    if (Files.exists(developmentSource)) {
                        try {
                            Files.copy(developmentSource, targetPath, StandardCopyOption.REPLACE_EXISTING);
                            BannedWords.LOGGER.info("Copied native library from development directory: {} -> {}", developmentSource, targetPath);
                            extracted = true;
                        } catch (IOException e) {
                            BannedWords.LOGGER.error("Failed to copy native library from development directory: {}", e.getMessage());
                        }
                    }
                }
                
                if (!extracted) {
                    BannedWords.LOGGER.warn("Could not extract or copy native library: {}", library);
                    allSuccessful = false;
                }
            }

            if (allSuccessful) {
                addToLibraryPath(NATIVES_DIR.toString());
                BannedWords.LOGGER.info("Added natives directory to java.library.path: {}", NATIVES_DIR);
            }

            initialized = allSuccessful;
            return allSuccessful;

        } catch (Exception e) {
            BannedWords.LOGGER.error("Failed to extract native files: {}", e.getMessage(), e);
            return false;
        }
    }    /**
     * Checks if the native libraries have been extracted previously.
     * @return true if the libraries are extracted, false otherwise
     */
    public static boolean isNativeFilesExtracted() {
        if (initialized) {
            return true;
        }

        if (!Files.exists(NATIVES_DIR)) {
            BannedWords.LOGGER.warn("Natives directory does not exist: {}", NATIVES_DIR);
            return false;
        }

        for (String library : NATIVE_LIBRARIES) {
            Path targetPath = NATIVES_DIR.resolve(library);
            if (!Files.exists(targetPath)) {
                BannedWords.LOGGER.warn("Missing native library: {}", targetPath);
                return false;
            }
        }

        addToLibraryPath(NATIVES_DIR.toString());

        initialized = true;
        return true;
    }

    /**
     * Extracts a single file from the JAR to the target path.
     * @param resourcePath Path inside the JAR (e.g., "natives/whisper.dll")
     * @param targetPath Target file path on the file system
     * @return true if extraction was successful, false otherwise
     */
    private static boolean extractFileFromJar(String resourcePath, Path targetPath) {
        try (InputStream inputStream = NativeLibraryExtractor.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                BannedWords.LOGGER.debug("Resource not found in JAR: {}", resourcePath);
                return false;
            }

            // Skip extraction if file already exists and has the same size
            if (Files.exists(targetPath)) {
                try {
                    long existingSize = Files.size(targetPath);
                    long jarSize = inputStream.available(); // Approximate size
                    if (existingSize > 0 && jarSize > 0 && Math.abs(existingSize - jarSize) < 1024) {
                        BannedWords.LOGGER.debug("Skipping extraction of {} (already exists)", targetPath.getFileName());
                        return true;
                    }
                } catch (IOException e) {
                    // If we can't check, proceed with extraction
                }
            }

            Files.createDirectories(targetPath.getParent());

            try (OutputStream outputStream = Files.newOutputStream(targetPath)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }

            BannedWords.LOGGER.debug("Extracted {} to {}", resourcePath, targetPath);
            return true;

        } catch (Exception e) {
            BannedWords.LOGGER.error("Failed to extract {} to {}: {}", resourcePath, targetPath, e.getMessage());
            return false;
        }
    }

    /**
     * Adds a directory to the java.library.path system property.
     * This is required for JNI libraries to be found at runtime.
     * @param path The path to add
     */
    private static void addToLibraryPath(String path) {
        try {
            String existingPath = System.getProperty("java.library.path");
            if (existingPath == null || existingPath.isEmpty()) {
                System.setProperty("java.library.path", path);
            } else if (!existingPath.contains(path)) {
                System.setProperty("java.library.path", existingPath + File.pathSeparator + path);
            }

            // Force the ClassLoader to re-read the java.library.path
            // This is a bit of a hack, but necessary for runtime library path changes
            try {
                java.lang.reflect.Field fieldSysPath = ClassLoader.class.getDeclaredField("sys_paths");
                fieldSysPath.setAccessible(true);
                fieldSysPath.set(null, null);
            } catch (Exception e) {
                BannedWords.LOGGER.debug("Could not reset ClassLoader sys_paths, library may not be found: {}", e.getMessage());
            }

        } catch (Exception e) {
            BannedWords.LOGGER.error("Failed to add path to java.library.path: {}", e.getMessage());
        }
    }    /**
     * Configures the library path for JNI loading.
     * This is a public wrapper for addToLibraryPath.
     */
    public static void configureLibraryPath() {
        if (Files.exists(NATIVES_DIR)) {
            addToLibraryPath(NATIVES_DIR.toString());
            BannedWords.LOGGER.debug("Configured library path to include: {}", NATIVES_DIR);
        } else {
            BannedWords.LOGGER.warn("Natives directory does not exist, cannot configure library path: {}", NATIVES_DIR);
        }
    }

    /**
     * Returns the default model file name.
     * @return The default model file name
     * @deprecated Model downloading is now handled by WhisperModelDownloader
     */
    @Deprecated
    public static String getDefaultModelFileName() {
        return "ggml-small.en-q8_0.bin";
    }

    /**
     * Returns the default model file full path.
     * @return The full path to the default model file
     * @deprecated Model downloading is now handled by WhisperModelDownloader
     */
    @Deprecated
    public static Path getDefaultModelFilePath() {
        return FabricLoader.getInstance().getGameDir().resolve("ggml-small.en-q8_0.bin");
    }

    /**
     * Returns the default model path (alias for getDefaultModelFilePath for compatibility).
     * @return The full path to the default model file
     * @deprecated Model downloading is now handled by WhisperModelDownloader
     */
    @Deprecated
    public static Path getDefaultModelPath() {
        return getDefaultModelFilePath();
    }

    /**
     * Returns the list of native libraries that will be extracted.
     * @return List of native library file names
     */
    public static List<String> getNativeLibraries() {
        return NATIVE_LIBRARIES;
    }

    /**
     * Returns the path where native libraries are extracted.
     * @return Path to the natives directory
     */
    public static Path getNativesDirectory() {
        return NATIVES_DIR;
    }

    /**
     * Returns whether the native files have been successfully extracted and initialized.
     * @return true if initialized, false otherwise
     */
    public static boolean isInitialized() {
        return initialized;
    }
}
