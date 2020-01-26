unzip -o $BUILD_HOME/resources/axis2-1.5.2-war.zip axis2.war
jar uf axis2.war .ebextensions
mkdir -p WEB-INF/services
mkdir -p WEB-INF/lib
mkdir -p WEB-INF/modules
mkdir -p WEB-INF/conf
cp rhedcloud-awsaccount-webservice-1.0.aar WEB-INF/services
cp ../../../build-test/libs/AwsAccountService/*.jar WEB-INF/lib
cp ../../../build-test/libs/AwsAccountService/jndi.properties WEB-INF/classes
cp -r ../../../build-test/configs/messaging/Environments/Examples/Jars/AwsAccountService/hibernate/* WEB-INF/classes      
cp ../../import.sql WEB-INF/classes
cp ../../../build-test/libs/Axis2/openeai-authorization-module.mar WEB-INF/modules
cp ../../../build-test/libs/Axis2/axis2.xml WEB-INF/conf
cp ../../../build-test/libs/Axis2/*.jar WEB-INF/lib
jar uf axis2.war WEB-INF/services/rhedcloud-awsaccount-webservice-1.0.aar
jar uf axis2.war WEB-INF/lib/*
jar uf axis2.war WEB-INF/classes
jar uf axis2.war WEB-INF/modules
jar uf axis2.war WEB-INF/conf
zip -d axis2.war WEB-INF/lib/httpcore-4.0.jar  # conflicts with httpcore-4.4.4.jar from lib folder
zip -d axis2.war WEB-INF/lib/commons-io-1.4.jar  # conflicts with commons-io-2.5.jar from lib folder
jar tf axis2.war
ls axis2.war

