package com.akun.hajikun.camera;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** 三脚架方块实体：只保存装在上面那台照相机（含它的胶卷）。 */
public class TripodBlockEntity extends BlockEntity {
    private ItemStack camera = ItemStack.EMPTY;
    /** 玩家破坏时已经手动掉过战利品，非玩家破坏的 destroy() 不再重复掉落。 */
    private boolean looted;

    public TripodBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public ItemStack camera() {
        return this.camera;
    }

    public boolean mounted() {
        return !this.camera.isEmpty();
    }

    public void setCamera(ItemStack stack) {
        this.camera = stack == null ? ItemStack.EMPTY : stack.copyWithCount(1);
        this.setChanged();
    }

    public ItemStack takeCamera() {
        ItemStack stack = this.camera;
        this.camera = ItemStack.EMPTY;
        this.setChanged();
        return stack;
    }

    public void markLooted() {
        this.looted = true;
    }

    public boolean looted() {
        return this.looted;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (!this.camera.isEmpty()) {
            output.store("camera", ItemStack.CODEC, this.camera);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.camera = input.read("camera", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        this.looted = false;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        // 客户端只需要 blockstate 里的 mounted 标志，胶卷仍留在服务端。
        return new CompoundTag();
    }
}
