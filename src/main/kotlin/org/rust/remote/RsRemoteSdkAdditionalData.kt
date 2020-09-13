/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.remote

import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.remote.*
import com.intellij.remote.ext.CredentialsCase
import com.intellij.remote.ext.CredentialsManager
import com.intellij.util.Consumer
import org.jdom.Element
import org.rust.ide.sdk.RsSdkAdditionalData

class RsRemoteSdkAdditionalData private constructor(
    toolchainPath: String,
    private val remoteSdkProperties: RemoteSdkPropertiesHolder
) : RsSdkAdditionalData(),
    RemoteSdkProperties by remoteSdkProperties,
    RemoteSdkAdditionalData<RsRemoteSdkCredentials> {
    var versionString: String? = null

    val presentableDetails: String
        get() = remoteConnectionCredentialsWrapper.getPresentableDetails(remoteSdkProperties.interpreterPath)

    private val remoteConnectionCredentialsWrapper: RemoteConnectionCredentialsWrapper =
        RemoteConnectionCredentialsWrapper()

    init {
        interpreterPath = toolchainPath
    }

    constructor(cargoPath: String) : this(cargoPath, RemoteSdkPropertiesHolder(RUST_HELPERS))

    override fun connectionCredentials(): RemoteConnectionCredentialsWrapper =
        remoteConnectionCredentialsWrapper

    override fun <C> setCredentials(key: Key<C>, credentials: C) {
        remoteConnectionCredentialsWrapper.setCredentials(key, credentials)
    }

    override fun getRemoteConnectionType(): CredentialsType<*> =
        remoteConnectionCredentialsWrapper.remoteConnectionType

    override fun switchOnConnectionType(vararg cases: CredentialsCase<*>) {
        remoteConnectionCredentialsWrapper.switchType(*cases)
    }

    override fun setSdkId(sdkId: String?) {
        throw IllegalStateException("sdkId in this class is constructed based on fields, so it can't be set")
    }

    override fun getSdkId(): String = constructSdkId(remoteConnectionCredentialsWrapper, remoteSdkProperties)

    override fun getRemoteSdkCredentials(): RsRemoteSdkCredentials? = null

    override fun getRemoteSdkCredentials(allowSynchronousInteraction: Boolean): RsRemoteSdkCredentials? = null

    override fun getRemoteSdkCredentials(
        project: Project?,
        allowSynchronousInteraction: Boolean
    ): RsRemoteSdkCredentials? = null

    override fun produceRemoteSdkCredentials(remoteSdkCredentialsConsumer: Consumer<RsRemoteSdkCredentials>) {
    }

    override fun produceRemoteSdkCredentials(
        allowSynchronousInteraction: Boolean,
        remoteSdkCredentialsConsumer: Consumer<RsRemoteSdkCredentials>
    ) {
    }

    override fun produceRemoteSdkCredentials(
        project: Project?,
        allowSynchronousInteraction: Boolean,
        remoteSdkCredentialsConsumer: Consumer<RsRemoteSdkCredentials>
    ) {
    }

    fun copy(): RsRemoteSdkAdditionalData {
        val copy = RsRemoteSdkAdditionalData(remoteSdkProperties.interpreterPath)
        copyTo(copy)
        return copy
    }

    fun copyTo(copy: RsRemoteSdkAdditionalData) {
        copy.versionString = versionString
        remoteSdkProperties.copyTo(copy.remoteSdkProperties)
        remoteConnectionCredentialsWrapper.copyTo(copy.remoteConnectionCredentialsWrapper)
    }

    override fun save(rootElement: Element) {
        super.save(rootElement)
        remoteSdkProperties.save(rootElement)
        rootElement.setAttribute(VERSION, StringUtil.notNullize(versionString))
        remoteConnectionCredentialsWrapper.save(rootElement)
    }

    companion object {
        private const val RUST_HELPERS: String = ".rust_helpers"

        private const val VERSION: String = "VERSION"

        fun loadRemote(sdk: Sdk, element: Element?): RsRemoteSdkAdditionalData {
            val path = sdk.homePath
            val data = RsRemoteSdkAdditionalData(RemoteSdkCredentialsHolder.getInterpreterPathFromFullPath(path))
            data.load(element)

            if (element != null) {
                CredentialsManager.getInstance().loadCredentials(path, element, data)
                data.remoteSdkProperties.load(element)
                data.versionString = element.getAttributeValue(VERSION).takeIf { it.isNotBlank() }
            }

            return data
        }

        private fun constructSdkId(
            remoteConnectionCredentialsWrapper: RemoteConnectionCredentialsWrapper,
            properties: RemoteSdkPropertiesHolder
        ): String = remoteConnectionCredentialsWrapper.id + properties.interpreterPath
    }

    override fun getRemoteSdkDataKey(): Any = remoteConnectionCredentialsWrapper.connectionKey
}
