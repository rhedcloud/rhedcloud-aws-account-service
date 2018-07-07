mvn package
echo copying
cp target/*.jar /Users/gwang28/apache/apache-tomcat-8.0.52/webapps/axis2/WEB-INF/lib
echo touching
touch /Users/gwang28/apache/apache-tomcat-8.0.52/webapps/axis2/WEB-INF/web.xml
