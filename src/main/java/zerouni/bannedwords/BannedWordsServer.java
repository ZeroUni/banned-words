package zerouni.bannedwords;

import zerouni.bannedwords.networking.BannedWordPacket;
import zerouni.bannedwords.server.BannedWordDetector;
import zerouni.bannedwords.server.PunishmentManager;
import zerouni.bannedwords.server.TickScheduler;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.util.Identifier;
import net.minecraft.sound.SoundEvent;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

/**
 * Server-side entrypoint for the BannedWords mod.
 * Handles receiving transcripts from clients and applying punishments.
 */
public class BannedWordsServer implements DedicatedServerModInitializer {
    private static BannedWordDetector bannedWordDetector;
    private static PunishmentManager punishmentManager;
    private static TickScheduler tickScheduler;

    /**
     * Called when the server-side mod is initialized.
     */
    @Override
    public void onInitializeServer() {
        // Initialize the tick scheduler
        tickScheduler = new TickScheduler();
        
        bannedWordDetector = new BannedWordDetector(
            BannedWords.getConfig().getBannedWords(),
            BannedWords.getConfig().getGracePeriodSeconds()
        );
        punishmentManager = new PunishmentManager(
            BannedWords.getConfig().getExplosionPower(),
            BannedWords.getConfig().getAudioClipIds(),
            BannedWords.getConfig().getAudioClipDurationsMillis(),
            tickScheduler  // Pass the scheduler to PunishmentManager
        );        
        
        // Register server-side packet handler for incoming transcripts
        ServerPlayNetworking.registerGlobalReceiver(BannedWordPacket.ID, (payload, context) -> {
            String transcript = payload.transcript();
            BannedWords.LOGGER.info("Server: Received transcript from {}: \"{}\"", context.player().getName().getString(), transcript);
            
            // Schedule the processing on the main server thread to avoid concurrency issues with Minecraft's world
            context.server().execute(() -> {
                BannedWords.LOGGER.debug("Server: Processing transcript on main thread: \"{}\"", transcript);
                if (bannedWordDetector.checkAndPunish(context.player(), transcript)) {
                    String detectedWord = bannedWordDetector.getLastDetectedWord();
                    BannedWords.LOGGER.info("Server: Applying punishment for banned word '{}' from player {}", detectedWord, context.player().getName().getString());
                    punishmentManager.applyPunishment(context.player(), detectedWord);
                } else {
                    BannedWords.LOGGER.info("Server: No banned words detected in transcript from {}: \"{}\"", context.player().getName().getString(), transcript);
                }
            });
        });

        // Set up sound events
        for (int i = 0; i < BannedWords.getConfig().getAudioClipIds().size(); i++) {
            String audioClipId = BannedWords.getConfig().getAudioClipIds().get(i);
            Identifier soundId = Identifier.of(BannedWords.MOD_ID, audioClipId);
            SoundEvent soundEvent = SoundEvent.of(soundId);
            Registry.register(Registries.SOUND_EVENT, soundId, soundEvent);
        }

        BannedWords.LOGGER.info("BannedWords Server Mod initialized with TickScheduler.");
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
}