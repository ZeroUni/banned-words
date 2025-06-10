package zerouni.bannedwords.util;

import zerouni.bannedwords.BannedWords;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Utility class for downloading files from GitHub raw URLs.
 */
public class GitHubFileDownloader {
    private static final int DOWNLOAD_TIMEOUT_MS = 30000;
    private static final int BUFFER_SIZE = 8192;

    /**
     * Ensures that the file at targetPath exists locally. If not, downloads it from rawUrl.
     * @param rawUrl The raw GitHub URL to download the file from.
     * @param targetPath The local path to save the file.
     * @return true if the file exists locally or was downloaded successfully, false otherwise.
     */
    public static boolean ensureFileExists(String rawUrl, Path targetPath) {
        if (Files.exists(targetPath)) {
            BannedWords.LOGGER.info("File already exists locally: {}", targetPath);
            return true;
        }
        return downloadFile(rawUrl, targetPath);
    }

    private static boolean downloadFile(String rawUrl, Path targetPath) {
        try {
            BannedWords.LOGGER.info("Downloading file from: {}", rawUrl);
            URL url = URI.create(rawUrl).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(DOWNLOAD_TIMEOUT_MS);
            connection.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "BannedWords-Minecraft-Mod/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                BannedWords.LOGGER.error("Failed to download file: HTTP {} - {}", responseCode, connection.getResponseMessage());
                return false;
            }

            Files.createDirectories(targetPath.getParent());
            Path tempPath = targetPath.getParent().resolve(targetPath.getFileName() + ".tmp");

            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new BufferedOutputStream(Files.newOutputStream(tempPath))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            BannedWords.LOGGER.info("Successfully downloaded file to: {}", targetPath);
            return true;
        } catch (IOException e) {
            BannedWords.LOGGER.error("Error downloading file from {}: {}", rawUrl, e.getMessage());
            return false;
        }
    }
}
