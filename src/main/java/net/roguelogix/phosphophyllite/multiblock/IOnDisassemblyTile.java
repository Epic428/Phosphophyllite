package net.roguelogix.phosphophyllite.multiblock;

@Deprecated(forRemoval = true)
public interface IOnDisassemblyTile {
    /**
     * Called when a multiblock is disassembled
     * not called when a multiblock is paused
     * called after a controller's onDisassembled and blockstates are updated
     */
    void onDisassembly();
}
