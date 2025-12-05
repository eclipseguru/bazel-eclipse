/*-
 *
 */
package com.salesforce.bazel.eclipse.core.classpath;

import static java.lang.String.format;
import static java.nio.file.Files.isRegularFile;
import static java.util.Arrays.stream;
import static org.eclipse.jdt.launching.JavaRuntime.computeUnresolvedRuntimeClasspath;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.internal.launching.RuntimeClasspathEntry;
import org.eclipse.jdt.launching.IRuntimeClasspathEntry;
import org.eclipse.jdt.launching.IRuntimeClasspathEntryResolver;
import org.eclipse.jdt.launching.IRuntimeClasspathEntryResolver2;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.salesforce.bazel.eclipse.core.BazelCore;
import com.salesforce.bazel.eclipse.core.BazelCorePlugin;
import com.salesforce.bazel.eclipse.core.model.BazelProject;
import com.salesforce.bazel.eclipse.core.util.trace.StopWatch;

@SuppressWarnings("restriction")
public class BazelClasspathContainerRuntimeResolver
        implements IRuntimeClasspathEntryResolver, IRuntimeClasspathEntryResolver2 {

    /**
     * Thre resolution context is an optimization. It captures state during the resolution of a container with the goal
     * to improve efficiency and performance.
     */
    private static final class ContainerResolutionContext {
        /**
         * the resolved classpath entries (in insertion order, no duplicates)
         */
        private final LinkedHashSet<IRuntimeClasspathEntry> resolvedClasspath = new LinkedHashSet<>();
        /**
         * the current resolution stack deepness
         */
        private int currentDepth = 0;
        /**
         * the set of already processed projects to avoid duplicate work
         */
        private final Set<IProject> processedProjects = new HashSet<>();

        public void add(IRuntimeClasspathEntry runtimeClasspathEntry) {
            resolvedClasspath.add(runtimeClasspathEntry);
        }

        /**
         * @param project
         *            the project being resolved
         * @return <code>true</code> if the project was never processed before, <code>false</code> otherwise
         */
        public boolean beginResolvingProject(IProject project) {
            currentDepth++;
            return processedProjects.add(project);
        }

        public void endResolvingProject(IProject project) {
            if (currentDepth == 0) {
                throw new IllegalStateException("Mismatched begin/end resolving project calls");
            }

            currentDepth--;
        }

        public IRuntimeClasspathEntry[] getResolvedClasspath() {
            return resolvedClasspath.toArray(new IRuntimeClasspathEntry[resolvedClasspath.size()]);
        }

        public boolean isDoneProcessingProjects() {
            return currentDepth == 0;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(BazelClasspathContainerRuntimeResolver.class);

    /**
     * The current thread's resolution context. Used to avoid cycles in project dependencies when resolving classpath
     * container entries.
     *
     * @see org.eclipse.jdt.launching.JavaRuntime.fgProjects (for similar implementation)
     */
    private static final ThreadLocal<ContainerResolutionContext> currentThreadResolutionContet =
            ThreadLocal.withInitial(ContainerResolutionContext::new);

    private static String extractRealJarName(String jarName) {
        // copied (and adapted) from BlazeJavaWorkspaceImporter
        if (jarName.endsWith("-hjar.jar")) {
            return jarName.substring(0, jarName.length() - "-hjar.jar".length()) + ".jar";
        }
        if (jarName.endsWith("-ijar.jar")) {
            return jarName.substring(0, jarName.length() - "-ijar.jar".length()) + ".jar";
        }
        return jarName;
    }

    ISchedulingRule getBuildRule() {
        return ResourcesPlugin.getWorkspace().getRuleFactory().buildRule();
    }

    BazelClasspathManager getClasspathManager() {
        return BazelCorePlugin.getInstance().getBazelModelManager().getClasspathManager();
    }

    @Override
    public boolean isVMInstallReference(IClasspathEntry entry) {
        return false;
    }

    private void populateWithRealJar(ContainerResolutionContext resolutionContext, IClasspathEntry e) {
        var jarPath = e.getPath();
        var jarName = extractRealJarName(jarPath.lastSegment());
        if (!jarName.equals(jarPath.lastSegment())) {
            var realJarPath = jarPath.removeLastSegments(1).append(jarName);
            if ("Runner_deploy-ijar.jar".equals(jarPath.lastSegment())) {
                LOG.error(
                    "Found Bazel's test runner dependencies on classpath. This is not recommended. Consider setting '--explicit_java_test_deps' in .bazelrc. ({} -> {})",
                    jarPath.lastSegment(),
                    realJarPath);
            } else {
                LOG.error(
                    "Found ijar on classpath. This is no longer expected. Consider running a sync to update the Bazel classpath. ({} -> {})",
                    jarPath.lastSegment(),
                    realJarPath);
            }

            // ensure it exists
            if (!isRegularFile(realJarPath.toPath())) {
                LOG.warn(
                    "Dropped ijar from runtime classpath: {} (real jar '{}' does not exist)",
                    jarPath,
                    realJarPath.lastSegment());
                return;
            }

            // replace entry with new jar
            LOG.debug("Replacing ijar '{}' on classpath with real jar '{}", jarPath.lastSegment(), realJarPath);
            e = JavaCore.newLibraryEntry(realJarPath, e.getSourceAttachmentPath(), e.getSourceAttachmentRootPath());
        }

        resolutionContext.add(new RuntimeClasspathEntry(e));
    }

    /**
     * Resolves a project classpath reference into all possible output folders and transitives and adds it to the
     * resolved classpath.
     *
     * @param projectToResolve
     *            the project reference
     * @param resolutionContext
     *            the resolution context
     * @throws CoreException
     *             in case of problems
     */
    private void populateWithResolvedProject(IProject projectToResolve, ContainerResolutionContext resolutionContext)
            throws CoreException {
        var javaProject = JavaCore.create(projectToResolve);

        // performance: use a saved container for Bazel projects if possible
        // (discovered in https://github.com/eclipseguru/bazel-eclipse/issues/37)
        if (populateWithSavedContainer(javaProject, resolutionContext)) {
            return; // we are done
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug(
                "No saved container found. Resolving project reference '{}' (thread '{}')",
                projectToResolve.getName(),
                Thread.currentThread().getName());
        }

        // never exclude test code because we use it for runtime dependencies as well
        final var excludeTestCode = false;

        // get the full transitive closure of the project
        var unresolvedRuntimeClasspath = computeUnresolvedRuntimeClasspath(javaProject, excludeTestCode);
        for (IRuntimeClasspathEntry unresolvedEntry : unresolvedRuntimeClasspath) {
            // resolve and add
            stream(JavaRuntime.resolveRuntimeClasspathEntry(unresolvedEntry, javaProject, excludeTestCode))
                    .forEach(resolutionContext::add);
        }
    }

    /**
     * Populates the resolved classpath with entries from the saved Bazel classpath container and ensures they are all
     * fully resolved.
     *
     * @param project
     *            the project whose saved container should be used
     * @param resolutionContext
     *            the resolution context
     * @return <code>true</code> if a saved container was found and used, <code>false</code> otherwise
     * @throws CoreException
     */
    private boolean populateWithSavedContainer(IJavaProject project, ContainerResolutionContext resolutionContext)
            throws CoreException {
        var bazelContainer = getClasspathManager().getSavedContainer(project.getProject());
        if (bazelContainer == null) {
            // nothing available
            LOG.debug("No saved Bazel classpath container found for project '{}'", project.getProject().getName());
            return false;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug(
                "Populating classpath with saved container of project '{}' (thread '{}')",
                project.getProject().getName(),
                Thread.currentThread().getName());
        }

        var workspaceRoot = project.getResource().getWorkspace().getRoot();
        var entries = bazelContainer.getFullClasspath();
        for (IClasspathEntry e : entries) {
            switch (e.getEntryKind()) {
                case IClasspathEntry.CPE_PROJECT: {
                    // projects need to be resolved properly so we have all the output folders and exported jars on the classpath
                    var sourceProject = workspaceRoot.getProject(e.getPath().segment(0));
                    if (resolutionContext.beginResolvingProject(sourceProject)) {
                        try {
                            // only resolve and add the projects if it was never attempted before
                            populateWithResolvedProject(sourceProject, resolutionContext);
                        } finally {
                            // remove from stack again when done resolving
                            resolutionContext.endResolvingProject(sourceProject);
                        }
                    } else if (LOG.isDebugEnabled()) {
                        // this should not happen in theory because Bazel is an acyclic graph as well as Eclipse doesn't like it but who knows...
                        LOG.debug(
                            "Skipping recursive resolution attempt for project '{}' in thread '{}' ({})",
                            sourceProject,
                            Thread.currentThread().getName());
                    }
                    break;
                }
                case IClasspathEntry.CPE_LIBRARY: {
                    // we can rely on the assumption that this is an absolute path pointing into Bazel's execroot
                    // but we have to exclude ijars from runtime
                    populateWithRealJar(resolutionContext, e);
                    break;
                }
                default:
                    throw new CoreException(
                            Status.error(
                                format(
                                    "Unexpected classpath entry in the persisted Bazel container. Try refreshing the classpath or report as bug. %s",
                                    e)));
            }
        }

        return true;
    }

    @Override
    public IRuntimeClasspathEntry[] resolveRuntimeClasspathEntry(IRuntimeClasspathEntry entry, IJavaProject project)
            throws CoreException {
        if ((entry == null) || (entry.getJavaProject() == null)) {
            return new IRuntimeClasspathEntry[0];
        }

        if ((entry.getType() != IRuntimeClasspathEntry.CONTAINER)
                || !BazelClasspathHelpers.isBazelClasspathContainer(entry.getPath())) {
            return new IRuntimeClasspathEntry[0];
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug(
                "Classpath entry resolution request in thread '{}' for Bazel container in project '{}'",
                Thread.currentThread().getName(),
                project.getProject().getName());
        }

        // this method can be entered recursively; luckily only within the same thread
        // therefore we use a ThreadLocal LinkedHashSet to keep track of recursive attempts
        var resolutionContext = currentThreadResolutionContet.get();
        if (!resolutionContext.beginResolvingProject(project.getProject())) {
            LOG.warn(
                "Detected recursive resolution attempt for project '{}' in thread '{}' ({})",
                project.getProject().getName(),
                Thread.currentThread().getName());
            return new IRuntimeClasspathEntry[0];
        }

        var isTopLevelResolution = resolutionContext.currentDepth == 0;
        var stopWatch = StopWatch.startNewStopWatch();
        try {
            // try the saved container
            // this is usually ok because we no longer use the ijars on project classpaths
            // the saved container also contains all runtime dependencies by default
            populateWithSavedContainer(project, resolutionContext);

            var bazelProject = BazelCore.create(project.getProject());
            if (bazelProject.isWorkspaceProject()) {
                // when debugging/launching in the workspace project we include all target/package projects automatically
                // this is odd because the projects should cause cyclic dependencies
                // however it is convenient with source code lookups for missing dependencies
                var bazelProjects = bazelProject.getBazelWorkspace().getBazelProjects();
                for (BazelProject sourceProject : bazelProjects) {
                    if (!sourceProject.isWorkspaceProject()) {
                        populateWithResolvedProject(sourceProject.getProject(), resolutionContext);
                    }
                }
            }

            return resolutionContext.getResolvedClasspath();
        } finally {
            resolutionContext.endResolvingProject(project.getProject());

            if (isTopLevelResolution) {
                // clean up thread local when we are done with the top-level resolution
                currentThreadResolutionContet.remove();

                if (!resolutionContext.isDoneProcessingProjects()) {
                    LOG.error(
                        "Resolution context for thread '{}' is not fully ended after top-level resolution of project '{}' was done. There are missing start/end calls. Please report as bug.",
                        Thread.currentThread().getName(),
                        project.getProject().getName());
                }
            }

            stopWatch.stop();

            if (LOG.isDebugEnabled()) {
                LOG.debug(
                    "Resolved Bazel classpath container for project '{}' in {}ms",
                    project.getProject().getName(),
                    stopWatch.getDuration(TimeUnit.MILLISECONDS));
            }
        }
    }

    @Override
    public IRuntimeClasspathEntry[] resolveRuntimeClasspathEntry(IRuntimeClasspathEntry entry,
            ILaunchConfiguration configuration) throws CoreException {
        if ((entry == null) || (entry.getJavaProject() == null)) {
            return new IRuntimeClasspathEntry[0];
        }

        return resolveRuntimeClasspathEntry(entry, entry.getJavaProject());
    }

    @Override
    public IVMInstall resolveVMInstall(IClasspathEntry entry) throws CoreException {
        return null;
    }

}
