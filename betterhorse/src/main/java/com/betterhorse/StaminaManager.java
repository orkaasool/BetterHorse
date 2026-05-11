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
    private final NamespacedKey sprintMultiplierKey;
    private final NamespacedKey speedModifierKey;
    private final NamespacedKey playerFovModifierKey;
    public final NamespacedKey seeBarKey;

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
    private final double MOMENTUM_TICK_SCALE; 
    
    // Player state trackers
    private final Set<UUID> activeSprints = new HashSet<>();
    private final Map<UUID, Double> playerMomentum = new HashMap<>();
    private final Map<UUID, BossBar> playerStaminaBars = new HashMap<>();
    
    // Breed variables
    public final NamespacedKey mutationValueKey;
    public final NamespacedKey sizeKey;
    public final double ABSOLUTE_MAX_STAMINA = 300.0;
    public final double ABSOLUTE_MAX_SPRINT = 1.3;   
    public final double ABSOLUTE_MAX_SPEED = 0.3375;

    public StaminaManager(betterhorse plugin) {
        this.plugin = plugin;
        this.seeBarKey = new NamespacedKey(plugin, "can_see_stamina");
        this.maxStaminaKey = new NamespacedKey(plugin, "max_stamina");
        this.currentStaminaKey = new NamespacedKey(plugin, "current_stamina");
        this.lastTickKey = new NamespacedKey(plugin, "last_tick");
        this.exhaustedStateKey = new NamespacedKey(plugin, "is_exhausted");
        this.sprintMultiplierKey = new NamespacedKey(plugin, "sprint_multiplier");
        this.speedModifierKey = new NamespacedKey(plugin, "stamina_speed_state");
        this.playerFovModifierKey = new NamespacedKey(plugin, "horse_sprint_fov");
        this.mutationValueKey = new NamespacedKey(plugin, "mutation_value");
        this.sizeKey = new NamespacedKey(plugin, "horse_size");

        this.DEPLETION_RATE = plugin.getConfig().getDouble("depletion-rate", 0.5);
        this.SPEED_SPRINTING = plugin.getConfig().getDouble("speed-modifiers.sprint", 0.1);
        this.SPEED_WALKING = plugin.getConfig().getDouble("speed-modifiers.walk", -0.2);
        this.SPEED_EXHAUSTED = plugin.getConfig().getDouble("speed-modifiers.exhausted", -0.4);
        this.MOMENTUM_TICK_SCALE = plugin.getConfig().getDouble("momentum-scale", 0.01); 
    }

    public double calculateAndSetMutation(Horse horse, double maxStamina, double sprintMultiplier, double speed) {
        PersistentDataContainer pdc = horse.getPersistentDataContainer();
        double staminaPerfection = Math.min(1.0, maxStamina / ABSOLUTE_MAX_STAMINA);
        double sprintPerfection = Math.min(1.0, sprintMultiplier / ABSOLUTE_MAX_SPRINT);
        double speedPerfection = Math.min(1.0, speed / ABSOLUTE_MAX_SPEED);
        double totalPerfection = (staminaPerfection + sprintPerfection + speedPerfection) / 3.2;
        double mutationValue = 1.1*((1.0 - totalPerfection)/(Math.random()+1.0));
        pdc.set(mutationValueKey, PersistentDataType.DOUBLE, mutationValue);
        return mutationValue;
    }

    public void createStaminaBar(Player player) {
    // Check if the player has the PDC flag OR a bypass permission
    byte canSee = player.getPersistentDataContainer().getOrDefault(seeBarKey, PersistentDataType.BYTE, (byte) 0);
    
    if (canSee == 1 || player.hasPermission("betterhorse.seebar")) {
        BossBar bar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID);
        bar.addPlayer(player);
        playerStaminaBars.put(player.getUniqueId(), bar);
        }
    }

    public void initializeHorse(Horse horse, double maxStamina) {
        PersistentDataContainer pdc = horse.getPersistentDataContainer();
        if (!pdc.has(maxStaminaKey, PersistentDataType.DOUBLE)) {
            pdc.set(maxStaminaKey, PersistentDataType.DOUBLE, maxStamina);
            pdc.set(currentStaminaKey, PersistentDataType.DOUBLE, maxStamina);
            pdc.set(lastTickKey, PersistentDataType.LONG, System.currentTimeMillis());
            pdc.set(exhaustedStateKey, PersistentDataType.BYTE, (byte) 0);
        }
        if (!pdc.has(sprintMultiplierKey, PersistentDataType.DOUBLE)) {
            pdc.set(sprintMultiplierKey, PersistentDataType.DOUBLE, getRandomSprintMultiplier());
        }
    }

    public double getMaxStamina(Horse horse) {
        return horse.getPersistentDataContainer().getOrDefault(maxStaminaKey, PersistentDataType.DOUBLE, getRandomMaxStamina());
    }

    public double getRandomMaxStamina() {
        return 100.0 + (Math.random() * 200.0);
    }

    public double getSprintMultiplier(Horse horse) {
        return horse.getPersistentDataContainer().getOrDefault(sprintMultiplierKey, PersistentDataType.DOUBLE, getRandomSprintMultiplier());
    }

    public double getRandomSprintMultiplier() {
        return 1.0 + (Math.random() * 0.3);
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
        double momentum = playerMomentum.getOrDefault(id, 0.0);
        BossBar bar = playerStaminaBars.get(id);
        if (bar != null) {
            double ratio = current / max;
            ratio = Math.max(0.0, Math.min(1.0, ratio));
            bar.setProgress(ratio);
        }
        if (isAttemptingSprint && !isExhausted) {
            current -= DEPLETION_RATE;
            // Building up Momentum, shares the Scale with Decaying
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
            // Momentum naturally decays, adjust the rate with Momentum Scale
            momentum -= MOMENTUM_TICK_SCALE;
            if (momentum < 0.0) momentum = 0.0;
            if (isExhausted) {
                if (bar != null) bar.setColor(BarColor.RED);
                setSpeedState(horse, SPEED_EXHAUSTED);
                removePlayerFovZoom(rider);
                momentum = 0.0; 
                // 8% chance to play exhaust sound each tick
                if (Math.random() < 0.08) {
                    horse.getWorld().playSound(horse.getLocation(), Sound.ENTITY_HORSE_BREATHE, 0.5f, 0.8f);
                }
                if (current > (max * RECOVERY_THRESHOLD)) {
                    if (bar != null) bar.setColor(BarColor.GREEN);
                    pdc.set(exhaustedStateKey, PersistentDataType.BYTE, (byte) 0);
                    horse.getWorld().playSound(horse.getLocation(), Sound.ENTITY_HORSE_AMBIENT, 0.8f, 1.2f);
                }
            }
        }
        
        if (!isExhausted) {
            double curveRatio = Math.pow(momentum, 3);
            // Speed is calculated with a curve and Sprint Multiplier
            double currentSpeed = SPEED_WALKING + ((SPEED_SPRINTING * getSprintMultiplier(horse) - SPEED_WALKING) * curveRatio);
            setSpeedState(horse, currentSpeed);
            
            if (curveRatio > 0.0) {
                applyPlayerFovZoom(rider, FOV_ZOOM_AMOUNT * curveRatio);
            } else {
                removePlayerFovZoom(rider);
            }
        }
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
        playerMomentum.remove(id); 
        BossBar bar = playerStaminaBars.remove(id);
        if (bar != null) {
            bar.removePlayer(player);
        }
    }
}