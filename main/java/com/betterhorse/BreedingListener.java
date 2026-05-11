package com.betterhorse;

import java.util.Random;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Horse;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class BreedingListener implements Listener {

    private final betterhorse plugin;
    private final StaminaManager staminaManager;

    public BreedingListener(betterhorse plugin, StaminaManager staminaManager) {
        this.plugin = plugin;
        this.staminaManager = staminaManager;
    }
    
    private double calculateTrait(double mStat, double fStat) {
        Random random = new Random();
        double higher = Math.max(mStat, fStat);
        double lower = Math.min(mStat, fStat);
        double weight = 1.5 + (random.nextDouble() * 1.4); 
        return (weight * higher + lower) / (weight + 1.0);
    }

    @EventHandler
    public void onHorseBreed(EntityBreedEvent event) {
        if (!(event.getEntity() instanceof Horse foal)) return;
        if (!(event.getMother() instanceof Horse mother)) return;
        if (!(event.getFather() instanceof Horse father)) return;

        PersistentDataContainer motherPdc = mother.getPersistentDataContainer();
        PersistentDataContainer fatherPdc = father.getPersistentDataContainer();
        PersistentDataContainer foalPdc = foal.getPersistentDataContainer();

        // 1. Fetch Stats for Mother (Now tied to Health)
        double mHealth = staminaManager.getMaxHealth(mother);
        double mSprint = staminaManager.getSprintMultiplier(mother);
        double mMutation = motherPdc.getOrDefault(staminaManager.mutationValueKey, PersistentDataType.DOUBLE, 0.1);
        AttributeInstance mSpeedAttr = mother.getAttribute(Attribute.MOVEMENT_SPEED);
        double mSpeed = (mSpeedAttr != null) ? mSpeedAttr.getBaseValue() : 0.225;

        // 2. Fetch Stats for Father
        double fHealth = staminaManager.getMaxHealth(father);
        double fSprint = staminaManager.getSprintMultiplier(father);
        double fMutation = fatherPdc.getOrDefault(staminaManager.mutationValueKey, PersistentDataType.DOUBLE, 0.1);
        AttributeInstance fSpeedAttr = father.getAttribute(Attribute.MOVEMENT_SPEED);
        double fSpeed = (fSpeedAttr != null) ? fSpeedAttr.getBaseValue() : 0.225;

        // 3. Calculate Weighted Averages
        double avgHealth = calculateTrait(mHealth, fHealth);
        double avgSprint = calculateTrait(mSprint, fSprint);
        double avgSpeed = calculateTrait(mSpeed, fSpeed);
        double avgMutation = calculateTrait(mMutation, fMutation);

        // 4. Apply Inheritance Formula with +/- random range
        double healthRoll = (Math.random() * avgMutation);
        double sprintRoll = (Math.random() * avgMutation);
        double speedRoll = (Math.random() * avgMutation);

        double foalHealth = avgHealth * 0.80 + avgHealth * healthRoll;
        double foalSprint = avgSprint * 0.80 + avgSprint * sprintRoll;
        double foalSpeed = avgSpeed * 0.80 + avgSpeed * speedRoll;

        // Cap stats so they don't break the game
        foalHealth = Math.min(foalHealth, staminaManager.ABSOLUTE_MAX_HEALTH); 
        foalSprint = Math.min(foalSprint, staminaManager.ABSOLUTE_MAX_SPRINT);
        foalSpeed = Math.min(foalSpeed, staminaManager.ABSOLUTE_MAX_SPEED);
        
        // 5. Calculate Size Variable
        boolean motherHasSize = motherPdc.has(staminaManager.sizeKey, PersistentDataType.DOUBLE);
        boolean fatherHasSize = fatherPdc.has(staminaManager.sizeKey, PersistentDataType.DOUBLE);
        
        double foalSize;
        if (!motherHasSize && !fatherHasSize) {
            foalSize = 0.8 + (Math.random() * 0.4); 
        } else {
            double mSize = motherPdc.getOrDefault(staminaManager.sizeKey, PersistentDataType.DOUBLE, 1.0);
            double fSize = fatherPdc.getOrDefault(staminaManager.sizeKey, PersistentDataType.DOUBLE, 1.0);
            
            double avgSize = (mSize + fSize) / 2.0;
            double sizeRoll = (Math.random() * 2 * avgMutation) - avgMutation;
            foalSize = avgSize * (1.0 + sizeRoll);
        }
        
        foalSize = Math.min(Math.max(foalSize, 0.8), 1.4); 

        // 6. Save to Foal PDC
        foalPdc.set(staminaManager.sizeKey, PersistentDataType.DOUBLE, foalSize);
        // Set the current stamina pool tracker to map to its full health
        foalPdc.set(new NamespacedKey(plugin, "current_stamina"), PersistentDataType.DOUBLE, foalHealth);
        foalPdc.set(new NamespacedKey(plugin, "sprint_multiplier"), PersistentDataType.DOUBLE, foalSprint);
        staminaManager.calculateAndSetMutation(foal, foalHealth, foalSprint, foalSpeed);

        // 7. Apply Physical Attributes to Foal
        AttributeInstance speedAttr = foal.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speedAttr != null) {
            speedAttr.setBaseValue(foalSpeed);
        }
        
        // Apply stamina stat over to the vanilla health pool
        AttributeInstance healthAttr = foal.getAttribute(Attribute.MAX_HEALTH);
        if (healthAttr != null) {
            healthAttr.setBaseValue(foalHealth);
        }
        foal.setHealth(foalHealth); // Heal the foal to its new max health

        AttributeInstance scaleAttr = foal.getAttribute(Attribute.SCALE);
        if (scaleAttr != null) {
            scaleAttr.setBaseValue(foalSize);
        }
    }
}