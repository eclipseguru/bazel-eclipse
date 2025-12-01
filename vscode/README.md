# Bazel extension for Java™️ Language Support for VS Code

[![Build](https://github.com/eclipseguru/bazel-eclipse/actions/workflows/ci.yml/badge.svg)](https://github.com/eclipseguru/bazel-eclipse/actions/workflows/ci.yml)
[![License](https://img.shields.io/github/license/salesforce/bazel-vscode-java?style=for-the-badge)](https://github.com/eclipseguru/bazel-eclipse/blob/main/vscode/LICENSE)

This extension adds support for Bazel to the Java™️ Language Support for VS Code.
It plugs into the Eclipse Java Language server and computes project dependencies and classpath information using Bazel `BUILD.bazel` files.

It's a lightweight integration without much additional Bazel support.
Hence, we recommend installing additional Bazel extensions for Starlark support (syntax highlighting, etc.)

## Getting Started

Go and [install the extension](vscode:extension/guw.bazel-eclipse-vscode) from the VSCode Marketplace (see [listing here](https://marketplace.visualstudio.com/items?itemName=guw.bazel-eclipse-vscode)) or OpenVSX Registry (see [listing here](https://open-vsx.org/extension/guw/bazel-eclipse-vscode)).

Once installed, open VSCode in any Bazel Workspace with Java targets.
The extension will look for a `WORKSPACE.bazel` or `MODULE.bazel` file to identify a Bazel workspace.
Next it will look for [a `.bazelproject` file](https://github.com/salesforce/bazel-eclipse/blob/main/docs/common/projectviews.md) to look for directories and targets to resolve.
If no `.bazelproject` file can be found a default one will be created.
For details of the lookup sequence please have a look at the latest implementation of [BazelProjectImporter.java in the language server](https://github.com/eclipseguru/bazel-eclipse/blob/main/bundles/com.salesforce.bazel.eclipse.jdtls/src/main/java/com/salesforce/bazel/eclipse/jdtls/managers/BazelProjectImporter.java).

[Troubleshoot tips](docs/troubleshoot.md) may be useful if it doesn't "just work".
