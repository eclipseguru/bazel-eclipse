/*-
 *
 */
package com.salesforce.bazel.eclipse.core.model;

import static com.salesforce.bazel.eclipse.core.BazelCoreSharedContstants.BAZEL_NATURE_ID;
import static java.util.Collections.unmodifiableList;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;

public final class BazelModelInfo extends BazelElementInfo {

    private final BazelModel bazelModel;

    private volatile List<BazelWorkspace> discoveredWorkspaces;

    public BazelModelInfo(BazelModel bazelModel) {
        this.bazelModel = bazelModel;
    }

    private List<BazelWorkspace> findWorkspaces() throws CoreException {
        List<BazelWorkspace> result = new ArrayList<>();
        var projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        for (IProject project : projects) {
            if (project.isOpen() && project.hasNature(BAZEL_NATURE_ID)) {
                var bazelProject = bazelModel.getModelManager().getBazelProject(project);
                if (bazelProject.isWorkspaceProject()) {
                    result.add(new BazelWorkspace(bazelProject.getLocation(), bazelModel));
                }
            }
        }
        return result;
    }

    @Override
    public BazelModel getOwner() {
        return bazelModel;
    }

    public List<BazelWorkspace> getWorkspaces() throws CoreException {
        var cachedWorkspaces = discoveredWorkspaces;
        if (cachedWorkspaces != null) {
            return cachedWorkspaces;
        }
        return discoveredWorkspaces = unmodifiableList(findWorkspaces());
    }

}
