package dev.theredja.src2mc.client.levels;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The INFRA-style loading screen shown over a level change: black with a thin placement progress bar
 * laid out like infra_loading_screen.res. Never pauses the game, never blurs, and Esc does not close
 * it; on failure it shows the message centred with a Back button instead.
 */
public final class SourceLoadingScreen extends Screen {
    private final String mapName;
    private String failure;

    SourceLoadingScreen(String mapName, String failure) {
        super(Component.literal("Loading"));
        this.mapName = mapName;
        this.failure = failure;
    }

    /** Switches to the failed state: the message and a Back button. */
    void fail(String message) {
        failure = message == null || message.isEmpty() ? "Level change failed." : message;
        rebuildWidgets();
    }

    @Override
    protected void init() {
        if (failure == null) return;
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> LevelChangeClient.dismiss())
            .bounds(width / 2 - 50, height / 2 + 20, 100, 20).build());
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF000000);
        if (failure != null) {
            graphics.drawCenteredString(font, failure, width / 2, height / 2 - 10, 0xFFFF5555);
        } else {
            // INFRA infra_loading_screen.res (640x480 virtual): bar at x=c-256, y=396, 511x1, fg 199, bg 16.
            float scale = height / 480f;
            int barX = Math.round(width / 2f - 256 * scale);
            int barY = Math.round(396 * scale);
            int barW = Math.round(511 * scale);
            int barH = Math.max(1, Math.round(scale));
            graphics.fill(barX, barY, barX + barW, barY + barH, 0xFF101010);
            graphics.fill(barX, barY, barX + Math.round(barW * LevelChangeClient.progress()), barY + barH, 0xFFC7C7C7);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
