package edu.northeastern.cs6510.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Runs {@code deployment/scripts/reconcile.sh}, the single implementation of checks R-1..R-6. */
public final class Reconciliation {
  private Reconciliation() { }

  public static void assertPass() {
    Path script = locateScript();
    try {
      Process process = new ProcessBuilder(List.of(bash(), script.toString(), "--wait-settled", "60"))
          .redirectErrorStream(true).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(0, process.waitFor(), "reconciliation failed:\n" + output);
    } catch (IOException e) {
      throw new IllegalStateException("cannot run " + script, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** On Windows prefer Git Bash; a bare "bash" may resolve to WSL, which cannot reach the published ports the same way. */
  private static String bash() {
    String configured = System.getenv("BASH_FOR_TESTS");
    if (configured != null && !configured.isBlank()) return configured;
    if (File.separatorChar == '\\') {
      for (String candidate : List.of("C:\\Program Files\\Git\\bin\\bash.exe", "C:\\Program Files\\Git\\usr\\bin\\bash.exe")) {
        if (new File(candidate).exists()) return candidate;
      }
    }
    return "bash";
  }

  private static Path locateScript() {
    for (Path candidate : List.of(Path.of("..", "deployment", "scripts", "reconcile.sh"),
                                  Path.of("domain-services", "deployment", "scripts", "reconcile.sh"))) {
      if (Files.exists(candidate)) return candidate.toAbsolutePath().normalize();
    }
    throw new IllegalStateException("reconcile.sh not found");
  }
}
