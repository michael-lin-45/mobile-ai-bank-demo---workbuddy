Set-Location "D:\GitHub\mobile-ai-bank-demo - workbuddy\observability\backend"
$env:JAVA_TOOL_OPTIONS = "-Xmx1024m"
java -jar "target\observability-backend-0.1.0-SNAPSHOT.jar" --server.port=9090 2>&1 | Out-File -FilePath "D:\GitHub\mobile-ai-bank-demo - workbuddy\.tmp-h2query\be-ps.log" -Encoding UTF8
