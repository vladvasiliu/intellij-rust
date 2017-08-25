/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.infer

interface NodeOrValue
interface Node: NodeOrValue {
    var parent: NodeOrValue
}
data class VarValue<out V>(val value: V?, val rank: Int): NodeOrValue

/**
 * We implement Tarjan's union-find
 * algorithm: when two keys are unified, one of them is converted
 * into a "redirect" pointing at the other. These redirects form a
 * DAG: the roots of the DAG (nodes that are not redirected) are each
 * associated with a value of type `V` and a rank. The rank is used
 * to keep the DAG relatively balanced, which helps keep the running
 * time of the algorithm under control. For more information, see
 * <http://en.wikipedia.org/wiki/Disjoint-set_data_structure>.
 *
 * TODO this class should provide snapshot-rollback feature
 */
@Suppress("UNCHECKED_CAST")
class UnificationTable<K : Node, V> {
    @Suppress("UNCHECKED_CAST")
    private data class Root<out K: Node, out V>(val key: K) {
        private val varValue: VarValue<V> = key.parent as VarValue<V>
        val rank: Int get() = varValue.rank
        val value: V? get() = varValue.value
    }

    private fun get(key: Node): Root<K, V> {
        val parent = key.parent
        return (parent as? Node)?.let {
            val got = get(it)
            key.parent = got.key // Path compression
            got
        } ?: Root(key as K)
    }

    private fun setValue(root: Root<K, V>, value: V) {
        root.key.parent = VarValue(value, root.rank)
    }

    private fun unify(rootA: Root<K, V>, rootB: Root<K, V>, newValue: V?): K {
        return when {
        // a has greater rank, so a should become b's parent,
        // i.e., b should redirect to a.
            rootA.rank > rootB.rank -> redirectRoot(rootA.rank, rootB, rootA, newValue)
        // b has greater rank, so a should redirect to b.
            rootA.rank < rootB.rank -> redirectRoot(rootB.rank, rootA, rootB, newValue)
        // If equal, redirect one to the other and increment the
        // other's rank.
            else -> redirectRoot(rootA.rank + 1, rootA, rootB, newValue)
        }
    }

    private fun redirectRoot(newRank: Int, oldRoot: Root<K, V>, newRoot: Root<K, V>, newValue: V?): K {
        val oldRootKey = oldRoot.key
        val newRootKey = newRoot.key
        oldRootKey.parent = newRootKey
        newRootKey.parent = VarValue(newValue, newRank)
        return newRootKey
    }

    fun find(key: K): K = get(key).key

    fun findValue(key: K): V? = get(key).value

    fun unifyVarVar(key1: K, key2: K): K? {
        val node1 = get(key1)
        val node2 = get(key2)

        if (node1.key == node2.key) return node1.key // already unified

        val val1 = node1.value
        val val2 = node2.value

        val newVal = if (val1 != null && val2 != null) {
            if (val1 != val2) error("unification error") // must be solved on the upper level
            val1
        } else {
            val1 ?: val2
        }

        return unify(node1, node2, newVal)
    }

    fun unifyVarValue(key: K, value: V) {
        val node = get(key)
        if (node.value != null && node.value != value) error("unification error") // must be solved on the upper level

        setValue(node, value)
    }
}
