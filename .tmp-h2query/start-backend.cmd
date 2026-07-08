@echo off
cd /d "D:\GitHub\mobile-ai-bank-demo - workbuddy\observability\backend"
set JAVA_TOOL_OPTIONS=-Xmx1024m
java -jar "target\observability-backend-0.1.0-SNAPSHOT.jar" --server.port=9090 > "D:\GitHub\mobile-ai-bank-demo - workbuddy\.tmp-h2query\backend-startup.log" 2>&1
