package com.betterhorse;

import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class HorseListener implements Listener {

    private final StaminaManager staminaManager;

    public HorseListener(StaminaManager staminaManager) {
        this.staminaManager = staminaManager;
    }

    @EventHandler
    public void onHorseMount(EntityMountEvent event) {
        if (event.getMount() instanceof Horse horse && event.getEntity() instanceof Player player) {
            staminaManager.removePlayerState(player);
            staminaManager.initializeHorse(horse);
            staminaManager.createStaminaBar(player);
            staminaManager.catchUpOfflineRecovery(horse);
        }
    }

    @EventHandler
    public void onPlayerInput(PlayerInputEvent event) {
        if (event.getPlayer().getVehicle() instanceof Horse) {
            staminaManager.processPlayerInput(event.getPlayer(), event.getInput().isSprint());
        }
    }

    @EventHandler
    public void onHorseDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player && event.getDismounted() instanceof Horse horse) {
            staminaManager.removePlayerFovZoom(player);            
            staminaManager.removePlayerState(player);
        }
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        staminaManager.removePlayerState(event.getPlayer());
    }
}