@echo off
cd /d "D:\GitHub\mobile-ai-bank-demo - workbuddy"
set OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318
set OTEL_SERVICE_NAME=mobile-bank-core
set OTEL_METRICS_EXPORTER=otlp
set OTEL_TRACES_EXPORTER=otlp
set OTEL_LOGS_EXPORTER=otlp
set JAVA_TOOL_OPTIONS=-Dreactor.netty.dns.nameservers=192.168.1.1 -Djava.net.preferIPv4Stack=true -javaagent:"D:\GitHub\mobile-ai-bank-demo - workbuddy\opentelemetry-javaagent.jar"
call mvnw.cmd spring-boot:run
