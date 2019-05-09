####build and start service locally 
####  uncomment ./gen-webservice awsaccount to generate ws
git pull
mvn package

mkdir -p target/deploy/lib
cp deploy/build-test/tests/AwsAccountService.xml target/deploy
cp deploy/build-test/tests/service.properties target/deploy
cp -R deploy/build-test/configs target/deploy
cp deploy/esb-dev/hibernate.cfg.xml target/deploy/configs/messaging/Environments/Examples/Jars/AwsAccountService/hibernate
cp  deploy/build-test/libs/AwsAccountService/*.jar target/deploy/lib
cp lib/*.jar target/deploy/lib
cp -R deploy/build-test/message target/deploy
cp target/*.jar target/deploy/lib

echo java
cd target/deploy
pwd
java -cp "lib/*:configs/messaging/Environments/Examples/Jars/AwsAccountService/*:configs/messaging/Environments/Examples/Jars/AwsAccountService/hibernate" -DdocUriBase=https://dev-config.app.emory.edu/ org.openeai.afa.GenericAppRunner service.properties 
cd ../..
