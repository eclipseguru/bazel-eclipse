/*-
 * Copyright (c) 2023 Salesforce and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *      Salesforce - Partially adapted and heavily inspired from Eclipse JDT, M2E and PDE
 */
package com.salesforce.bazel.eclipse.core.model;

import static com.salesforce.bazel.eclipse.core.BazelCoreSharedContstants.BAZEL_NATURE_ID;
import static java.lang.String.format;
import static java.nio.file.Files.isRegularFile;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.idea.blaze.base.model.primitives.ExternalWorkspace;
import com.salesforce.bazel.eclipse.core.extensions.DetectBazelVersionAndSetBinaryJob;
import com.salesforce.bazel.eclipse.core.model.buildfile.FunctionCall;
import com.salesforce.bazel.eclipse.core.model.execution.BazelModelCommandExecutionService;
import com.salesforce.bazel.eclipse.core.projectview.BazelProjectFileReader;
import com.salesforce.bazel.eclipse.core.projectview.BazelProjectView;
import com.salesforce.bazel.sdk.BazelVersion;
import com.salesforce.bazel.sdk.command.BazelBinary;
import com.salesforce.bazel.sdk.command.BazelInfoCommand;
import com.salesforce.bazel.sdk.command.BazelModDumpRepoMappingCommand;
import com.salesforce.bazel.sdk.command.BazelQueryForTargetProtoCommand;
import com.salesforce.bazel.sdk.command.querylight.Target;

public final class BazelWorkspaceInfo extends BazelElementInfo {

    private enum BazelInfoKey {
        EXECUTION_ROOT("execution_root"),
        RELEASE("release"),
        REPOSITORY_CACHE("repository_cache"),
        BAZEL_BIN("bazel-bin"),
        BAZEL_GENFILES("bazel-genfiles"),
        BAZEL_TESTLOGS("bazel-testlogs"),
        COMMAND_LOG("command_log"),
        OUTPUT_BASE("output_base"),
        INSTALL_BASE("install_base"),
        OUTPUT_PATH("output_path"),
        STARLARK_SEMANTICS("starlark-semantics");

        static List<String> allKeys() {
            return Arrays.stream(values()).map(BazelInfoKey::key).collect(Collectors.toList());
        }

        private final String key;

        BazelInfoKey(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    private static final BazelVersion UNKNOWN_BAZEL_VERSION = new BazelVersion(999, 999, 999);

    private static Logger LOG = LoggerFactory.getLogger(BazelWorkspaceInfo.class);

    private static final String RELEASE_VERSION_PREFIX = "release ";

    private final IPath root;
    private final Path workspaceFile;
    private final BazelWorkspace bazelWorkspace;
    private volatile IProject project;
    private volatile BazelProject bazelProject;
    private volatile BazelProjectView bazelProjectView;
    private volatile BazelProjectFileSystemMapper bazelProjectFileSystemMapper;

    private IPath excutionRoot;
    private String name;
    private String release;
    private IPath repositoryCache;
    private IPath bazelBin;
    private IPath bazelGenfiles;
    private IPath bazelTestlogs;
    private IPath commandLog;
    private IPath installBase;
    private IPath outputBase;
    private IPath outputPath;
    private String starlarkSemantics;
    private BazelVersion bazelVersion;

    private volatile Map<String, BazelRuleAttributes> externalRepositoryRuleByName;

    private BazelBinary bazelBinary;

    private Map<String, ExternalWorkspace> externalWorkspaceByRepoName;

    public BazelWorkspaceInfo(IPath root, Path workspaceFile, BazelWorkspace bazelWorkspace) {
        this.root = root;
        this.workspaceFile = workspaceFile;
        this.bazelWorkspace = bazelWorkspace;
    }

    public List<BazelProject> findBazelProjects() throws CoreException {
        var result = new ArrayList<BazelProject>();
        var projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        for (IProject project : projects) {
            if ((project.isOpen() && project.hasNature(BAZEL_NATURE_ID))
                    && BazelProject.hasWorkspaceRootPropertySetToLocation(project, getRoot())) {
                result.add(bazelWorkspace.getModelManager().getBazelProject(project));
            }
        }
        return result;
    }

    public IPath getBazelBin() {
        return bazelBin;
    }

    /**
     * {@return a specific binary configured for this workspace, or <code>null</code> if a default Bazel binary should
     * be used}
     */
    BazelBinary getBazelBinary() {
        return bazelBinary;
    }

    public IPath getBazelGenfiles() {
        return bazelGenfiles;
    }

    public BazelProject getBazelProject() throws CoreException {
        var cachedBazelProject = bazelProject;
        if (cachedBazelProject != null) {
            return cachedBazelProject;
        }

        return bazelProject = new BazelProject(getProject(), bazelWorkspace.getModel());
    }

    private BazelProjectFileSystemMapper getBazelProjectFileSystemMapper() {
        var cachedBazelProjectFileSystemMapper = bazelProjectFileSystemMapper;
        if (cachedBazelProjectFileSystemMapper != null) {
            return cachedBazelProjectFileSystemMapper;
        }

        return bazelProjectFileSystemMapper = new BazelProjectFileSystemMapper(getOwner());
    }

    public BazelProjectView getBazelProjectView() throws CoreException {
        var cachedProjectView = bazelProjectView;
        if (cachedProjectView != null) {
            return cachedProjectView;
        }

        var projectViewLocation = getBazelProjectFileSystemMapper().getProjectViewLocation();
        try {
            return bazelProjectView =
                    new BazelProjectFileReader(projectViewLocation.toPath(), getRoot().toPath()).read();
        } catch (IOException e) {
            throw new CoreException(
                    Status.error(
                        format(
                            "Error reading project view '%s'. Please check the setup. Each workspace is required to have a project view to work properly in an IDE. %s",
                            projectViewLocation,
                            e.getMessage()),
                        e));
        }
    }

    public IPath getBazelTestlogs() {
        return bazelTestlogs;
    }

    /**
     * {@return the Bazel version detected when loading the workspace (none <code>null</code> after the workspace was
     * {@link #load(BazelModelCommandExecutionService) load} was called).}
     */
    BazelVersion getBazelVersion() {
        return requireNonNull(bazelVersion, "not loaded");
    }

    public IPath getCommandLog() {
        return commandLog;
    }

    public IPath getExcutionRoot() {
        return excutionRoot;
    }

    private String getExpectedOutput(Map<String, String> infoResult, BazelInfoKey key) throws CoreException {
        var value = infoResult.get(key.key());
        if ((value == null) || value.isBlank()) {
            throw new CoreException(
                    Status.error(
                        format(
                            "incomplete bazel info output in workspace '%s': %s missing%n%navailable info:%n%s",
                            root,
                            key.key(),
                            infoResult.entrySet()
                                    .stream()
                                    .map(e -> e.getKey() + ": " + e.getValue())
                                    .collect(joining(System.lineSeparator())))));
        }

        return value;
    }

    private IPath getExpectedOutputAsPath(Map<String, String> infoResult, BazelInfoKey key) throws CoreException {
        return new org.eclipse.core.runtime.Path(getExpectedOutput(infoResult, key));
    }

    public Stream<BazelRuleAttributes> getExternalRepositoriesByRuleClass(Predicate<String> ruleClassPredicate)
            throws CoreException {
        var externalRepositoryRuleByName = this.externalRepositoryRuleByName;
        if (externalRepositoryRuleByName == null) {
            externalRepositoryRuleByName = loadExternalRepositoryRules();
        }

        return externalRepositoryRuleByName.values().stream().filter(a -> ruleClassPredicate.test(a.getRuleClass()));
    }

    public BazelRuleAttributes getExternalRepository(String externalRepositoryName) throws CoreException {
        if (externalRepositoryRuleByName != null) {
            return externalRepositoryRuleByName.get(externalRepositoryName);
        }

        return loadExternalRepositoryRules().get(externalRepositoryName);
    }

    public ExternalWorkspace getExternalRepositoryMappingByRepoName(String repoName) throws CoreException {
        var externalWorkspaceByRepoName = this.externalWorkspaceByRepoName;
        if (externalWorkspaceByRepoName == null) {
            externalWorkspaceByRepoName = loadExternalRepoMappings();
        }

        return externalWorkspaceByRepoName.get(repoName);
    }

    public Stream<ExternalWorkspace> getExternalRepositoryMappings() throws CoreException {
        var externalWorkspaceByRepoName = this.externalWorkspaceByRepoName;
        if (externalWorkspaceByRepoName == null) {
            externalWorkspaceByRepoName = loadExternalRepoMappings();
        }

        return externalWorkspaceByRepoName.values().stream();
    }

    public IPath getInstallBase() {
        return installBase;
    }

    public String getName() {
        return name;
    }

    public IPath getOutputBase() {
        return outputBase;
    }

    public IPath getOutputPath() {
        return outputPath;
    }

    @Override
    public BazelWorkspace getOwner() {
        return bazelWorkspace;
    }

    IProject getProject() throws CoreException {
        var cachedProject = project;
        if (cachedProject != null) {
            return cachedProject;
        }

        // we don't care about the actual project name - we look for the path
        var projects = getEclipseWorkspaceRoot().getProjects();
        for (IProject project : projects) {
            if (root.equals(project.getLocation())) {
                return this.project = project;
            }
        }

        throw new CoreException(
                Status.error(
                    format(
                        "Unable to find project for Bazel workspace root '%s' in the Eclipse workspace. Please check the workspace setup!",
                        root)));
    }

    public String getRelease() {
        return release;
    }

    public IPath getRepositoryCache() {
        return repositoryCache;
    }

    public IPath getRoot() {
        return root;
    }

    public String getStarlarkSemantics() {
        return starlarkSemantics;
    }

    public Path getWorkspaceFile() {
        return workspaceFile;
    }

    public String getWorkspaceName() {
        return requireNonNull(name, "not loaded");
    }

    private void initializeBazelBinary(Path binary) {
        // we should not be using any service but just execute it directly
        // because BazelElementCommandExecutor calls BazelWorksapce#getBazelBinary

        // use job to signal progress
        var job = new DetectBazelVersionAndSetBinaryJob(binary, false, bazelBinary -> {
            this.bazelBinary = bazelBinary;
        }, () -> {
            var defaultVersion = UNKNOWN_BAZEL_VERSION;
            LOG.error(
                "Unable to detect version for Bazel binary '{}' (configured via .bazelproject file) - defaulting to '{}'",
                binary,
                defaultVersion);
            return new BazelBinary(binary, defaultVersion);
        });
        job.schedule();
        try {
            // wait for completion
            job.join();
        } catch (InterruptedException e) {
            throw new OperationCanceledException("Interrupted waiting for Bazel binary version detection to happen");
        }
    }

    public boolean isBzlModEnabled() {
        // validate that bzlmod is enabled (technically this validates that the --enable_bzlmod is not
        // changed from the default `true` aka set to false)
        // source: https://github.com/bazelbuild/intellij/blob/d40c9126ea4e4fd998b8245a2b5e2489f3fc8d6e/base/src/com/google/idea/blaze/base/model/ExternalWorkspaceDataProvider.java#L81-L88
        var starLarkSemantics = starlarkSemantics;

        if ((starLarkSemantics == null) || starLarkSemantics.isEmpty()) {
            // if starlark-semantics is not set, we assume that Bazel is too old to support bzlmod
            return false;
        }

        return !starLarkSemantics.contains("enable_bzlmod=false");
    }

    public boolean isWorkspaceFileSupportEnabled() {
        // check starlark-semantics for "enable_workspace=true"
        var starLarkSemantics = starlarkSemantics;

        // in Bazel 8+ the default was flipped to false
        var isBazel8OrAbove = getBazelVersion().isAtLeast(8, 0, 0);

        if ((starLarkSemantics == null) || starLarkSemantics.isEmpty()) {
            // if starlark-semantics is not set, we decide based on the Bazel version
            return !isBazel8OrAbove;
        }

        // starlark-semantics will contain the opposite of the default value
        // in Bazel 8+ we check for "enable_workspace=true" to enable it
        if (isBazel8OrAbove) {
            return starLarkSemantics.contains("enable_workspace=true");
        }

        // in Bazel < 8 we check for "enable_workspace=false" to check it's not disables.
        return !starLarkSemantics.contains("enable_workspace=false");
    }

    public void load(BazelModelCommandExecutionService executionService) throws CoreException {
        var workspaceRoot = getWorkspaceFile().getParent();

        // check for a workspace specific binary
        // note: this will trigger loading the project view
        // but here the project view is optional (we may be called without being projects)
        var projectViewLocation = getBazelProjectFileSystemMapper().getProjectViewLocation();
        if (isRegularFile(projectViewLocation.toPath())) {
            var workspaceBinary = getBazelProjectView().bazelBinary();
            if (workspaceBinary != null) {
                // resolve against the workspace root
                var binary = workspaceBinary.isAbsolute() ? workspaceBinary.toPath()
                        : workspaceRoot.resolve(workspaceBinary.toPath());
                initializeBazelBinary(binary);
            }
        }

        try {
            // we use the BazelModelCommandExecutionService directly because there is a cycle dependency between
            // BazelModelCommandExecutor and BazelWorkspace#getBazelBinary

            var workspaceCommand =
                    new BazelInfoCommand(workspaceRoot, "Reading workspace info", BazelInfoKey.allKeys());
            workspaceCommand.setBazelBinary(getBazelBinary());

            var infoResult = executionService.executeOutsideWorkspaceLockAsync(workspaceCommand, bazelWorkspace).get();

            // sanity check
            if (infoResult.isEmpty()) {
                throw new CoreException(
                        Status.error(
                            format(
                                "bazel info did not return any output in workspace '%s'. Please check the bazel output and binary setup/configuration!",
                                root)));

            }

            excutionRoot = getExpectedOutputAsPath(infoResult, BazelInfoKey.EXECUTION_ROOT);
            release = getExpectedOutput(infoResult, BazelInfoKey.RELEASE);
            repositoryCache = getExpectedOutputAsPath(infoResult, BazelInfoKey.REPOSITORY_CACHE);
            bazelBin = getExpectedOutputAsPath(infoResult, BazelInfoKey.BAZEL_BIN);
            bazelGenfiles = getExpectedOutputAsPath(infoResult, BazelInfoKey.BAZEL_GENFILES);
            bazelTestlogs = getExpectedOutputAsPath(infoResult, BazelInfoKey.BAZEL_TESTLOGS);
            commandLog = getExpectedOutputAsPath(infoResult, BazelInfoKey.COMMAND_LOG);
            installBase = getExpectedOutputAsPath(infoResult, BazelInfoKey.INSTALL_BASE);
            outputBase = getExpectedOutputAsPath(infoResult, BazelInfoKey.OUTPUT_BASE);
            outputPath = getExpectedOutputAsPath(infoResult, BazelInfoKey.OUTPUT_PATH);
            starlarkSemantics = getExpectedOutput(infoResult, BazelInfoKey.STARLARK_SEMANTICS);

            if (release.startsWith(RELEASE_VERSION_PREFIX)) {
                // parse the version from bazel info instead of using BazelBinary (if available)
                bazelVersion = BazelVersion.parseVersion(release.substring(RELEASE_VERSION_PREFIX.length()));
            } else {
                // ensure it's not null
                bazelVersion = UNKNOWN_BAZEL_VERSION;
            }

            // in bzlmod the execution root segment seems to be broken, it's always _main (https://github.com/bazelbuild/bazel/issues/2317#issuecomment-1849740317)
            var moduleFile = bazelWorkspace.getBazelModuleFile();
            if (moduleFile.exists()) {
                // read the file directly as we are within a loading lock already
                try {
                    var reader = new BazelStarlarkFileReader(moduleFile.getLocation().toPath());
                    reader.read();
                    var moduleCall = reader.getModuleCall();
                    if (moduleCall != null) {
                        var module = new FunctionCall(null, moduleCall, Collections.emptyMap());
                        name = module.getStringArgument("repo_name");

                        // fallback to module name if repo name is undefined
                        if (name == null) {
                            name = module.getStringArgument("name");
                        }
                    }
                } catch (IOException e) {
                    // ignore (fallback to pre-bzlmod)
                    LOG.debug(
                        "Ignored exception reading MODULE.bazel file '{}': {}",
                        moduleFile.getLocation(),
                        e.getMessage(),
                        e);
                }
            }

            // in none bzlmod or if name is empty we use the execution root per https://github.com/bazelbuild/bazel/issues/2317
            if ((name == null) || name.isBlank()) {
                name = excutionRoot.lastSegment();
            }

            // we don't want to use '_main' because it's useless and makes resolution challenging
            if ("_main".equals(name)) {
                // according to https://github.com/bazelbuild/bazel/issues/2317#issuecomment-1849830507 the parent directory name is not wrong
                name = bazelWorkspace.getLocation().lastSegment();
            }
        } catch (InterruptedException e) {
            throw new OperationCanceledException("cancelled");
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause == null) {
                throw new CoreException(
                        Status.error(
                            format("bazel info failed in workspace '%s' for with unknown reason", workspaceRoot),
                            e));
            }
            throw new CoreException(
                    Status.error(
                        format(
                            "bazel info failed in workspace '%s': %s%nPlease check the bazel output and binary setup/configuration!",
                            workspaceRoot,
                            cause.getMessage() != null ? cause.getMessage() : cause.toString()),
                        cause));
        }
    }

    private synchronized Map<String, ExternalWorkspace> loadExternalRepoMappings() throws CoreException {
        if (externalWorkspaceByRepoName != null) {
            return externalWorkspaceByRepoName;
        }

        var workspaceRoot = getWorkspaceFile().getParent();

        var repoMappingCommand =
                new BazelModDumpRepoMappingCommand(workspaceRoot, "", "Reading bzlmod repository mappings");
        List<ExternalWorkspace> externalWorkspaces =
                bazelWorkspace.getCommandExecutor().runQueryWithoutLock(repoMappingCommand);

        return externalWorkspaceByRepoName =
                externalWorkspaces.stream().collect(toMap(ExternalWorkspace::repoName, Function.identity())); // index by the "repo name" attribute
    }

    private synchronized Map<String, BazelRuleAttributes> loadExternalRepositoryRules() throws CoreException {
        if (externalRepositoryRuleByName != null) {
            return externalRepositoryRuleByName;
        }

        if (isBzlModEnabled()) {
            throw new IllegalStateException(
                    "External repository rules are not supported in bzlmod workspaces. Please use the bzlmod repository mappings instead.");
        }

        var workspaceRoot = getWorkspaceFile().getParent();
        var allExternalQuery = new BazelQueryForTargetProtoCommand(
                workspaceRoot,
                "//external:*",
                false,
                List.of("--noproto:rule_inputs_and_outputs", "--noproto:locations", "--noproto:default_values"),
                "Querying for external repositories");

        var externalTargets = bazelWorkspace.getCommandExecutor().runQueryWithoutLock(allExternalQuery);

        return externalRepositoryRuleByName = externalTargets.stream()
                .filter(Target::hasRule)
                .map(Target::rule)
                .map(BazelRuleAttributes::new)
                .collect(toMap(BazelRuleAttributes::getName, Function.identity())); // index by the "name" attribute
    }
}
