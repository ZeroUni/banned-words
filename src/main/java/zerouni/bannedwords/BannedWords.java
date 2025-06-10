package zerouni.bannedwords;

import zerouni.bannedwords.config.BannedWordsConfig;
import zerouni.bannedwords.networking.BannedWordPacket;
import zerouni.bannedwords.server.BannedWordDetector;
import zerouni.bannedwords.server.PunishmentManager;
import zerouni.bannedwords.server.TickScheduler;
import zerouni.bannedwords.util.NativeLibraryExtractor;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.util.Identifier;
import net.minecraft.sound.SoundEvent;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main common entrypoint for the BannedWords Fabric mod.
 * Handles initialization of shared components like configuration.
 */
public class BannedWords implements ModInitializer {
    public static final String MOD_ID = "banned-words";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static BannedWordsConfig config;
    private static BannedWordDetector bannedWordDetector;
    private static PunishmentManager punishmentManager;
    private static TickScheduler tickScheduler;

    /**
     * Called when the mod is initialized.
     * This method runs on both client and server environments.
     */
    @Override
    public void onInitialize() {
        LOGGER.info("Initializing BannedWords Mod (Common)!");
        
        // Extract native libraries during initialization (models are now downloaded on-demand)
        LOGGER.info("Extracting native libraries...");
        if (NativeLibraryExtractor.extractNativeFiles()) {
            LOGGER.info("Native libraries extracted successfully");
        } else {
            LOGGER.error("Failed to extract native libraries - whisper functionality may not work");
        }
        
        config = BannedWordsConfig.load();
        
        LOGGER.info("Setting up logging level from config: {}", config.getLogLevel());
        configureLogLevel(config.getLogLevel());
        LOGGER.debug("BannedWords mod initialized with config: {}", config);
        
        PayloadTypeRegistry.playC2S().register(BannedWordPacket.ID, BannedWordPacket.CODEC);
        
        // Register sound events during mod initialization (before registry freezes)
        for (int i = 0; i < config.getAudioClipIds().size(); i++) {
            String audioClipId = config.getAudioClipIds().get(i);
            Identifier soundId = Identifier.of(MOD_ID, audioClipId);
            SoundEvent soundEvent = SoundEvent.of(soundId);
            Registry.register(Registries.SOUND_EVENT, soundId, soundEvent);
            LOGGER.info("Registered sound event: {}", soundId);
        }
        
        // Initialize server components when server starts (works for both single-player and dedicated servers)
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            LOGGER.info("Server starting - initializing BannedWords server components");
            initializeServerComponents();
        });
    }

    /**
     * Initialize server-side components for both integrated and dedicated servers
     */
    private static void initializeServerComponents() {
        tickScheduler = new TickScheduler();
        
        bannedWordDetector = new BannedWordDetector(
            config.getBannedWords(),
            config.getGracePeriodSeconds()
        );
        punishmentManager = new PunishmentManager(
            config.getExplosionPower(),
            config.getAudioClipIds(),
            config.getAudioClipDurationsMillis(),
            tickScheduler
        );        
        
        ServerPlayNetworking.registerGlobalReceiver(BannedWordPacket.ID, (payload, context) -> {
            String transcript = payload.transcript();
            LOGGER.info("Server: Received transcript from {}: \"{}\"", context.player().getName().getString(), transcript);
            
            context.server().execute(() -> {
                LOGGER.debug("Server: Processing transcript on main thread: \"{}\"", transcript);
                if (bannedWordDetector.checkAndPunish(context.player(), transcript)) {
                    String detectedWord = bannedWordDetector.getLastDetectedWord();
                    LOGGER.info("Server: Applying punishment for banned word '{}' from player {}", detectedWord, context.player().getName().getString());
                    punishmentManager.applyPunishment(context.player(), detectedWord);
                } else {
                    LOGGER.info("Server: No banned words detected in transcript from {}: \"{}\"", context.player().getName().getString(), transcript);
                }
            });
        });

        LOGGER.info("BannedWords Server components initialized with TickScheduler.");
    }

    /**
     * Retrieves the current configuration for the mod.
     * @return The BannedWordsConfig instance.
     */
    public static BannedWordsConfig getConfig() {
        return config;
    }
    
    /**
     * Gets the tick scheduler instance.
     * @return The TickScheduler instance, or null if not initialized
     */
    public static TickScheduler getTickScheduler() {
        return tickScheduler;
    }

    /**
     * Called from the server tick mixin to tick the scheduler.
     * This method is safe to call even if the scheduler is not initialized.
     */
    public static void tickScheduler() {
        if (tickScheduler != null) {
            tickScheduler.tick();
        }
    }
    
    /**
     * Configures the logging level for the BannedWords mod logger.
     * @param logLevelString The logging level as a string (e.g., "DEBUG", "INFO", "WARN", "ERROR")
     */
    private static void configureLogLevel(String logLevelString) {
        try {
            Level logLevel = Level.valueOf(logLevelString.toUpperCase());
            
            Configurator.setLevel(MOD_ID, logLevel);
            
            LOGGER.info("BannedWords logging level set to: {}", logLevel);
            LOGGER.debug("Debug logging is now enabled for BannedWords mod");
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid log level '{}' specified in config, using default level. Valid levels are: DEBUG, INFO, WARN, ERROR", logLevelString);
        } catch (Exception e) {
            LOGGER.warn("Failed to configure log level: {}", e.getMessage());
        }
    }
}