package com.mccal.folio

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSessionPresenter
import androidx.window.core.ExperimentalWindowApi

/** Owns the public dual-screen session; never requests hidden display or device-state APIs. */
@OptIn(ExperimentalWindowApi::class)
internal class StandByLighting(private val activity: ComponentActivity) {
    val controller = WindowAreaController.getOrCreate()
    var requested by mutableStateOf(false)
        private set
    var presenting by mutableStateOf(false)
        private set
    private var session: WindowAreaSessionPresenter? = null
    private var contentView: ComposeView? = null
    private var generation = 0
    private var pendingRequest by mutableStateOf<Int?>(null)
    val awaitingStart: Boolean get() = pendingRequest != null

    fun start(area: WindowAreaInfo, composition: CompositionContext, content: @Composable () -> Unit) {
        if (requested || awaitingStart) return
        val request = ++generation
        pendingRequest = request
        requested = true
        runCatching {
            controller.presentContentOnWindowArea(area.token, activity, activity.mainExecutor,
                object : WindowAreaPresentationSessionCallback {
                    override fun onSessionStarted(session: WindowAreaSessionPresenter) {
                        if (pendingRequest == request) pendingRequest = null
                        if (request != generation || !requested) {
                            runCatching { session.close() }
                            return
                        }
                        this@StandByLighting.session = session
                        runCatching {
                            session.window?.let { window ->
                                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                WindowCompat.setDecorFitsSystemWindows(window, false)
                                WindowCompat.getInsetsController(window, window.decorView)
                                    .hide(WindowInsetsCompat.Type.systemBars())
                            }
                            val view = ComposeView(session.context).apply {
                                setViewTreeLifecycleOwner(activity)
                                setViewTreeViewModelStoreOwner(activity)
                                setViewTreeSavedStateRegistryOwner(activity)
                                setParentCompositionContext(composition)
                                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                                setContent(content)
                            }
                            contentView = view
                            session.setContentView(view)
                            Diagnostics.event("StandBy ambient light: dual-screen session started")
                        }.onFailure {
                            Diagnostics.event("StandBy ambient light failed: ${it.javaClass.simpleName}")
                            stop()
                        }
                    }

                    override fun onContainerVisibilityChanged(isVisible: Boolean) {
                        if (request == generation) presenting = isVisible
                    }

                    override fun onSessionEnded(t: Throwable?) {
                        if (pendingRequest == request) pendingRequest = null
                        if (request != generation) return
                        // The system already ended the session; do not close it again from its callback.
                        session = null
                        stop()
                        Diagnostics.event("StandBy ambient light ended: ${t?.javaClass?.simpleName ?: "system"}")
                    }
                })
        }.onFailure {
            if (pendingRequest == request) pendingRequest = null
            Diagnostics.event("StandBy ambient light unavailable: ${it.javaClass.simpleName}")
            stop()
        }
    }

    fun stop() {
        generation++
        // A start request has no public cancel handle until its callback supplies a session.
        // Keep pendingRequest until that callback so a replacement cannot overlap it.
        requested = false
        presenting = false
        val previousSession = session
        session = null
        contentView?.disposeComposition()
        contentView = null
        runCatching { previousSession?.close() }
    }
}
