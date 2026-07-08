# 可观测系统对接指南

## 快速启动
### 1. 启动后端服务
```powershell
cd observability/backend
./mvnw spring-boot:run
```
服务地址：http://localhost:9090
H2控制台：http://localhost:9090/h2-console (账号admin/密码123456)
Prometheus指标：http://localhost:9090/actuator/prometheus

### 2. 启动前端面板
```powershell
cd observability/frontend
npm install
npm start
```
面板地址：http://localhost:3000

## 核心项目对接配置
### 1. 新增依赖（pom.xml）
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-otlp</artifactId>
</dependency>
```

### 2. 新增配置（application.yml）
```yaml
management:
  otlp:
    metrics:
      export:
        url: http://localhost:9090/api/v1/metrics
        step: 10s
  tracing:
    sampling:
      probability: 1.0
    propagation:
      type: w3c
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

### 3. 验证对接
启动核心项目后，访问 http://localhost:9090/actuator/prometheus 可以看到核心项目上报的指标，前端面板会自动展示。

## 功能说明
- ✅ 指标监控：请求量、耗时、成功率、JVM指标、智能体执行指标
- ✅ 日志查询：全链路日志查询，支持按traceId、级别、时间范围筛选
- ✅ 链路追踪：智能体执行全链路可视化，定位性能瓶颈
- ✅ 告警规则：自定义阈值告警，支持邮件/钉钉推送
