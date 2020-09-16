/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.remote.sdk

import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.remote.RemoteSdkException
import com.intellij.remote.RemoteSdkFactoryImpl
import org.rust.ide.sdk.RsSdkType
import org.rust.remote.getRemoteToolchainVersion
import java.awt.Component

object RsRemoteToolchainFactory : RemoteSdkFactoryImpl<RsRemoteSdkAdditionalData>() {

    override fun getSdkType(data: RsRemoteSdkAdditionalData): SdkType = RsSdkType.getInstance()

    override fun createSdk(
        existingSdks: Collection<Sdk>,
        sdkType: SdkType,
        data: RsRemoteSdkAdditionalData,
        sdkName: String?
    ): ProjectJdkImpl = SdkConfigurationUtil.createSdk(
        existingSdks,
        generateSdkHomePath(data),
        sdkType,
        data,
        sdkName
    )

    override fun initSdk(sdk: Sdk, project: Project?, ownerComponent: Component?) {
        try {
            (sdk.sdkType as RsSdkType).setupSdkPaths(sdk)
        } catch (e: Exception) {
            throw RemoteSdkException(e.message, e)
        }
    }

    override fun canSaveUnfinished(): Boolean = true

    override fun sdkName(): String = "Rust"

    override fun getSdkName(data: RsRemoteSdkAdditionalData, version: String?): String =
        "Remote ${version ?: "unknown toolchain"} ${data.presentableDetails}"

    override fun getDefaultUnfinishedName(): String = "Remote ${sdkName()} toolchain"

    override fun getSdkVersion(project: Project, data: RsRemoteSdkAdditionalData): String? =
        getRemoteToolchainVersion(project, data, true)?.rustc?.semver?.parsedVersion
}
