/*-
 * Copyright (c) 2025 Gunnar Wagenknecht and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *      Gunnar Wagenknecht - adapted from M2E, JDT or other Eclipse project
 */
package com.salesforce.bazel.sdk.command.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class ShellUtilTest {

    /**
     * Test method for {@link com.salesforce.bazel.sdk.command.shell.ShellUtil#toQuotedStringForShell(java.util.List)}.
     */
    @Test
    void testToQuotedStringForShell_quotation() {
        var shellUtil = new ShellUtil();

        assertEquals(
            "\"\"",
            shellUtil.toQuotedStringForShell(List.of("")),
            "Empty argument should be quoted as empty string");
        assertEquals("\" \"", shellUtil.toQuotedStringForShell(List.of(" ")), "Argument with space should be quoted");
    }

}
