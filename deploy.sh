echo  ************Preparing tomcat and axis2...***************
wget  https://archive.apache.org/dist/tomcat/tomcat-8/v8.0.50/bin/apache-tomcat-8.0.50.tar.gz
tar xvzf apache-tomcat-8.0.50.tar.gz
set TOMCAT_HOME=apache-tomcat-8.0.50

jar tf deploy/build-test/libs/Axis2/axis2.war
cp deploy/build-test/libs/Axis2/axis2.war deploy/build-test/apache-tomcat-8.0.50/webapps
cd deploy/build-test/apache-tomcat-8.0.50/bin
./startup.sh

cd $BUILD_HOME/deploy/esb-dev/libs/Axis2
unzip -o $BUILD_HOME/resources/axis2-1.5.2-war.zip axis2.war
jar uf axis2.war $BUILD_HOME/.ebextensions
mkdir -p WEB-INF/services
mkdir -p WEB-INF/lib
mkdir -p WEB-INF/classes
cp emory-awsaccount-webservice-1.0.aar WEB-INF/services
cp ../../../build-test/libs/AwsAccountService/* WEB-INF/lib
cp ../../../build-test/configs/messaging/Environments/Examples/Jars/AwsAccountService/*.jar WEB-INF/lib
cp -r ../../../build-test/configs/messaging/Environments/Examples/Jars/AwsAccountService/hibernate/* WEB-INF/classes
cp ../../hibernate.cfg.xml WEB-INF/classes
jar uf axis2.war WEB-INF/services/emory-awsaccount-webservice-1.0.aar
jar uf axis2.war WEB-INF/lib/*
jar uf axis2.war WEB-INF/classes
zip -d axis2.war WEB-INF/lib/httpcore-4.0.jar  # conflicts with httpcore-4.4.4.jar from lib folder
rm -Rf WEB-INF
jar tf axis2.war


#cp ../axis2/1.5.2 $TOMCAT_HOME/webapps/axis2
git clone git@bitbucket.org:itarch/openeai-servicegen.git
cp deploy/build-test/servicegen-configs/awsaccount.properties openeai-servicegen/properties
cd openeai-servicegen
./gen-webservice awsaccount

cd ..

mvn package
echo copying
cp target/*.jar $TOMCAT_HOME/webapps/axis2/WEB-INF/lib
echo touching
touch /Users/gwang28/apache/apache-tomcat-8.0.52/webapps/axis2/WEB-INF/web.xml
