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
    NavHost(navController = nav, startDestination = "files") {
        composable("files") { backStack ->
            FileListScreen(
                repository = repository,
                savedStateHandle = backStack.savedStateHandle,
                onOpenFile = { name -> nav.navigate("editor/${URLEncoder.encode(name, "UTF-8")}") },
            )
        }
        composable(
            route = "editor/{name}",
            arguments = listOf(navArgument("name") { type = NavType.StringType }),
        ) { backStack ->
            val raw = backStack.arguments?.getString("name").orEmpty()
            val name = URLDecoder.decode(raw, "UTF-8")
            EditorScreen(
                fileName = name,
                repository = repository,
                fileListHandle = nav.previousBackStackEntry?.savedStateHandle,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
