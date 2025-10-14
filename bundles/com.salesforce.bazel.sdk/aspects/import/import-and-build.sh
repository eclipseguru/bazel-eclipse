#!/usr/bin/env bash
set -e

# use proper tar
if [ "$(uname)" == "Darwin" ]; then
    tar="gtar"
else
    tar="tar"
fi
echo "Using '$tar' on $(uname)!"

if ! command -v $tar &> /dev/null; then
    echo "$tar could not be found"
    echo "On macOS: brew install gnu-tar"
    echo "Also, check PATH environment: $PATH"
    exit 1
fi

# download & extract
#
#   Note, wehn updating:
#     1. replace the hash with the one you want to update to
#     2. check it builds (dependencies exist)
#     3. update com.salesforce.bazel.sdk.aspects.intellij.IntellijAspects.ASPECTS_VERSION
#
git_sha="3f0edce7702ced14ed26c026b79ae1cd26467749"
git_sha_short=${git_sha::6}

# abort if file already exists
if test -f "../aspects-${git_sha_short}.zip"; then
    echo "aspects-${git_sha_short}.zip already there; delete it force re-import"
    exit 0
fi

# clean-up old left overs
rm -rf bazel-*
rm -rf intellij*
rm -f aspect_*.jar

# ensure directory exists
mkdir -p intellij

# download repo
curl -L https://github.com/bazelbuild/intellij/archive/${git_sha}.tar.gz | ${tar} --strip-components 1 -C intellij -xz

# build the aspects
pushd intellij > /dev/null
bazel build //aspect:all
popd > /dev/null

# generate tarball
cp intellij/bazel-bin/aspect/aspect_lib.jar .
cp intellij/bazel-bin/aspect/aspect_template_lib.jar .
bazel build :aspects

# copy to location
cp -vf bazel-bin/aspects.zip ../aspects-${git_sha_short}.zip

