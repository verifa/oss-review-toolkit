/*
 * Copyright (c) 2017-2018 HERE Europe B.V.
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

package com.here.ort.downloader.vcs

import ch.frankel.slf4k.*

import com.here.ort.downloader.DownloadException
import com.here.ort.downloader.VersionControlSystem
import com.here.ort.model.Package
import com.here.ort.model.VcsInfo
import com.here.ort.utils.ProcessCapture
import com.here.ort.utils.getCommandVersion
import com.here.ort.utils.log
import com.here.ort.utils.normalizedPath
import com.here.ort.utils.showStackTrace

import java.io.File
import java.io.IOException
import java.util.regex.Pattern

object Mercurial : VersionControlSystem() {
    private const val EXTENSION_LARGE_FILES = "largefiles = "
    private const val EXTENSION_SPARSE = "sparse = "

    override val aliases = listOf("mercurial", "hg")
    override val commandName = "hg"
    override val movingRevisionNames = listOf("tip", "default")

    override fun getVersion(): String {
        val versionRegex = Pattern.compile("Mercurial .*\\([Vv]ersion (?<version>[\\d.]+)\\)")

        return getCommandVersion("hg") {
            versionRegex.matcher(it.lineSequence().first()).let {
                if (it.matches()) {
                    it.group("version")
                } else {
                    ""
                }
            }
        }
    }

    override fun getWorkingTree(vcsDirectory: File) =
            object : WorkingTree(vcsDirectory) {
                override fun isValid(): Boolean {
                    if (!workingDir.isDirectory) {
                        return false
                    }

                    // Do not use runMercurialCommand() here as we do not require the command to succeed.
                    val hgRootPath = ProcessCapture(workingDir, "hg", "root")
                    return hgRootPath.isSuccess() && workingDir.path.startsWith(hgRootPath.stdout().trimEnd())
                }

                override fun isShallow() = false

                override fun getRemoteUrl() =
                        run(workingDir, "paths", "default").stdout().trimEnd()

                override fun getRevision() =
                        run(workingDir, "--debug", "id", "-i").stdout().trimEnd()

                override fun getRootPath() = run(workingDir, "root").stdout().trimEnd().normalizedPath

                override fun listRemoteTags(): List<String> {
                    // Mercurial does not have the concept of global remote tags. Its "regular tags" are defined per
                    // branch as part of the committed ".hgtags" file. See https://stackoverflow.com/a/2059189/1127485.
                    run(workingDir, "pull", "-r", "default")
                    val tags = run(workingDir, "cat", "-r", "default", ".hgtags").stdout().trimEnd()
                    return tags.lines().map {
                        it.split(' ').last()
                    }.sorted()
                }
            }

    override fun isApplicableUrl(vcsUrl: String) = ProcessCapture("hg", "identify", vcsUrl).isSuccess()

    override fun initWorkingTree(targetDir: File, vcs: VcsInfo): WorkingTree {
        // We cannot detect beforehand if the Large Files extension would be required, so enable it by default.
        val extensionsList = mutableListOf(EXTENSION_LARGE_FILES)

        if (vcs.path.isNotBlank() && isAtLeastVersion("4.3")) {
            // Starting with version 4.3 Mercurial has experimental built-in support for sparse checkouts, see
            // https://www.mercurial-scm.org/wiki/WhatsNew#Mercurial_4.3_.2F_4.3.1_.282017-08-10.29
            extensionsList.add(EXTENSION_SPARSE)
        }

        run(targetDir, "init")
        File(targetDir, ".hg/hgrc").writeText("""
                [paths]
                default = ${vcs.url}
                [extensions]

                """.trimIndent() + extensionsList.joinToString(separator = "\n"))

        if (extensionsList.contains(EXTENSION_SPARSE)) {
            log.info { "Configuring Mercurial to do sparse checkout of path '${vcs.path}'." }
            run(targetDir, "debugsparse", "-I", "${vcs.path}/**", "-I", "LICENSE*", "-I", "LICENCE*")
        }

        return getWorkingTree(targetDir)
    }

    override fun updateWorkingTree(workingTree: WorkingTree, revision: String, recursive: Boolean) {
        // To safe network bandwidth, only pull exactly the revision we want. Do not use "-u" to update the
        // working tree just yet, as Mercurial would only update if new changesets were pulled. But that might
        // not be the case if the requested revision is already available locally.
        run(workingTree.workingDir, "pull", "-r", revision)

        // Explicitly update the working tree to the desired revision.
        run(workingTree.workingDir, "update", revision)

        // TODO: Implement updating of subrepositories.
    }
}
