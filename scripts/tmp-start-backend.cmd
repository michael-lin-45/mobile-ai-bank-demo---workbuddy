@echo off
cd /d "D:\GitHub\mobile-ai-bank-demo - workbuddy"
java -jar "D:\GitHub\mobile-ai-bank-demo - workbuddy\observability\backend\target\observability-backend-0.1.0-SNAPSHOT.jar" --server.port=9090 > "D:\GitHub\mobile-ai-bank-demo - workbuddy\scripts\logs\backend-startup.log" 2>&1
