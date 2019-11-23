####build and start service locally 
####  uncomment ./gen-webservice awsaccount to generate ws
git pull
mvn package

mkdir -p target/deploy/lib
cp deploy/esb-dev/tests/AwsAccountService.xml target/deploy
cp deploy/build-test/tests/service.properties target/deploy
cp -R deploy/build-test/configs target/deploy
cp deploy/build-test/libs/Axis2/WEB-INF/classes/hibernate.cfg.xml target/deploy/configs/messaging/Environments/Examples/Jars/AwsAccountService/hibernate


sed -i 's/CONNECTION_URL/'"$RHEDCLOUD_DB_CONNECTION_URL"'/g' target/deploy/configs/messaging/Environments/Examples/Jars/AwsAccountService/hibernate/hibernate.cfg.xml
sed -i 's/CONNECTION_USERNAME/'"$RHEDCLOUD_DB_CONNECTION_USERNAME"'/g'  target/deploy/configs/messaging/Environments/Examples/Jars/AwsAccountService/hibernate/hibernate.cfg.xml
sed -i 's/CONNECTION_PASSWORD/'"$RHEDCLOUD_DB_CONNECTION_PASSWORD"'/g'  target/deploy/configs/messaging/Environments/Examples/Jars/AwsAccountService/hibernate/hibernate.cfg.xml
sed -i 's/HIBERNATE_HBM2DDL_AUTO/validate/g'  target/deploy/configs/messaging/Environments/Examples/Jars/AwsAccountService/hibernate/hibernate.cfg.xml

cp  deploy/build-test/libs/AwsAccountService/* target/deploy/lib
cp lib/*.jar target/deploy/lib
cp -R deploy/build-test/message target/deploy
cp target/*.jar target/deploy/lib

echo java
cd target/deploy
pwd
java -Xms1000m -Xmx4000m -cp "lib/:lib/*:configs/messaging/Environments/Examples/Jars/AwsAccountService/*:configs/messaging/Environments/Examples/Jars/AwsAccountService/hibernate" -DdocUriBase=/Users/gwang28/project/emoryoit/runtime/dev/ org.openeai.afa.GenericAppRunner service.properties 
cd ../..
