package mods.immibis.redlogic.chips.ingame;

import java.util.Arrays;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.util.ForgeDirection;

import mods.immibis.redlogic.Utils;
import mods.immibis.redlogic.api.wiring.IBundledEmitter;
import mods.immibis.redlogic.api.wiring.IBundledUpdatable;
import mods.immibis.redlogic.api.wiring.IBundledWire;
import mods.immibis.redlogic.api.wiring.IConnectable;
import mods.immibis.redlogic.api.wiring.IRedstoneEmitter;
import mods.immibis.redlogic.api.wiring.IRedstoneUpdatable;
import mods.immibis.redlogic.api.wiring.IRedstoneWire;
import mods.immibis.redlogic.api.wiring.IWire;
import mods.immibis.redlogic.chips.generated.CCOFactory;
import mods.immibis.redlogic.chips.generated.CompiledCircuitObject;

public class TileCustomCircuit extends TileEntity implements IRedstoneUpdatable, IRedstoneEmitter, IBundledUpdatable, IBundledEmitter, IConnectable {
	private final boolean[][] circuitOutputs = new boolean[6][];

	private String className = "";
	private CompiledCircuitObject circuit;
	private boolean failedCreatingCircuit;
	private byte[] serializedCircuit;

	private int rotation; // 0 to 3, clockwise (when seen from top)
	private int color; // matches wool
	private String customName; // copied from ItemStack

	private boolean isChangeDelayed; // redstone expects changes at most once every 2 in-game ticks
	private boolean updateQueued = false;

	public static final int[][] actualToFakeDirMap = new int[][] {
		{0, 1, 2, 3, 4, 5},
		{0, 1, 4, 5, 3, 2},
		{0, 1, 3, 2, 5, 4},
		{0, 1, 5, 4, 2, 3},
	};
	
	private int actualToFakeDir(int dir) {
		return actualToFakeDirMap[(rotation - circuit.rotation) & 3][dir];
	}
	
	private void createCircuitObject() {
		if(CCOFactory.instance == null)
			return;
		
		if(circuit == null && !failedCreatingCircuit) {
			try {
				if(serializedCircuit != null) {
					circuit = CCOFactory.deserialize(serializedCircuit);
					serializedCircuit = null;
					if(circuit._inputs == null || circuit._outputs == null)
						circuit = null;
				} else
					circuit = CCOFactory.instance.createObject(className);
			} catch(Throwable e) {
				new Exception("Failed to create circuit object at "+xCoord+","+yCoord+","+zCoord+" unknown dimension", e).printStackTrace();
				failedCreatingCircuit = true;
			}
		}
	}

	private boolean storeOutputs() {
		boolean isUpdateNecessary = false;
		for (int i = 0; i < 6; i++) {
			if (!Arrays.equals(circuitOutputs[i], circuit._outputs[i])) {
				isUpdateNecessary = true;
				circuitOutputs[i] = circuit._outputs[i].clone();
			}
		}
		return isUpdateNecessary;
	}
	
	@Override
	public void updateEntity() {
		if(worldObj.isRemote) return;
		if(circuit == null)
			createCircuitObject();
		
		updateQueued = true;
		if (updateQueued && circuit != null) {
			updateQueued = false;
			
			try {
				circuit.update();
			} catch(Error e) {
				failedCreatingCircuit = true;
				circuit = null;
				e.printStackTrace();
				return;
			}

			if (storeOutputs()) {
				if (!isChangeDelayed) {
					notifyExtendedNeighbours();
					isChangeDelayed = true;
				}
			}
			
			for(int k = 0; k < 6; k++) {
				ForgeDirection dir = ForgeDirection.VALID_DIRECTIONS[k];
				TileEntity te = worldObj.getTileEntity(xCoord+dir.offsetX, yCoord+dir.offsetY, zCoord+dir.offsetZ);
				switch(circuit._outputs[actualToFakeDir(k)].length) {
				case 1:
					if(te instanceof IRedstoneUpdatable)
						((IRedstoneUpdatable)te).onRedstoneInputChanged();
					break;
				case 16:
					if(te instanceof IBundledUpdatable)
						((IBundledUpdatable)te).onBundledInputChanged();
					break;
				}
			}
		}

		if (isChangeDelayed) {
			isChangeDelayed = false;
		}
	}

	public void init(ItemStack stack, EntityPlayer player) {
		Vec3 look = player.getLook(1.0f);
		
        double absx = Math.abs(look.xCoord);
        double absz = Math.abs(look.zCoord);
        
        if(absx > absz)
        	if(look.xCoord < 0)
        		rotation = 3;
        	else
        		rotation = 1;
        else
        	if(look.zCoord < 0)
        		rotation = 0;
        	else
        		rotation = 2;
        
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
		
		this.className = ItemCustomCircuit.getClassName(stack);
		this.color = ItemCustomCircuit.getColor(stack);
		this.customName = ItemCustomCircuit.getDisplayName(stack);
		createCircuitObject();

		worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
		onRedstoneInputChanged();
	}
	
	/*private short encodeBits(boolean[] a) {
		short rv = 0;
		for(int k = 0; k < a.length; k++)
			if(a[k])
				rv |= (short)(1 << k);
		return rv;
	}
	private void decodeBits(boolean[] a, short enc) {
		for(int k = 0; k < a.length; k++)
			a[k] = (enc & (1 << k)) != 0;
	}*/
	
	@Override
	public void writeToNBT(NBTTagCompound tag) {
		super.writeToNBT(tag);
		tag.setString("className", className);
		
		if(circuit != null) {
			tag.setByteArray("serialized", CCOFactory.serialize(circuit));
			
		} else if(serializedCircuit != null)
			// circuit failed to deserialize, so we keep the serialized bytes around
			tag.setByteArray("serialized", serializedCircuit);
		
		tag.setBoolean("uq", updateQueued);
		tag.setBoolean("cd", isChangeDelayed);
		tag.setByte("rotation", (byte) rotation);
		tag.setByte("color", (byte) color);
		if (tag.hasKey("customName")) {
			tag.setString("customName", customName);
		}
	}
	
	@Override
	public void readFromNBT(NBTTagCompound tag) {
		super.readFromNBT(tag);
		
		className = tag.getString("className");
		if(tag.hasKey("serialized"))
			serializedCircuit = tag.getByteArray("serialized");

		updateQueued = tag.getBoolean("uq");
		rotation = tag.getByte("rotation");
		color = tag.getByte("color");
		isChangeDelayed = tag.getBoolean("cd");
		if (customName != null && customName.length() > 0) {
			customName = tag.getString("customName");
		}
	}
	
	@Override
	public Packet getDescriptionPacket() {
		NBTTagCompound tag = new NBTTagCompound();
		tag.setByte("r", (byte) rotation);
		tag.setByte("c", (byte) color);
		return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, tag);
	}
	
	@Override
	public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
		rotation = pkt.func_148857_g().getByte("r");
		color = pkt.func_148857_g().getByte("c");
		worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
	}

	@Override
	public short getEmittedSignalStrength(int blockFace, int toDirection) {
		if(circuit == null)
			return 0;
		toDirection = actualToFakeDir(toDirection);
		if(circuit._outputs[toDirection].length != 1)
			return 0;
		return circuit._outputs[toDirection][0] ? (short)255 : 0;
	}
	
	@Override
	public byte[] getBundledCableStrength(int blockFace, int toDirection) {
		if(circuit == null)
			return null;
		
		toDirection = actualToFakeDir(toDirection);
		
		boolean[] outputs = circuit._outputs[toDirection];
		if(outputs.length != 16)
			return null;
		
		byte[] b = new byte[16];
		for(int k = 0; k < 16; k++)
			b[k] = outputs[k] ? (byte)255 : 0;
		return b;
	}
	
	@Override
	public boolean connects(IWire wire, int blockFace, int fromDirection) {
		if(circuit == null) return false;
		int numIn = circuit._inputs[fromDirection].length;
		int numOut = circuit._outputs[fromDirection].length;
		
		if(wire instanceof IRedstoneWire)
			return numIn == 1 || numOut == 1;
		if(wire instanceof IBundledWire)
			return numIn == 16 || numOut == 16;
		return false;
	}
	
	@Override
	public boolean connectsAroundCorner(IWire wire, int blockFace, int fromDirection) {
		return false;
	}

	@Override
	public void onRedstoneInputChanged() {
		if(circuit == null) return;
		if(updateQueued) return;
		
		boolean anyChanged = false;
		
		for(int k = 0; k < 6; k++) {
			ForgeDirection fd = ForgeDirection.VALID_DIRECTIONS[k];
			int fakeDir = actualToFakeDir(k);
			if(circuit._inputs[fakeDir].length == 1) {
				boolean _new = Utils.getPowerStrength(worldObj, xCoord+fd.offsetX, yCoord+fd.offsetY, zCoord+fd.offsetZ, k^1, -1) > 0;
				
				if(!_new)
					for(int i = 0; i < 6; i++)
						if((i&6) != (k&6)) {
							_new = Utils.getPowerStrength(worldObj, xCoord+fd.offsetX, yCoord+fd.offsetY, zCoord+fd.offsetZ, k^1, i) > 0;
							if(_new)
								break;
						}
				
				if(circuit._inputs[fakeDir][0] != _new) {
					circuit._inputs[fakeDir][0] = _new;
					anyChanged = true;
				}
				
			} else if(circuit._inputs[fakeDir].length == 16) {
				TileEntity te = worldObj.getTileEntity(xCoord+fd.offsetX, yCoord+fd.offsetY, zCoord+fd.offsetZ);
				if(te instanceof IBundledEmitter) {
					byte[] str = ((IBundledEmitter)te).getBundledCableStrength(-1, k^1);
					if(str == null)
						for(int i = 0; i < 6; i++)
							if((i&6) != (k&6)) {
								str = ((IBundledEmitter)te).getBundledCableStrength(i, k^1);
								if(str != null)
									break;
							}
					
					boolean[] inputs = circuit._inputs[fakeDir];
					if(str == null) {
						for(int i = 0; i < 16; i++) {
							if(inputs[i]) {
								inputs[i] = false;
								anyChanged = true;
							}
						}
						
					} else {	
						for(int i = 0; i < 16; i++) {
							if(inputs[i] != (str[i] != 0)) {
								inputs[i] = (str[i] != 0);
								anyChanged = true;
							}
						}
					}
				}
			}
		}
		
		updateQueued = anyChanged;
	}
	
	@Override
	public void onBundledInputChanged() {
		onRedstoneInputChanged();
	}
	
	private void notifyExtendedNeighbours() {
		worldObj.notifyBlocksOfNeighborChange(xCoord, yCoord, zCoord, getBlockType());
		worldObj.notifyBlocksOfNeighborChange(xCoord-1, yCoord, zCoord, getBlockType());
		worldObj.notifyBlocksOfNeighborChange(xCoord+1, yCoord, zCoord, getBlockType());
		worldObj.notifyBlocksOfNeighborChange(xCoord, yCoord-1, zCoord, getBlockType());
		worldObj.notifyBlocksOfNeighborChange(xCoord, yCoord+1, zCoord, getBlockType());
		worldObj.notifyBlocksOfNeighborChange(xCoord, yCoord, zCoord-1, getBlockType());
		worldObj.notifyBlocksOfNeighborChange(xCoord, yCoord, zCoord + 1, getBlockType());
	}

	public String getClassName() {
		return className;
	}

	public void rotate() {
		rotation = (rotation + 1) & 3;
		worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
		this.markDirty();
	}
	
	public int getRotation() {
		return rotation;
	}

	public int getColor() {
		return color;
	}

	public String getCustomItemName() {
		return customName;
	}

	public void setColor(int color) {
		this.color = color;
		worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
	}
}
