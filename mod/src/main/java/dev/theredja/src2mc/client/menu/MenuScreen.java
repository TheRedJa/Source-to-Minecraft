package dev.theredja.src2mc.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The invisible screen that gives the menu its mouse cursor: Minecraft releases the mouse while any screen is
 * open, and this one draws nothing (no blur, no background), does not pause the game and sends what the cursor
 * does to {@link MenuClient}, which works out the panel under it. Esc opens the pause menu, as in the game.
 */
final class MenuScreen extends Screen {
    MenuScreen() { super(Component.empty()); }

    @Override public boolean isPauseScreen() { return false; }

    @Override public boolean shouldCloseOnEsc() { return false; }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    /** Draws nothing; the hover is worked out every frame, as the camera may be gliding under a still cursor. */
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MenuClient.hover(mouseX, mouseY);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        MenuClient.click(mouseX, mouseY);
        return true;
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            MenuClient.pause();
            return true;
        }
        // Chat stays reachable as the dev console; the menu screen comes back once chat closes.
        var options = Minecraft.getInstance().options;
        if (options.keyChat.matches(keyCode, scanCode)) {
            Minecraft.getInstance().setScreen(new ChatScreen(""));
            return true;
        }
        if (options.keyCommand.matches(keyCode, scanCode)) {
            Minecraft.getInstance().setScreen(new ChatScreen("/"));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
