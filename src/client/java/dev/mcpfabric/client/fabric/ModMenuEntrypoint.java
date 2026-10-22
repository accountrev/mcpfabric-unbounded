package dev.mcpfabric.client.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.mcpfabric.client.config.ConfigScreen;
import net.fabricmc.loader.api.FabricLoader;

/** Mod Menu's config button. The screen is built with Cloth Config, so without it there is none. */
public class ModMenuEntrypoint implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return FabricLoader.getInstance().isModLoaded("cloth-config") ? ConfigScreen::create : ModMenuApi.super.getModConfigScreenFactory();
	}
}
