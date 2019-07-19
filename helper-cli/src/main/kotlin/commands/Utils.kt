/*
 * Copyright (C) 2017-2018 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.ort.helper.commands

import com.here.ort.model.config.Excludes
import com.here.ort.model.config.PathExclude
import com.here.ort.model.config.RepositoryConfiguration
import com.here.ort.model.config.ScopeExclude
import com.here.ort.model.yamlMapper

import java.io.File

/**
 * Serialize a [RepositoryConfiguration] as YAML to the given target [File].
 */
fun RepositoryConfiguration.prettyPrintAsYaml(targetFile: File) {
    // TODO: make the output nicer and consider conforming with yamllint.
    // The use of backslashes for multi-line strings maybe can be improved as well.

    yamlMapper.writeValue(targetFile, this)
}

/**
 * Return a copy with the [PathExclude]s replaced by the given scope excludes.
 */
fun RepositoryConfiguration.replacePathExcludes(pathExculdes: List<PathExclude>)
        : RepositoryConfiguration = copy(excludes = (excludes ?: Excludes()).copy(paths = pathExculdes))

/**
 * Return a copy with the [ScopeExclude]s replaced by the given scope excludes.
 */
fun RepositoryConfiguration.replaceScopeExcludes(scopeExcludes: List<ScopeExclude>)
    : RepositoryConfiguration = copy(excludes = (excludes ?: Excludes()).copy(scopes = scopeExcludes))

/**
 * Returns a copy with sorting applied to all entry types we want to have sorted.
 */
fun RepositoryConfiguration.sortEntries(): RepositoryConfiguration =
    sortPathExcludes().sortScopeExcludes()

/**
 * Returns a copy with the [PathExclude]s sorted.
 */
fun RepositoryConfiguration.sortPathExcludes(): RepositoryConfiguration =
    copy(
        excludes = excludes?.let {
            val paths = it.paths.sortedBy { pathExclude ->
                pathExclude.pattern.removePrefix("*").removePrefix("*")
            }
            it.copy(paths = paths)
        }
    )

/**
 * Returns a copy with the [ScopeExclude]s sorted.
 */
fun RepositoryConfiguration.sortScopeExcludes(): RepositoryConfiguration =
    copy(
        excludes = excludes?.let {
            val scopes = it.scopes.sortedBy { scopeExclude ->
                scopeExclude.name.toString().removePrefix(".*")
            }
            it.copy(scopes = scopes)
        }
    )

 /**
 * Returns an approximation for the Set-Cover Problem.
 */
fun <K, V>  greedySetCover(sets: Map<K, Set<V>>): Set<K> {
    val result = mutableSetOf<K>()

    var uncovered = sets.values.flatMap { it }.toMutableSet()
    var queue = sets.entries.toMutableSet()

    while(!queue.isEmpty()) {
        val max = queue.maxBy { it.value.intersect(uncovered).size }!!

        if (uncovered.intersect(max.value).size > 0) {
            uncovered.removeAll(max.value)
            queue.remove(max)
            result.add(max.key)
        } else {
            break
        }
    }

    return result
}
