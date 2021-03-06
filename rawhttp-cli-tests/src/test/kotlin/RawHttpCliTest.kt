import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.startsWith
import org.junit.Assert.assertThat
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale


class RawHttpCliTest : RawHttpCliTester() {


    @Test
    fun canPrintHelp() {
        fun assertHelpOptOutput(handle: ProcessHandle) {
            val exitValue = handle.waitForEndAndGetStatus()
            assertThat(exitValue, equalTo(0))
            assertThat(handle.err, equalTo(""))
            assertThat(handle.out, startsWith("=============== RawHTTP CLI ==============="))
        }

        val shortOptHandle = runCli("-h")
        assertHelpOptOutput(shortOptHandle)

        val longOptHandle = runCli("help")
        assertHelpOptOutput(longOptHandle)
    }

    @Test
    fun canReadRequestFromSysIn() {
        val handle = runCli("send")

        // write the request to the process sysin
        handle.process.outputStream.writer().use {
            it.write(SUCCESS_HTTP_REQUEST)
        }

        assertOutputIsSuccessResponse(handle)
    }

    @Test
    fun canReadRequestFromTextArgument() {
        val handle = runCli("send", "-t", SUCCESS_HTTP_REQUEST)
        assertOutputIsSuccessResponse(handle)
    }

    @Test
    fun serverReturns404OnNonExistentResource() {
        val handle = runCli("send", "-t", NOT_FOUND_HTTP_REQUEST)
        assertOutputIs404Response(handle)
    }

    @Test
    fun canReadRequestFromFile() {
        val tempFile = File.createTempFile(javaClass.name, "request")
        tempFile.writeText(SUCCESS_HTTP_REQUEST)

        val handle = runCli("send", "-f", tempFile.absolutePath)
        assertOutputIsSuccessResponse(handle)
    }

    @Test
    fun canServeLocalDirectory() {
        val workDir = File(".")
        val someFileInWorkDir = workDir.listFiles()?.firstOrNull { it.isFile }
                ?: return fail("Cannot run test, no files found in the working directory: ${workDir.absolutePath}")

        val handle = runCli("serve", ".")

        val response = try {
            sendHttpRequest("""
            GET http://localhost:8080/${someFileInWorkDir.name}
            Accept: */*
            """.trimIndent()).eagerly()
        } catch (e:AssertionError){
            println(handle)
            throw e
        } finally {
            handle.sendStopSignalToRawHttpServer()
        }

        handle.verifyProcessTerminatedWithExitCode(143) // SIGKILL

        assertThat(response.statusCode, equalTo(200))
        assertTrue(response.body.isPresent)
        assertThat(response.body.get().asBytes(), equalTo(someFileInWorkDir.readBytes()))
    }

    @Test
    fun canServeResourceUsingCustomMediaTypes() {
        val tempDir = createTempDir(javaClass.name)
        val mp3File = File(tempDir, "resource.mp3")
        mp3File.writeText("Music!!")
        val jsonFile = File(tempDir, "some.json")
        jsonFile.writeText("true")

        val mediaTypesFile = File(tempDir, "media.properties")
        mediaTypesFile.writeText("mp3: music/mp3")

        val handle = runCli("serve", tempDir.absolutePath, "--media-types", mediaTypesFile.absolutePath)

        val (mp3response, jsonResponse) = try {
            sendHttpRequest("""
            GET http://localhost:8080/${mp3File.name}
            Accept: */*
            """.trimIndent()).eagerly() to sendHttpRequest("""
            GET http://localhost:8080/${jsonFile.name}
            Accept: */*
            """.trimIndent()).eagerly()
        } finally {
            handle.sendStopSignalToRawHttpServer()
        }

        handle.verifyProcessTerminatedWithExitCode(143) // SIGKILL

        assertThat(mp3response.statusCode, equalTo(200))
        assertTrue(mp3response.body.isPresent)
        assertThat(mp3response.body.get().asBytes(), equalTo(mp3File.readBytes()))
        assertThat(mp3response.headers["Content-Type"], equalTo(listOf("music/mp3")))

        // verify that the standard mappings are still used
        assertThat(jsonResponse.statusCode, equalTo(200))
        assertTrue(jsonResponse.body.isPresent)
        assertThat(jsonResponse.body.get().asBytes(), equalTo(jsonFile.readBytes()))
        assertThat(jsonResponse.headers["Content-Type"], equalTo(listOf("application/json")))
    }

    @Test
    fun canServeAnyDirectoryLoggingRequests() {
        val tempDir = createTempDir(javaClass.name)
        val someFile = File(tempDir, "my-file")
        someFile.writeText("Hello RawHTTP!")

        val handle = runCli("serve", tempDir.absolutePath, "--log-requests")

        val response = try {
            sendHttpRequest("""
            GET http://localhost:8080/${someFile.name}
            Accept: */*
            """.trimIndent()).eagerly()
        } finally {
            handle.sendStopSignalToRawHttpServer()
        }

        handle.verifyProcessTerminatedWithExitCode(143) // SIGKILL

        assertThat("Server returned unexpected status code\n$handle",
                response.statusCode, equalTo(200))
        assertTrue(response.body.isPresent)
        assertThat(response.body.get().asString(Charsets.UTF_8), equalTo("Hello RawHTTP!"))

        // log format follows the Common Log Format - https://en.wikipedia.org/wiki/Common_Log_Format
        val dateRegex = Regex("[0-9.:]+ \\[(?<date>.+)] \".+\" \\d{3} \\d+").toPattern()

        // verify the request was logged
        val lastOutputLine = handle.out.lines().asReversed().find { !it.isEmpty() }
        val match = dateRegex.matcher(lastOutputLine)

        if (!match.find()) {
            return fail("Process output did not match the expected value:\n$handle")
        }

        val logDate = match.group("date")

        // should be able to parse the date with the formatter used in Common Log Format
        val dateFormat = DateTimeFormatter
                .ofPattern("d/MMM/yyyy:HH:mm:ss z")
                .withLocale(Locale.getDefault())
                .withZone(ZoneId.systemDefault())

        val parsedLogDate = LocalDateTime.parse(logDate, dateFormat)

        // should be very recent date
        assertTrue("Parsed Date seems too different from the expected: $parsedLogDate",
                parsedLogDate.isBefore(LocalDateTime.now().plusSeconds(5)) &&
                        parsedLogDate.isAfter(LocalDateTime.now().minusSeconds(10)))

        assertThat(handle.err, equalTo(""))
    }

    @Test
    fun doesNotExposeParentDirectoryWhenServingDirectory() {
        val tempDir = createTempDir(javaClass.name)
        val parentDirFile = File(tempDir.parentFile, tempDir.name + ".test")
        parentDirFile.writeText("not visible")
        parentDirFile.deleteOnExit()

        val handle = runCli("serve", tempDir.absolutePath)

        val response = try {
            sendHttpRequest("""
            GET http://localhost:8080/../${parentDirFile.name}
            Accept: */*
            """.trimIndent()).eagerly()
        } finally {
            handle.sendStopSignalToRawHttpServer()
        }

        handle.verifyProcessTerminatedWithExitCode(143) // SIGKILL

        assertThat("Server returned unexpected status code\n$handle",
                response.statusCode, equalTo(404))
        assertTrue(response.body.isPresent)
        assertThat(response.body.get().asString(Charsets.UTF_8), equalTo("Resource was not found."))
    }

    @Test
    fun canFindResourceWithoutExtension() {
        val tempDir = createTempDir(javaClass.name)
        val jsonFile = File(tempDir, "hello.json")
        val xmlFile = File(tempDir, "hello.xml")
        jsonFile.writeText("{\"hello\": true}")
        xmlFile.writeText("<hello>true</hello>")
        tempDir.deleteOnExit()

        val handle = runCli("serve", tempDir.absolutePath)

        val (jsonResponse, xmlResponse) = try {
            sendHttpRequest("""
            GET http://localhost:8080/hello
            Accept: application/json
            """.trimIndent()).eagerly() to
                    sendHttpRequest("""
            GET http://localhost:8080/hello
            Accept: text/xml
            """.trimIndent()).eagerly()
        } finally {
            handle.sendStopSignalToRawHttpServer()
        }

        handle.verifyProcessTerminatedWithExitCode(143) // SIGKILL

        assertThat("Server returned unexpected status code\n$handle",
                jsonResponse.statusCode, equalTo(200))
        assertTrue(jsonResponse.body.isPresent)
        assertThat(jsonResponse.body.get().asString(Charsets.UTF_8), equalTo(jsonFile.readText()))

        assertThat("Server returned unexpected status code\n$handle",
                xmlResponse.statusCode, equalTo(200))
        assertTrue(xmlResponse.body.isPresent)
        assertThat(xmlResponse.body.get().asString(Charsets.UTF_8), equalTo(xmlFile.readText()))
    }

}
