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

package com.here.ort.model.config

import java.nio.file.FileSystems
import java.nio.file.Paths

/**
 * A curation for license finding(s).
 */
data class LicenseFindingCuration(
    /**
     * A glob to match the file path of a license finding.
     */
    val pattern: String,
    /**
     * List of start lines this [LicenseFindingCuration] curation matches with.
     * If empty all start lines are matched.
     */
    val start_lines: Set<Int> = emptySet(),
    /**
     * The amount of lines the license finding points to. 0 matches any amount of lines
     */
    val line_count: Int = 0,
    /**
     * The detected license this [LicenseFindingCuration] matches with.
     */
    val detected_license: String = "",
    /**
     * The license all matching license findings shall be replaces with as SPDX expression which also allows setting
     * the license to be empty. See how to use 'NONE' in section 3.13 of
     * https://spdx.org/spdx-specification-21-web-version#h.jxpfx0ykyb60.
     */
    val concluded_license: String
) {
    private val pathMatcher by lazy { FileSystems.getDefault().getPathMatcher("glob:$pattern") }
    private val endLines: Set<Int> by lazy {
        if (start_lines.isEmpty() || line_count == 0) {
            start_lines
        }
        else {
            start_lines.map { it + line_count - 1}.toSet()
        }
    }

    init {
        require(start_lines.all { it >= 0 } )
        require(line_count >= 0)
    }

    fun matches(path: String, start_line: Int, endLine: Int, license: String): Boolean {
        require(start_line  <= endLine)

        return pathMatcher.matches(Paths.get(path)) &&
                (start_lines.isEmpty() || start_line in start_lines) &&
                (endLines.isEmpty() || endLine in endLines) &&
                (detected_license.isEmpty() || license == detected_license)
    }
}
