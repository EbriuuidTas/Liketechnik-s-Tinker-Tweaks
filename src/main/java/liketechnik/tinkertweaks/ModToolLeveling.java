package liketechnik.tinkertweaks;

import liketechnik.tinkertweaks.capability.CapabilityDamageXp;
import liketechnik.tinkertweaks.config.Config;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import slimeknights.tconstruct.library.entity.EntityProjectileBase;
import slimeknights.tconstruct.library.events.TinkerToolEvent;
import slimeknights.tconstruct.library.modifiers.*;
import slimeknights.tconstruct.library.tinkering.TinkersItem;
import slimeknights.tconstruct.library.tools.ProjectileLauncherNBT;
import slimeknights.tconstruct.library.tools.ranged.BowCore;
import slimeknights.tconstruct.library.utils.TagUtil;
import slimeknights.tconstruct.library.utils.Tags;
import slimeknights.tconstruct.library.utils.TinkerUtil;
import slimeknights.tconstruct.library.utils.ToolBuilder;
import slimeknights.tconstruct.library.utils.ToolHelper;
import slimeknights.tconstruct.tools.melee.TinkerMeleeWeapons;

import java.util.List;

public class ModToolLeveling extends ProjectileModifierTrait {

  public ModToolLeveling() {
    super("tinkertweaks", 0xffffff);

    aspects.clear();
    addAspects(new ModifierAspect.DataAspect(this));

    MinecraftForge.EVENT_BUS.register(this);
  }

  @Override
  public boolean isHidden() {
    return true;
  }

  @Override
  public boolean canApplyCustom(ItemStack stack) {
    return true;
  }

  @Override
  public void applyEffect(NBTTagCompound rootCompound, NBTTagCompound modifierTag) {
    super.applyEffect(rootCompound, modifierTag);

    ToolLevelNBT data = getLevelData(modifierTag);

    // apply bonus modifiers
    NBTTagCompound toolTag = TagUtil.getToolTag(rootCompound);
    int modifiers = toolTag.getInteger(Tags.FREE_MODIFIERS) + data.bonusModifiers;
    toolTag.setInteger(Tags.FREE_MODIFIERS, Math.max(0, modifiers));
    TagUtil.setToolTag(rootCompound, toolTag);
  }

  /* Actions that award XP */

  @Override
  public void afterBlockBreak(ItemStack tool, World world, IBlockState state, BlockPos pos, EntityLivingBase player, boolean wasEffective) {
    if(wasEffective && player instanceof EntityPlayer) {
      addXp(tool, 1, (EntityPlayer) player);
    }
  }

  @Override
  public void afterHit(ItemStack tool, EntityLivingBase player, EntityLivingBase target, float damageDealt, boolean wasCritical, boolean wasHit) {
    if(!target.getEntityWorld().isRemote && wasHit && player instanceof EntityPlayer) {
      // if we killed it the event for distributing xp was already fired and we just do it manually here
      EntityPlayer entityPlayer = (EntityPlayer) player;
      if(!target.isEntityAlive()) {
        addXp(tool, Math.round(damageDealt), entityPlayer);
      }
      else if(target.hasCapability(CapabilityDamageXp.CAPABILITY, null)) {
        target.getCapability(CapabilityDamageXp.CAPABILITY, null).addDamageFromTool(damageDealt, tool, entityPlayer);
      }
    }
  }

  @Override
  public void onBlock(ItemStack tool, EntityPlayer player, LivingHurtEvent event) {
    if(player != null && !player.world.isRemote && player.getActiveItemStack() == tool) {
      int xp = Math.round(event.getAmount());
      addXp(tool, xp, player);
    }
  }

  @SubscribeEvent
  public void onMattock(TinkerToolEvent.OnMattockHoe event) {
    addXp(event.itemStack, 1, event.player);
  }

  @SubscribeEvent
  public void onScythe(TinkerToolEvent.OnScytheHarvest event) {
    if(!event.isCanceled()) {
      addXp(event.itemStack, 1, event.player);
    }
  }

  @SubscribeEvent
  public void onPath(TinkerToolEvent.OnShovelMakePath event) {
    addXp(event.itemStack, 1, event.player);
  }
  
  @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
  public void onLivingHurt(LivingAttackEvent event) {
    // if it's cancelled it got handled by the battlesign (or something else. but it's a prerequisite.)
    if(!event.isCanceled()) {
      return;
    }
    if(event.getSource().isUnblockable() || !event.getSource().isProjectile() || event.getSource().getTrueSource() == null) {
      return;
    }
    // hit entity is a player?
    if(!(event.getEntity() instanceof EntityPlayer)) {
      return;
    }
    EntityPlayer player = (EntityPlayer) event.getEntity();
    // needs to be blocking with a battlesign
    if(!player.isActiveItemStackBlocking() || player.getActiveItemStack().getItem() != TinkerMeleeWeapons.battleSign) {
      return;
    }
    // broken battlesign.
    if(ToolHelper.isBroken(player.getActiveItemStack())) {
      return;
    }
    
    // at this point we duplicated all the logic if the battlesign should reflect a projectile.. bleh.
    int xp = Math.max(1, Math.round(event.getAmount()));
    addXp(player.getActiveItemStack(), xp, player);
  }
  
  /* XP Handling */
  
  public void addXp(ItemStack tool, int amount, EntityPlayer player) {
    NBTTagList tagList = TagUtil.getModifiersTagList(tool);
    int index = TinkerUtil.getIndexInCompoundList(tagList, identifier);
    NBTTagCompound modifierTag = tagList.getCompoundTagAt(index);
    
    ToolLevelNBT data = getLevelData(modifierTag);
    data.xp += amount;
    
    // is max level?
    if (!Config.canLevelUp(data.level)) {
      return;
    }
    
    int xpForLevelup = getXpForLevelup(data.level, tool);
    
    boolean leveledUp = false;
    // check for levelup
    if (data.xp >= xpForLevelup) {
      data.xp -= xpForLevelup;
      data.level++;
    
      List<IModifier> modifiers = Config.getModifiers(tool.getItem());
      int modifierIndex;
      boolean applied = false;
      do {
        if (Config.modifierAndFree()) {
          modifierIndex = random.nextInt(modifiers.size());
        } else {
          modifierIndex = random.nextInt(modifiers.size() + 1);
        }
      
        if (modifierIndex == modifiers.size() || Config.modifierAndFree()) {
          data.bonusModifiers++;
        }
        if (modifierIndex != modifiers.size() || Config.modifierAndFree()) {
          IModifier modifier = modifiers.get(modifierIndex);
        
          int freeModifiers = ToolHelper.getFreeModifiers(tool);
        
          try {
            if (modifier.canApply(tool, tool)) {
              modifier.apply(tool);
              applied = true;
            } else {
              modifiers.remove(modifierIndex);
              continue;
            }
          } catch (TinkerGuiException e) {
            modifiers.remove(modifierIndex);
            continue;
          }
        
          data.bonusModifiers += freeModifiers - ToolHelper.getFreeModifiers(tool);
        }
      } while (!applied && !modifiers.isEmpty());
    
      leveledUp = true;
    }
    
    data.write(modifierTag);
    //tagList.set(index, modifierTag);
    TagUtil.setModifiersTagList(tool, tagList);
    
    if (leveledUp) {
      this.apply(tool);
      if (!player.world.isRemote) {
        // for some reason the proxy is messed up. cba to fix now
        LiketechniksTinkerTweaks.proxy.playLevelupDing(player);
        LiketechniksTinkerTweaks.proxy.sendLevelUpMessage(data.level, tool, player);
      }
      try {
        NBTTagCompound rootTag = TagUtil.getTagSafe(tool);
        ToolBuilder.rebuildTool(rootTag, (TinkersItem) tool.getItem());
        tool.setTagCompound(rootTag);
      } catch (TinkerGuiException e) {
        // this should never happen
        e.printStackTrace();
      }
    }
  }

  public int getXpForLevelup(int level, ItemStack tool) {
    if(level <= 1) {
      return Config.getBaseXpForTool(tool.getItem());
    }
    return (int) ((float) getXpForLevelup(level - 1, tool) * Config.getLevelMultiplier());
  }

  private ToolLevelNBT getLevelData(ItemStack itemStack) {
    return getLevelData(TinkerUtil.getModifierTag(itemStack, getModifierIdentifier()));
  }

  private ToolLevelNBT getLevelData(NBTTagCompound modifierNBT) {
    return new ToolLevelNBT(modifierNBT);
  }

  @Override
  public void afterHit(EntityProjectileBase projectile, World world, ItemStack ammoStack, EntityLivingBase attacker, Entity target, double impactSpeed) {
    if(impactSpeed > 0.4f && attacker instanceof EntityPlayer) {
      ItemStack launcher = projectile.tinkerProjectile.getLaunchingStack();
      if(launcher.getItem() instanceof BowCore) {
        double drawTime = ((BowCore) launcher.getItem()).getDrawTime();
        double drawSpeed = ProjectileLauncherNBT.from(launcher).drawSpeed;
        double drawTimeInSeconds = 1d / (20d * drawSpeed/drawTime);
        // we award 5 xp per 1s draw time
        int xp = MathHelper.ceil((5d * drawTimeInSeconds));
        this.addXp(launcher, xp, (EntityPlayer) attacker);
      }
    }
  }
}
