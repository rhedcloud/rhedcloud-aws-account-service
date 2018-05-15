#!/bin/sh

echo
echo "OpenEAI Example Enterprise Execution System"
echo "-------------------------------------------"
echo

if [ "$OPENEAI_JAVA_HOME" = "" ] ; then
  echo "ERROR: OPENEAI_JAVA_HOME not found in your environment."
  echo
  echo "Please, set the OPENEAI_JAVA_HOME variable in your environment to match the"
  echo "location of the Java Virtual Machine you want to use.\n"
  exit 1
fi

OPENEAI_HOME=.

if [ "$OPENEAI_HOME" = "" ] ; then
  echo "ERROR: OPENEAI_HOME not found in your environment."
  echo
  echo "Please, set the OPENEAI_HOME variable in your environment to match the"
  echo "location of the OpenEAI Examples distribution you've un-packed.\n"
  exit 1
fi

if [ "$1" = "" ] ; then
  echo "ERROR: Invalid number of arguments."
  echo
  echo "Please provide a 'target' that you wish to start."
  echo "Targets are specified in the build.xml file."
  exit 1
fi

if [ `echo $OSTYPE | grep -n cygwin` ]; then
  PS=";"
else
  PS=":"
fi

OPENEAI_RUNTIME=$OPENEAI_HOME export OPENEAI_RUNTIME
OPENEAI_LIB=$OPENEAI_HOME/libs/ElasticIpService
BUILD_FILE=$OPENEAI_HOME/build.xml export BUILD_FILE
LOCALCLASSPATH=$JAVA_HOME/lib/tools.jar${PS}$OPENEAI_LIB/ant.jar${PS}$OPENEAI_LIB/ant-nodeps.jar${PS}$OPENEAI_LIB/ant-launcher.jar

ANT_HOME=$OPENEAI_LIB

echo Starting $1
echo
echo OPENEAI_RUNTIME=$OPENEAI_RUNTIME
echo OPENEAI_LIB=$OPENEAI_LIB
echo BUILD_FILE=$BUILD_FILE
echo LOCALCLASSPATH=$LOCALCLASSPATH

echo Starting Ant...
echo

# One person found a seg fault with jdk 1.3.0 on Linux where adding -classic
# to the following line fixed the issue

$OPENEAI_JAVA_HOME/bin/java -Dant.home=$ANT_HOME -classpath $LOCALCLASSPATH org.apache.tools.ant.Main -buildfile $BUILD_FILE $*&
