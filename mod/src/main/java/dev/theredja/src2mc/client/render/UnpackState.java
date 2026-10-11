package dev.theredja.src2mc.client.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

/**
 * Pixel-unpack state for our texture uploads. Vanilla and other mods leave a row length, skips or
 * a bound unpack buffer behind (vanilla's {@code NativeImage.upload} sets them per call), so an
 * upload that only sets the alignment can read the wrong bytes: a colour-correction lookup
 * re-uploaded after a world switch turned the screen green that way.
 */
public final class UnpackState {
    private UnpackState() {}

    /** Tightly packed rows at {@code alignment}, no skips, no unpack buffer; returns the unpack buffer that was bound. */
    public static int begin(int alignment) {
        int buffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        if (buffer != 0) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        return buffer;
    }

    /** Back to the default alignment and the unpack buffer {@link #begin} found. */
    public static void end(int buffer) {
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        if (buffer != 0) GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, buffer);
    }
}
