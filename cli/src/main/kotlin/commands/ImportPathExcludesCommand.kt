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

package com.here.ort.commands

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import com.beust.jcommander.Parameters
import com.here.ort.CommandWithHelp
import com.here.ort.analyzer.Analyzer
import com.here.ort.model.*
import com.here.ort.model.config.*
import com.here.ort.utils.PARAMETER_ORDER_MANDATORY
import com.here.ort.utils.PARAMETER_ORDER_OPTIONAL
import java.io.File

@Parameters(commandNames = ["import-path-excludes"], commandDescription = "Imports path excludes into an ort.yml file.")
object ImportPathExcludesCommand : CommandWithHelp() {
    @Parameter(description = "Outputfile.",
        names = ["--target-ort-yml-file", "-t"],
        required = true,
        order = PARAMETER_ORDER_MANDATORY)
    private lateinit var targetFile: File

    @Parameter(description = "The directory containing the sources for the input ort result.",
        names = ["--scan-dir"],
        order = PARAMETER_ORDER_OPTIONAL)
    private var scanDir: File? = null

    @Parameter(description = "Repository configurations file.",
        names = ["--repository-configurations-file"],
        order = PARAMETER_ORDER_MANDATORY)
    private lateinit var repositoryConfigurationsFile: File

    override fun runCommand(jc: JCommander): Int {

        val config = if (targetFile.isFile) {
            targetFile.readValue<RepositoryConfiguration>()
        } else {
            RepositoryConfiguration()
        }

        val existingExcludes = config.excludes ?: Excludes()
        val existingPathExcludes = existingExcludes.paths
        val importedPathExcludes = generatePathExcludesFromDatabase(scanDir?.absoluteFile, repositoryConfigurationsFile)
        val mergedPathExcludes = mergePathExcludes(importedPathExcludes, existingPathExcludes)

        val resultConfig = config.copy(excludes = existingExcludes.copy(paths = mergedPathExcludes))

        yamlMapper.writeValue(targetFile, resultConfig)
        println("configuration written to: '${targetFile.absolutePath}'")

        return 0
    }

    private fun mergePathExcludes(lhs: List<PathExclude>, rhs: List<PathExclude>): List<PathExclude> {
        val result = mutableMapOf<String, PathExclude>()

        result.putAll(rhs.associateBy { it.pattern })
        result.putAll(lhs.associateBy { it.pattern })

        return result.values
            .toList()
            .sortedBy { it.pattern }
    }

    private fun generatePathExcludesFromDatabase(scanDir: File?, repositoryConfigurationsFile: File?)
            : List<PathExclude> {
        val result = mutableListOf<PathExclude>()

        if (scanDir == null || !scanDir.isDirectory
            || repositoryConfigurationsFile == null || !repositoryConfigurationsFile.isFile) {
            return result
        }
        println("Generating path excludes from database.")

        val gitRepositoryPaths = findGitRepositoryPaths(scanDir)
        println("Found ${gitRepositoryPaths.size} git repositories.")

        val repoConfigs = repositoryConfigurationsFile
            .readValue<RepositoryConfigurations>()
            .configurations

        println("global repo configs: ${repoConfigs.size}")

        val allFiles = findAllFiles(scanDir)

        gitRepositoryPaths.forEach { vcsUrl, paths ->
            println("vcs: $vcsUrl, paths: $paths")
            println("cc: ${repoConfigs.containsKey(vcsUrl)}")
            println("normalizeVcsUrl: ${stripUserInfo(vcsUrl)}")
            repoConfigs[vcsUrl]?.let { config ->
                println(vcsUrl)
                config.excludes?.paths?.forEach { pathExclude ->
                    paths.forEach { path ->
                        val generatedPathExclude = pathExclude.copy(
                            pattern = path + '/' + pathExclude.pattern
                        )

                        if (allFiles.any { generatedPathExclude.matches(it) } ) {
                            result.add(generatedPathExclude)
                        }
                    }
                }
            }
        }

        return result
    }

    private fun stripUserInfo(url: String): String {
        return url.replace("viernau@", "")
    }

    private fun findAllFiles(dir: File): List<String> {
        val result = mutableListOf<String>()

        dir.walk().forEach { file ->
            if (!file.isDirectory) {
                result.add(file.relativeTo(dir).path)
            }
        }

        return result
    }

    private fun findGitRepositoryPaths(dir: File): Map<String, Set<String>> {
        if (!dir.isDirectory) {
            return emptyMap()
        }

        val analyzer = Analyzer(AnalyzerConfiguration(true, true))
        val ortResult = analyzer.analyze(
            absoluteProjectPath = dir,
            packageManagers = emptyList(),
            packageCurationsFile = null,
            repositoryConfigurationFile = null
        )

        val result = mutableMapOf<String, MutableSet<String>>()

        ortResult.repository.nestedRepositories.forEach { path, vcs ->
            if (vcs.type == VcsType.GIT) {
                result
                    .getOrPut(stripUserInfo(vcs.url)) { mutableSetOf() }
                    .add(path)
            }
        }

        return result
    }
}
