package com.salesforce.bazel.sdk.command;

import static java.nio.file.Files.createTempFile;
import static java.nio.file.Files.readAllLines;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import com.salesforce.bazel.sdk.BazelVersion;

/**
 * <code>bazel cquery --output label</code>
 */
public class BazelCQueryForLabelsCommand extends BazelCQueryCommand<Collection<String>> {

    public BazelCQueryForLabelsCommand(Path workspaceRoot, String query, boolean keepGoing, String purpose) {
        super(workspaceRoot, query, keepGoing, purpose);
        setCommandArgs("--output", "label");
    }

    @Override
    protected Collection<String> doGenerateResult() throws IOException {
        return readAllLines(getStdOutFile());
    }

    @Override
    public List<String> prepareCommandLine(BazelVersion bazelVersion) throws IOException {
        // redirect output to file for parsing
        var stdoutFile = createTempFile("bazel_cquery_stdout_", ".txt");
        setRedirectStdOutToFile(stdoutFile);

        // prepare regular query command line
        return super.prepareCommandLine(bazelVersion);
    }
}
