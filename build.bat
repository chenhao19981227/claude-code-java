@echo off
set "JAVA_HOME=D:\CodingApp\JDK21\jdk-21.0.10+7"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d D:\openCodeProject\claude-code-java
mvn package -DskipTests 2>&1
