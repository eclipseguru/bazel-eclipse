package com.salesforce.bazel.sdk.command;

import static java.lang.String.format;
import static java.nio.file.Files.createTempFile;
import static java.nio.file.Files.writeString;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.salesforce.bazel.sdk.BazelVersion;

/**
 * <code>bazel query</code>
 * <p>
 * Uses <code>--query_file</code> to avoid escaping issues with the query.
 * </p>
 *
 * @param <R>
 *            the query output result
 */
public abstract class BazelQueryCommand<R> extends BazelReadOnlyCommand<R> {

    protected enum QueryCommand {
        query, cquery
    }

    private final String query;
    private final boolean keepGoing;

    public BazelQueryCommand(Path workspaceRoot, String query, boolean keepGoing, String purpose) {
        this(QueryCommand.query, workspaceRoot, query, keepGoing, purpose);
    }

    protected BazelQueryCommand(QueryCommand queryCommand, Path workspaceRoot, String query, boolean keepGoing,
            String purpose) {
        super(queryCommand.name(), workspaceRoot, purpose);
        this.query = query;
        this.keepGoing = keepGoing;
    }

    @Override
    protected void appendToStringDetails(ArrayList<String> toStringCommandLine) {
        toStringCommandLine.add(getCommand());
        toStringCommandLine.add(getQuery());
    }

    public String getQuery() {
        return query;
    }

    @Override
    public List<String> prepareCommandLine(BazelVersion bazelVersion) throws IOException {
        var commandLine = super.prepareCommandLine(bazelVersion);

        if (keepGoing) {
            commandLine.add("--keep_going");
        }

        // check version for cquery (https://github.com/bazelbuild/bazel/issues/12924)
        var canUseQueryFile = getCommand().equals(QueryCommand.query.name()) || bazelVersion.isAtLeast(6, 2, 0);

        if (canUseQueryFile) {
            // write query into file and use that
            var queryFile = createTempFile(format("bazel_%s_", getCommand()), ".query.txt");
            writeString(queryFile, query);

            commandLine.add("--query_file");
            commandLine.add(queryFile.toString());
        } else {
            commandLine.add(query);
        }

        return commandLine;
    }

    @Override
    protected boolean shouldFailExitCode(int exitCode) {
        if (keepGoing && (exitCode == 3)) {
            // if --keep_going is set, we do not fail on exit code 3
            return false;
        }
        return super.shouldFailExitCode(exitCode);
    }
}
