####build and start service locally 
####  uncomment ./gen-webservice awsaccount to generate ws
git pull
mvn clean package

mkdir -p target/deploy/lib
cp deploy/esb-dev/tests/AwsAccountService.xml target/deploy
cp deploy/build-test/tests/service.properties target/deploy
cp -R deploy/build-test/configs target/deploy

cp  deploy/build-test/libs/AwsAccountService/* target/deploy/lib
cp lib/*.jar target/deploy/lib
cp -R deploy/build-test/message target/deploy
cp deploy/build-test/tests/AwsAccountService-hibernate-dev.cfg.xml target/deploy/configs/messaging/Environments/Examples/Jars/AwsAccountService/hibernate/hibernate.cfg.xml
cp target/*.jar target/deploy/lib

echo java
cd target/deploy
pwd
java -Xms1000m -Xmx4000m -cp "lib/:lib/*:configs/messaging/Environments/Examples/Jars/AwsAccountService/*:configs/messaging/Environments/Examples/Jars/AwsAccountService/hibernate" -DdocUriBase=/Users/gwang28/project/emoryoit/runtime/dev/ org.openeai.afa.GenericAppRunner service.properties 
cd ../..
