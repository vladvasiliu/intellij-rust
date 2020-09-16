/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.remote

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapiext.isHeadlessEnvironment
import com.intellij.openapiext.isUnitTestMode
import com.intellij.remote.PathMappingProvider
import com.intellij.remote.RemoteMappingsManager
import com.intellij.remote.RemoteSdkAdditionalData
import org.rust.openapiext.runUnderProgress
import org.rust.remote.sdk.RsRemoteSdkAdditionalData

object RsRemoteProcessStarter {
    private const val CARGO_PREFIX: String = "cargo"

    fun startRemoteProcess(
        sdk: Sdk,
        commandLine: GeneralCommandLine,
        project: Project?,
        pathMapper: RsRemotePathMapper?
    ): ProcessHandler {
        val processHandler = try {
            doStartRemoteProcess(sdk, commandLine, project, pathMapper)
        } catch (e: RsUnsupportedSdkTypeException) {
            throw ExecutionException("Support for ${sdk.name} is not available.\nPlease check the corresponding plugin.", e)
        } catch (e: ExecutionException) {
            if (isUnitTestMode || isHeadlessEnvironment) {
                throw RuntimeException(e)
            }
            throw ExecutionException("Can't use remote Rust toolchain: " + e.message, e)
        }
        ProcessTerminatedListener.attach(processHandler)
        return processHandler
    }

    private fun doStartRemoteProcess(
        sdk: Sdk,
        commandLine: GeneralCommandLine,
        project: Project?,
        pathMapper: RsRemotePathMapper?
    ): ProcessHandler {
        val additionalData = sdk.sdkAdditionalData
        require(additionalData is RsRemoteSdkAdditionalData)
        return try {
            val manager = RsRemoteProcessStarterManager.getManager(additionalData)
            manager.startRemoteProcess(
                project,
                commandLine,
                additionalData,
                appendMappings(project, pathMapper, additionalData)
            )
        } catch (e: InterruptedException) {
            throw ExecutionException(e)
        }
    }

    private fun appendMappings(
        project: Project?,
        pathMapper: RsRemotePathMapper?,
        data: RemoteSdkAdditionalData<*>
    ): RsRemotePathMapper {
        val newPathMapper = RsRemotePathMapper.cloneMapper(pathMapper)
        newPathMapper.addAll(data.pathMappings.pathMappings, RsPathMappingType.SYS_PATH)

        if (project == null) return newPathMapper

        val mappings = RemoteMappingsManager.getInstance(project).getForServer(CARGO_PREFIX, data.sdkId)
        if (mappings != null) {
            newPathMapper.addAll(mappings.settings, RsPathMappingType.USER_DEFINED)
        }

        for (mappingProvider in PathMappingProvider.getSuitableMappingProviders(data)) {
            val settings = ProgressManager.getInstance().runUnderProgress("Accessing remote toolchain...") {
                mappingProvider.getPathMappingSettings(project, data)
            }
            newPathMapper.addAll(settings.pathMappings, RsPathMappingType.REPLICATED_FOLDER)
        }

        return newPathMapper
    }
}
