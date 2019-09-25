/*
 * Copyright (C) 2019 HERE Europe B.V.
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

import com.fasterxml.jackson.databind.JsonNode
import com.here.ort.analyzer.AbstractPackageManagerFactory
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
import com.here.ort.model.jsonMapper
import com.here.ort.utils.CommandLineTool
import com.here.ort.utils.log
import com.here.ort.utils.textValueOrEmpty
import java.io.File
import java.util.SortedSet
import java.util.Stack

/**
 * The [Conan](https://conan.io/) package manager for C / C++.
 */
open class Conan(
    name: String,
    analysisRoot: File,
    analyzerConfig: AnalyzerConfiguration,
    repoConfig: RepositoryConfiguration
) : PackageManager(name, analysisRoot, analyzerConfig, repoConfig), CommandLineTool {
    companion object {
        private const val REQUIRED_CONAN_VERSION = "1.3"
        private const val SCOPE_NAME_DEPENDENCIES = "requires"
        private const val SCOPE_NAME_DEV_DEPENDENCIES = "build-requires"
    }
    class Factory : AbstractPackageManagerFactory<Conan>("Conan") {
        override val globsForDefinitionFiles = listOf("conanfile.txt", "conanfile.py")

        override fun create(
            analysisRoot: File,
            analyzerConfig: AnalyzerConfiguration,
            repoConfig: RepositoryConfiguration
        ) = Conan(managerName, analysisRoot, analyzerConfig, repoConfig)
    }

    override fun command(workingDir: File?) = "conan"

    private fun runInspectRawField(pkgName: String, workingDir: File, field: String): String {
        return run(workingDir, "inspect", pkgName, "--raw", field).stdout
    }
    /*
    override fun getVersionRequirement(): Requirement = Requirement.buildStrict(REQUIRED_CONAN_VERSION)

    override fun beforeResolution(definitionFiles: List<File>) =
        checkVersion(ignoreActualVersion = analyzerConfig.ignoreToolVersions)

    */

    private fun extractDependencyTree(
        rootNode: JsonNode,
        workingDir: File,
        pkg: JsonNode,
        scopeName: String
    ): SortedSet<PackageReference> {
        val result = mutableSetOf<PackageReference>()
        pkg[ scopeName ]?.forEach {

            val childRef = it.textValueOrEmpty()

            rootNode.iterator().forEach { child ->
                if (child["reference"].textValueOrEmpty() == childRef) {
                    log.debug { "Found child. '$childRef'" }
                    val childScope = SCOPE_NAME_DEPENDENCIES

                    val childDependencies = extractDependencyTree(rootNode, workingDir, child, childScope)
                    val packageReference = PackageReference(
                        id = extractPackageId(child, workingDir),
                        dependencies = childDependencies
                    )
                    result.add(packageReference)
                }
            }
        }
        return result.toSortedSet()
    }
    // Runs through each package and extracts list of deps (including transitive)
    private fun extractDependencies(
        rootNode: JsonNode,
        scopeName: String,
        workingDir: File
    ): SortedSet<PackageReference> {
        val stack = Stack<JsonNode>()
        val dependencies = mutableSetOf<PackageReference>()

        stack.addAll(rootNode)
        while (!stack.empty()) {
            val pkg = stack.pop()
            extractDependencyTree(rootNode, workingDir, pkg, scopeName).forEach {
                dependencies.add(it)
            }
        }
        return dependencies.toSortedSet()
    }

    private fun removeProjectPackage(rootNode: JsonNode, definitionFile: File): List<JsonNode> {
        val projectPackage: JsonNode? = rootNode.find {
            // Contains because conanfile.py's reference string often includes other data.
            it["reference"].textValueOrEmpty().contains(definitionFile.name)
        }
        return if (projectPackage != null) {
            rootNode.minusElement(projectPackage)
        } else {
            rootNode.toList<JsonNode>()
        }
    }

    override fun resolveDependencies(definitionFile: File): ProjectAnalyzerResult? {
        log.info { "Resolving dependencies for: '$definitionFile'" }
        val workingDir = definitionFile.parentFile
        val dependenciesJson = run(workingDir, "info", ".", "-j").stdout
        val rootNode = jsonMapper.readTree(dependenciesJson)

        val packageList = removeProjectPackage(rootNode, definitionFile)

        val packages = extractPackages(packageList, workingDir)

        val definitionFileJson = rootNode.find {
            it["reference"].textValueOrEmpty().contains(definitionFile.name)
        }

        val projectPackage = extractProjectPackage(definitionFileJson!!, definitionFile, workingDir)
        val dependenciesScope = Scope(
            name = SCOPE_NAME_DEPENDENCIES,
            dependencies = extractDependencies(rootNode, SCOPE_NAME_DEPENDENCIES, workingDir)
        )
        val devDependenciesScope = Scope(
            name = SCOPE_NAME_DEV_DEPENDENCIES,
            dependencies = extractDependencies(rootNode, SCOPE_NAME_DEV_DEPENDENCIES, workingDir)
        )

        val project = Project(
            id = projectPackage.id,
            definitionFilePath = VersionControlSystem.getPathInfo(definitionFile).path,
            declaredLicenses = projectPackage.declaredLicenses,
            vcs = projectPackage.vcs,
            vcsProcessed = processProjectVcs(workingDir, projectPackage.vcs, projectPackage.homepageUrl),
            homepageUrl = projectPackage.homepageUrl,
            scopes = sortedSetOf(dependenciesScope, devDependenciesScope)
        )

        return ProjectAnalyzerResult(
            project = project,
            packages = packages.map { it.value.toCuratedPackage() }.toSortedSet()
        )
    }
    protected open fun hasLockFile(projectDir: File) = null

    private fun extractVcsInfo(node: JsonNode) =
        VcsInfo(
            type = VcsType.GIT,
            url = node["url"].textValueOrEmpty(),
            revision = node["revision"].textValueOrEmpty()
        )

    private fun extractDeclaredLicenses(node: JsonNode): SortedSet<String> =
        sortedSetOf<String>().apply {
            val license = node["license"]
            license?.forEach {
                add(it.textValueOrEmpty())
            }
        }
    private fun extractPackageId(node: JsonNode, workingDir: File) =
        Identifier(
            type = "Conan",
            namespace = "",
            name = extractPackageField(node, workingDir, "name"),
            version = extractPackageField(node, workingDir, "version")
        )

    // Runs 'conan inspect --raw' over a specified package to extract specified field.
    private fun extractPackageField(node: JsonNode, workingDir: File, field: String): String {
        if (!listOf("conanfile.txt", "conanfile.py", "", " ").contains(node["display_name"].textValueOrEmpty())) {
            val pkgField = runInspectRawField(node["display_name"].textValueOrEmpty(), workingDir, field)
            return pkgField
        }
        return node["display_name"].textValueOrEmpty()
    }

    private fun extractPackage(node: JsonNode, workingDir: File) =
        Package(
            id = extractPackageId(node, workingDir),
            declaredLicenses = extractDeclaredLicenses(node),
            description = extractPackageField(node, workingDir, "description"),
            homepageUrl = node["homepage"].textValueOrEmpty(),
            binaryArtifact = RemoteArtifact.EMPTY, // TODO: implement me!
            sourceArtifact = RemoteArtifact.EMPTY, // TODO: implement me!
            vcs = extractVcsInfo(node)
        )

    /**
     * Project package options differ depending on which definition file is used:
     * conanfile.py: allows us to run 'conan inspect conanfile.py' and extract useful project metadata
     * conanfile.txt: does not allow 'conan inspect conanfile.txt'.
     * TODO: Format of 'conan info' output of conanfile.txt may be such that we can get project metadata
     *  from the 'requires' field... need to investigate whether this is a sure thing before implementing.
     */
    private fun extractProjectPackage(projectPackageJson: JsonNode, definitionFile: File, workingDir: File): Package {
        if (definitionFile.name == "conanfile.py") return Package(
            id = Identifier(
                type = managerName,
                namespace = "",
                name = runInspectRawField(definitionFile.name, workingDir, "name"),
                version = runInspectRawField(definitionFile.name, workingDir, "version")
            ),
            declaredLicenses = extractDeclaredLicenses(projectPackageJson),
            description = runInspectRawField(definitionFile.name, workingDir, "description"),
            homepageUrl = projectPackageJson["homepage"].textValueOrEmpty(),
            binaryArtifact = RemoteArtifact.EMPTY, // TODO: implement me!
            sourceArtifact = RemoteArtifact.EMPTY, // TODO: implement me!
            vcs = extractVcsInfo(projectPackageJson)
        )
        return Package(
            id = Identifier(
                type = managerName,
                namespace = "",
                name = projectPackageJson["reference"].textValueOrEmpty(),
                version = ""
            ),
            declaredLicenses = extractDeclaredLicenses(projectPackageJson),
            description = "",
            homepageUrl = projectPackageJson["homepage"].textValueOrEmpty(),
            binaryArtifact = RemoteArtifact.EMPTY, // TODO: implement me!
            sourceArtifact = RemoteArtifact.EMPTY, // TODO: implement me!
            vcs = extractVcsInfo(projectPackageJson)
        )
    }

    private fun extractPackages(node: List<JsonNode>, workingDir: File): Map<String, Package> {

        val result = mutableMapOf<String, Package>()

        val stack = Stack<JsonNode>()
        stack.addAll(node)

        while (!stack.empty()) {
            val currentNode = stack.pop()
            val pkg = extractPackage(currentNode, workingDir)
            result["${pkg.id.name}:${pkg.id.version}"] = pkg
        }
        return result
    }
}
