cd 'D:\GitHub\mobile-ai-bank-demo - workbuddy\observability\backend'
java -Xms512m -Xmx1024m -XX:MaxMetaspaceSize=256m -jar "target/observability-backend-0.1.0-SNAPSHOT.jar" --server.port=9090 > "D:\GitHub\mobile-ai-bank-demo - workbuddy\.tmp-h2query\be-full.log" 2>&1
