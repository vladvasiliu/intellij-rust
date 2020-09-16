/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.remote

import com.intellij.util.AbstractPathMapper
import com.intellij.util.PathMappingSettings
import com.intellij.util.containers.MultiMap
import java.util.*

class RsRemotePathMapper : AbstractPathMapper(), Cloneable {
    private val pathMappings: MultiMap<RsPathMappingType, PathMappingSettings.PathMapping> = MultiMap.createSet()

    fun addMapping(local: String?, remote: String?, type: RsPathMappingType) {
        pathMappings.putValue(type, PathMappingSettings.PathMapping(local, remote))
    }

    override fun isEmpty(): Boolean = pathMappings.isEmpty

    override fun convertToLocal(remotePath: String): String {
        for (type in RsPathMappingType.values()) {
            val localPath = convertToLocal(remotePath, pathMappings[type])
            if (localPath != null) {
                return localPath
            }
        }
        return remotePath
    }

    override fun convertToRemote(localPath: String): String {
        for (type in RsPathMappingType.values()) {
            val remotePath = convertToRemote(localPath, pathMappings[type])
            if (remotePath != null) {
                return remotePath
            }
        }
        return localPath
    }

    fun addAll(mappings: Collection<PathMappingSettings.PathMapping>, type: RsPathMappingType) {
        for (mapping in mappings) {
            pathMappings.putValue(type, clonePathMapping(mapping))
        }
    }

    override fun getAvailablePathMappings(): Collection<PathMappingSettings.PathMapping> =
        Collections.unmodifiableCollection(pathMappings.values())

    public override fun clone(): RsRemotePathMapper = cloneMapper(this)

    companion object {

        fun cloneMapper(mapper: RsRemotePathMapper?): RsRemotePathMapper {
            val pathMapper = RsRemotePathMapper()
            if (mapper != null) {
                for ((key, value) in mapper.pathMappings.entrySet()) {
                    for (pathMapping in value) {
                        pathMapper.addMapping(pathMapping.localRoot, pathMapping.remoteRoot, key)
                    }
                }
            }
            return pathMapper
        }

        private fun clonePathMapping(pathMapping: PathMappingSettings.PathMapping): PathMappingSettings.PathMapping =
            PathMappingSettings.PathMapping(pathMapping.localRoot, pathMapping.remoteRoot)
    }
}

enum class RsPathMappingType {
    USER_DEFINED,
    REPLICATED_FOLDER,
    SYS_PATH
}
