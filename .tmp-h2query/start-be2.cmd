@echo off
set "JAVA_TOOL_OPTIONS="
set "PATH=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin;C:\Windows\System32;C:\Windows"
cd /d "D:\GitHub\mobile-ai-bank-demo - workbuddy\observability\backend"
java -Xmx1024m -jar "target\observability-backend-0.1.0-SNAPSHOT.jar" --server.port=9090 > "D:\GitHub\mobile-ai-bank-demo - workbuddy\.tmp-h2query\be-out.log" 2>&1
