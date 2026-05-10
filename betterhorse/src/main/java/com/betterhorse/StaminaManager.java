package com.betterhorse;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class StaminaManager {

    private final betterhorse plugin;
    private final NamespacedKey maxStaminaKey;
    private final NamespacedKey currentStaminaKey;
    private final NamespacedKey lastTickKey;
    private final NamespacedKey exhaustedStateKey;
    
    private final NamespacedKey speedModifierKey;
    private final NamespacedKey playerFovModifierKey;

    // Fixed tuning variables
    private final double BASE_RECOVERY = 0.1;  
    private final double BONUS_RECOVERY = 0.05; 
    private final double RECOVERY_THRESHOLD = 0.4; 
    private final double FOV_ZOOM_AMOUNT = 0.3; 

    // Configurable tuning variables
    private final double DEPLETION_RATE; 
    private final double SPEED_SPRINTING;
    private final double SPEED_WALKING;
    private final double SPEED_EXHAUSTED;
    private final double MOMENTUM_TICK_SCALE; // Controls the curve of the t^3 deceleration
    
    // Player state trackers
    private final Set<UUID> activeSprints = new HashSet<>();
    private final Map<UUID, Double> playerMomentum = new HashMap<>();
    private final Map<UUID, BossBar> playerStaminaBars = new HashMap<>();

    public StaminaManager(betterhorse plugin) {
        this.plugin = plugin;
        this.maxStaminaKey = new NamespacedKey(plugin, "max_stamina");
        this.currentStaminaKey = new NamespacedKey(plugin, "current_stamina");
        this.lastTickKey = new NamespacedKey(plugin, "last_tick");
        this.exhaustedStateKey = new NamespacedKey(plugin, "is_exhausted");
        this.speedModifierKey = new NamespacedKey(plugin, "stamina_speed_state");
        this.playerFovModifierKey = new NamespacedKey(plugin, "horse_sprint_fov");

        this.DEPLETION_RATE = plugin.getConfig().getDouble("depletion-rate", 1.0);
        this.SPEED_SPRINTING = plugin.getConfig().getDouble("speed-modifiers.sprint", 0);
        this.SPEED_WALKING = plugin.getConfig().getDouble("speed-modifiers.walk", -0.2);
        this.SPEED_EXHAUSTED = plugin.getConfig().getDouble("speed-modifiers.exhausted", -0.4);
        // Default 0.035 means it takes ~20 ticks (1 second) to decelerate to walking speed
        this.MOMENTUM_TICK_SCALE = plugin.getConfig().getDouble("momentum-scale", 0.05); 
    }

    public void createStaminaBar(Player player) {
        // Creates a Green solid bar.
        BossBar bar = Bukkit.createBossBar("§aHorse Stamina", BarColor.GREEN, BarStyle.SOLID);
        bar.addPlayer(player);
        playerStaminaBars.put(player.getUniqueId(), bar);
    }

    public void initializeHorse(Horse horse, double maxStamina) {
        PersistentDataContainer pdc = horse.getPersistentDataContainer();
        if (!pdc.has(maxStaminaKey, PersistentDataType.DOUBLE)) {
            pdc.set(maxStaminaKey, PersistentDataType.DOUBLE, maxStamina);
            pdc.set(currentStaminaKey, PersistentDataType.DOUBLE, maxStamina);
            pdc.set(lastTickKey, PersistentDataType.LONG, System.currentTimeMillis());
            pdc.set(exhaustedStateKey, PersistentDataType.BYTE, (byte) 0);
        }
    }

    public double getMaxStamina(Horse horse) {
        return horse.getPersistentDataContainer().getOrDefault(maxStaminaKey, PersistentDataType.DOUBLE, getRandomMaxStamina());
    }

    public double getRandomMaxStamina() {
        return 100.0 + (Math.random() * 200.0);
    }

    public void processPlayerInput(Player player, boolean isSprinting) {
        UUID id = player.getUniqueId();
        
        if (isSprinting) {
            activeSprints.add(id);
        } else {
            activeSprints.remove(id);
        }
    }

    public void tickHorse(Horse horse, Player rider) {
        PersistentDataContainer pdc = horse.getPersistentDataContainer();
        double max = getMaxStamina(horse);
        double current = pdc.getOrDefault(currentStaminaKey, PersistentDataType.DOUBLE, max);
        boolean isExhausted = pdc.getOrDefault(exhaustedStateKey, PersistentDataType.BYTE, (byte) 0) == (byte) 1;

        UUID id = rider.getUniqueId();
        boolean isAttemptingSprint = activeSprints.contains(id);
        BossBar bar = playerStaminaBars.get(id);
        
        // Fetch current momentum (0.0 is walking, 1.0 is full sprint)
        double momentum = playerMomentum.getOrDefault(id, 0.0);
        
        if (bar != null) {
            double ratio = current / max;
            ratio = Math.max(0.0, Math.min(1.0, ratio));
            bar.setProgress(ratio);
        }
        
        if (isAttemptingSprint && !isExhausted) {
            current -= DEPLETION_RATE;
            
            // Build momentum
            momentum += MOMENTUM_TICK_SCALE;
            if (momentum > 1.0) momentum = 1.0;
            
            if (current <= 1) {
                current = 1;
                pdc.set(exhaustedStateKey, PersistentDataType.BYTE, (byte) 1);
                horse.getWorld().playSound(horse.getLocation(), Sound.ENTITY_HORSE_BREATHE, 1f, 0.6f);
            }
        } else {
            current += BASE_RECOVERY + (max / current) * BONUS_RECOVERY;
            if (current >= max) {
                current = max;
            }
            
            // Lose momentum
            momentum -= MOMENTUM_TICK_SCALE;
            if (momentum < 0.0) momentum = 0.0;
            
            if (isExhausted) {
                bar.setColor(BarColor.RED);
                setSpeedState(horse, SPEED_EXHAUSTED);
                removePlayerFovZoom(rider);
                momentum = 0.0; // Force momentum to 0 so they walk upon recovery
                
                if (Math.random() < 0.08) {
                    horse.getWorld().playSound(horse.getLocation(), Sound.ENTITY_HORSE_BREATHE, 0.5f, 0.8f);
                }

                if (current > (max * RECOVERY_THRESHOLD)) {
                    bar.setColor(BarColor.GREEN);
                    pdc.set(exhaustedStateKey, PersistentDataType.BYTE, (byte) 0);
                    horse.getWorld().playSound(horse.getLocation(), Sound.ENTITY_HORSE_AMBIENT, 0.8f, 1.2f);
                }
            }
        }
        
        if (!isExhausted) {
            // Apply your power-of-3 curve to the 0.0 - 1.0 momentum value
            double curveRatio = Math.pow(momentum, 3);
            
            // Calculate speed and FOV directly from the single curve
            double currentSpeed = SPEED_WALKING + ((SPEED_SPRINTING - SPEED_WALKING) * curveRatio);
            setSpeedState(horse, currentSpeed);
            
            if (curveRatio > 0.0) {
                applyPlayerFovZoom(rider, FOV_ZOOM_AMOUNT * curveRatio);
            } else {
                removePlayerFovZoom(rider);
            }
        }
        
        // Save state
        playerMomentum.put(id, momentum);
        pdc.set(currentStaminaKey, PersistentDataType.DOUBLE, current);
        pdc.set(lastTickKey, PersistentDataType.LONG, System.currentTimeMillis());
    }

    public void catchUpOfflineRecovery(Horse horse) {
        PersistentDataContainer pdc = horse.getPersistentDataContainer();
        if (!pdc.has(lastTickKey, PersistentDataType.LONG)) return;
        long lastTick = pdc.get(lastTickKey, PersistentDataType.LONG);
        long ticksPassed = (System.currentTimeMillis() - lastTick) / 50;
        if (ticksPassed <= 0) return;
        double max = getMaxStamina(horse);
        double current = pdc.getOrDefault(currentStaminaKey, PersistentDataType.DOUBLE, max);
        for (int i = 0; i < Math.min(ticksPassed, 24000); i++) {
            if (current >= max) {
                current = max;
                break;
            }
            current += BASE_RECOVERY;
        }
        if (current > (max * RECOVERY_THRESHOLD)) {
            pdc.set(exhaustedStateKey, PersistentDataType.BYTE, (byte) 0);
        }
        pdc.set(currentStaminaKey, PersistentDataType.DOUBLE, current);
        pdc.set(lastTickKey, PersistentDataType.LONG, System.currentTimeMillis());
    }

    private void setSpeedState(Horse horse, double scalarModifier) {
        AttributeInstance speedAttr = horse.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speedAttr == null) return;
        speedAttr.removeModifier(speedModifierKey);
        AttributeModifier modifier = new AttributeModifier(speedModifierKey, scalarModifier, AttributeModifier.Operation.ADD_SCALAR);
        speedAttr.addModifier(modifier);
    }

    private void applyPlayerFovZoom(Player player, double zoomAmount) {
        AttributeInstance speedAttr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speedAttr != null) {
            speedAttr.removeModifier(playerFovModifierKey); 
            AttributeModifier modifier = new AttributeModifier(playerFovModifierKey, zoomAmount, AttributeModifier.Operation.ADD_SCALAR);
            speedAttr.addModifier(modifier);
        }
    }

    public void removePlayerFovZoom(Player player) {
        AttributeInstance speedAttr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speedAttr != null) {
            speedAttr.removeModifier(playerFovModifierKey);
        }
    }

    public void removePlayerState(Player player) {
        UUID id = player.getUniqueId();
        activeSprints.remove(id);
        playerMomentum.remove(id); // Updated reference
        BossBar bar = playerStaminaBars.remove(id);
        if (bar != null) {
            bar.removePlayer(player);
        }
    }
}