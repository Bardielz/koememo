package dev.koememo.offloadprobe;

import static android.opengl.EGL14.*;
import static android.opengl.GLES31.*;

import android.opengl.*;
import android.util.Log;
import java.nio.*;
import java.util.Arrays;

final class GpuLevel {
  private static final String SOURCE =
      "#version 310 es\n"
          + "layout(local_size_x=64) in; layout(std430,binding=0) readonly buffer Input { uint"
          + " pcm[]; };\n"
          + "layout(std430,binding=1) writeonly buffer Result { float sum[]; }; shared float"
          + " partial[64];\n"
          + "void main(){ uint i=gl_GlobalInvocationID.x, j=gl_LocalInvocationID.x; uint"
          + " v=pcm[i];\n"
          + "float a=float(int((v & 65535u)^32768u)-32768), b=float(int((v>>16u)^32768u)-32768);\n"
          + "partial[j]=a*a+b*b; barrier(); for(uint n=32u;n>0u;n>>=1u){\n"
          + "if(j<n)partial[j]+=partial[j+n]; barrier(); }"
          + " if(j==0u)sum[gl_WorkGroupID.x]=partial[0]; }";

  static void run() {
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    try {
      if (!eglInitialize(display, null, 0, null, 0)) throw new IllegalStateException("EGL init");
      EGLConfig[] cfg = new EGLConfig[1];
      int[] count = new int[1];
      if (!eglChooseConfig(
              display,
              new int[] {
                EGL_RENDERABLE_TYPE,
                EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL_SURFACE_TYPE,
                EGL_PBUFFER_BIT,
                EGL_NONE
              },
              0,
              cfg,
              0,
              1,
              count,
              0)
          || count[0] == 0) throw new IllegalStateException("No ES3 config");
      context =
          eglCreateContext(
              display,
              cfg[0],
              EGL_NO_CONTEXT,
              new int[] {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE},
              0);
      surface =
          eglCreatePbufferSurface(
              display, cfg[0], new int[] {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE}, 0);
      if (!eglMakeCurrent(display, surface, surface, context))
        throw new IllegalStateException("No EGL context");
      Log.i(Probe.TAG, "renderer=" + glGetString(GL_RENDERER));
      int shader = glCreateShader(GL_COMPUTE_SHADER);
      glShaderSource(shader, SOURCE);
      glCompileShader(shader);
      int program = glCreateProgram();
      glAttachShader(program, shader);
      glLinkProgram(program);
      glGetProgramiv(program, GL_LINK_STATUS, count, 0);
      if (count[0] == 0)
        throw new IllegalStateException(glGetShaderInfoLog(shader) + glGetProgramInfoLog(program));
      glUseProgram(program);
      for (int rate : new int[] {16000, 24000, 48000}) benchmark(rate);
    } finally {
      eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
      if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
      if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
      eglTerminate(display);
    }
  }

  private static void benchmark(int rate) {
    int samples = rate * 128 / 1000, groups = samples / 128;
    ByteBuffer pcm = ByteBuffer.allocateDirect(samples * 2).order(ByteOrder.nativeOrder());
    for (int i = 0; i < samples; i++) pcm.putShort((short) (i * 1237 + 31));
    pcm.flip();
    byte[] original = new byte[pcm.remaining()];
    pcm.get(original);
    pcm.rewind();
    int[] buffers = new int[2];
    glGenBuffers(2, buffers, 0);
    for (int i = 0; i < 2; i++) {
      glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffers[i]);
      glBufferData(
          GL_SHADER_STORAGE_BUFFER, i == 0 ? samples * 2 : groups * 4, null, GL_DYNAMIC_DRAW);
      glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, buffers[i]);
    }
    long[] cpu = new long[50], gpu = new long[50];
    double error = 0;
    for (int trial = -10; trial < cpu.length; trial++) {
      long start = System.nanoTime();
      double ref = 0;
      for (int i = 0; i < original.length; i += 2) {
        double x = (short) ((original[i] & 255) | (original[i + 1] << 8));
        ref += x * x;
      }
      long middle = System.nanoTime();
      glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffers[0]);
      glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, samples * 2, pcm);
      glDispatchCompute(groups, 1, 1);
      glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
      glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffers[1]);
      ByteBuffer result =
          (ByteBuffer) glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, groups * 4, GL_MAP_READ_BIT);
      if (result == null) throw new IllegalStateException("GPU readback");
      result.order(ByteOrder.nativeOrder());
      double sum = 0;
      for (int g = 0; g < groups; g++) sum += result.getFloat();
      if (!glUnmapBuffer(GL_SHADER_STORAGE_BUFFER) || glGetError() != GL_NO_ERROR)
        throw new IllegalStateException("GPU buffer error");
      long end = System.nanoTime();
      error = Math.max(error, Math.abs(10 * Math.log10(sum / ref)));
      if (trial >= 0) {
        cpu[trial] = middle - start;
        gpu[trial] = end - middle;
      }
    }
    glDeleteBuffers(2, buffers, 0);
    if (!(error < 0.01)) throw new IllegalStateException("energy mismatch: " + error);
    Arrays.sort(cpu);
    Arrays.sort(gpu);
    Log.i(
        Probe.TAG,
        "rate="
            + rate
            + " samples="
            + samples
            + " cpu_median_us="
            + cpu[25] / 1000.0
            + " gpu_roundtrip_median_us="
            + gpu[25] / 1000.0
            + " max_error_db="
            + error);
  }
}
