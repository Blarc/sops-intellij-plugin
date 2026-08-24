package com.github.blarc.sops.intellij.plugin

import com.intellij.execution.configurations.GeneralCommandLine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.lang.reflect.Proxy
import com.intellij.openapi.project.Project

class SopsErrorTest {

    @Test
    fun `blank executable path produces ExecutableNotSet`() = runBlocking {
        var error: SopsError? = null

        SopsWrapper.run(
            sopsPath = "",
            sopsCommand = "decrypt",
            project = projectProxy(),
            onSuccess = {},
            onError = { error = it }
        )

        assertEquals(SopsError.ExecutableNotSet, error)
    }

    @Test
    fun `process creation failures produce ProcessNotCreated`() = runBlocking {
        var error: SopsError? = null

        SopsWrapper.execute(
            GeneralCommandLine("/definitely/not/a/sops-executable"),
            onSuccess = {},
            onError = { error = it }
        )

        val processError = assertIs<SopsError.ProcessNotCreated>(error)
        assertTrue(processError.message.isNotEmpty())
    }

    @Test
    fun `SOPS command failures preserve the diagnostic message`() = runBlocking {
        var error: SopsError? = null

        SopsWrapper.execute(
            GeneralCommandLine("sh").withParameters("-c", "printf diagnostic >&2; exit 23"),
            onSuccess = {},
            onError = { error = it }
        )

        assertEquals(SopsError.CommandFailed("diagnostic"), error)
    }

    @Test
    fun `unrelated command failures have no actions`() {
        val error = SopsError.CommandFailed("failed to decrypt file")

        assertTrue(error.getActions().isEmpty())
    }

    @Test
    fun `edit errors show concise distinct SOPS diagnostics`() {
        val output = """
            time="2026-08-21T09:24:56+02:00" level=error msg="Could not load tree, probably due to invalid syntax. Press enter to return to the editor, or Ctrl+C to exit." error="yaml: line 5: could not find expected ':'"
            time="2026-08-21T09:24:56+02:00" level=error msg="Could not load tree, probably due to invalid syntax. Press enter to return to the editor, or Ctrl+C to exit." error="yaml: line 5: could not find expected ':'"
            time="2026-08-21T09:24:56+02:00" level=error msg="Tree not valid for encryption. Press enter to return to the editor, or Ctrl+C to exit." error="File cannot be completely empty, it must contain at least one document"
        """.trimIndent()

        val expected = """
            Could not load tree, probably due to invalid syntax: yaml: line 5: could not find expected ':'

            Tree not valid for encryption: File cannot be completely empty, it must contain at least one document
        """.trimIndent()

        assertEquals(expected, SopsWrapper.formatEditError(output))
    }

    private fun projectProxy(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java)
    ) { _, _, _ -> null } as Project
}
