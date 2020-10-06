/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.project.settings.impl

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.configurationStore.serializeObjectInto
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.PsiModificationTrackerImpl
import com.intellij.util.xmlb.XmlSerializer.deserializeInto
import org.jdom.Element
import org.jetbrains.annotations.TestOnly
import org.rust.cargo.project.configurable.RsProjectConfigurable
import org.rust.cargo.project.settings.RustProjectSettingsService
import org.rust.cargo.project.settings.RustProjectSettingsService.*
import org.rust.cargo.project.settings.RustProjectSettingsService.Companion.RUST_SETTINGS_TOPIC
import org.rust.cargo.toolchain.ExternalLinter

private const val serviceName: String = "RustProjectSettings"

@com.intellij.openapi.components.State(name = serviceName, storages = [
    Storage(StoragePathMacros.WORKSPACE_FILE),
    Storage("misc.xml", deprecated = true)
])
class RustProjectSettingsServiceImpl(
    private val project: Project
) : PersistentStateComponent<Element>, RustProjectSettingsService {
    @Volatile
    private var _state: State = State()

    override var settingsState: State
        get() = _state.copy()
        set(newState) {
            if (_state != newState) {
                val oldState = _state
                _state = newState.copy()
                notifySettingsChanged(oldState, newState)
            }
        }

    override val version: Int? get() = _state.version
    override val sdk: Sdk? get() = _state.sdk
    override val autoUpdateEnabled: Boolean get() = _state.autoUpdateEnabled
    override val externalLinter: ExternalLinter get() = _state.externalLinter
    override val runExternalLinterOnTheFly: Boolean get() = _state.runExternalLinterOnTheFly
    override val externalLinterArguments: String get() = _state.externalLinterArguments
    override val compileAllTargets: Boolean get() = _state.compileAllTargets
    override val useOffline: Boolean get() = _state.useOffline
    override val macroExpansionEngine: MacroExpansionEngine get() = _state.macroExpansionEngine
    override val doctestInjectionEnabled: Boolean get() = _state.doctestInjectionEnabled
    override val useRustfmt: Boolean get() = _state.useRustfmt
    override val runRustfmtOnSave: Boolean get() = _state.runRustfmtOnSave

    override fun getState(): Element {
        val element = Element(serviceName)
        serializeObjectInto(_state, element)
        return element
    }

    override fun loadState(element: Element) {
        val rawState = element.clone()
        rawState.updateToCurrentVersion()
        deserializeInto(_state, rawState)
    }

    override fun modify(action: (State) -> Unit) {
        settingsState = settingsState.also(action)
    }

    @TestOnly
    override fun modifyTemporary(parentDisposable: Disposable, action: (State) -> Unit) {
        val oldState = settingsState
        settingsState = oldState.also(action)
        Disposer.register(parentDisposable) {
            _state = oldState
        }
    }

    override fun configureToolchain() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, RsProjectConfigurable::class.java)
    }

    private fun notifySettingsChanged(oldState: State, newState: State) {
        val event = RustSettingsChangedEvent(oldState, newState)
        project.messageBus.syncPublisher(RUST_SETTINGS_TOPIC).rustSettingsChanged(event)

        if (event.isChanged(State::doctestInjectionEnabled)) {
            // flush injection cache
            (PsiManager.getInstance(project).modificationTracker as PsiModificationTrackerImpl).incCounter()
        }
        if (event.affectsHighlighting) {
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }
}
