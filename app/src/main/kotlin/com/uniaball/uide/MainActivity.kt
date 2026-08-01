package com.uniaball.uide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.uniaball.uide.data.FileRepository
import com.uniaball.uide.ui.EditorScreen
import com.uniaball.uide.ui.FileListScreen
import com.uniaball.uide.ui.theme.UIDETheme
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val ROUTE_FILES = "files"
private const val ROUTE_EDITOR = "editor/{name}"
private const val ARG_FILE_NAME = "name"
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
    }
}

@Composable
private fun AppNav(repository: FileRepository) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = ROUTE_FILES) {
        composable(ROUTE_FILES) { backStack ->
            FileListScreen(
                repository = repository,
                savedStateHandle = backStack.savedStateHandle,
                onOpenFile = { name -> nav.navigate("editor/${URLEncoder.encode(name, UTF8)}") },
            )
        }
        composable(
            route = ROUTE_EDITOR,
            arguments = listOf(navArgument(ARG_FILE_NAME) { type = NavType.StringType }),
        ) { backStack ->
            val raw = backStack.arguments?.getString(ARG_FILE_NAME).orEmpty()
            val name = URLDecoder.decode(raw, UTF8)
            EditorScreen(
                fileName = name,
                repository = repository,
                fileListHandle = nav.previousBackStackEntry?.savedStateHandle,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
