package com.uniaball.uide

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.uniaball.uide.build.CProjectBuilder
import com.uniaball.uide.build.EnvironmentChecker
import com.uniaball.uide.build.NativeExec
import com.uniaball.uide.build.ProcessRunner
import com.uniaball.uide.build.ToolchainManager
import com.uniaball.uide.data.FileRepository
import com.uniaball.uide.ui.BuildRunScreen
import com.uniaball.uide.ui.EditorScreen
import com.uniaball.uide.ui.FileListScreen
import com.uniaball.uide.ui.ToolchainScreen
import com.uniaball.uide.ui.theme.UIDETheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val ROUTE_FILES = "files"
private const val ROUTE_EDITOR = "editor/{name}"
private const val ROUTE_TOOLCHAIN = "toolchain"
private const val ROUTE_BUILD = "build/{path}"
private const val ARG_FILE_NAME = "name"
private const val ARG_PATH = "path"
private const val EXTRA_SELF_TEST = "uide_self_test"
private const val EXTRA_INSTALL_TOOLCHAIN = "uide_install_toolchain"
private const val EXTRA_BUILD_PROJECT = "uide_build"
private const val EXTRA_RUN_PROBE = "uide_probe"
private const val EXTRA_RUN_ENV_PROBE = "uide_env_probe"
private const val LOG_TAG = "UIDE:SelfTest"

/** Duration of the destination transitions (open a screen / go back). */
private const val NAV_ANIM_MS = 400
private val UTF8 = StandardCharsets.UTF_8.name()

class MainActivity : ComponentActivity() {
    private lateinit var repository: FileRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = FileRepository.fromContext(this)

        setContent {
            UIDETheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNav(repository)
                }
            }
        }

        if (intent?.getBooleanExtra(EXTRA_SELF_TEST, false) == true) {
            runSelfTest()
        }
        if (intent?.getBooleanExtra(EXTRA_INSTALL_TOOLCHAIN, false) == true) {
            installToolchain()
        }
        intent?.getStringExtra(EXTRA_BUILD_PROJECT)?.let { runBuild(it) }
if (intent?.getBooleanExtra(EXTRA_RUN_PROBE, false) == true) {
            runToolchainProbe()
        }
        if (intent?.getBooleanExtra(EXTRA_RUN_ENV_PROBE, false) == true) {
            runEnvProbe()
        }
    }

    /** Prints how the app itself starts a native process; see cpp/probe.c. */
    private fun runEnvProbe() {
        lifecycleScope.launch {
            val nativeExec = NativeExec(this@MainActivity)
            val report = StringBuilder()
            val probe = extractAsset("uidexec-probe-arm64")?.absolutePath
            report.appendLine("probe=$probe")
            if (probe != null) {
                // Reuse the toolchain's own cmake to see whether it is reachable.
                val cmake = File(nativeExec.binDir, "cmake").absolutePath
                for ((label, extra) in listOf(
                    "argv0=full-path" to emptyMap(),
                    "argv0=bare-name" to mapOf("UIDE_PROBE_ARGV0" to "cmake"),
                )) {
                    report.appendLine("=== $label ===")
                    val handle = ProcessRunner(nativeExec).launch(
                        program = probe,
                        args = listOf(cmake, "--version"),
                        workingDir = nativeExec.tmpDir,
                        env = extra,
                    )
                    val collector = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
                    val collect = collector.launch {
                        handle.lines.collect { report.appendLine(it.text) }
                    }
                    val exit = handle.await()
                    collect.cancel()
                    report.appendLine("exit=$exit")
                }
            }
            runCatching {
                File(nativeExec.externalDir, "env-probe.txt").writeText(report.toString())
            }
        }
    }

    private fun extractAsset(name: String): File? {
        val dir = NativeExec(this).selftestDir
        if (!dir.isDirectory) dir.mkdirs()
        val target = File(dir, name)
        if (target.isFile) return target
        return runCatching {
            assets.open(name).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setExecutable(true, true)
            target.takeIf { it.canExecute() }
        }.getOrNull()
    }

    private fun runToolchainProbe() {
        lifecycleScope.launch {
            val nativeExec = NativeExec(this@MainActivity)
            val script = File(nativeExec.prefixDir, "uide-probe.sh")
            val report = StringBuilder("script=${script.absolutePath} exists=${script.isFile}\n")
            if (script.isFile) {
                val handle = ProcessRunner(nativeExec).launch(
                    program = script.absolutePath,
                    workingDir = nativeExec.prefixDir,
                )
                val collector = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
                val collect = collector.launch {
                    handle.lines.collect { report.appendLine(it.text) }
                }
                val exit = handle.await()
                collect.cancel()
                report.appendLine("exit=$exit")
            }
            runCatching {
                File(nativeExec.externalDir, "probe-report.txt").writeText(report.toString())
            }
            Log.i(LOG_TAG, "toolchain probe finished")
        }
    }

    private fun runBuild(projectPath: String) {
        lifecycleScope.launch {
            val nativeExec = NativeExec(this@MainActivity)
            val builder = CProjectBuilder(
                context = this@MainActivity,
                nativeExec = nativeExec,
                runner = ProcessRunner(nativeExec),
            )
            val report = StringBuilder()
            val outcome = builder.build(builder.projectDir(projectPath)) { line ->
                report.appendLine((if (line.isError) "E " else "  ") + line.text)
            }
report.appendLine("configure exit: ${outcome.configureExitCode}")
            report.appendLine("build exit: ${outcome.buildExitCode}")
            report.appendLine("artifacts: ${outcome.artifacts.joinToString { it.name }}")
            outcome.issues.take(40).forEach {
                report.appendLine("issue ${it.severity} ${it.file}:${it.line}: ${it.message}")
            }
            outcome.artifacts.forEach { artifact ->
                report.appendLine("--- running ${artifact.name} ---")
                val process = builder.runArtifact(artifact)
                if (process == null) {
                    report.appendLine("  failed to allocate a pty")
                    return@forEach
                }
                val collector = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
                val collect = collector.launch {
                    process.lines.collect { report.appendLine("  ${it.text}") }
                }
                // Headless diagnostics have no keyboard, so close stdin right
                // away: programs reading until EOF terminate on their own.
                process.endOfInput()
                val exit = process.await()
                collect.cancel()
                report.appendLine("  exit=$exit")
            }
            val file = java.io.File(nativeExec.externalDir, "build-report.txt")
            runCatching { file.writeText(report.toString()) }
            Log.i(LOG_TAG, "build finished: $projectPath")
        }
    }

    private fun installToolchain() {
        lifecycleScope.launch {
            val nativeExec = NativeExec(this@MainActivity)
            val state = ToolchainManager(this@MainActivity, nativeExec).install()
            Log.i(LOG_TAG, "toolchain install: $state")
        }
    }

    /** Same checks as the "鏋勫缓鐜" screen, but reachable from `adb shell am start`. */
    private fun runSelfTest() {
        lifecycleScope.launch {
            val nativeExec = NativeExec(this@MainActivity)
            val checker = EnvironmentChecker(
                context = this@MainActivity,
                nativeExec = nativeExec,
                runner = ProcessRunner(nativeExec),
            )
            checker.runAndReport().forEach {
                Log.i(LOG_TAG, "${if (it.ok) "PASS" else "FAIL"} ${it.name} :: ${it.detail}")
            }
            Log.i(LOG_TAG, "self-test finished")
        }
    }
}

@Composable
private fun AppNav(repository: FileRepository) {
    val nav = rememberNavController()

    NavHost(
        navController = nav,
        startDestination = ROUTE_FILES,
// Opening a screen (file, build, toolchain) slides the new content in
        // from the right while the old one leaves to the left; going back does
        // the mirror image.  Fixed tweens instead of springs, so a navigation
        // arriving mid-flight cannot stretch the transition indefinitely.
        enterTransition = {
            slideInHorizontally(animationSpec = tween(NAV_ANIM_MS)) { it } +
                fadeIn(animationSpec = tween(NAV_ANIM_MS))
        },
        exitTransition = {
            slideOutHorizontally(animationSpec = tween(NAV_ANIM_MS)) { -it } +
                fadeOut(animationSpec = tween(NAV_ANIM_MS))
        },
        popEnterTransition = {
            slideInHorizontally(animationSpec = tween(NAV_ANIM_MS)) { -it } +
                fadeIn(animationSpec = tween(NAV_ANIM_MS))
        },
        popExitTransition = {
            slideOutHorizontally(animationSpec = tween(NAV_ANIM_MS)) { it } +
                fadeOut(animationSpec = tween(NAV_ANIM_MS))
        },
    ) {
        composable(ROUTE_FILES) { backStack ->
            FileListScreen(
                repository = repository,
                savedStateHandle = backStack.savedStateHandle,
                onOpenFile = { name -> nav.navigate("editor/${URLEncoder.encode(name, UTF8)}") },
                onOpenToolchain = { nav.navigate(ROUTE_TOOLCHAIN) },
                onBuild = { path -> nav.navigate("build/${URLEncoder.encode(path, UTF8)}") },
            )
        }
        composable(ROUTE_TOOLCHAIN) {
            ToolchainScreen(onBack = { nav.popBackStack() })
        }
        composable(
            route = ROUTE_BUILD,
            arguments = listOf(navArgument(ARG_PATH) { type = NavType.StringType }),
        ) { backStack ->
            val raw = backStack.arguments?.getString(ARG_PATH).orEmpty()
            val path = runCatching { URLDecoder.decode(raw, UTF8) }.getOrDefault(raw)
            BuildRunScreen(
                projectPath = path,
                onBack = { nav.popBackStack() },
                onOpenFile = { name ->
                    nav.navigate("editor/${URLEncoder.encode(name, UTF8)}")
                },
            )
        }
        composable(
            route = ROUTE_EDITOR,
            arguments = listOf(navArgument(ARG_FILE_NAME) { type = NavType.StringType }),
        ) { backStack ->
            val raw = backStack.arguments?.getString(ARG_FILE_NAME).orEmpty()
            val name = runCatching { URLDecoder.decode(raw, UTF8) }.getOrDefault(raw)
            EditorScreen(
                fileName = name,
                repository = repository,
                fileListHandle = nav.previousBackStackEntry?.savedStateHandle,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
