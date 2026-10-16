package dev.mcpfabric.supervision;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What the narrow supervised operations accept (#27): plain block ids and block states without data,
 * and only commands whose root the operator listed in {@code supervisedCommands}. Every method throws
 * {@link IllegalArgumentException} with a message for the caller.
 */
public final class SupervisedRules {
	private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+(:[a-z0-9_./-]+)?");
	private static final Pattern STATE = Pattern.compile("\\[[a-z0-9_]+=[a-z0-9_]+(,[a-z0-9_]+=[a-z0-9_]+)*]");
	public static final int MAX_COMMAND_LENGTH = 256;

	private SupervisedRules() {}

	/** A block id such as {@code stone} or {@code minecraft:stone}, namespaced. */
	public static String blockId(String id) {
		String s = id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
		if (!ID.matcher(s).matches()) {
			throw new IllegalArgumentException("Not a block id: '" + id + "' (expected e.g. minecraft:stone, without state or data).");
		}
		return s.indexOf(':') < 0 ? "minecraft:" + s : s;
	}

	/**
	 * A block with an optional state ({@code oak_stairs[facing=north]}), namespaced. Block data
	 * ({@code {...}}) is refused: it can create command blocks.
	 */
	public static String block(String block) {
		String s = block == null ? "" : block.strip().toLowerCase(Locale.ROOT);
		int bracket = s.indexOf('[');
		String id = bracket < 0 ? s : s.substring(0, bracket);
		String state = bracket < 0 ? "" : s.substring(bracket);
		if (!ID.matcher(id).matches() || (!state.isEmpty() && !STATE.matcher(state).matches())) {
			throw new IllegalArgumentException("Not a block: '" + block + "' (expected e.g. minecraft:oak_stairs[facing=north]; block data {...} is not accepted).");
		}
		return (id.indexOf(':') < 0 ? "minecraft:" + id : id) + state;
	}

	/** The id of a block from {@link #block}, without its state. */
	public static String baseId(String block) {
		int bracket = block.indexOf('[');
		return bracket < 0 ? block : block.substring(0, bracket);
	}

	/**
	 * A single-line command without a leading slash whose root (first word) is one of
	 * {@code allowedRoots}. Data ({@code {...}}) is refused in every command.
	 */
	public static String command(String command, Collection<String> allowedRoots) {
		String s = command == null ? "" : command.strip();
		if (s.startsWith("/")) s = s.substring(1).strip();
		if (s.isEmpty()) throw new IllegalArgumentException("Empty command.");
		if (s.length() > MAX_COMMAND_LENGTH) throw new IllegalArgumentException("Commands are limited to " + MAX_COMMAND_LENGTH + " characters.");
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < 0x20 || c == 0x7f) throw new IllegalArgumentException("A command is one line of printable text.");
			if (c == '{') throw new IllegalArgumentException("Command data ({...}) is not accepted in supervised commands.");
		}
		int space = s.indexOf(' ');
		String root = (space < 0 ? s : s.substring(0, space)).toLowerCase(Locale.ROOT);
		boolean allowed = false;
		if (allowedRoots != null) {
			for (String a : allowedRoots) {
				if (a != null && root.equals(a.strip().replaceFirst("^/", "").toLowerCase(Locale.ROOT))) allowed = true;
			}
		}
		if (!allowed) {
			throw new IllegalArgumentException("Command '" + root + "' is not in supervisedCommands in mcpfabric.config.json.");
		}
		return s;
	}
}
