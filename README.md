# Bazel Eclipse Feature (BEF) and Bazel Java Language Server (BJLS)

This Git repository contains two [Bazel](http://bazel.io) IDE projects.
They share a large amount of code and therefore live together here.
Both provide IDE integrations for Java projects built by the [Bazel](https://bazel.build) build system.

## Project Health

Originally started at Salesforce, the code was forked into this repo [eclipseguru/bazel-eclipse](https://github.com/eclipseguru/bazel-eclipse/).
I will continue to maintain and update it at best effort.
My main interest is the JDTLS extension.
As much as I still like and prefer Eclipse over others, my main use shifted to VS Code/Cursor developing remotely on a workspace.
I seldom develop on a local machine these days.

Contributions and help is still very welcome!

FWIW, I continue to debate with myself whether JDT is the right technology here.
The main problem is the duplication of work between JDT and Bazel.
If JDT would not require to generate class files and work mostly from source and an index/database it would be idea.
Bonus poits if such an index/database could be built by a Bazel aspect and shared via Bazel's remote cache capability.

## BEF: Bazel Eclipse Feature ![BEF Logo](docs/logos/bef_logo_small.png)

This is the Eclipse Feature for developing [Bazel](http://bazel.io) projects in Eclipse.
The Bazel Eclipse Feature supports importing, building, and testing Java projects that are built using the Bazel build system.

Full documentation is available here:
- [Bazel Eclipse Feature docs](docs/bef/README.md)

## BJLS: Bazel Java Language Server ![BEF Logo](docs/logos/bjls_logo_small.jpeg)

This is a [Language Server](https://microsoft.github.io/language-server-protocol/) implementation for Java projects built by Bazel.
This enables IDEs such as [Visual Studio Code](https://code.visualstudio.com/) to be used to develop the project.

Full documentation is available here:
- [Bazel Java Language Server docs](docs/bjls/README.md)


## Community, Support and Contributions

We use GitHub Discussions!
Please file an Issue if you have an issue or would like to request a new feature.

Development/contribution discussion happen in [Bazel's ide-dev Slack channel](https://bazelbuild.slack.com/archives/CM8JQCANN).

We welcome any contributions.
Please review our [code of conduct](CODE_OF_CONDUCT.md) and our [contribution guide](CONTRIBUTING.md).


## Developing BEF or BJLS

Please look at our [contribution guide](CONTRIBUTING.md).

