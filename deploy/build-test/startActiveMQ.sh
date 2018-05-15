#!/bin/sh

echo
echo "Start ActiveMQ for OpenEAI Examples"
echo "----------------------------------"
echo

if [ "$JAVA_HOME" = "" ] ; then
  echo "ERROR: JAVA_HOME not found in your environment."
  echo
  echo "Please, set the JAVA_HOME variable in your environment to match the"
  echo "location of the Java Virtual Machine you want to use."
  exit 1
fi

if [ "$OPENEAI_HOME" = "" ] ; then
  echo "ERROR: OPENEAI_HOME not found in your environment."
  echo
  echo "Please, set the OPENEAI_HOME variable in your environment to match the"
  echo "location of the OpenEAI Examples distribution you've un-packed."
  exit 1
fi


ACTIVEMQ_HOME=$OPENEAI_HOME/apache-activemq-5.7.0
export ACTIVEMQ_HOME
#DEPLOYMENT_DESCRIPTOR=$OPENEAI_HOME/configs/messaging/Environments/Examples/Brokers/ActiveMQ/Broker1/OpenJMS-Config.xml

echo $ACTIVEMQ_HOME
echo "Starting ActiveMQ..."

$ACTIVEMQ_HOME/bin/activemq console &
#$ACTIVEMQ_HOME/bin/activemq console 
