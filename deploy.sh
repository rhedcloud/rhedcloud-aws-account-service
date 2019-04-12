mvn package
echo copying
export TOMCAT_HOME=apache-tomcat-8.0.50
cp target/*.jar $TOMCAT_HOME/webapps/axis2/WEB-INF/lib
echo touching
touch $TOMCAT_HOME/webapps/axis2/WEB-INF/web.xml
