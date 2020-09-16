/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.sdk

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.*
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.remote.ext.LanguageCaseCollector
import com.intellij.util.Consumer
import org.jdom.Element
import org.rust.cargo.toolchain.RustToolchain
import org.rust.ide.icons.RsIcons
import org.rust.ide.sdk.RsSdkUtils.detectRustSdks
import org.rust.ide.sdk.add.RsAddSdkDialog
import org.rust.ide.sdk.flavors.RsSdkFlavor
import org.rust.openapiext.computeWithCancelableProgress
import org.rust.remote.RsCredentialsContribution
import org.rust.remote.sdk.RsRemoteSdkAdditionalData
import org.rust.remote.getRemoteToolchainVersion
import org.rust.stdext.toPath
import java.lang.ref.WeakReference
import javax.swing.Icon
import javax.swing.JComponent

/**
 * Class should be final and singleton since some code checks its instance by ref.
 */
class RsSdkType : SdkType(RUST_SDK_ID_NAME) {

    override fun getIcon(): Icon = RsIcons.RUST

    override fun getHelpTopic(): String = "reference.project.structure.sdk.rust"

    override fun getIconForAddAction(): Icon = RsIcons.RUST

    override fun suggestHomePath(): String? = suggestHomePaths().firstOrNull()

    override fun suggestHomePaths(): Collection<String> {
        val existingSdks = ProjectJdkTable.getInstance().allJdks.toList()
        return detectRustSdks(existingSdks).mapNotNull { it.homePath }
    }

    override fun isValidSdkHome(path: String?): Boolean = RsSdkFlavor.getFlavor(path) != null

    override fun getHomeChooserDescriptor(): FileChooserDescriptor =
        object : FileChooserDescriptor(false, true, false, false, false, false) {
            override fun validateSelectedFiles(files: Array<VirtualFile>) {
                val file = files.firstOrNull() ?: return
                if (!isValidSdkHome(file.path)) {
                    throw Exception("Selected directory '${file.name}' doesn't contain Rust toolchain")
                }
            }
        }.withTitle("Select Rust Toolchain Path").withShowHiddenFiles(SystemInfo.isUnix)

    override fun supportsCustomCreateUI(): Boolean = true

    override fun showCustomCreateUI(
        sdkModel: SdkModel,
        parentComponent: JComponent,
        selectedSdk: Sdk?,
        sdkCreatedCallback: Consumer<Sdk>
    ) {
        val project = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(parentComponent))
        RsAddSdkDialog.show(project, sdkModel.sdks.toList()) { sdk ->
            if (sdk != null) {
                sdk.putUserData(SDK_CREATOR_COMPONENT_KEY, WeakReference(parentComponent))
                sdkCreatedCallback.consume(sdk)
            }
        }
    }

    override fun suggestSdkName(currentSdkName: String?, sdkHome: String): String = "Rust"

    override fun createAdditionalDataConfigurable(
        sdkModel: SdkModel,
        sdkModificator: SdkModificator
    ): AdditionalDataConfigurable = RsAdditionalDataConfigurable(sdkModel)

    override fun saveAdditionalData(additionalData: SdkAdditionalData, additional: Element) {
        (additionalData as? RsSdkAdditionalData)?.save(additional)
    }

    override fun loadAdditionalData(currentSdk: Sdk, additional: Element): SdkAdditionalData {
        val homePath = currentSdk.homePath
        return if (homePath != null && isCustomSdkHomePath(homePath)) {
            RsRemoteSdkAdditionalData.loadRemote(currentSdk, additional)
        } else {
            RsSdkAdditionalData.loadSdkData(currentSdk, additional)
        }
    }

    override fun getPresentableName(): String = RUST_SDK_ID_NAME

    override fun sdkPath(homePath: VirtualFile): String {
        val path = super.sdkPath(homePath)
        return FileUtil.toSystemDependentName(path)
    }

    override fun getVersionString(sdk: Sdk): String? {
        if (!RsSdkUtils.isRemote(sdk)) {
            val toolchain = sdk.toolchain ?: return null
            return getVersionString(toolchain)
        }

        val data = sdk.sdkAdditionalData as? RsRemoteSdkAdditionalData ?: return null
        var versionString = data.versionString
        if (versionString.isNullOrEmpty()) {
            versionString = try {
                val versionInfo = getRemoteToolchainVersion(null, data, true)
                versionInfo?.rustc?.semver?.parsedVersion
            } catch (e: Exception) {
                LOG.warn("Couldn't get toolchain version:" + e.message, e)
                "undefined"
            }
            data.versionString = versionString
        }
        return versionString
    }

    override fun getVersionString(sdkHome: String?): String? {
        val sdkPath = sdkHome?.toPath() ?: return null
        val toolchain = RustToolchain(sdkPath, null)
        if (toolchain.isRustupAvailable) return null
        return getVersionString(toolchain)
    }

    private fun getVersionString(toolchain: RustToolchain): String? {
        val project = ProjectManager.getInstance().defaultProject
        val versionInfo = project.computeWithCancelableProgress("Fetching rustc version...") {
            toolchain.queryVersions()
        }
        return versionInfo.rustc?.semver?.parsedVersion
    }

    // TODO: use [OrderRootType.SOURCES] to store stdlib path
    override fun isRootTypeApplicable(type: OrderRootType): Boolean = false

    override fun sdkHasValidPath(sdk: Sdk): Boolean =
        RsSdkUtils.isRemote(sdk) || sdk.homeDirectory?.isValid ?: false

    override fun isLocalSdk(sdk: Sdk): Boolean = !RsSdkUtils.isRemote(sdk)

    companion object {
        private val LOG: Logger = Logger.getInstance(RsSdkType::class.java)

        private const val RUST_SDK_ID_NAME: String = "Rust SDK"

        private val SDK_CREATOR_COMPONENT_KEY: Key<WeakReference<JComponent>> =
            Key.create("#org.rust.ide.sdk.creatorComponent")

        private val CUSTOM_RUST_SDK_HOME_PATH_PATTERN: Regex = "[-a-zA-Z_0-9]{2,}:.*".toRegex()

        fun getInstance(): RsSdkType = findInstance(RsSdkType::class.java)

        /**
         * Returns whether provided Rust toolchain path corresponds to custom Rust SDK.
         *
         * @param homePath SDK home path
         * @return whether provided Rust toolchain path corresponds to Rust SDK
         */
        fun isCustomSdkHomePath(homePath: String): Boolean =
            CUSTOM_RUST_SDK_HOME_PATH_PATTERN.matches(homePath)

        fun isIncompleteRemote(sdk: Sdk): Boolean {
            if (!RsSdkUtils.isRemote(sdk)) return false
            val additionalData = sdk.sdkAdditionalData as? RsRemoteSdkAdditionalData ?: return true
            return additionalData.isValid
        }

        fun hasInvalidRemoteCredentials(sdk: Sdk): Boolean {
            if (!RsSdkUtils.isRemote(sdk)) return false
            val additionalData = sdk.sdkAdditionalData as? RsRemoteSdkAdditionalData ?: return false
            val result = Ref.create(false)
            additionalData.switchOnConnectionType(
                *object : LanguageCaseCollector<RsCredentialsContribution>() {
                    override fun processLanguageContribution(
                        languageContribution: RsCredentialsContribution,
                        credentials: Any?
                    ) {
                        result.set(credentials == null)
                    }
                }.collectCases(RsCredentialsContribution::class.java)
            )
            return result.get()
        }
    }
}
