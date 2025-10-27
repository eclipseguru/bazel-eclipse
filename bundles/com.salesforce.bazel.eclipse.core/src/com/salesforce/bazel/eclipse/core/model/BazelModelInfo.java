/*-
 *
 */
package com.salesforce.bazel.eclipse.core.model;

import static com.salesforce.bazel.eclipse.core.BazelCoreSharedContstants.BAZEL_NATURE_ID;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;

public final class BazelModelInfo extends BazelElementInfo {

    private final BazelModel bazelModel;

    private List<BazelWorkspace> bazelWorkspaces;

    public BazelModelInfo(BazelModel bazelModel) {
        this.bazelModel = bazelModel;
    }

    public List<BazelWorkspace> findWorkspaces() throws CoreException {
        if (bazelWorkspaces == null) {
            bazelWorkspaces = getWorkspaces();
        }
        return bazelWorkspaces;
    }

    @Override
    public BazelModel getOwner() {
        return bazelModel;
    }

    private List<BazelWorkspace> getWorkspaces() throws CoreException {
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

}
