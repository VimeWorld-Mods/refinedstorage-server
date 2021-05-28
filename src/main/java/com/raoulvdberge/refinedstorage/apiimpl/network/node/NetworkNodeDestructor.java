package com.raoulvdberge.refinedstorage.apiimpl.network.node;

import com.mojang.authlib.GameProfile;
import com.raoulvdberge.refinedstorage.RS;
import com.raoulvdberge.refinedstorage.api.util.Action;
import com.raoulvdberge.refinedstorage.apiimpl.network.node.cover.CoverManager;
import com.raoulvdberge.refinedstorage.apiimpl.util.OneSixMigrationHelper;
import com.raoulvdberge.refinedstorage.inventory.fluid.FluidInventory;
import com.raoulvdberge.refinedstorage.inventory.item.ItemHandlerBase;
import com.raoulvdberge.refinedstorage.inventory.item.ItemHandlerUpgrade;
import com.raoulvdberge.refinedstorage.inventory.listener.ListenerNetworkNode;
import com.raoulvdberge.refinedstorage.tile.TileDestructor;
import com.raoulvdberge.refinedstorage.tile.config.IComparable;
import com.raoulvdberge.refinedstorage.tile.config.IFilterable;
import com.raoulvdberge.refinedstorage.tile.config.IType;
import com.raoulvdberge.refinedstorage.util.StackUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.BlockShulkerBox;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.InventoryHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.management.PlayerProfileCache;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityShulkerBox;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.world.BlockEvent.BreakEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.wrappers.BlockLiquidWrapper;
import net.minecraftforge.fluids.capability.wrappers.FluidBlockWrapper;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

public class NetworkNodeDestructor extends NetworkNode implements IComparable, IFilterable, IType, ICoverable {
    public static final String ID = "destructor";
    private static final String NBT_COMPARE = "Compare";
    private static final String NBT_MODE = "Mode";
    private static final String NBT_TYPE = "Type";
    private static final String NBT_PICKUP = "Pickup";
    private static final String NBT_COVERS = "Covers";
    private static final String NBT_FLUID_FILTERS = "FluidFilters";
    private static final int BASE_SPEED = 20;
    private final ItemHandlerBase itemFilters = new ItemHandlerBase(9, new ListenerNetworkNode(this));
    private final FluidInventory fluidFilters = new FluidInventory(9, new ListenerNetworkNode(this));
    private final ItemHandlerUpgrade upgrades = new ItemHandlerUpgrade(4, new ListenerNetworkNode(this), 2, 6, 7, 8, 9);
    private int compare = 3;
    private int mode = 1;
    private int type = 0;
    private boolean pickupItem = false;
    private final CoverManager coverManager = new CoverManager(this);
    
    public NetworkNodeDestructor(World world, BlockPos pos) {
        super(world, pos);
    }
    
    public int getEnergyUsage() {
        return RS.INSTANCE.config.destructorUsage + this.upgrades.getEnergyUsage();
    }
    
    private FakePlayer getFakePlayer() {
        WorldServer world = (WorldServer) this.world;
        UUID owner = this.getOwner();
        if (owner != null) {
            PlayerProfileCache profileCache = world.getMinecraftServer().getPlayerProfileCache();
            GameProfile profile = profileCache.getProfileByUUID(owner);
            if (profile != null) {
                return FakePlayerFactory.get(world, profile);
            }
        }
        
        return FakePlayerFactory.getMinecraft(world);
    }
    
    public void update() {
        super.update();
        if (this.canUpdate() && this.ticks % this.upgrades.getSpeed(20, 4) == 0) {
            BlockPos front = this.pos.offset(this.getDirection());
            if (this.pickupItem && this.type == 0) {
                List<Entity> droppedItems = new ArrayList();
                Chunk chunk = this.world.getChunk(front);
                chunk.getEntitiesWithinAABBForEntity(null, new AxisAlignedBB(front), droppedItems, null);
                
                for (Entity entity : droppedItems) {
                    if (entity instanceof EntityItem && !entity.isDead) {
                        ItemStack droppedItem = ((EntityItem) entity).getItem();
                        if (IFilterable.acceptsItem(this.itemFilters, this.mode, this.compare, droppedItem) && this.network.insertItem(droppedItem, droppedItem.getCount(), Action.SIMULATE) == null) {
                            this.network.insertItemTracked(droppedItem.copy(), droppedItem.getCount());
                            this.world.removeEntity(entity);
                            break;
                        }
                    }
                }
            } else if (this.type == 0) {
                IBlockState frontBlockState = this.world.getBlockState(front);
                Block frontBlock = frontBlockState.getBlock();
                ItemStack frontStack = frontBlock.getPickBlock(frontBlockState, new RayTraceResult(new Vec3d(this.pos.getX(), this.pos.getY(), this.pos.getZ()), this.getDirection().getOpposite()), this.world, front, this.getFakePlayer());
                if (!frontStack.isEmpty() && IFilterable.acceptsItem(this.itemFilters, this.mode, this.compare, frontStack) && (double) frontBlockState.getBlockHardness(this.world, front) != -1.0D) {
                    NonNullList<ItemStack> drops = NonNullList.create();
                    if (frontBlock instanceof BlockShulkerBox) {
                        drops.add(((BlockShulkerBox) frontBlock).getItem(this.world, front, frontBlockState));
                        TileEntity shulkerBoxTile = this.world.getTileEntity(front);
                        if (shulkerBoxTile instanceof TileEntityShulkerBox) {
                            ((TileEntityShulkerBox) shulkerBoxTile).setDestroyedByCreativePlayer(true);
                            ((TileEntityShulkerBox) shulkerBoxTile).clear();
                        }
                    } else if (this.upgrades.hasUpgrade(6) && frontBlock.canSilkHarvest(this.world, front, frontBlockState, null)) {
                        drops.add(frontStack);
                    } else {
                        frontBlock.getDrops(drops, this.world, front, frontBlockState, this.upgrades.getFortuneLevel());
                    }
                    
                    Iterator var17 = drops.iterator();
                    
                    while (var17.hasNext()) {
                        ItemStack drop = (ItemStack) var17.next();
                        if (this.network.insertItem(drop, drop.getCount(), Action.SIMULATE) != null) {
                            return;
                        }
                    }
                    
                    BreakEvent e = new BreakEvent(this.world, front, frontBlockState, this.getFakePlayer());
                    if (!MinecraftForge.EVENT_BUS.post(e)) {
                        this.world.playEvent(null, 2001, front, Block.getStateId(frontBlockState));
                        this.world.setBlockToAir(front);
                        Iterator var20 = drops.iterator();
                        
                        while (var20.hasNext()) {
                            ItemStack drop = (ItemStack) var20.next();
                            if (this.network == null) {
                                InventoryHelper.spawnItemStack(this.world, front.getX(), front.getY(), front.getZ(), drop);
                            } else {
                                this.network.insertItemTracked(drop, drop.getCount());
                            }
                        }
                    }
                }
            } else if (this.type == 1) {
                Block frontBlock = this.world.getBlockState(front).getBlock();
                IFluidHandler handler = null;
                if (frontBlock instanceof BlockLiquid) {
                    handler = new BlockLiquidWrapper((BlockLiquid) frontBlock, this.world, front);
                } else if (frontBlock instanceof IFluidBlock) {
                    handler = new FluidBlockWrapper((IFluidBlock) frontBlock, this.world, front);
                }
                
                if (handler != null) {
                    FluidStack stack = handler.drain(1000, false);
                    if (stack != null && IFilterable.acceptsFluid(this.fluidFilters, this.mode, this.compare, stack) && this.network.insertFluid(stack, stack.amount, Action.SIMULATE) == null) {
                        FluidStack drained = handler.drain(1000, true);
                        this.network.insertFluidTracked(drained, drained.amount);
                    }
                }
            }
        }
        
    }
    
    public int getCompare() {
        return this.compare;
    }
    
    public void setCompare(int compare) {
        this.compare = compare;
        this.markDirty();
    }
    
    public int getMode() {
        return this.mode;
    }
    
    public void setMode(int mode) {
        this.mode = mode;
        this.markDirty();
    }
    
    public void read(NBTTagCompound tag) {
        super.read(tag);
        StackUtils.readItems(this.upgrades, 1, tag);
        if (tag.hasKey("Covers")) {
            this.coverManager.readFromNbt(tag.getTagList("Covers", 10));
        }
        
    }
    
    public String getId() {
        return "destructor";
    }
    
    public NBTTagCompound write(NBTTagCompound tag) {
        super.write(tag);
        StackUtils.writeItems(this.upgrades, 1, tag);
        tag.setTag("Covers", this.coverManager.writeToNbt());
        return tag;
    }
    
    public NBTTagCompound writeConfiguration(NBTTagCompound tag) {
        super.writeConfiguration(tag);
        tag.setInteger("Compare", this.compare);
        tag.setInteger("Mode", this.mode);
        tag.setInteger("Type", this.type);
        tag.setBoolean("Pickup", this.pickupItem);
        StackUtils.writeItems(this.itemFilters, 0, tag);
        tag.setTag("FluidFilters", this.fluidFilters.writeToNbt());
        return tag;
    }
    
    public void readConfiguration(NBTTagCompound tag) {
        super.readConfiguration(tag);
        if (tag.hasKey("Compare")) {
            this.compare = tag.getInteger("Compare");
        }
        
        if (tag.hasKey("Mode")) {
            this.mode = tag.getInteger("Mode");
        }
        
        if (tag.hasKey("Type")) {
            this.type = tag.getInteger("Type");
        }
        
        if (tag.hasKey("Pickup")) {
            this.pickupItem = tag.getBoolean("Pickup");
        }
        
        StackUtils.readItems(this.itemFilters, 0, tag);
        if (tag.hasKey("FluidFilters")) {
            this.fluidFilters.readFromNbt(tag.getCompoundTag("FluidFilters"));
        }
        
        OneSixMigrationHelper.migrateEmptyWhitelistToEmptyBlacklist(this.version, this, this.itemFilters);
    }
    
    public IItemHandler getUpgrades() {
        return this.upgrades;
    }
    
    public IItemHandler getInventory() {
        return this.itemFilters;
    }
    
    public boolean hasConnectivityState() {
        return true;
    }
    
    public IItemHandler getDrops() {
        return new CombinedInvWrapper(this.upgrades, this.coverManager.getAsInventory());
    }
    
    public int getType() {
        return this.world.isRemote ? TileDestructor.TYPE.getValue() : this.type;
    }
    
    public void setType(int type) {
        this.type = type;
        this.markDirty();
    }
    
    public IItemHandlerModifiable getItemFilters() {
        return this.itemFilters;
    }
    
    public FluidInventory getFluidFilters() {
        return this.fluidFilters;
    }
    
    public boolean canConduct(@Nullable EnumFacing direction) {
        return this.coverManager.canConduct(direction);
    }
    
    public boolean isPickupItem() {
        return this.pickupItem;
    }
    
    public void setPickupItem(boolean pickupItem) {
        this.pickupItem = pickupItem;
    }
    
    public CoverManager getCoverManager() {
        return this.coverManager;
    }
}
