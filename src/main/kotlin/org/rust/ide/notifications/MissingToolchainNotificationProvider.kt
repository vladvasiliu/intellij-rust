/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.notifications

import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapiext.isUnitTestMode
import com.intellij.ui.EditorNotificationPanel
import org.rust.cargo.project.model.*
import org.rust.cargo.project.settings.RustProjectSettingsService
import org.rust.cargo.project.settings.RustProjectSettingsService.RustSettingsChangedEvent
import org.rust.cargo.project.settings.RustProjectSettingsService.RustSettingsListener
import org.rust.cargo.project.settings.rustSettings
import org.rust.cargo.project.settings.toolchain
import org.rust.cargo.project.workspace.StandardLibrary
import org.rust.ide.sdk.RsSdkAdditionalData
import org.rust.ide.sdk.RsSdkType
import org.rust.ide.sdk.id
import org.rust.lang.core.psi.isRustFile

/**
 * Warn user if rust toolchain or standard library is not properly configured.
 *
 * Try to fix this automatically (toolchain from PATH, standard library from the last project)
 * and if not successful show the actual notification to the user.
 */
class MissingToolchainNotificationProvider(project: Project) : RsNotificationProvider(project), DumbAware {

    override val VirtualFile.disablingKey: String get() = NOTIFICATION_STATUS_KEY

    init {
        project.messageBus.connect().apply {
            subscribe(RustProjectSettingsService.RUST_SETTINGS_TOPIC,
                object : RustSettingsListener {
                    override fun rustSettingsChanged(e: RustSettingsChangedEvent) {
                        updateAllNotifications()
                    }
                })

            subscribe(CargoProjectsService.CARGO_PROJECTS_TOPIC, object : CargoProjectsService.CargoProjectsListener {
                override fun cargoProjectsUpdated(service: CargoProjectsService, projects: Collection<CargoProject>) {
                    updateAllNotifications()
                }
            })

            subscribe(ProjectJdkTable.JDK_TABLE_TOPIC, object : ProjectJdkTable.Listener {
                override fun jdkRemoved(jdk: Sdk) {
                    if (jdk.sdkType is RsSdkType) {
                        updateAllNotifications()
                    }
                }

                override fun jdkAdded(jdk: Sdk) {}
                override fun jdkNameChanged(jdk: Sdk, previousName: String) {}
            })

            subscribe(RsSdkAdditionalData.RUST_ADDITIONAL_DATA_TOPIC, object : RsSdkAdditionalData.Listener {
                override fun sdkAdditionalDataChanged(sdk: Sdk) {
                    if (sdk.sdkType !is RsSdkType) return
                    val projectSdk = project.rustSettings.sdk ?: return
                    if (sdk.id == projectSdk.id) {
                        updateAllNotifications()
                    }
                }
            })
        }
    }

    override fun getKey(): Key<EditorNotificationPanel> = PROVIDER_KEY

    override fun createNotificationPanel(
        file: VirtualFile,
        editor: FileEditor,
        project: Project
    ): RsEditorNotificationPanel? {
        if (isUnitTestMode) return null
        if (!(file.isRustFile || file.isCargoToml) || isNotificationDisabled(file)) return null
        if (guessAndSetupRustProject(project)) return null

        val toolchain = project.toolchain
        if (toolchain == null || !toolchain.looksLikeValidToolchain()) {
            return createBadToolchainPanel(file)
        }

        val cargoProjects = project.cargoProjects

        if (!cargoProjects.initialized) return null

        val workspace = cargoProjects.findProjectForFile(file)?.workspace ?: return null
        if (!workspace.hasStandardLibrary) {
            // If rustup is not null, the WorkspaceService will use it
            // to add stdlib automatically. This happens asynchronously,
            // so we can't reliably say here if that succeeded or not.
            if (!toolchain.isRustupAvailable) return createLibraryAttachingPanel(file)
        }

        return null
    }

    private fun createBadToolchainPanel(file: VirtualFile): RsEditorNotificationPanel =
        RsEditorNotificationPanel(NO_RUST_TOOLCHAIN).apply {
            text = "No Rust toolchain configured"
            createActionLabel("Setup toolchain") {
                project.rustSettings.configureToolchain()
            }
            createActionLabel("Do not show again") {
                disableNotification(file)
                updateAllNotifications()
            }
        }

    private fun createLibraryAttachingPanel(file: VirtualFile): RsEditorNotificationPanel =
        RsEditorNotificationPanel(NO_ATTACHED_STDLIB).apply {
            text = "Can not attach stdlib sources automatically without rustup."
            createActionLabel("Attach manually") {
                val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                val stdlib = FileChooser.chooseFile(descriptor, this, project, null) ?: return@createActionLabel
                if (StandardLibrary.fromFile(stdlib) != null) {
                    val data = project.rustSettings.sdk?.sdkAdditionalData as? RsSdkAdditionalData
                    data?.explicitPathToStdlib = stdlib.path
                } else {
                    project.showBalloon(
                        "Invalid Rust standard library source path: `${stdlib.presentableUrl}`",
                        NotificationType.ERROR
                    )
                }
                updateAllNotifications()
            }

            createActionLabel("Do not show again") {
                disableNotification(file)
                updateAllNotifications()
            }
        }

    companion object {
        private const val NOTIFICATION_STATUS_KEY = "org.rust.hideToolchainNotifications"
        const val NO_RUST_TOOLCHAIN = "NoRustToolchain"
        const val NO_ATTACHED_STDLIB = "NoAttachedStdlib"

        private val PROVIDER_KEY: Key<EditorNotificationPanel> = Key.create("Setup Rust toolchain")
    }
}
