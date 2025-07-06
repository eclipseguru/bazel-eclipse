package com.salesforce.bazel.sdk.command;

import static java.nio.file.Files.createTempFile;
import static java.nio.file.Files.deleteIfExists;
import static java.nio.file.Files.readString;
import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonParser;
import com.google.idea.blaze.base.model.primitives.ExternalWorkspace;
import com.salesforce.bazel.sdk.BazelVersion;

/**
 * <code>bazel mod dump_repo_mapping</code>
 * <p>
 * {@code bazel mod dump_repo_mapping} takes a canonical repository name and will dump a map from repoName ->
 * canonicalName of all the external repositories available to that repository The name {@code ""} is special and
 * considered to be <em>the main workspace</em> so in order to dump the main repository map we would invoke it like
 * {@code bazel mod dump_repo_mapping ""}.
 *
 * <p>
 * Additionally the flag {@code --enable_workspace} needs to be off for this to work. The flag is default off in bazel
 * 8.0.0 but it is on between 7.1.0 and 8.0.0. So we need to also pass this along in between those versions for the
 * command to work well.
 * </p>
 */
public class BazelModDumpRepoMappingCommand extends BazelReadOnlyCommand<List<ExternalWorkspace>> {

    private static final Logger LOG = LoggerFactory.getLogger(BazelModDumpRepoMappingCommand.class);

    public BazelModDumpRepoMappingCommand(Path workspaceRoot, String repoName, String purpose) {
        super("mod", workspaceRoot, purpose);
        setCommandArgs("dump_repo_mapping", repoName);
    }

    @Override
    protected List<ExternalWorkspace> doGenerateResult() throws IOException {
        try {
            var json = JsonParser.parseString(readString(getStdOutFile(), Charset.defaultCharset()).trim())
                    .getAsJsonObject();

            return json.entrySet()
                    .stream()
                    .filter(e -> e.getValue().isJsonPrimitive())
                    .filter(e -> !e.getValue().getAsString().trim().isEmpty())
                    .map(e -> ExternalWorkspace.create(e.getValue().getAsString(), e.getKey()))
                    .collect(toList());
        } finally {
            try {
                deleteIfExists(getStdOutFile());
            } catch (IOException e) {
                LOG.warn("Error deleting '{}'. Please delete manually to save some space.", getStdOutFile(), e);
            }
        }
    }

    @Override
    public List<String> prepareCommandLine(BazelVersion bazelVersion) throws IOException {
        // redirect output to file for parsing
        var stdoutFile = createTempFile("bazel_mod_dump_repo_mapping_stdout_", ".json");
        setRedirectStdOutToFile(stdoutFile);

        var commandLine = super.prepareCommandLine(bazelVersion);

        if (!bazelVersion.isAtLeast(8, 0, 0)) {
            commandLine.add("--noenable_workspace");
        }

        return commandLine;
    }

    @Override
    protected boolean supportsInjectionOfAdditionalBazelOptions() {
        return false;
    }
}
