package com.pebbles_boon.metalrender.nativebridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class NativeLoader {
  private static final String MOD_ID = "metalrender";
  private static final String DYLIB_RESOURCE = "/libmetalrender.dylib";
  private static final String DYLIB_NAME = "libmetalrender.dylib";
  private static final String METALLIB_RESOURCE = "/shaders.metallib";
  private static final String METALLIB_NAME = "shaders.metallib";

  private static volatile boolean loaded;
  private static volatile boolean skippedNonMac;
  private static volatile Path loadedPath;
  private static volatile Path metallibPath;

  public static synchronized void load() {
    if (loaded || skippedNonMac) {
      return;
    }

    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (!os.contains("mac")) {
      skippedNonMac = true;
      return;
    }

    try {
      Path cacheDir = Paths.get(System.getProperty("user.home"), "." + MOD_ID, "natives");
      Files.createDirectories(cacheDir);

      Path dylibPath = cacheDir.resolve(DYLIB_NAME);
      if (!Files.exists(dylibPath) || isOutdated(DYLIB_RESOURCE, dylibPath)) {
        extractResource(DYLIB_RESOURCE, dylibPath);
        makeExecutable(dylibPath);
        removeQuarantine(dylibPath);
        adhocSign(dylibPath);
      }
      loadedPath = dylibPath.toAbsolutePath();
      System.load(loadedPath.toString());

      try {
        Path libPath = cacheDir.resolve(METALLIB_NAME);
        if (!Files.exists(libPath) || isOutdated(METALLIB_RESOURCE, libPath)) {
          extractResource(METALLIB_RESOURCE, libPath);
          removeQuarantine(libPath);
        }
        metallibPath = libPath.toAbsolutePath();
      } catch (IllegalStateException missing) {
        System.err.println("[MetalRender] bundled metallib not found, skipping: "
            + missing.getMessage());
      }

      loaded = true;
    } catch (Exception e) {
      throw new RuntimeException("Failed to load native library for " + MOD_ID, e);
    }
  }

  public static Path getLoadedPath() {
    return loadedPath;
  }

  public static Path getMetallibPath() {
    return metallibPath;
  }

  public static boolean isLoaded() {
    return loaded;
  }

  private static void extractResource(String resourcePath, Path target) throws IOException {
    try (InputStream in = NativeLoader.class.getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalStateException("Native resource not found: " + resourcePath);
      }
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static boolean isOutdated(String resourcePath, Path file) {
    try (InputStream in = NativeLoader.class.getResourceAsStream(resourcePath)) {
      if (in == null) {
        return false;
      }
      long bundledSize = in.available();
      if (bundledSize <= 0) {
        byte[] bundled = in.readAllBytes();
        bundledSize = bundled.length;
      }
      return Files.size(file) != bundledSize;
    } catch (IOException e) {
      return true;
    }
  }

  private static void makeExecutable(Path file) {
    try {
      Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
      perms.add(PosixFilePermission.OWNER_EXECUTE);
      Files.setPosixFilePermissions(file, perms);
    } catch (UnsupportedOperationException | IOException ignored) {
      file.toFile().setExecutable(true, true);
    }
  }

  private static void removeQuarantine(Path file) {
    try {
      new ProcessBuilder("xattr", "-d", "com.apple.quarantine", file.toString())
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .start()
          .waitFor(15, TimeUnit.SECONDS);
    } catch (Exception ignored) {
    }
  }

  private static void adhocSign(Path file) throws Exception {
    Process p = new ProcessBuilder("codesign", "-s", "-", "--force", file.toString())
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start();
    boolean done = p.waitFor(60, TimeUnit.SECONDS);
    if (!done) {
      p.destroyForcibly();
      throw new RuntimeException("codesign timed out for " + file);
    }
    if (p.exitValue() != 0) {
      throw new RuntimeException("codesign failed with exit code " + p.exitValue());
    }
  }

  private NativeLoader() {
  }
}
