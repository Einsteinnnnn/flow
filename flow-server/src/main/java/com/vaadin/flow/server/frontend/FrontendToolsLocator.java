/*
 * Copyright 2000-2023 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.flow.server.frontend;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.internal.Pair;

/**
 * Helps to locate the tools in the system by their names.
 * <p>
 * For internal use only. May be renamed or removed in a future release.
 *
 * @since 1.2
 */
public class FrontendToolsLocator implements Serializable {
    private static final String FAILED_WITH_EXIT_CODE_MSG = "Command '{}' failed with exit code '{}'";

    private static class CommandResult implements Serializable {
        private final String command;
        private final int exitCode;
        private final List<String> stdout;
        private final List<String> stderr;

        private CommandResult(String command, int exitCode, List<String> stdout,
                List<String> stderr) {
            this.command = command;
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        private List<String> getStdout() {
            return stdout;
        }

        private boolean isSuccessful() {
            return exitCode == 0;
        }
    }

    /**
     * Makes an attempt to locate the tool by its name. If there are multiple
     * tools to pick from, the first one that will be selected.
     *
     * @param toolName
     *            the name of a tool to locate, not {@code null}
     * @return absolute path to a tool if it was located and
     *         {@link FrontendToolsLocator#verifyTool(File)} returned
     *         {@code true} for it or {@link Optional#empty()} if there are no
     *         such tools
     */
    public Optional<File> tryLocateTool(String toolName) {
        List<String> candidateLocations = executeCommand(false,
                isWindows() ? "where" : "which", toolName)
                .map(this::omitErrorResult).map(CommandResult::getStdout)
                .orElseGet(() -> Arrays.asList(
                        // Add most common paths in unix #5611
                        "/usr/local/bin/" + toolName,
                        "/opt/local/bin/" + toolName, "/opt/bin/" + toolName));

        for (String candidateLocation : candidateLocations) {
            File candidate = new File(candidateLocation);
            if (verifyTool(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Verifies that the tool specified works by performing its test launch.
     *
     * @param toolPath
     *            the path to a tool to check
     * @return {@code true} if the test launch had ended with successful error
     *         code, {@code false} otherwise
     */
    public boolean verifyTool(File toolPath) {
        return Optional.ofNullable(toolPath).filter(File::isFile)
                .map(File::getAbsolutePath)
                .flatMap(path -> executeCommand(true, path, "-v"))
                .map(this::omitErrorResult).isPresent();
    }

    boolean isWindows() {
        String osName = System.getProperty("os.name");
        return osName != null && osName.toLowerCase().startsWith("windows");
    }

    private Optional<CommandResult> executeCommand(boolean logErrorOnFail,
            String... commandParts) {
        String commandString = Arrays.toString(commandParts);
        Process process;
        try {
            process = FrontendUtils
                    .createProcessBuilder(Arrays.asList(commandParts)).start();
        } catch (IOException e) {
            if (logErrorOnFail) {
                log().error("Failed to execute the command '{}'", commandString,
                        e);
            } else if (log().isDebugEnabled()) {
                log().debug("Failed to execute the command '{}'", commandString,
                        e);
            }
            return Optional.empty();
        }

        int exitCode = -1;
        long timeStamp = System.currentTimeMillis();
        CompletableFuture<Pair<String, String>> streamConsumer = FrontendUtils
                .consumeProcessStreams(process);
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            log().error(
                    "Unexpected interruption happened during '{}' command execution",
                    commandString, e);
            // Restore interrupted state
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (exitCode == -1) {
                process.destroyForcibly();
            }
        }

        long executionTime = System.currentTimeMillis() - timeStamp;
        if (log().isDebugEnabled() && executionTime > 3000) {
            log().debug("Command '{}' execution took over 3 seconds",
                    commandString);
        }

        if (exitCode > 0) {
            if (logErrorOnFail) {
                log().error(FAILED_WITH_EXIT_CODE_MSG, commandString, exitCode);
            } else if (log().isDebugEnabled()) {
                log().debug(FAILED_WITH_EXIT_CODE_MSG, commandString, exitCode);
            }
            streamConsumer.cancel(true);
            return Optional.empty();
        }

        List<String> stdout;
        List<String> stderr;
        try {
            // Command is expected to execute fast, so give up after a while to
            // prevent the current process to be blocked indefinitely because
            // of pending STDOUT or STDERR
            Pair<String, String> outputs = streamConsumer.get(10,
                    TimeUnit.SECONDS);
            stdout = new ArrayList<>(
                    List.of(outputs.getFirst().split(System.lineSeparator())));
            stderr = new ArrayList<>(
                    List.of(outputs.getSecond().split(System.lineSeparator())));
        } catch (ExecutionException | InterruptedException
                | TimeoutException e) {
            Throwable cause = e;
            if (e instanceof InterruptedException) {
                // Restore interrupted state
                Thread.currentThread().interrupt();
            }
            if (e instanceof ExecutionException) {
                cause = e.getCause();
            }
            log().error("Failed to read the command '{}' stdout/stderr",
                    commandString, cause);
            return Optional.empty();
        }

        return Optional.of(new CommandResult(commandString, process.exitValue(),
                stdout, stderr));
    }

    private CommandResult omitErrorResult(CommandResult commandResult) {
        if (!commandResult.isSuccessful()) {
            if (log().isDebugEnabled()) {
                log().debug(
                        "Command '{}' exited with non-zero exit code: {}. stdout:\n'{}'\nstderr:\n'{}'",
                        commandResult.command, commandResult.exitCode,
                        commandResult.exitCode,
                        String.join("\n", commandResult.stderr));
            }
            return null;
        }
        if (commandResult.stdout.isEmpty()) {
            if (log().isDebugEnabled()) {
                log().debug("Command '{}' has no output, stderr:\n'{}'",
                        commandResult.command,
                        String.join("\n", commandResult.stderr));
            }
            return null;
        }
        if (!commandResult.stderr.isEmpty()) {
            // "npm -v" can output deprecation warnings to stderr but it still
            // works
            if (log().isDebugEnabled()) {
                log().debug(
                        "Command '{}' has non-empty stderr but assuming this is fine:\n'{}'",
                        commandResult.command,
                        String.join("\n", commandResult.stderr));
            }
        }
        return commandResult;
    }

    private Logger log() {
        return LoggerFactory.getLogger(FrontendToolsLocator.class);
    }
}
