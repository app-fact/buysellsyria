package com.buysellsyria.rep

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.buysellsyria.rep.ui.RepTheme
import com.buysellsyria.rep.ui.screens.CustomerScreen
import com.buysellsyria.rep.ui.screens.CustomersScreen
import com.buysellsyria.rep.ui.screens.HistoryScreen
import com.buysellsyria.rep.ui.screens.HomeScreen
import com.buysellsyria.rep.ui.screens.LoadsScreen
import com.buysellsyria.rep.ui.screens.LoginScreen
import com.buysellsyria.rep.ui.screens.NewCustomerScreen
import com.buysellsyria.rep.ui.screens.ReceiptScreen
import com.buysellsyria.rep.ui.screens.SaleMode
import com.buysellsyria.rep.ui.screens.SaleScreen
import com.buysellsyria.rep.ui.screens.SettingsScreen
import com.buysellsyria.rep.ui.screens.StockScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = (application as RepApp).repo
        setContent {
            RepTheme {
                val nav = rememberNavController()
                AppNav(nav, if (repo.session.isLoggedIn) Routes.HOME else Routes.LOGIN)
            }
        }
    }
}

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val NEW_CUSTOMER = "customer_new"
    const val HISTORY = "history"
    const val LOADS = "loads"
    const val SETTINGS = "settings"

    /** next = what to do after picking a customer: "view" or a SaleMode name or "collect". */
    fun customers(next: String = "view") = "customers/$next"
    fun customer(key: String) = "customer/$key"
    fun sale(mode: SaleMode, customerKey: String? = null) = "sale/${mode.name}?customer=${customerKey ?: ""}"
    fun stock(kind: String) = "stock/$kind"
    fun receipt(kind: String, uuid: String) = "receipt/$kind/$uuid"
}

@Composable
fun AppNav(nav: NavHostController, start: String) {
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(navController = nav, startDestination = start) {
        composable(Routes.LOGIN) {
            LoginScreen(onLoggedIn = {
                nav.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } }
            })
        }
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(Routes.customers("{next}"), listOf(navArgument("next") { type = NavType.StringType })) {
            CustomersScreen(nav, it.arguments?.getString("next") ?: "view")
        }
        composable(Routes.customer("{key}"), listOf(navArgument("key") { type = NavType.StringType })) {
            CustomerScreen(nav, it.arguments?.getString("key")!!)
        }
        composable(Routes.NEW_CUSTOMER) { NewCustomerScreen(nav) }
        composable(
            "sale/{mode}?customer={customer}",
            listOf(
                navArgument("mode") { type = NavType.StringType },
                navArgument("customer") { type = NavType.StringType; defaultValue = "" },
            ),
        ) {
            val mode = SaleMode.valueOf(it.arguments?.getString("mode")!!)
            SaleScreen(nav, mode, it.arguments?.getString("customer")?.ifBlank { null })
        }
        composable(Routes.stock("{kind}"), listOf(navArgument("kind") { type = NavType.StringType })) {
            StockScreen(it.arguments?.getString("kind")!!, back)
        }
        composable(
            Routes.receipt("{kind}", "{uuid}"),
            listOf(navArgument("kind") { type = NavType.StringType }, navArgument("uuid") { type = NavType.StringType }),
        ) {
            ReceiptScreen(it.arguments?.getString("kind")!!, it.arguments?.getString("uuid")!!) {
                nav.popBackStack(Routes.HOME, inclusive = false)
            }
        }
        composable(Routes.HISTORY) { HistoryScreen(nav) }
        composable(Routes.LOADS) { LoadsScreen(nav) }
        composable(Routes.SETTINGS) {
            SettingsScreen(back, onLoggedOut = {
                nav.navigate(Routes.LOGIN) { popUpTo(0) }
            })
        }
    }
}
