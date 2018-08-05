####build and start service locally using local tomcat, install and use dev envivironment including appConfig,sample message and EOs
####  uncomment ./gen-webservice awsaccount to generate ws
git pull
mvn package

echo  ************Preparing tomcat and axis2...***************
if [! test -d 'apache-tomcat-8.0.50']; then
    wget  https://archive.apache.org/dist/tomcat/tomcat-8/v8.0.50/bin/apache-tomcat-8.0.50.tar.gz
    tar xvzf apache-tomcat-8.0.50.tar.gz
fi


if [! test -d 'openeai-servicegen']; then
    git clone git@bitbucket.org:itarch/openeai-servicegen.git
fi
cp deploy/build-test/servicegen-configs/awsaccount.properties openeai-servicegen/properties
cd openeai-servicegen
./gen-webservice awsaccount
cd ..

mkdir -p deploy/esb-dev/libs/Axis2
cp openeai-servicegen/target/awsaccount/emory-awsaccount-webservice/build/lib/emory-awsaccount-webservice-1.0-wsdl-classes.jar deploy/build-test/libs/AwsAccountService/emory-awsaccount-webservice-1.0-wsdl-classes.jar
cp openeai-servicegen/target/awsaccount/emory-awsaccount-webservice/build/lib/emory-awsaccount-webservice-1.0-localhost.aar deploy/esb-dev/libs/Axis2/emory-awsaccount-webservice-1.0.aar

cd deploy/esb-dev/libs/Axis2
unzip -o ../../../../resources/axis2-1.5.2-war.zip axis2.war
mkdir -p WEB-INF/services
mkdir -p WEB-INF/lib
mkdir -p WEB-INF/classes
mkdir -p WEB-INF/modules
mkdir -p WEB-INF/conf
cp ../../../../lib/aws-moa.jar WEB-INF/lib
cp ../../../../lib/emory-moa-1.2.jar WEB-INF/lib
cp ../../../../lib/openeai.jar WEB-INF/lib
cp ../../../../target/*.jar WEB-INF/lib
cp ../../../build-test/libs/AwsAccountService/* WEB-INF/lib
cp ../../../build-test/libs/Axis2/*.jar WEB-INF/lib
cp ../../../build-test/configs/messaging/Environments/Examples/Jars/AwsAccountService/*.jar WEB-INF/lib
cp ../../../build-test/libs/Axis2/openeai-authorization-module.mar WEB-INF/modules
cp ../../../build-test/libs/Axis2/axis2.xml WEB-INF/conf
cp -r ../../../build-test/configs/messaging/Environments/Examples/Jars/AwsAccountService/hibernate/* WEB-INF/classes
cp ../../hibernate.cfg.xml WEB-INF/classes
cp emory-awsaccount-webservice-1.0.aar WEB-INF/services
jar uf axis2.war WEB-INF/services/emory-awsaccount-webservice-1.0.aar
jar uf axis2.war WEB-INF/lib/*
jar uf axis2.war WEB-INF/classes
jar uf axis2.war WEB-INF/modules
jar uf axis2.war WEB-INF/conf
zip -d axis2.war WEB-INF/lib/httpcore-4.0.jar  # conflicts with httpcore-4.4.4.jar from lib folder
rm -Rf WEB-INF

cp axis2.war ../../../../apache-tomcat-8.0.50/webapps
cd ../../../../apache-tomcat-8.0.50/bin
rm logs/*
rm ../logs/*
export TOMCAT_HOME=..
export CATALINA_HOME=..
./startup.sh


#cp ../axis2/1.5.2 $TOMCAT_HOME/webapps/axis2

