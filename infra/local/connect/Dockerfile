# Debezium 공식 모니터링 예제와 같은 방식: Connect 이미지에 Prometheus jmx_exporter 에이전트를 얹는다.
# 성능 측정 스택(docker-compose.perf.yml)에서만 쓴다. 기본 CDC 스택은 원본 이미지를 그대로 쓴다.
FROM quay.io/debezium/connect:3.7.0.Final
ARG JMX_EXPORTER_VERSION=1.0.1
RUN mkdir -p /kafka/etc \
 && curl -sfL -o /kafka/etc/jmx_prometheus_javaagent.jar \
    https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/${JMX_EXPORTER_VERSION}/jmx_prometheus_javaagent-${JMX_EXPORTER_VERSION}.jar
COPY jmx-exporter.yml /kafka/etc/jmx-exporter.yml
