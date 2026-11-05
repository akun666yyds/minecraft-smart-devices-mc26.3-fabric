package com.akun.hajikun.tv;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/** 电视物品：携带尺寸，放置时由方块读取并铺开整块多格屏幕。 */
public class TvItem extends BlockItem {
    private final TvKind kind;

    public TvItem(Block block, Item.Properties properties, TvKind kind) {
        super(block, properties);
        this.kind = kind;
    }

    public TvKind kind() {
        return this.kind;
    }
}
