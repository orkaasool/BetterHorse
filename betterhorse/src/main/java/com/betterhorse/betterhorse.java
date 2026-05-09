package com.betterhorse;

import org.bukkit.Bukkit;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class betterhorse extends JavaPlugin {

    private StaminaManager staminaManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.staminaManager = new StaminaManager(this);
        
        getServer().getPluginManager().registerEvents(new HorseListener(staminaManager), this);

        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getVehicle() instanceof Horse horse) {
                        staminaManager.tickHorse(horse, player);
                    }
                }
            }
        }.runTaskTimer(this, 1L, 1L);
        
        getLogger().info("HorseStamina enabled!");
    }
}