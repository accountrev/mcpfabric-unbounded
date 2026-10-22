package dev.mcpfabric.client.config;

//? if >=1.21.9 {
/*import com.mojang.blaze3d.platform.InputConstants;
*///?}
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
//? if >=1.21.9 {
/*import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
*///?}
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Optional;

/** A row of buttons sharing its width: Cloth Config has no button entry. */
final class ButtonRowEntry extends AbstractConfigListEntry<Object> {
	private final List<Button> buttons;

	ButtonRowEntry(Button... buttons) {
		super(Component.empty(), false);
		this.buttons = List.of(buttons);
	}

	/** Press a button the way a click does, without the click sound. */
	static void press(Button button) {
		//? if <1.21.9 {
		button.onPress();
		//?} else
		/*button.onPress(new MouseButtonEvent(0, 0, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));*/
	}

	@Override
	public Object getValue() {
		return null;
	}

	@Override
	public Optional<Object> getDefaultValue() {
		return Optional.empty();
	}

	@Override
	public List<? extends GuiEventListener> children() {
		return buttons;
	}

	@Override
	public List<? extends NarratableEntry> narratables() {
		return buttons;
	}

	//? if <26.1 {
	@Override
	public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float delta) {
		layout(x, y, entryWidth);
		for (Button button : buttons) button.render(graphics, mouseX, mouseY, delta);
	}
	//?} else {
	/*@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean isHovered, float delta) {
		layout(x, y, entryWidth);
		for (Button button : buttons) button.extractRenderState(graphics, mouseX, mouseY, delta);
	}
	*///?}

	private void layout(int x, int y, int width) {
		int gap = 4;
		int each = (width - gap * (buttons.size() - 1)) / buttons.size();
		for (int i = 0; i < buttons.size(); i++) {
			Button button = buttons.get(i);
			button.setX(x + i * (each + gap));
			button.setY(y);
			button.setWidth(each);
		}
	}
}
