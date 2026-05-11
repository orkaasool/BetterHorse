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
    // Generates a weight between 1.8 and 3.3 for the stronger parent
    double weight = 1.8 + (random.nextDouble() * 1.5); 
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

        // 1. Fetch Stats for Mother
        double mStamina = staminaManager.getMaxStamina(mother);
        double mSprint = staminaManager.getSprintMultiplier(mother);
        double mMutation = motherPdc.getOrDefault(staminaManager.mutationValueKey, PersistentDataType.DOUBLE, 0.1);
        AttributeInstance mSpeedAttr = mother.getAttribute(Attribute.MOVEMENT_SPEED);
        double mSpeed = (mSpeedAttr != null) ? mSpeedAttr.getBaseValue() : 0.225; // 0.225 is default horse speed

        // 2. Fetch Stats for Father
        double fStamina = staminaManager.getMaxStamina(father);
        double fSprint = staminaManager.getSprintMultiplier(father);
        double fMutation = fatherPdc.getOrDefault(staminaManager.mutationValueKey, PersistentDataType.DOUBLE, 0.1);
        AttributeInstance fSpeedAttr = father.getAttribute(Attribute.MOVEMENT_SPEED);
        double fSpeed = (fSpeedAttr != null) ? fSpeedAttr.getBaseValue() : 0.225;

        // 3. Calculate Weighted Averages
        double avgStamina = calculateTrait(mStamina, fStamina);
        double avgSprint = calculateTrait(mSprint, fSprint);
        double avgSpeed = calculateTrait(mSpeed, fSpeed);
        double avgMutation = (mMutation + fMutation) / 2.0;

        // 4. Apply Inheritance Formula with +/- random range
        double staminaRoll = (Math.random() * avgMutation);
        double sprintRoll = (Math.random() * avgMutation);
        double speedRoll = (Math.random() * avgMutation);

        double foalStamina = avgStamina*0.8 + avgStamina*staminaRoll;
        double foalSprint = avgSprint*0.8 + avgSprint*sprintRoll;
        double foalSpeed = avgSpeed*0.8 + avgSpeed*speedRoll;

        // Cap stats so they don't break the game over many generations
        foalStamina = Math.min(foalStamina, staminaManager.ABSOLUTE_MAX_STAMINA); 
        foalSprint = Math.min(foalSprint, staminaManager.ABSOLUTE_MAX_SPRINT);
        foalSpeed = Math.min(foalSpeed, staminaManager.ABSOLUTE_MAX_SPEED);
        // 5. Calculate Size Variable via Key Existence
        boolean motherHasSize = motherPdc.has(staminaManager.sizeKey, PersistentDataType.DOUBLE);
        boolean fatherHasSize = fatherPdc.has(staminaManager.sizeKey, PersistentDataType.DOUBLE);
        
        double foalSize;
        if (!motherHasSize && !fatherHasSize) {
            // Both are wild horses (no size key). Roll a brand new random size for the foal.
            foalSize = 0.8 + (Math.random() * 0.4); 
        } else {
            // At least one parent is bred. Calculate inherited size.
            // If one parent is wild, their missing size defaults to standard 1.0
            double mSize = motherPdc.getOrDefault(staminaManager.sizeKey, PersistentDataType.DOUBLE, 1.0);
            double fSize = fatherPdc.getOrDefault(staminaManager.sizeKey, PersistentDataType.DOUBLE, 1.0);
            
            double avgSize = (mSize + fSize) / 2.0;
            double sizeRoll = (Math.random() * 2 * avgMutation) - avgMutation;
            foalSize = avgSize * (1.0 + sizeRoll);
        }
        
        // Cap size to prevent massive graphical glitches
        foalSize = Math.min(Math.max(foalSize, 0.8), 1.4); 

        // 6. Save to Foal PDC
        foalPdc.set(staminaManager.sizeKey, PersistentDataType.DOUBLE, foalSize);
        foalPdc.set(new NamespacedKey(plugin, "max_stamina"), PersistentDataType.DOUBLE, foalStamina);
        foalPdc.set(new NamespacedKey(plugin, "current_stamina"), PersistentDataType.DOUBLE, foalStamina);
        foalPdc.set(new NamespacedKey(plugin, "sprint_multiplier"), PersistentDataType.DOUBLE, foalSprint);
        
        // Recalculate and set the Foal's own mutation value based on its new stats
        staminaManager.calculateAndSetMutation(foal, foalStamina, foalSprint, foalSpeed);

        // 7. Apply Physical Attributes to Foal (1.21.2+ format)
        AttributeInstance speedAttr = foal.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speedAttr != null) {
            speedAttr.setBaseValue(foalSpeed);
        }

        // SCALE attribute is now standard! No more "valueOf" needed.
        AttributeInstance scaleAttr = foal.getAttribute(Attribute.SCALE);
        if (scaleAttr != null) {
            scaleAttr.setBaseValue(foalSize);
        }
    }
}