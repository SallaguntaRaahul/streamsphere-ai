#!/usr/bin/env python3
"""Generates a service pom.xml. Usage: new-service-pom.py <name> [extra-artifact ...]
extras: redis, s3, fabric8, nojdbc, noamqp"""
import sys, pathlib
name, extras = sys.argv[1], set(sys.argv[2:])
root = pathlib.Path(__file__).resolve().parents[2]
dep = lambda g, a, scope=None, extra="": f"    <dependency><groupId>{g}</groupId><artifactId>{a}</artifactId>{f'<scope>{scope}</scope>' if scope else ''}{extra}</dependency>\n"
sb = "org.springframework.boot"
deps = "".join([
  dep("ai.streamsphere","technical-common"),
  dep(sb,"spring-boot-starter-webmvc"), dep(sb,"spring-boot-starter-validation"), dep(sb,"spring-boot-starter-actuator"),
  dep(sb,"spring-boot-starter-security-oauth2-resource-server"), dep(sb,"spring-boot-starter-restclient"),
  dep(sb,"spring-boot-starter-opentelemetry"), dep("io.micrometer","micrometer-registry-prometheus","runtime"),
  dep("org.springdoc","springdoc-openapi-starter-webmvc-ui"),
])
if "nojdbc" not in extras:
  deps += dep(sb,"spring-boot-starter-jdbc") + dep(sb,"spring-boot-starter-flyway") + dep("org.flywaydb","flyway-database-postgresql") + dep("org.postgresql","postgresql","runtime")
if "noamqp" not in extras:
  deps += dep(sb,"spring-boot-starter-amqp")
if "redis" in extras:
  deps += dep(sb,"spring-boot-starter-data-redis")
if "s3" in extras:
  deps += dep("software.amazon.awssdk","s3") + dep("software.amazon.awssdk","url-connection-client")
if "fabric8" in extras:
  deps += dep("io.fabric8","kubernetes-client")
deps += dep(sb,"spring-boot-starter-test","test") + dep(sb,"spring-boot-starter-webmvc-test","test") + dep("org.springframework.security","spring-security-test","test")
if "nojdbc" not in extras:
  deps += dep(sb,"spring-boot-testcontainers","test") + dep("org.testcontainers","testcontainers-postgresql","test") + dep("org.testcontainers","testcontainers-junit-jupiter","test")
pom = f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>ai.streamsphere</groupId>
    <artifactId>streamsphere-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <relativePath>../../pom.xml</relativePath>
  </parent>
  <artifactId>{name}-service</artifactId>
  <name>StreamSphere {name} service</name>

  <dependencies>
{deps}  </dependencies>

  <build>
    <finalName>app</finalName>
    <plugins>
      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
    </plugins>
  </build>
</project>
"""
out = root / "services" / name / "pom.xml"
out.write_text(pom)
print("wrote", out)
