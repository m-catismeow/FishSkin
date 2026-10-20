package dev.mcatismeow.fishskin.runtime.forge;

import dev.mcatismeow.fishskin.runtime.Platform;
import dev.mcatismeow.fishskin.runtime.net.Net;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.nio.file.Path;

final class ForgePlatform implements Platform {
    private final ForgeNet net = new ForgeNet();

    @Override public Path configDir() { return FMLPaths.CONFIGDIR.get(); }

    @Override public Path gameDir() { return FMLPaths.GAMEDIR.get(); }

    @Override public boolean isModLoaded(String id) { return ModList.get().isLoaded(id); }

    @Override public Net net() { return net; }

    @Override
    public <T extends BlockEntity> BlockEntityType<T> blockEntityType(BlockEntityFactory<T> factory, Block block) {
        return BlockEntityType.Builder.<T>of(factory::create, block).build(null);
    }

    ForgeNet forgeNet() { return net; }
}
