package dev.mcpfabric.handlers.support;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.config.McpConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
//? if >=1.21.6 {
import net.minecraft.util.ProblemReporter;
//?}
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
//? if >=1.21.6 {
import net.minecraft.world.level.storage.TagValueOutput;
//?}

/**
 * Data the {@code data} toggles of the mod config add to answers. Each {@code add*} method does
 * nothing while its toggles are off, so answers stay as they were by default.
 */
public final class ExtraData {
	private ExtraData() {}

	/**
	 * The stack's data components that differ from its item's defaults (enchantments, custom name,
	 * lore, damage, potion contents, custom data...), as {@code components}: those the item toggles send.
	 */
	public static void addComponents(JsonObject out, ItemStack stack, HolderLookup.Provider registries) {
		McpConfig.ItemComponents toggles = McpFabric.config().data.items;
		if (!toggles.any() || stack.isEmpty()) return;
		DataComponentPatch patch = stack.getComponentsPatch();
		if (patch.isEmpty()) return;
		DataComponentPatch.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), patch).result().ifPresent(all -> {
			JsonObject sent = new JsonObject();
			all.getAsJsonObject().entrySet().forEach(c -> {
				if (toggles.sends(c.getKey())) sent.add(c.getKey(), c.getValue());
			});
			if (sent.size() > 0) out.add("components", sent);
		});
	}

	/** The entity's full saved data, as {@code nbt}. */
	public static void addEntityNbt(JsonObject out, Entity e) {
		McpConfig.EntityNbt toggles = McpFabric.config().data.entityNbt;
		if (e instanceof Player ? toggles.players : toggles.others) out.add("nbt", json(save(e)));
	}

	/** The block entity's saved data (container contents, sign text, spawner...), as {@code blockEntity}. */
	public static void addBlockEntity(JsonObject out, Level level, BlockPos pos, BlockState state) {
		McpConfig.BlockEntities toggles = McpFabric.config().data.blockEntities;
		if (!(toggles.containers || toggles.signs || toggles.spawners || toggles.other) || !state.hasBlockEntity()) return;
		BlockEntity be = level.getBlockEntity(pos);
		if (be == null) return;
		boolean sent = be instanceof Container ? toggles.containers
				: be instanceof SignBlockEntity ? toggles.signs
				: be instanceof SpawnerBlockEntity || be instanceof TrialSpawnerBlockEntity ? toggles.spawners
				: toggles.other;
		if (sent) out.add("blockEntity", json(be.saveWithFullMetadata(level.registryAccess())));
	}

	/** Active effects by id. */
	public static JsonObject effects(LivingEntity le) {
		JsonObject o = new JsonObject();
		for (MobEffectInstance inst : le.getActiveEffects()) {
			JsonObject e = new JsonObject();
			e.addProperty("amplifier", inst.getAmplifier());
			e.addProperty("durationTicks", inst.getDuration());
			o.add(BuiltInRegistries.MOB_EFFECT.getKey(inst.getEffect().value()).toString(), e);
		}
		return o;
	}

	/** Current value of every attribute the entity has, by id. */
	public static JsonObject attributes(LivingEntity le) {
		JsonObject o = new JsonObject();
		for (Attribute attribute : BuiltInRegistries.ATTRIBUTE) {
			AttributeInstance instance = le.getAttribute(BuiltInRegistries.ATTRIBUTE.wrapAsHolder(attribute));
			if (instance != null) o.addProperty(BuiltInRegistries.ATTRIBUTE.getKey(attribute).toString(), instance.getValue());
		}
		return o;
	}

	/** Entities save through a ValueOutput since 1.21.6. */
	private static CompoundTag save(Entity e) {
		//? if >=1.21.6 {
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, e.registryAccess());
		e.saveWithoutId(out);
		return out.buildResult();
		//?} else
		/*return e.saveWithoutId(new CompoundTag());*/
	}

	private static JsonElement json(CompoundTag tag) {
		return NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag);
	}
}
