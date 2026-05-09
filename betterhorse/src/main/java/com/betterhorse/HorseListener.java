package com.betterhorse;

import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInputEvent;

public class HorseListener implements Listener {

    private final StaminaManager staminaManager;

    public HorseListener(StaminaManager staminaManager) {
        this.staminaManager = staminaManager;
    }

    @EventHandler
    public void onHorseMount(EntityMountEvent event) {
        if (event.getMount() instanceof Horse horse) {
            // Checks if stamina exists in the PDC. 
            // If it is the first time a player is riding this horse, it permanently assigns a random value.
            // If it already has stamina, it safely does nothing.
            staminaManager.initializeHorse(horse, staminaManager.getRandomMaxStamina());
            
            // Calculate how much stamina it recovered while standing still
            staminaManager.catchUpOfflineRecovery(horse);
        }
    }

    @EventHandler
    public void onPlayerInput(PlayerInputEvent event) {
        if (event.getPlayer().getVehicle() instanceof Horse) {
            // Pass the accurate input state directly into our double-tap logic
            staminaManager.processPlayerInput(event.getPlayer(), event.getInput().isForward());
        }
    }

    @EventHandler
    public void onHorseDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player && event.getDismounted() instanceof Horse horse) {
            staminaManager.removePlayerFovZoom(player);
            staminaManager.removeHorseSpeedState(horse);
            
            // Clean up player state to prevent memory leaks
            staminaManager.removePlayerState(player);
        }
    }
    
    // Catch players who disconnect while riding the horse
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        staminaManager.removePlayerState(event.getPlayer());
    }
}