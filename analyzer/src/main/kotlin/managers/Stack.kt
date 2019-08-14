/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
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

package com.here.ort.analyzer.managers

import ch.frankel.slf4k.*

import com.here.ort.analyzer.AbstractPackageManagerFactory
import com.here.ort.analyzer.HTTP_CACHE_PATH
import com.here.ort.analyzer.PackageManager
import com.here.ort.downloader.VersionControlSystem
import com.here.ort.model.Identifier
import com.here.ort.model.Package
import com.here.ort.model.PackageReference
import com.here.ort.model.Project
import com.here.ort.model.ProjectAnalyzerResult
import com.here.ort.model.RemoteArtifact
import com.here.ort.model.Scope
import com.here.ort.model.VcsInfo
import com.here.ort.model.VcsType
import com.here.ort.model.config.AnalyzerConfiguration
import com.here.ort.model.config.RepositoryConfiguration
import com.here.ort.utils.CommandLineTool
import com.here.ort.utils.OkHttpClientHelper
import com.here.ort.utils.OkHttpClientHelper.applyProxySettingsFromEnv
import com.here.ort.utils.ProcessCapture
import com.here.ort.utils.log
import com.here.ort.utils.safeDeleteRecursively

import com.vdurmont.semver4j.Requirement

import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.file.FileSystems
import java.util.SortedSet

import okhttp3.Request

/**
 * The [Stack](https://haskellstack.org/) package manager for Haskell.
 */
class Stack(
    name: String,
    analyzerRoot: File,
    analyzerConfig: AnalyzerConfiguration,
    repoConfig: RepositoryConfiguration
) : PackageManager(name, analyzerRoot, analyzerConfig, repoConfig), CommandLineTool {
    class Factory : AbstractPackageManagerFactory<Stack>("Stack") {
        override val globsForDefinitionFiles = listOf("stack.yaml")

        override fun create(
            analyzerRoot: File,
            analyzerConfig: AnalyzerConfiguration,
            repoConfig: RepositoryConfiguration
        ) = Stack(managerName, analyzerRoot, analyzerConfig, repoConfig)
    }

    private data class TreeLine(val level: Int, val name: String, val version: String) {
        companion object {
            private val DEPENDENCY_TREE_LINE_REGEX = Regex("(.+) ([^ ]+) ([^ ]+)")

            fun parse(line: String): TreeLine {
                DEPENDENCY_TREE_LINE_REGEX.matchEntire(line)?.groupValues?.let {
                    return TreeLine(it[1].length, it[2], it[3])
                } ?: throw IOException("Error parsing dependency tree line '$line'.")
            }
        }
    }

    override fun command(workingDir: File?) = "stack"

    override fun getVersionRequirement(): Requirement = Requirement.buildIvy("[2.1.1,)")

    override fun beforeResolution(definitionFiles: List<File>) =
        checkVersion(
            ignoreActualVersion = analyzerConfig.ignoreToolVersions,
            transform = { it.removePrefix("Version ").substringBefore(',') }
        )

    override fun resolveDependencies(definitionFile: File): ProjectAnalyzerResult? {
        val workingDir = definitionFile.parentFile

        // Parse project information from the *.cabal file.
        val cabalMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.cabal")

        val cabalFiles = workingDir.listFiles(FileFilter {
            cabalMatcher.matches(it.toPath())
        })

        val cabalFile = when (cabalFiles.size) {
            0 -> throw IOException("No *.cabal file found in '$workingDir'.")
            1 -> cabalFiles.first()
            else -> throw IOException("Multiple *.cabal files found in '$cabalFiles'.")
        }

        val projectPackage = parseCabalFile(cabalFile.readText())
        val projectId = projectPackage.id.copy(type = managerName)

        fun runStack(vararg command: String): ProcessCapture {
            // Delete any left-overs from interrupted stack runs.
            File(workingDir, ".stack-work").safeDeleteRecursively()

            return run(workingDir, *command)
        }

        // Parse package information from the stack.yaml file.
        fun buildDependencyTree(
            scope: String,
            scopeDependencies: SortedSet<PackageReference>,
            allPackages: MutableMap<Identifier, Package>
        ) {
            fun buildDependencySubTree(treeIterator: Iterator<String>, previousLevel: Int, dependencies: SortedSet<PackageReference>) {
                while (treeIterator.hasNext()) {
                    val dependency = TreeLine.parse(treeIterator.next())

                    val pkgId = Identifier("Hackage", "", dependency.name, dependency.version)
                    val pkgFallback = Package.EMPTY.copy(id = pkgId, purl = pkgId.toPurl())

                    val pkg = allPackages.getOrPut(pkgId) {
                        // Enrich the package with additional meta-data from Hackage.
                        downloadCabalFile(pkgId)?.let {
                            parseCabalFile(it)
                        } ?: pkgFallback
                    }

                    val packageRef = pkg.toReference()

                    when {
                        dependency.level == previousLevel -> dependencies += packageRef
                        dependency.level > previousLevel -> {
                            packageRef.dependencies += packageRef
                            buildDependencySubTree(treeIterator, dependency.level, packageRef.dependencies)
                        }
                    }
                }
            }

            val tree = runStack("ls", "dependencies", "--tree", "--global-hints", "--$scope").stdout.trim()
            val treeIterator = tree.lineSequence().iterator()

            val header = treeIterator.next()
            if (!treeIterator.hasNext() || header != "Packages") {
                throw IOException("Unexpected dependency tree header '$header'.")
            }

            val root = TreeLine.parse(treeIterator.next())
            if (!treeIterator.hasNext() || root.name != projectId.name) {
                throw IOException("Unexpected dependency tree root '$root'.")
            }

            // The textual tree representation uses an indent of 2 spaces between levels.
            buildDependencySubTree(treeIterator, root.level + 2, scopeDependencies)
        }

        // A map of package IDs to enriched package information.
        val allPackages = mutableMapOf<Identifier, Package>()

        val externalDependencies = sortedSetOf<PackageReference>()
        buildDependencyTree("external", externalDependencies, allPackages)

        val testDependencies = sortedSetOf<PackageReference>()
        buildDependencyTree("test", testDependencies, allPackages)

        val benchDependencies = sortedSetOf<PackageReference>()
        buildDependencyTree("bench", benchDependencies, allPackages)

        val scopes = sortedSetOf(
            Scope("external", externalDependencies),
            Scope("test", testDependencies),
            Scope("bench", benchDependencies)
        )

        val project = Project(
            id = projectId,
            definitionFilePath = VersionControlSystem.getPathInfo(definitionFile).path,
            declaredLicenses = projectPackage.declaredLicenses,
            vcs = projectPackage.vcs,
            vcsProcessed = processProjectVcs(workingDir, projectPackage.vcs, projectPackage.homepageUrl),
            homepageUrl = projectPackage.homepageUrl,
            scopes = scopes
        )

        return ProjectAnalyzerResult(project, allPackages.values.map { it.toCuratedPackage() }.toSortedSet())
    }

    private fun getPackageUrl(name: String, version: String) =
        "https://hackage.haskell.org/package/$name-$version"

    private fun downloadCabalFile(pkgId: Identifier): String? {
        val pkgRequest = Request.Builder()
            .get()
            .url("${getPackageUrl(pkgId.name, pkgId.version)}/src/${pkgId.name}.cabal")
            .build()

        return OkHttpClientHelper.execute(HTTP_CACHE_PATH, pkgRequest, applyProxySettingsFromEnv).use { response ->
            val body = response.body?.string()?.trim()

            if (response.code != HttpURLConnection.HTTP_OK || body.isNullOrEmpty()) {
                log.warn { "Unable to retrieve Hackage meta-data for package '${pkgId.toCoordinates()}'." }
                if (body != null) {
                    log.warn { "The response was '$body' (code ${response.code})." }
                }

                null
            } else {
                body
            }
        }
    }

    private fun parseKeyValue(i: ListIterator<String>, keyPrefix: String = ""): Map<String, String> {
        fun getIndentation(line: String) =
            line.takeWhile { it.isWhitespace() }.length

        var indentation: Int? = null
        val map = mutableMapOf<String, String>()

        while (i.hasNext()) {
            val line = i.next()

            // Skip blank lines and comments.
            if (line.isBlank() || line.trimStart().startsWith("--")) continue

            if (indentation == null) {
                indentation = getIndentation(line)
            } else if (indentation != getIndentation(line)) {
                // Stop if the indentation level changes.
                i.previous()
                break
            }

            val keyValue = line.split(':', limit = 2).map { it.trim() }
            when (keyValue.size) {
                1 -> {
                    // Handle lines without a colon.
                    val nestedMap = parseKeyValue(i, keyPrefix + keyValue[0].replace(" ", "-") + "-")
                    map += nestedMap
                }
                2 -> {
                    // Handle lines with a colon.
                    val key = (keyPrefix + keyValue[0]).toLowerCase()

                    val valueLines = mutableListOf<String>()

                    var isBlock = false
                    if (keyValue[1].isNotEmpty()) {
                        if (keyValue[1] == "{") {
                            // Support multi-line values that use curly braces instead of indentation.
                            isBlock = true
                        } else {
                            valueLines += keyValue[1]
                        }
                    }

                    // Parse a multi-line value.
                    while (i.hasNext()) {
                        var indentedLine = i.next()

                        if (isBlock) {
                            if (indentedLine == "}") {
                                // Stop if a block closes.
                                break
                            }
                        } else {
                            if (indentedLine.isNotBlank() && getIndentation(indentedLine) <= indentation) {
                                // Stop if the indentation level does not increase.
                                i.previous()
                                break
                            }
                        }

                        indentedLine = indentedLine.trim()

                        // Within a multi-line value, lines with only a dot mark empty lines.
                        if (indentedLine == ".") {
                            if (valueLines.isNotEmpty()) {
                                valueLines += ""
                            }
                        } else {
                            valueLines += indentedLine
                        }
                    }

                    val trimmedValueLines = valueLines.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
                    map[key] = trimmedValueLines.joinToString("\n")
                }
            }
        }

        return map
    }

    // TODO: Consider replacing this with a Haskell helper script that calls "readGenericPackageDescription" and dumps
    // it as JSON to the console.
    private fun parseCabalFile(cabal: String): Package {
        // For an example file see
        // https://hackage.haskell.org/package/transformers-compat-0.5.1.4/src/transformers-compat.cabal
        val map = parseKeyValue(cabal.lines().listIterator())

        val id = Identifier(
            type = "Hackage",
            namespace = map["category"].orEmpty(),
            name = map["name"].orEmpty(),
            version = map["version"].orEmpty()
        )

        val artifact = RemoteArtifact.EMPTY.copy(
            url = "${getPackageUrl(id.name, id.version)}/${id.name}-${id.version}.tar.gz"
        )

        val vcsType = (map["source-repository-this-type"] ?: map["source-repository-head-type"]).orEmpty()
        val vcsUrl = (map["source-repository-this-location"] ?: map["source-repository-head-location"]).orEmpty()
        val vcs = VcsInfo(
            type = VcsType(vcsType),
            revision = map["source-repository-this-tag"].orEmpty(),
            url = vcsUrl
        )

        val homepageUrl = map["homepage"].orEmpty()

        return Package(
            id = id,
            declaredLicenses = map["license"]?.let { sortedSetOf(it) } ?: sortedSetOf(),
            description = map["description"].orEmpty(),
            homepageUrl = homepageUrl,
            binaryArtifact = RemoteArtifact.EMPTY,
            sourceArtifact = artifact,
            vcs = vcs,
            vcsProcessed = processPackageVcs(vcs, homepageUrl)
        )
    }
}
