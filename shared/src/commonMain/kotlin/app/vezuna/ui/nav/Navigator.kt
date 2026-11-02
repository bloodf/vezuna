package app.vezuna.ui.nav

import app.vezuna.ui.AppScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The five labeled root destinations, in this order. Import is an action, the assistant lives under More. */
enum class Destination(val root: AppScreen) {
    TODAY(AppScreen.Dashboard),
    RECORDS(AppScreen.Labs),
    CARE(AppScreen.Medications),
    TRENDS(AppScreen.Trends),
    MORE(AppScreen.More),
    ;

    companion object {
        fun rootOf(screen: AppScreen): Destination? = entries.firstOrNull { it.root == screen }
    }
}

data class NavState(
    val root: Destination = Destination.TODAY,
    val stacks: Map<Destination, List<AppScreen>> = Destination.entries.associateWith { listOf(it.root) },
) {
    val stack: List<AppScreen> get() = stacks.getValue(root)
    val current: AppScreen get() = stack.last()
    val isRoot: Boolean get() = stack.size == 1

    /** True while back has something to do inside the app (pop, or return to Today). */
    val canGoBack: Boolean get() = !isRoot || root != Destination.TODAY
}

/**
 * One back stack per root destination. Switching roots keeps each root's stack, so returning to a tab
 * restores where the person was; reselecting the current root pops to its first screen.
 */
class Navigator {
    private val mutableState = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = mutableState.asStateFlow()

    val current: AppScreen get() = mutableState.value.current

    fun selectRoot(destination: Destination) {
        val state = mutableState.value
        mutableState.value = if (state.root == destination) {
            state.copy(stacks = state.stacks + (destination to listOf(destination.root)))
        } else {
            state.copy(root = destination)
        }
    }

    /** A root screen selects its tab; any other screen goes on top of the current tab's stack. */
    fun navigate(screen: AppScreen) {
        Destination.rootOf(screen)?.let { destination ->
            val state = mutableState.value
            mutableState.value = state.copy(root = destination, stacks = state.stacks + (destination to listOf(destination.root)))
            return
        }
        val state = mutableState.value
        if (state.current == screen) return
        mutableState.value = state.copy(stacks = state.stacks + (state.root to state.stack + screen))
    }

    /**
     * Ends the task on top of the current tab (pops it) and goes to [screen], for flows that finish elsewhere:
     * saving a manual result closes the import screen and opens Records, and the origin tab does not keep
     * the finished screen.
     */
    fun finishAndGo(screen: AppScreen) {
        val state = mutableState.value
        if (!state.isRoot) mutableState.value = state.copy(stacks = state.stacks + (state.root to state.stack.dropLast(1)))
        navigate(screen)
    }

    /** Returns false when there is nothing left to go back to, so the platform can handle it (Android exits). */
    fun back(): Boolean {
        val state = mutableState.value
        return when {
            !state.isRoot -> {
                mutableState.value = state.copy(stacks = state.stacks + (state.root to state.stack.dropLast(1)))
                true
            }
            state.root != Destination.TODAY -> {
                mutableState.value = state.copy(root = Destination.TODAY)
                true
            }
            else -> false
        }
    }

    /** Drops every pushed screen; used when the profile changes, because routes carry that profile's IDs. */
    fun resetToRoots() {
        mutableState.value = NavState(root = mutableState.value.root)
    }
}
