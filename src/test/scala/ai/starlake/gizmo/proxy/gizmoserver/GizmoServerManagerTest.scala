package ai.starlake.gizmo.proxy.gizmoserver

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class GizmoServerManagerTest extends AnyFunSuite with Matchers:

  /** Subclass that captures onUnexpectedExit calls instead of halt(1) */
  private class TestableGizmoServerManager(
      port: Int,
      initSqlCommands: String,
      envVars: Map[String, String],
      idleTimeout: Int,
      scriptPath: String,
      startupWaitMs: Long = 5000L
  ) extends GizmoServerManager(port, initSqlCommands, envVars, idleTimeout, scriptPath, startupWaitMs):
    val unexpectedExitCalled = new AtomicBoolean(false)
    override protected def onUnexpectedExit(process: java.lang.Process): Unit =
      unexpectedExitCalled.set(true)

  /** Creates a temporary shell script that sleeps then exits */
  private def createScript(body: String): String =
    val f = Files.createTempFile("gizmo-test-", ".sh").toFile
    f.deleteOnExit()
    Files.writeString(f.toPath, s"#!/bin/bash\n$body\n")
    f.setExecutable(true)
    f.getAbsolutePath

  test("start throws RuntimeException when backend script fails immediately") {
    // /usr/bin/false exits immediately with code 1 — simulates Docker not available
    val manager = TestableGizmoServerManager(
      port = 19999,
      initSqlCommands = "",
      envVars = Map.empty,
      idleTimeout = -1,
      scriptPath = "/usr/bin/false",
      startupWaitMs = 500L
    )

    val ex = intercept[RuntimeException] {
      manager.start()
    }
    ex.getMessage should include("failed to start")

    // The onUnexpectedExit (halt) should NOT have been called —
    // startup failures should be handled by the exception, not by killing the JVM
    manager.unexpectedExitCalled.get() shouldBe false

    manager.stop()
  }

  test("onUnexpectedExit is called when backend crashes after successful startup") {
    // Script that survives startup check then dies — simulates post-startup crash
    val script = createScript("sleep 5 & BGPID=$!; wait $BGPID")
    val manager = TestableGizmoServerManager(
      port = 19998,
      initSqlCommands = "",
      envVars = Map.empty,
      idleTimeout = -1,
      scriptPath = script,
      startupWaitMs = 200L
    )

    manager.start()
    manager.isRunning shouldBe true
    manager.unexpectedExitCalled.get() shouldBe false

    // Simulate unexpected crash by killing the process directly
    // (without going through stopInternal which sets process = None)
    val killScript = new ProcessBuilder("pkill", "-f", script).start()
    killScript.waitFor()

    // Wait for the onExit callback to fire
    Thread.sleep(500)

    manager.unexpectedExitCalled.get() shouldBe true

    manager.stop()
  }

  test("normal stop does not trigger onUnexpectedExit") {
    val script = createScript("sleep 30")
    val manager = TestableGizmoServerManager(
      port = 19997,
      initSqlCommands = "",
      envVars = Map.empty,
      idleTimeout = -1,
      scriptPath = script,
      startupWaitMs = 200L
    )

    manager.start()
    manager.isRunning shouldBe true
    manager.stop()

    Thread.sleep(300)
    manager.unexpectedExitCalled.get() shouldBe false
  }
