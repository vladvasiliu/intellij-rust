/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig.buildtool

import com.intellij.build.BuildContentManager
import com.intellij.build.BuildViewManager
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.RunManager
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.execution.impl.RunnerAndConfigurationSettingsImpl
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.notification.NotificationGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapiext.isHeadlessEnvironment
import com.intellij.openapiext.isUnitTestMode
import com.intellij.ui.SystemNotifications
import com.intellij.ui.content.MessageView
import com.intellij.util.EnvironmentUtil
import com.intellij.util.concurrency.FutureResult
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.text.SemVer
import com.intellij.util.ui.UIUtil
import org.jetbrains.annotations.TestOnly
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.runconfig.CargoCommandRunner
import org.rust.cargo.runconfig.CargoRunState
import org.rust.cargo.runconfig.addFormatJsonOption
import org.rust.cargo.runconfig.command.CargoCommandConfiguration
import org.rust.cargo.toolchain.CargoCommandLine
import org.rust.cargo.util.CargoArgsParser.Companion.parseArgs
import org.rust.ide.experiments.RsExperiments
import org.rust.openapiext.isFeatureEnabled
import org.rust.openapiext.saveAllDocuments
import java.util.concurrent.Future

@Suppress("UnstableApiUsage")
object CargoBuildManager {
    private val LOG_NOTIFICATION_GROUP: NotificationGroup = NotificationGroup.logOnlyGroup("Build Log")

    private val BUILDABLE_COMMANDS: List<String> = listOf("run", "test", "with")

    @JvmField
    val CANCELED_BUILD_RESULT: Future<CargoBuildResult> =
        FutureResult(CargoBuildResult(succeeded = false, canceled = true, started = 0))

    private val MIN_RUSTC_VERSION: SemVer = SemVer("1.32.0", 1, 32, 0)

    val Project.isBuildToolWindowEnabled: Boolean
        get() {
            if (!isFeatureEnabled(RsExperiments.BUILD_TOOL_WINDOW)) return false
            val rustcVersion = cargoProjects
                .allProjects
                .mapNotNull { it.rustcInfo?.version?.semver }
                .min()
                ?: return false
            return rustcVersion >= MIN_RUSTC_VERSION
        }

    fun build(buildConfiguration: CargoBuildConfiguration): Future<CargoBuildResult> {
        val configuration = buildConfiguration.configuration
        val environment = buildConfiguration.environment

        environment.cargoPatches += cargoBuildPatch
        val state = CargoRunState(
            environment,
            configuration,
            configuration.clean().ok ?: return CANCELED_BUILD_RESULT
        )

        val cargoProject = state.cargoProject ?: return CANCELED_BUILD_RESULT

        // Make sure build tool window is initialized:
        ServiceManager.getService(cargoProject.project, BuildContentManager::class.java)

        if (isUnitTestMode) {
            lastBuildCommandLine = state.commandLine
        }

        return execute(CargoBuildContext(
            cargoProject = cargoProject,
            environment = environment,
            taskName = "Build",
            progressTitle = "Building...",
            isTestBuild = state.commandLine.command == "test"
        )) {
            val buildProgressListener = if (isUnitTestMode) {
                mockBuildProgressListener ?: EmptyBuildProgressListener
            } else {
                ServiceManager.getService(project, BuildViewManager::class.java)
            }

            if (!isHeadlessEnvironment) {
                @Suppress("UsePropertyAccessSyntax")
                val buildToolWindow = BuildContentManager.getInstance(project).getOrCreateToolWindow()
                buildToolWindow.setAvailable(true, null)
                buildToolWindow.show(null)
            }

            processHandler = state.startProcess(emulateTerminal = true)
            processHandler?.addProcessListener(CargoBuildAdapter(this, buildProgressListener))
            processHandler?.startNotify()
        }
    }

    private fun execute(
        context: CargoBuildContext,
        doExecute: CargoBuildContext.() -> Unit
    ): FutureResult<CargoBuildResult> {
        context.environment.notifyProcessStartScheduled()
        val processCreationLock = Any()

        when {
            isUnitTestMode ->
                context.indicator = mockProgressIndicator ?: EmptyProgressIndicator()
            isHeadlessEnvironment ->
                context.indicator = EmptyProgressIndicator()
            else -> {
                val indicatorResult = FutureResult<ProgressIndicator>()
                UIUtil.invokeLaterIfNeeded {
                    object : Task.Backgroundable(context.project, context.taskName, true) {
                        override fun run(indicator: ProgressIndicator) {
                            indicatorResult.set(indicator)

                            var wasCanceled = false
                            while (!context.result.isDone) {
                                if (!wasCanceled && indicator.isCanceled) {
                                    wasCanceled = true
                                    synchronized(processCreationLock) {
                                        context.processHandler?.destroyProcess()
                                    }
                                }

                                try {
                                    Thread.sleep(100)
                                } catch (e: InterruptedException) {
                                    throw ProcessCanceledException(e)
                                }
                            }
                        }
                    }.queue()
                }

                try {
                    context.indicator = indicatorResult.get()
                } catch (e: ExecutionException) {
                    context.result.setException(e)
                    return context.result
                }
            }
        }

        context.indicator?.text = context.progressTitle
        context.indicator?.text2 = ""

        ApplicationManager.getApplication().executeOnPooledThread {
            if (!context.waitAndStart()) return@executeOnPooledThread
            context.environment.notifyProcessStarting()

            if (isUnitTestMode) {
                context.doExecute()
                return@executeOnPooledThread
            }

            // BACKCOMPAT: 2019.3
            @Suppress("DEPRECATION")
            TransactionGuard.submitTransaction(context.project, Runnable {
                synchronized(processCreationLock) {
                    val isCanceled = context.indicator?.isCanceled ?: false
                    if (isCanceled) {
                        context.canceled()
                        return@Runnable
                    }

                    MessageView.SERVICE.getInstance(context.project) // register ToolWindowId.MESSAGES_WINDOW
                    saveAllDocuments()
                    context.doExecute()
                }
            })
        }

        return context.result
    }

    fun isBuildConfiguration(configuration: CargoCommandConfiguration): Boolean {
        val args = ParametersListUtil.parse(configuration.command)
        return when (val command = args.firstOrNull()) {
            "build" -> true
            "test" -> {
                val additionalArguments = args.drop(1)
                val (commandArguments, _) = parseArgs(command, additionalArguments)
                "--no-run" in commandArguments
            }
            else -> false
        }
    }

    fun getBuildConfiguration(configuration: CargoCommandConfiguration): CargoCommandConfiguration? {
        if (isBuildConfiguration(configuration)) return configuration

        val args = ParametersListUtil.parse(configuration.command)
        val command = args.firstOrNull() ?: return null
        if (command !in BUILDABLE_COMMANDS) return null
        val additionalArguments = args.drop(1)
        val (commandArguments, _) = parseArgs(command, additionalArguments)

        // https://github.com/intellij-rust/intellij-rust/issues/3707
        if (command == "test" && commandArguments.contains("--doc")) return null

        val buildConfiguration = configuration.clone() as CargoCommandConfiguration
        buildConfiguration.name = "Build `${buildConfiguration.name}`"
        buildConfiguration.command = when (command) {
            "run" -> ParametersListUtil.join("build", *commandArguments.toTypedArray())
            "test" -> ParametersListUtil.join("test", "--no-run", *commandArguments.toTypedArray())
            "with" -> ParametersListUtil.join("build", *commandArguments.toTypedArray())
            else -> return null
        }
        return buildConfiguration
    }

    fun createBuildEnvironment(buildConfiguration: CargoCommandConfiguration): ExecutionEnvironment? {
        require(isBuildConfiguration(buildConfiguration))
        val project = buildConfiguration.project
        val runManager = RunManager.getInstance(project) as? RunManagerImpl ?: return null
        val executor = ExecutorRegistry.getInstance().getExecutorById(DefaultRunExecutor.EXECUTOR_ID) ?: return null
        val runner = ProgramRunner.findRunnerById(CargoCommandRunner.RUNNER_ID) ?: return null
        val settings = RunnerAndConfigurationSettingsImpl(runManager, buildConfiguration)
        return ExecutionEnvironment(executor, runner, settings, project)
    }

    fun showBuildNotification(
        project: Project,
        messageType: MessageType,
        message: String,
        details: String? = null,
        time: Long = 0
    ) {
        val notificationContent = buildNotificationMessage(message, details, time)
        val notification = LOG_NOTIFICATION_GROUP.createNotification(notificationContent, messageType)
        notification.notify(project)

        if (messageType === MessageType.ERROR) {
            MessageView.SERVICE.getInstance(project) // register ToolWindowId.MESSAGES_WINDOW
            val manager = ToolWindowManager.getInstance(project)
            invokeLater {
                manager.notifyByBalloon(ToolWindowId.MESSAGES_WINDOW, messageType, notificationContent)
            }
        }

        SystemNotifications.getInstance().notify(
            notification.groupId,
            StringUtil.capitalizeWords(message, true),
            details ?: ""
        )
    }

    private fun buildNotificationMessage(message: String, details: String?, time: Long): String {
        var notificationContent = message + if (details == null) "" else " with $details"
        if (time > 0) notificationContent += " in " + StringUtil.formatDuration(time, " ")
        return notificationContent
    }

    private val cargoBuildPatch: CargoPatch = { commandLine ->
        val additionalArguments = commandLine.additionalArguments.toMutableList()
        additionalArguments.remove("-q")
        additionalArguments.remove("--quiet")
        addFormatJsonOption(additionalArguments, "--message-format")
        val environmentVariables = EnvironmentVariablesData.create(
            EnvironmentUtil.getEnvironmentMap() +
                commandLine.environmentVariables.envs - "CI" + ("TERM" to "ansi"),
            false
        )
        commandLine.copy(additionalArguments = additionalArguments, environmentVariables = environmentVariables)
    }

    @TestOnly
    @Volatile
    var testBuildId: Any? = null

    @TestOnly
    @Volatile
    var mockBuildProgressListener: MockBuildProgressListener? = null

    @TestOnly
    @Volatile
    var mockProgressIndicator: MockProgressIndicator? = null

    @TestOnly
    @Volatile
    var lastBuildCommandLine: CargoCommandLine? = null
}
