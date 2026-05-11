package com.betterhorse;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

public class StaminaCommand implements CommandExecutor {

    private final StaminaManager staminaManager;

    public StaminaCommand(StaminaManager staminaManager) {
        this.staminaManager = staminaManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Require admin permission to run the command
        if (!sender.hasPermission("betterhorse.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage("§cUsage: /staminabar <player>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage("§cPlayer not found or is offline.");
            return true;
        }

        // Check if they currently have it unlocked
        byte current = target.getPersistentDataContainer().getOrDefault(staminaManager.seeBarKey, PersistentDataType.BYTE, (byte) 0);
        
        if (current == 0) {
            // Unlock it
            target.getPersistentDataContainer().set(staminaManager.seeBarKey, PersistentDataType.BYTE, (byte) 1);
            sender.sendMessage("§aStamina bar unlocked for " + target.getName() + ".");
            target.sendMessage("§aYou have unlocked the ability to see the horse stamina bar!");
        } else {
            // Lock it
            target.getPersistentDataContainer().set(staminaManager.seeBarKey, PersistentDataType.BYTE, (byte) 0);
            sender.sendMessage("§cStamina bar locked for " + target.getName() + ".");
            target.sendMessage("§cYour ability to see the horse stamina bar has been revoked.");
        }

        return true;
    }
}