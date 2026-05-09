package com.betterhorse;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
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
    private final double BONUS_RECOVERY = 0.005; 
    private final double RECOVERY_THRESHOLD = 0.3; 
    private final double FOV_ZOOM_AMOUNT = 0.4; 

    // Configurable tuning variables
    private final double DEPLETION_RATE; 
    private final double SPEED_SPRINTING;
    private final double SPEED_WALKING;
    private final double SPEED_EXHAUSTED;
    
    // Player state tracking for sprint input
    private final Map<UUID, Long> lastWPress = new HashMap<>();
    private final Map<UUID, Boolean> lastWState = new HashMap<>();
    private final Set<UUID> activeSprints = new HashSet<>();

    public StaminaManager(betterhorse plugin) {
        this.plugin = plugin;
        this.maxStaminaKey = new NamespacedKey(plugin, "max_stamina");
        this.currentStaminaKey = new NamespacedKey(plugin, "current_stamina");
        this.lastTickKey = new NamespacedKey(plugin, "last_tick");
        this.exhaustedStateKey = new NamespacedKey(plugin, "is_exhausted");
        this.speedModifierKey = new NamespacedKey(plugin, "stamina_speed_state");
        this.playerFovModifierKey = new NamespacedKey(plugin, "horse_sprint_fov");

        // Load values from config.yml
        this.DEPLETION_RATE = plugin.getConfig().getDouble("depletion-rate", 1.0);
        this.SPEED_SPRINTING = plugin.getConfig().getDouble("speed-modifiers.sprint", 0.1);
        this.SPEED_WALKING = plugin.getConfig().getDouble("speed-modifiers.walk", -0.2);
        this.SPEED_EXHAUSTED = plugin.getConfig().getDouble("speed-modifiers.exhausted", -0.4);
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

    public void processPlayerInput(Player player, boolean isForward) {
        UUID id = player.getUniqueId();
        boolean wasForward = lastWState.getOrDefault(id, false);
        if (isForward && !wasForward) {
            // Player just pressed 'W'
            long now = System.currentTimeMillis();
            long lastPress = lastWPress.getOrDefault(id, 0L);
            if (now - lastPress < 300) { // 300ms window for double tap
                activeSprints.add(id);
            }
            lastWPress.put(id, now);
        } else if (!isForward) {
            // Player let go of 'W', stop their sprint
            activeSprints.remove(id);
        }
        lastWState.put(id, isForward);
    }

    public void tickHorse(Horse horse, Player rider) {
        PersistentDataContainer pdc = horse.getPersistentDataContainer();
        double max = getMaxStamina(horse);
        double current = pdc.getOrDefault(currentStaminaKey, PersistentDataType.DOUBLE, max);
        boolean isExhausted = pdc.getOrDefault(exhaustedStateKey, PersistentDataType.BYTE, (byte) 0) == (byte) 1;

        UUID id = rider.getUniqueId();
        
        // Combine standard sprint APIs with our custom Double-Tap W sprint
        boolean isAttemptingSprint = activeSprints.contains(id);
        if (isAttemptingSprint && !isExhausted) {
            current -= DEPLETION_RATE;
            setSpeedState(horse, SPEED_SPRINTING); 
            applyPlayerFovZoom(rider); 
            if (current <= 0) {
                current = 0;
                pdc.set(exhaustedStateKey, PersistentDataType.BYTE, (byte) 1);
                setSpeedState(horse, SPEED_EXHAUSTED); 
                removePlayerFovZoom(rider); 
                horse.getWorld().playSound(horse.getLocation(), Sound.ENTITY_HORSE_BREATHE, 1.0f, 0.6f);
            }
        } else {
            removePlayerFovZoom(rider); 
            setSpeedState(horse, SPEED_WALKING); 
            double recoveryRate = BASE_RECOVERY + (current * BONUS_RECOVERY);
            current += recoveryRate;
            
            if (current >= max) {
                current = max;
            }

            if (isExhausted) {
                // Play a quiet breathing sound occasionally while recovering (10% chance per tick)
                if (Math.random() < 0.1) {
                    horse.getWorld().playSound(horse.getLocation(), Sound.ENTITY_HORSE_BREATHE, 0.5f, 0.8f);
                }

                if (current > (max * RECOVERY_THRESHOLD)) {
                    pdc.set(exhaustedStateKey, PersistentDataType.BYTE, (byte) 0);
                    setSpeedState(horse, SPEED_WALKING); 
                    
                    // Play a sound when the horse is fully recovered and ready to sprint again!
                    horse.getWorld().playSound(horse.getLocation(), Sound.ENTITY_HORSE_AMBIENT, 0.8f, 1.2f);
                } else {
                    setSpeedState(horse, SPEED_EXHAUSTED); 
                }
            }
        }

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
            current += BASE_RECOVERY + ((current / max) * BONUS_RECOVERY);
        }

        if (current > (max * RECOVERY_THRESHOLD)) {
            pdc.set(exhaustedStateKey, PersistentDataType.BYTE, (byte) 0);
        }

        pdc.set(currentStaminaKey, PersistentDataType.DOUBLE, current);
        pdc.set(lastTickKey, PersistentDataType.LONG, System.currentTimeMillis());
    }

    private void setSpeedState(Horse horse, double scalarModifier) {
        // Changed to Attribute.MOVEMENT_SPEED for 1.21.4+
        AttributeInstance speedAttr = horse.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speedAttr == null) return;
        
        speedAttr.removeModifier(speedModifierKey);
        AttributeModifier modifier = new AttributeModifier(speedModifierKey, scalarModifier, AttributeModifier.Operation.ADD_SCALAR);
        speedAttr.addModifier(modifier);
    }

    public void removeHorseSpeedState(Horse horse) {
        AttributeInstance speedAttr = horse.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speedAttr != null) {
            speedAttr.removeModifier(speedModifierKey);
        }
    }

    private void applyPlayerFovZoom(Player player) {
        AttributeInstance speedAttr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speedAttr != null && speedAttr.getModifier(playerFovModifierKey) == null) {
            AttributeModifier modifier = new AttributeModifier(playerFovModifierKey, FOV_ZOOM_AMOUNT, AttributeModifier.Operation.ADD_SCALAR);
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
        lastWPress.remove(id);
        lastWState.remove(id);
        activeSprints.remove(id);
    }

}