@echo off
cd /d "D:\GitHub\mobile-ai-bank-demo - workbuddy\observability\otel-collector"
"D:\GitHub\mobile-ai-bank-demo - workbuddy\observability\otel-collector\otelcol-contrib.exe" --config "D:\GitHub\mobile-ai-bank-demo - workbuddy\observability\otel-collector\config.yaml" > "D:\GitHub\mobile-ai-bank-demo - workbuddy\observability\otel-collector\collector.log" 2>&1
