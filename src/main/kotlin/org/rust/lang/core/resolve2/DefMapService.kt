/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.resolve2

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.rust.lang.core.crate.CratePersistentId
import org.rust.lang.core.macros.macroExpansionManager
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.RustPsiChangeListener
import org.rust.lang.core.psi.rustPsiManager
import org.rust.lang.core.psi.rustStructureModificationTracker
import org.rust.openapiext.checkWriteAccessAllowed
import org.rust.openapiext.fileId
import org.rust.openapiext.pathAsPath
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class DefMapHolder(private val project: Project) {
    @Volatile
    var defMap: CrateDefMap? = null

    /** Value of [rustStructureModificationTracker] at the time when [defMap] started to built */
    @Volatile
    private var defMapStamp: Long = -1

    fun hasLatestStamp(): Boolean = !shouldRebuild && defMapStamp == project.structureStamp

    fun setLatestStamp() {
        defMapStamp = project.structureStamp
    }

    fun checkHasLatestStamp() {
        check(hasLatestStamp()) {
            "DefMapHolder must have latest stamp right after DefMap($defMap) was updated. " +
                "$defMapStamp vs ${project.structureStamp}"
        }
    }

    @Volatile
    var shouldRebuild: Boolean = true
        set(value) {
            field = value
            if (value) {
                shouldRecheck = false
                changedFiles.clear()
            }
        }

    @Volatile
    var shouldRecheck: Boolean = false
    val changedFiles: MutableSet<RsFile> = hashSetOf()

    override fun toString(): String = "DefMapHolder($defMap, stamp=$defMapStamp)"

    companion object {
        private val Project.structureStamp: Long get() = rustStructureModificationTracker.modificationCount
    }
}

// todo разделить interface и impl
@Service
class DefMapService(val project: Project) : Disposable, RustPsiChangeListener {

    private val defMaps: ConcurrentHashMap<CratePersistentId, DefMapHolder> = ConcurrentHashMap()
    val defMapsBuildLock: Any = Any()

    /**
     * todo store [FileInfo] as values ?
     * See [FileInfo.modificationStamp].
     */
    private val fileModificationStamps: ConcurrentHashMap<FileId, Pair<Long, CratePersistentId>> = ConcurrentHashMap()

    /** Merged map of [CrateDefMap.missedFiles] for all crates */
    private val missedFiles: ConcurrentHashMap<Path, CratePersistentId> = ConcurrentHashMap()

    init {
        val connection = project.messageBus.connect()
        project.rustPsiManager.subscribeRustPsiChange(connection, this)
    }

    fun getDefMapHolder(crate: CratePersistentId): DefMapHolder {
        return defMaps.computeIfAbsent(crate) { DefMapHolder(project) }
    }

    fun afterDefMapBuilt(defMap: CrateDefMap) {
        val crate = defMap.crate

        // todo ?
        fileModificationStamps.values.removeIf { (_, it) -> it == crate }
        fileModificationStamps += defMap.fileInfos
            .mapValues { (_, info) -> info.modificationStamp to crate }

        // todo придумать что-нибудь получше вместо removeIf
        //  мб хранить в ключах defMap.modificationStamp и сравнивать его после .get() ?
        missedFiles.values.removeIf { it == crate }
        missedFiles += defMap.missedFiles.associateWith { crate }
    }

    fun onCargoWorkspaceChanged() {
        // todo как-нибудь найти изменённый крейт и установить shouldRecheck только для него ?
        scheduleRecheckAllDefMaps()
    }

    fun onFileAdded(file: RsFile) {
        checkWriteAccessAllowed()
        val path = file.virtualFile.pathAsPath
        val crate = missedFiles[path] ?: return
        getDefMapHolder(crate).shouldRebuild = true
    }

    fun onFileRemoved(file: RsFile) {
        checkWriteAccessAllowed()
        val crate = findCrate(file) ?: return
        getDefMapHolder(crate).shouldRebuild = true
    }

    fun onFileChanged(file: RsFile) {
        checkWriteAccessAllowed()
        val crate = findCrate(file) ?: return
        getDefMapHolder(crate).changedFiles += file
    }

    /** Note: we can't use [RsFile.crate], because it can trigger resolve */
    private fun findCrate(file: RsFile): CratePersistentId? {
        val fileId = file.virtualFile.fileId
        val (_, crate) = fileModificationStamps[fileId] ?: return null
        return crate
    }

    /** Needed when macro expansion is disabled */
    override fun rustPsiChanged(file: PsiFile, element: PsiElement, isStructureModification: Boolean) {
        if (!project.macroExpansionManager.isMacroExpansionEnabled) {
            scheduleRebuildAllDefMaps()
        }
    }

    fun scheduleRebuildAllDefMaps() {
        for (defMapHolder in defMaps.values) {
            defMapHolder.shouldRebuild = true
        }
    }

    fun scheduleRecheckAllDefMaps() {
        for (defMapHolder in defMaps.values) {
            defMapHolder.shouldRecheck = true
        }
    }

    override fun dispose() {}
}

val Project.defMapService: DefMapService
    get() = service()
