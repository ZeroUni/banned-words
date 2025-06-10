package zerouni.bannedwords.server;

import zerouni.bannedwords.BannedWords;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.World.ExplosionSourceType;
import net.minecraft.sound.SoundEvent;
import net.minecraft.registry.Registries;


/**
 * Manages the in-game punishments for players who say banned words.
 * Includes broadcasting messages, playing audio, and causing explosions.
 */
public class PunishmentManager {
    private final float explosionPower;
    private final List<String> audioClipIds;
    private final Map<String, Long> audioClipDurationsMillis;
    private final TickScheduler tickScheduler;
    private final Random random = new Random();

    /**
     * Constructs a PunishmentManager.
     * @param explosionPower The power of the explosion caused by the punishment.
     * @param audioClipIds A list of Identifiers for the custom sound events to play.
     * @param audioClipDurationsMillis A map where keys are audio clip IDs and values are their durations in milliseconds.
     * @param tickScheduler The tick scheduler for delayed explosions.
     */
    public PunishmentManager(float explosionPower, List<String> audioClipIds, Map<String, Long> audioClipDurationsMillis, TickScheduler tickScheduler) {
        this.explosionPower = explosionPower;
        this.audioClipIds = audioClipIds;
        this.audioClipDurationsMillis = audioClipDurationsMillis;
        this.tickScheduler = tickScheduler;
        BannedWords.LOGGER.info("PunishmentManager initialized with {} audio clips and TickScheduler.", audioClipIds.size());
    }    
    
    /**
     * Applies the defined punishment to the given player.
     * @param player The player to be punished.
     * @param bannedWord The specific banned word or phrase that was detected.
     */
    public void applyPunishment(ServerPlayerEntity player, String bannedWord) {
        BannedWords.LOGGER.info("PunishmentManager: Starting punishment for player {} who said '{}'", player.getName().getString(), bannedWord);
        
        // 1. Broadcast chat message to all players
        PlayerManager playerList = player.getServer().getPlayerManager();
        if (playerList == null) {
            BannedWords.LOGGER.error("PlayerList is null. Cannot broadcast chat message.");
            return;
        }
        playerList.broadcast(
            Text.literal(
                String.format("§c[§4Banned Words§c] §r%s §csaid a banned word: §4%s§c!", player.getName().getString(), bannedWord)
            ),
            false
        );
        
        BannedWords.LOGGER.info("Chat message broadcast for {} saying '{}'.", player.getName().getString(), bannedWord);

        // 2. Play audio clip for the player
        if (!audioClipIds.isEmpty()) {
            String chosenSoundId = audioClipIds.get(random.nextInt(audioClipIds.size()));
            SoundEvent soundEvent = Registries.SOUND_EVENT.get(Identifier.of(BannedWords.MOD_ID, chosenSoundId));

            if (soundEvent != null) {
                long soundDurationMillis = audioClipDurationsMillis.getOrDefault(chosenSoundId, 2000L); 

                player.getWorld().playSoundFromEntity(
                    null,
                    player,
                    soundEvent,
                    player.getSoundCategory(),
                    1.0f,
                    1.0f
                );
                BannedWords.LOGGER.info("Playing sound '{}' for player {}. Duration: {}ms", chosenSoundId, player.getName().getString(), soundDurationMillis);                // 3. Schedule explosion after audio finishes
                tickScheduler.schedule(() -> {
                    if (player.isAlive()) {                       
                        player.getWorld().createExplosion(
                            null,
                            player.getX(), player.getY(), player.getZ(),
                            explosionPower,
                            true,
                            ExplosionSourceType.TNT
                        );
                        BannedWords.LOGGER.info("Explosion triggered for player {} after audio clip '{}'.", player.getName().getString(), chosenSoundId);
                    }
                }, soundDurationMillis);
            } else {
                BannedWords.LOGGER.warn("Sound event '{}' not found. Skipping audio playback.", chosenSoundId);
                triggerImmediateExplosion(player);
            }
        } else {
            BannedWords.LOGGER.warn("No audio clips configured. Triggering immediate explosion.");
            triggerImmediateExplosion(player);
        }
    }

    /**
     * Triggers an explosion on the player immediately.
     * This is used as a fallback if no audio clips are configured or found.
     * @param player The player to explode.
     */
    private void triggerImmediateExplosion(ServerPlayerEntity player) {
        if (player.isAlive()) {
            player.getWorld().createExplosion(
                null,
                player.getX(), player.getY(), player.getZ(),
                explosionPower,
                true,
                ExplosionSourceType.TNT
            );
        }
    }
}