####build and start service locally 
####  uncomment ./gen-webservice awsaccount to generate ws

echo java
cd target/deploy
pwd
java -Xms1000m -Xmx4000m -cp "lib/:lib/*:configs/messaging/Environments/Examples/Jars/AwsAccountService/*:configs/messaging/Environments/Examples/Jars/AwsAccountService/hibernate" -DdocUriBase=/Users/gwang28/project/emoryoit/runtime/dev/ org.openeai.afa.GenericAppRunner service.properties 
cd ../..
