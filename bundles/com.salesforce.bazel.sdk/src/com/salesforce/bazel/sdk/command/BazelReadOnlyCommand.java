package com.salesforce.bazel.sdk.command;

import java.nio.file.Path;

/**
 * A "read-only" command which does not modify the Bazel state.
 * <p>
 * This class is primarily used for commands that query a Bazel workspace and do not modify it. They are usually save to
 * run when file system changes are not expected.
 * </p>
 *
 * @param <R>
 *            the query output result
 */
public abstract class BazelReadOnlyCommand<R> extends BazelCommand<R> {

    public BazelReadOnlyCommand(String command, Path workingDirectory, String purpose) {
        super(command, workingDirectory, purpose);
    }

}
