package dev.theredja.src2mc.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/**
 * A copy of the main render target's depth at one moment of the frame, for a later pass to read.
 * Copied by a framebuffer blit into a texture of the same internal format, which a blit requires;
 * with Iris the main target's depth texture is also its {@code depthtex0}, so the copy sees the
 * shader pack's geometry too. Raw GL throughout: Iris neither redirects nor blocks it, and every
 * binding it changes is put back.
 */
final class DepthSnapshot implements AutoCloseable {
    private int texture = -1, source = -1, destination = -1;
    private int width, height, format;

    /** The copy's texture; -1 before the first copy. */
    int texture() { return texture; }

    /** Copies the main target's depth as it is now. False when there is nothing to copy. */
    boolean copy(RenderTarget main) {
        RenderSystem.assertOnRenderThread();
        int depth = main.getDepthTextureId();
        if (depth <= 0 || main.width <= 0 || main.height <= 0) return false;
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
        int sourceFormat = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            allocate(main.width, main.height, sourceFormat);
            if (source < 0) source = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source);
            int attachment = stencil(sourceFormat) ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT;
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, depth, 0);
            GL11.glReadBuffer(GL11.GL_NONE);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, destination);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            // Detached again, so the main target's depth is not held by a framebuffer of ours.
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, 0, 0);
            return true;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        }
    }

    private void allocate(int newWidth, int newHeight, int newFormat) {
        if (texture >= 0 && newWidth == width && newHeight == height && newFormat == format) return;
        close();
        width = newWidth;
        height = newHeight;
        format = newFormat;
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        boolean stencil = stencil(format);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, format, width, height, 0,
            stencil ? GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT,
            stencil ? (format == GL30.GL_DEPTH32F_STENCIL8 ? GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV : GL30.GL_UNSIGNED_INT_24_8) : GL11.GL_FLOAT,
            (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL30.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL30.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        destination = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, destination);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, stencil ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT,
            GL11.GL_TEXTURE_2D, texture, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
    }

    private static boolean stencil(int format) {
        return format == GL30.GL_DEPTH24_STENCIL8 || format == GL30.GL_DEPTH32F_STENCIL8 || format == GL30.GL_DEPTH_STENCIL;
    }

    @Override
    public void close() {
        if (texture >= 0) GL11.glDeleteTextures(texture);
        if (destination >= 0) GL30.glDeleteFramebuffers(destination);
        texture = -1;
        destination = -1;
    }
}
