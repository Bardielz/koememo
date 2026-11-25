package dev.koememo.probes;

import android.opengl.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import static android.opengl.EGL14.*;
import static android.opengl.GLES31.*;

/** Shell-only RNNoise benchmark. No microphone, activity, or application data access. */
public final class RnGpu {
    private static final int MAX = 12;
    private static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }
    private static void check(boolean ok, String operation) {
        if (!ok) throw new IllegalStateException(operation);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 3 && args.length != 4) throw new IllegalArgumentException("probe-directory input.pcm output.f32 [scores.f32]");
        EGLDisplay d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        EGLContext ctx = EGL_NO_CONTEXT;
        EGLSurface surface = EGL_NO_SURFACE;
        try {
            check(eglInitialize(d, null, 0, null, 0), "eglInitialize");
            EGLConfig[] cfg = new EGLConfig[1];
            int[] count = new int[1];
            check(eglChooseConfig(d, new int[]{EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE}, 0, cfg, 0, 1, count, 0) && count[0]>0, "ES3 config");
            ctx = eglCreateContext(d, cfg[0], EGL_NO_CONTEXT, new int[]{EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE}, 0);
            surface = eglCreatePbufferSurface(d,cfg[0],new int[]{EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE},0);
            check(eglMakeCurrent(d,surface,surface,ctx), "eglMakeCurrent");
            System.err.println("renderer=" + glGetString(GL_RENDERER));
            run(args);
        } finally {
            eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
            if (surface != EGL_NO_SURFACE) eglDestroySurface(d,surface);
            if (ctx != EGL_NO_CONTEXT) eglDestroyContext(d,ctx);
            eglTerminate(d);
        }
    }
    private static void run(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(shader, new String(Files.readAllBytes(dir.resolve("network.glsl")), "UTF-8"));
        glCompileShader(shader);
        int[] ok = new int[1];
        glGetShaderiv(shader,GL_COMPILE_STATUS,ok,0);
        check(ok[0]!=0, glGetShaderInfoLog(shader));
        int program = glCreateProgram();
        glAttachShader(program,shader);
        glLinkProgram(program);
        glGetProgramiv(program,GL_LINK_STATUS,ok,0);
        check(ok[0]!=0,glGetProgramInfoLog(program));
        glUseProgram(program);
        int uniform = glGetUniformLocation(program,"frames");
        byte[] model = Files.readAllBytes(dir.resolve("weights.bin"));
        int[] size = {model.length,MAX*42*4,MAX*23*4,168*4};
        int[] id = new int[4];
        glGenBuffers(4,id,0);
        for (int i=0;i<4;i++) {
            ByteBuffer data = direct(size[i]); // zero initial recurrent state
            if (i==0) { data.put(model); data.rewind(); }
            glBindBuffer(GL_SHADER_STORAGE_BUFFER,id[i]);
            glBufferData(GL_SHADER_STORAGE_BUFFER,size[i],data,i==0?GL_STATIC_DRAW:GL_DYNAMIC_DRAW);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,i,id[i]);
        }
        byte[] input = new byte[size[1]], output = new byte[size[2]];
        ByteBuffer upload = direct(size[1]);
        java.util.List<String> command = new java.util.ArrayList<>();
        java.util.Collections.addAll(command,dir.resolve("pipeline").toString(),"gpu",args[1],args[2]);
        if (args.length == 4) command.add(args[3]);
        Process child = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (DataInputStream from = new DataInputStream(child.getInputStream());
             OutputStream to = child.getOutputStream()) {
            int n;
            while ((n=from.read())!=-1) {
                check(n>=1 && n<=MAX,"invalid batch");
                int inputBytes=n*42*4, outputBytes=n*23*4;
                from.readFully(input,0,inputBytes);
                upload.clear();
                upload.put(input,0,inputBytes).flip();
                glBindBuffer(GL_SHADER_STORAGE_BUFFER,id[1]);
                glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,inputBytes,upload);
                glUniform1i(uniform,n);
                glDispatchCompute(1,1,1);
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
                glBindBuffer(GL_SHADER_STORAGE_BUFFER,id[2]);
                ByteBuffer mapped = (ByteBuffer) glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,outputBytes,GL_MAP_READ_BIT);
                check(mapped!=null,"GPU readback");
                mapped.get(output,0,outputBytes);
                check(glUnmapBuffer(GL_SHADER_STORAGE_BUFFER) && glGetError()==GL_NO_ERROR,"GPU batch failed");
                to.write(output,0,outputBytes);
                to.flush();
            }
            check(child.waitFor()==0,"pipeline failed: " + child.exitValue());
        } finally {
            child.destroy();
            glDeleteBuffers(4,id,0);
            glDeleteShader(shader);
            glDeleteProgram(program);
        }
    }
}
