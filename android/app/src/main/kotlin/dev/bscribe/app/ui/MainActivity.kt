package dev.bscribe.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.bscribe.app.ui.detail.SessionDetailScreen
import dev.bscribe.app.ui.record.RecordScreen
import dev.bscribe.app.ui.sessions.SessionListScreen
import dev.bscribe.app.ui.settings.SettingsScreen
import dev.bscribe.app.ui.theme.ScribeTheme

object Routes {
    const val SESSIONS = "sessions"
    const val RECORD = "record"
    const val SETTINGS = "settings"
    const val SESSION_DETAIL = "session/{sessionId}"
    fun sessionDetail(id: String) = "session/$id"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScribeTheme {
                ScribeNavHost(rememberNavController())
            }
        }
    }
}

@Composable
private fun ScribeNavHost(nav: NavHostController) {
    NavHost(navController = nav, startDestination = Routes.SESSIONS) {
        composable(Routes.SESSIONS) {
            SessionListScreen(
                onRecord = { nav.navigate(Routes.RECORD) },
                onOpenSession = { id -> nav.navigate(Routes.sessionDetail(id)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.RECORD) {
            RecordScreen(
                onFinished = { sessionId ->
                    nav.popBackStack(Routes.SESSIONS, inclusive = false)
                    if (sessionId != null) nav.navigate(Routes.sessionDetail(sessionId))
                },
            )
        }
        composable(Routes.SESSION_DETAIL) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
            SessionDetailScreen(
                sessionId = sessionId,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
