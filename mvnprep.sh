#!/bin/bash

# This script automatically generates patch versions from git commits at a
# release branch. Since the project.version can't be changed inside a Maven
# build, this has to be done externally.
#
# Just run this before you use Maven to build.
# Adapted from the original script by the fleximon project.
# (github:/corlab/fleximon-rsb-matlab)

GITVERSION=`git describe --tags --long --match release-*.* 2>/dev/null`
if [ $? -eq 0 ]; then
  echo $GITVERSION > gitversion
fi

GITBRANCH=`git rev-parse --abbrev-ref HEAD 2>/dev/null`
if [ $? -eq 0 ]; then
  echo $GITBRANCH > gitbranch
else
  GITBRANCH=`cat gitbranch`
fi

GITPATCH=`perl -p -e 's/-g[0-9a-fA-F]+$//;s/^.*-//' gitversion`

RELBRANCH=`perl -p -e 's/^[0-9]+\.[0-9]+$//' gitbranch`

if [ -z "$GITPATCH" ] || [ -z "$GITBRANCH" ]; then
  echo "Could not get version/branch information. Is this either a git checkout or an official source archive?" 1>&2
  exit 1
fi

if [ -z "$RELBRANCH" ]; then
  FULLVERSION=$GITBRANCH.$GITPATCH
  echo "We are on a release branch, version is $FULLVERSION"
  mvn versions:set -DnewVersion=$FULLVERSION
  echo "Changed the version in the pom.xml, you are now ready to build!"
else
  echo "We are not on a release branch, not changing the version. You can build now!"
fi
